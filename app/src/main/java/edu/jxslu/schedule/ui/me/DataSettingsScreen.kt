package edu.jxslu.schedule.ui.me

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.ImportTargetDialogHost
import edu.jxslu.schedule.ui.common.SettingsSection

/**
 * 课表数据子页：导出 / 导入 JSON、剪贴板导入、清空。
 *
 * 从「我的 → 课表数据」进入（DESIGN §3.3），不再摊在设置主页上。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataSettingsScreen(
    onBack: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val oneShot by viewModel.oneShot.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showClearConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(oneShot) {
        when (val s = oneShot) {
            is OneShot.Message -> {
                // 清空等操作带撤销动作：Snackbar 给「撤销」，点了就回滚
                val result = snackbar.showSnackbar(
                    s.text,
                    actionLabel = if (s.undo != null) "撤销" else null,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) s.undo?.invoke()
                viewModel.consumeOneShot()
            }
            is OneShot.ConfirmImport -> Unit
            null -> Unit
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) viewModel.exportJson(context, uri)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.importFromUri(context, uri)
    }

    // 根因：迁到 SubpageActivity 独立窗口后没有外层 Scaffold 垫状态栏，
    // windowInsets 归零（嵌 NavHost 时期防双倍空白的老规避）会让顶栏顶进状态栏；
    // 现走 M3 默认——TopAppBar 自行消费状态栏，contentWindowInsets 管住手势条。
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表数据") },
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
            SettingsSection(
                title = "导入 / 导出",
                subtitle = "JSON 字段对齐拾光互通课表模型，可与兼容工具互导；导出的是当前课表「${state.timetableName.ifBlank { "—" }}」，成绩随文件一起备份（导入时整体替换）。",
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { exportLauncher.launch("juw_courses.json") },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("导出 JSON…") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "application/json",
                                "text/plain",
                                "application/octet-stream",
                                "*/*",
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从文件导入 JSON…") }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.importFromClipboard(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("从剪贴板导入") }
            }

            SettingsSection(
                title = "危险操作",
                subtitle = "清空当前课表全部课程；提示条消失前可点「撤销」恢复。",
            ) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showClearConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("清空全部课程", color = MaterialTheme.colorScheme.error)
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    "导入会弹出目标课表选择：可覆盖或合并，也可新建课表承接（DESIGN §4.9）。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }

    (oneShot as? OneShot.ConfirmImport)?.let { confirm ->
        ImportTargetDialogHost(
            courses = confirm.preview.courses,
            title = "导入 JSON 课表",
            term = confirm.preview.term,
            // 文件带成绩段/学期作息段时必须让用户知情：整体替换、不可撤销（DESIGN §4.3）
            note = buildString {
                confirm.preview.scores.takeIf { it.isNotEmpty() }?.let { scores ->
                    append(
                        "${scores.map { it.term }.distinct().size} 个学期共 ${scores.size} 条成绩，" +
                            "导入时会整体替换现有成绩。",
                    )
                }
                val configParts = buildList {
                    if (confirm.preview.semester != null) add("学期配置")
                    if (confirm.preview.slotCount > 0) add("作息 ${confirm.preview.slotCount} 节")
                }
                if (configParts.isNotEmpty()) {
                    if (isNotEmpty()) append(" ")
                    append("并将恢复目标课表的${configParts.joinToString("与")}。")
                }
            },
            defaultMerge = false,
            repo = Graph.repository(context),
            onConfirm = { target, merge -> viewModel.confirmImport(target, merge) },
            onDismiss = { viewModel.cancelPendingImport() },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("确认清空全部课程？") },
            text = {
                Text(
                    "将删除当前课表「${state.timetableName.ifBlank { "—" }}」中的全部课程。\n" +
                        "清空后提示条消失前可撤销；请重新从教务导入或从 JSON 恢复。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearConfirm = false
                        viewModel.clearCourses()
                    },
                ) { Text("清空", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("取消") }
            },
        )
    }
}
