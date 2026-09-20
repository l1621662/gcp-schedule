package edu.jxslu.schedule.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.Timetable
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.BookMarked
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.MoreVertical

data class ManageUiState(
    val timetables: List<Timetable> = emptyList(),
    val currentId: Long = 0L,
    val defaultSourceId: Long? = null,
    val counts: Map<Long, Int> = emptyMap(),
    val message: String? = null,
)

class TimetableViewModel(private val repo: ScheduleRepository) : ViewModel() {

    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ManageUiState> = combine(
        repo.timetables,
        repo.currentTimetableId,
        message,
    ) { list, currentId, msg ->
        ManageUiState(
            timetables = list,
            currentId = currentId,
            defaultSourceId = repo.defaultConfigSourceId(),
            counts = list.associate { it.id to repo.timetableCourseCount(it.id) },
            message = msg,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ManageUiState())

    fun consumeMessage() {
        message.value = null
    }

    fun select(id: Long) {
        viewModelScope.launch {
            repo.setCurrentTimetable(id)
        }
    }

    fun create(name: String) {
        viewModelScope.launch {
            repo.createTimetable(name)
            message.value = "已新建课表「${name.trim()}」"
        }
    }

    fun rename(id: Long, name: String) {
        viewModelScope.launch {
            repo.renameTimetable(id, name)
        }
    }

    fun duplicate(id: Long) {
        viewModelScope.launch {
            repo.duplicateTimetable(id)
            message.value = "已复制课表"
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch {
            val ok = repo.deleteTimetable(id)
            message.value = if (ok) "已删除课表" else "至少要保留一张课表"
        }
    }

    /** 设为/取消默认配置源。引用型默认：之后新建的课表拷贝该课表当时的设置。 */
    fun setDefaultSource(id: Long?) {
        viewModelScope.launch {
            repo.setDefaultConfigSource(id)
            message.value = if (id == null) "已取消默认配置" else "已设为新建课表的默认配置"
        }
    }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TimetableViewModel(repo) as T
    }
}

/**
 * 课表管理页（DESIGN §4.9）：新建 / 切换 / 重命名 / 复制 / 删除 / 设为默认配置。
 *
 * 排版约定：主操作（切换）是卡内填充按钮，重命名/复制/默认/删除收进「⋮」菜单——
 * 五个平铺文字按钮在窄屏必然溢出，且删除混在平铺行里易误触。
 * 删除保护放在 Repository（至少保留一张；删当前自动切换），这里只负责禁用入口与文案。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableManageScreen(
    onBack: () -> Unit,
    viewModel: TimetableViewModel = viewModel(
        factory = TimetableViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var createOpen by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var renameTarget by remember { mutableStateOf<Timetable?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<Timetable?>(null) }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "「当前」= 课表页正在使用；「默认配置」= 新建课表时拷贝它的设置。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            state.timetables.forEach { t ->
                TimetableCard(
                    timetable = t,
                    isCurrent = t.id == state.currentId,
                    isDefaultSource = t.id == state.defaultSourceId,
                    courseCount = state.counts[t.id] ?: 0,
                    canDelete = state.timetables.size > 1,
                    onSwitch = { viewModel.select(t.id) },
                    onRename = {
                        renameText = t.name
                        renameTarget = t
                    },
                    onDuplicate = { viewModel.duplicate(t.id) },
                    onToggleDefault = {
                        viewModel.setDefaultSource(if (t.id == state.defaultSourceId) null else t.id)
                    },
                    onDelete = { deleteTarget = t },
                )
            }
            // 列表尾部的第二新建入口：长列表里比顶栏按钮更快够到
            OutlinedButton(
                onClick = {
                    newName = ""
                    createOpen = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("新建课表")
            }
        }
    }

    if (createOpen) {
        NameDialog(
            title = "新建课表",
            label = "课表名称",
            initial = newName,
            onValueChange = { newName = it },
            // 重名校验：建出两张「我的课表」后，切换弹层与管理页都难分彼此
            error = state.timetables
                .firstOrNull { it.name == newName.trim() }?.let { "已有同名课表「${it.name}」，换个名字" },
            onConfirm = {
                viewModel.create(newName)
                createOpen = false
            },
            onDismiss = { createOpen = false },
        )
    }

    renameTarget?.let { t ->
        NameDialog(
            title = "重命名课表",
            label = "课表名称",
            initial = renameText,
            onValueChange = { renameText = it },
            error = state.timetables
                .firstOrNull { it.id != t.id && it.name == renameText.trim() }
                ?.let { "已有同名课表「${it.name}」，换个名字" },
            onConfirm = {
                viewModel.rename(t.id, renameText)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    deleteTarget?.let { t ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除课表「${t.name}」？") },
            text = {
                Text(
                    "将删除该课表的全部课程与设置（不可撤销）。\n" +
                        "课程数：${state.counts[t.id] ?: 0}",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.delete(t.id)
                        deleteTarget = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

/** 单张课表卡：名称 + 徽标 + 课程数；主操作「设为当前」，其余操作收进「⋮」菜单。 */
@Composable
private fun TimetableCard(
    timetable: Timetable,
    isCurrent: Boolean,
    isDefaultSource: Boolean,
    courseCount: Int,
    canDelete: Boolean,
    onSwitch: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onToggleDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    timetable.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$courseCount 门课",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            if (isCurrent || isDefaultSource) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (isCurrent) Badge("当前", MaterialTheme.colorScheme.primary)
                    if (isDefaultSource) Badge("默认配置", MaterialTheme.colorScheme.secondary)
                }
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent) {
                    Text(
                        "使用中",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                } else {
                    FilledTonalButton(
                        onClick = {
                            haptics.tap()
                            onSwitch()
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 4.dp,
                        ),
                        modifier = Modifier.padding(vertical = 6.dp).height(34.dp),
                    ) { Text("设为当前", style = MaterialTheme.typography.labelMedium) }
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(
                        onClick = {
                            haptics.tap()
                            menuOpen = true
                        },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            HugeIcons.MoreVertical,
                            contentDescription = "更多操作",
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("重命名") },
                            leadingIcon = { Icon(HugeIcons.Edit02, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                menuOpen = false
                                onRename()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("复制") },
                            leadingIcon = { Icon(HugeIcons.Copy01, contentDescription = null, modifier = Modifier.size(18.dp)) },
                            onClick = {
                                menuOpen = false
                                onDuplicate()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(if (isDefaultSource) "取消默认" else "设为默认") },
                            leadingIcon = {
                                Icon(HugeIcons.BookMarked, contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            onClick = {
                                menuOpen = false
                                onToggleDefault()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = {
                                Icon(
                                    HugeIcons.Delete02,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            // 只有一张时禁用删除：Repository 也会拒绝，这里提前禁掉入口
                            enabled = canDelete,
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .background(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun NameDialog(
    title: String,
    label: String,
    initial: String,
    onValueChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    /** 非 null 时禁止确认并显示错误（如同名课表已存在）。 */
    error: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = initial,
                onValueChange = onValueChange,
                label = { Text(label) },
                isError = error != null,
                supportingText = { error?.let { Text(it) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(enabled = initial.isNotBlank() && error == null, onClick = onConfirm) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}
