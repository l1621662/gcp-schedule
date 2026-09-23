package edu.gcp.schedule.ui.me

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.text.KeyboardOptions
import edu.gcp.schedule.Graph
import edu.gcp.schedule.data.repo.ScheduleRepository
import edu.gcp.schedule.domain.ShortcutItem
import edu.gcp.schedule.domain.ShortcutOps
import edu.gcp.schedule.domain.ShortcutSettings
import edu.gcp.schedule.domain.Shortcuts
import edu.gcp.schedule.ui.common.InlineNoticeRow
import edu.gcp.schedule.ui.common.NoticeFeedback
import edu.gcp.schedule.ui.common.NoticeTone
import edu.gcp.schedule.ui.common.SettingItem
import edu.gcp.schedule.ui.common.SettingSwitchRow
import edu.gcp.schedule.ui.common.SettingsDivider
import edu.gcp.schedule.ui.common.SettingsSection
import edu.gcp.schedule.ui.common.ShortcutIcon
import edu.gcp.schedule.ui.common.ShortcutLauncher
import edu.gcp.schedule.ui.common.shortcutIconChoices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AppStore
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.Play
import me.rerere.hugeicons.stroke.RefreshCcw

/**
 * 编辑表单的临时状态。[id] 为 null 表示新增（保存时生成 id）；[presetIndex] 随条目
 * 带进带出，决定弹层里的「重置为默认」按钮与图标。
 */
private data class ShortcutDraft(
    val id: String? = null,
    val presetIndex: Int = -1,
    val name: String = "",
    val uri: String = "",
    val pkg: String = "",
    val activity: String = "",
    val icon: String = "",
) {
    fun toItem(): ShortcutItem = ShortcutItem(
        id = id ?: ShortcutOps.newCustomId(),
        name = name.trim(),
        uri = uri.trim(),
        pkg = pkg.trim(),
        activity = activity.trim(),
        presetIndex = presetIndex,
        icon = icon,
    )
}

private fun ShortcutItem.toDraft(): ShortcutDraft = ShortcutDraft(
    id = id,
    presetIndex = presetIndex,
    name = name,
    uri = uri,
    pkg = pkg,
    activity = activity,
    icon = icon,
)

/** 已安装应用选择器的列表项（DESIGN §3.8）：点一下填好包名，Activity 留给用户按需补。 */
private data class InstalledApp(
    val label: String,
    val pkg: String,
    val icon: Drawable,
)

/** 有桌面入口的应用；排除自身，按应用名排序。Manifest 的 MAIN/LAUNCHER queries 是可见性前提。 */
private fun queryInstalledApps(context: Context): List<InstalledApp> {
    val pm = context.packageManager
    val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0).asSequence()
        .mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == context.packageName) return@mapNotNull null
            val label = try {
                ri.loadLabel(pm)?.toString().orEmpty()
            } catch (_: Exception) {
                ""
            }
            if (label.isBlank()) return@mapNotNull null
            InstalledApp(label, pkg, ri.loadIcon(pm))
        }
        .distinctBy { it.pkg }
        .sortedBy { it.label.lowercase() }
        .toList()
}

/** 应用图标 Drawable → Bitmap（accompanist 的 DrawablePainter 不在依赖里，不值得为它加）。 */
private fun drawableToBitmap(drawable: Drawable): Bitmap {
    if (drawable is android.graphics.drawable.BitmapDrawable && drawable.bitmap != null) {
        return drawable.bitmap
    }
    val width = maxOf(drawable.intrinsicWidth, 1)
    val height = maxOf(drawable.intrinsicHeight, 1)
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    drawable.setBounds(0, 0, canvas.width, canvas.height)
    drawable.draw(canvas)
    return bitmap
}

/**
 * 快捷方式设置子页（DESIGN §3.8）：开关 + 预设/自定义两组条目 + 编辑弹层。
 *
 * 预设可编辑、可「重置为默认」，不可删除——三条预设是这个功能的底线配置，
 * 删没了用户就得自己重新填 URI。自定义条目可增删，总数上限 [Shortcuts.MAX_COUNT]。
 * 「测试」用当前表单值即时拉起，与今日页共用 [ShortcutLauncher]，错误口径一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShortcutSettingsScreen(
    onBack: () -> Unit,
    /** Snackbar「去设置」带来的条目 id：落库数据到位后自动展开其编辑弹层（一次性） */
    focusItemId: String? = null,
    viewModel: ShortcutSettingsViewModel = viewModel(
        factory = ShortcutSettingsViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val context = LocalContext.current
    val state by viewModel.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ShortcutDraft?>(null) }
    var pendingDelete by remember { mutableStateOf<ShortcutItem?>(null) }
    var confirmResetAll by remember { mutableStateOf(false) }

    // 就近修正闭环：从今日页 Snackbar 过来时，数据落库后直接展开目标条目；
    // id 失效（条目已删）就安静落在列表页
    var focusConsumed by remember { mutableStateOf(false) }
    LaunchedEffect(focusItemId) {
        if (focusItemId == null || focusConsumed) return@LaunchedEffect
        val item = viewModel.findItem(focusItemId) ?: return@LaunchedEffect
        focusConsumed = true
        editing = item.toDraft()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("快捷方式") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(title = "今日页") {
                Spacer(Modifier.height(8.dp))
                SettingSwitchRow(
                    title = "在今日页显示",
                    subtitle = "关闭后今日页不再展示快捷入口",
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled,
                    icon = HugeIcons.Flash,
                )
            }

            val presets = state.items.filter { it.presetIndex >= 0 }
            val customs = state.items.filter { it.presetIndex < 0 }

            SettingsSection(title = "预设") {
                presets.forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    ShortcutRow(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < presets.lastIndex,
                        onMove = { delta -> viewModel.move(item.id, delta) },
                        onEdit = { editing = item.toDraft() },
                    )
                }
            }

            SettingsSection(title = "自定义") {
                if (customs.isEmpty()) {
                    Text(
                        text = "还没有自定义快捷方式。可以放学校常用网页、App 内页面等。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
                customs.forEachIndexed { index, item ->
                    if (index > 0) SettingsDivider()
                    ShortcutRow(
                        item = item,
                        canMoveUp = index > 0,
                        canMoveDown = index < customs.lastIndex,
                        onMove = { delta -> viewModel.move(item.id, delta) },
                        onEdit = { editing = item.toDraft() },
                    )
                }
                SettingsDivider()
                val canAdd = state.items.size < Shortcuts.MAX_COUNT
                SettingItem(
                    title = "添加快捷方式",
                    subtitle = "打开 App · 直达页面 · 打开链接",
                    icon = HugeIcons.Add01,
                    enabled = canAdd,
                    value = if (canAdd) null else "已达上限",
                    onClick = if (canAdd) {
                        { editing = ShortcutDraft() }
                    } else {
                        null
                    },
                )
            }

            TextButton(
                onClick = { confirmResetAll = true },
                modifier = Modifier.padding(vertical = 4.dp),
            ) {
                Icon(HugeIcons.RefreshCcw, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("恢复全部默认")
            }
        }
    }

    editing?.let { draft ->
        ShortcutEditSheet(
            draft = draft,
            onDismiss = { editing = null },
            onSave = { item ->
                viewModel.upsert(item)
                editing = null
            },
            onTest = { item ->
                // 测试不落盘：用当前表单值直接拉起，结果与今日页同一执行层。
                // 结果作为行内反馈回给弹层（弹层不关，续测下一个配置时旧结果被覆盖）
                ShortcutLauncher.launch(context, item)?.let {
                    NoticeFeedback(it, NoticeTone.Error)
                } ?: NoticeFeedback("已启动「${item.name}」", NoticeTone.Success)
            },
            onResetConfirmed = if (draft.presetIndex >= 0 && draft.id != null) {
                {
                    viewModel.resetPreset(draft.presetIndex)
                    editing = null
                }
            } else {
                null
            },
            onDelete = if (draft.presetIndex < 0 && draft.id != null) {
                { pendingDelete = draft.toItem() }
            } else {
                null
            },
        )
    }

    pendingDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除快捷方式") },
            text = { Text("删除「${item.name}」？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(item.id)
                    editing = null
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (confirmResetAll) {
        AlertDialog(
            onDismissRequest = { confirmResetAll = false },
            title = { Text("恢复全部默认") },
            text = { Text("所有快捷方式将恢复为 3 条预设，自定义条目会被移除，今日页开关重新打开。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetAll()
                    confirmResetAll = false
                }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(onClick = { confirmResetAll = false }) { Text("取消") }
            },
        )
    }
}

/** 条目行：图标 + 名称 + 目标摘要；右侧上移/下移调序，点行进编辑弹层。 */
@Composable
private fun ShortcutRow(
    item: ShortcutItem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ShortcutIcon(item, Modifier.size(26.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = Shortcuts.targetSummary(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(
                HugeIcons.ArrowUp01,
                contentDescription = "上移",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canMoveUp) 0.6f else 0.2f),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(
                HugeIcons.ArrowDown01,
                contentDescription = "下移",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canMoveDown) 0.6f else 0.2f),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onEdit) {
            Icon(
                HugeIcons.Edit02,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 编辑弹层。名称必填；目标按「Activity > 链接 > 只填包名」的优先级折算（DESIGN §4.16），
 * 表单里四项都摆出来，helper 文案交代三种填法；「测试」不保存直接拉起验证。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShortcutEditSheet(
    draft: ShortcutDraft,
    onDismiss: () -> Unit,
    onSave: (ShortcutItem) -> Unit,
    /** 测试即拉起验证；返回结果（成功/失败）由弹层**行内**展示，见 [testResult] */
    onTest: (ShortcutItem) -> NoticeFeedback,
    /** 非空 = 预设槽位，弹层显示「重置为默认」 */
    onResetConfirmed: (() -> Unit)?,
    /** 非空 = 自定义条目，弹层显示「删除」（点击后由外层确认弹窗兜底） */
    onDelete: (() -> Unit)?,
) {
    val context = LocalContext.current
    var name by remember(draft.id) { mutableStateOf(draft.name) }
    var uri by remember(draft.id) { mutableStateOf(draft.uri) }
    var pkg by remember(draft.id) { mutableStateOf(draft.pkg) }
    var activity by remember(draft.id) { mutableStateOf(draft.activity) }
    var icon by remember(draft.id) { mutableStateOf(draft.icon) }
    var error by remember(draft.id) { mutableStateOf<String?>(null) }
    // 测试结果（DESIGN §3.8「测试即时拉起验证，错误行内提示」）：表单不关，
    // 结果就贴在按钮上方——此前是 Toast，而弹层是独立窗口、比页面高一层，
    // 页面级提示在它下面根本看不见
    var testResult by remember(draft.id) { mutableStateOf<NoticeFeedback?>(null) }

    // ---- 已安装应用选择器：按需加载一次，存在表单外层状态里避免反复查询 ----
    var showPicker by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var appSearch by remember { mutableStateOf("") }
    LaunchedEffect(showPicker) {
        if (showPicker && apps == null) {
            apps = withContext(Dispatchers.IO) { queryInstalledApps(context) }
        }
    }
    if (showPicker) {
        InstalledAppPicker(
            apps = apps,
            search = appSearch,
            onSearch = { appSearch = it },
            onPick = { app ->
                pkg = app.pkg
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }

    // 表单当前值 → 条目。测试与保存共用：测试就是「不落盘的保存前演练」
    val buildItem = {
        ShortcutDraft(
            id = draft.id,
            presetIndex = draft.presetIndex,
            name = name,
            uri = uri,
            pkg = pkg,
            activity = activity,
            icon = icon,
        ).toItem()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (draft.id == null) "添加快捷方式" else "编辑快捷方式",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                if (draft.presetIndex >= 0) {
                    Text(
                        text = "预设",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            // 图标选择只对自定义条目生效：预设固定品牌图（DESIGN §4.16）
            if (draft.presetIndex < 0) {
                Text(
                    text = "图标",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(shortcutIconChoices) { (key, vector) ->
                        val selected = icon == key || (icon.isBlank() && key == "link")
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                    } else {
                                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                                    },
                                )
                                .border(
                                    width = if (selected) 1.5.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    },
                                    shape = CircleShape,
                                )
                                .clickable { icon = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                vector,
                                contentDescription = null,
                                tint = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                },
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("名称") },
                singleLine = true,
                isError = error != null && name.isBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = uri,
                onValueChange = { uri = it },
                label = { Text("链接（可选）") },
                placeholder = { Text("https:// 或 pinduoduo:// 开头") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = pkg,
                onValueChange = { pkg = it },
                label = { Text("包名（可选）") },
                placeholder = { Text("如 com.cainiao.wireless") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { showPicker = true }) {
                        Icon(
                            HugeIcons.AppStore,
                            contentDescription = "从已安装应用选择",
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = activity,
                onValueChange = { activity = it },
                label = { Text("Activity 类名（可选）") },
                placeholder = { Text("完整类名，直达该页面（可跳过开屏广告）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "三种填法：只填包名 = 打开 App；包名 + Activity = 直达页面；填链接 = 打开链接（配包名限定在指定 App 内打开）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(top = 10.dp),
            )
            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            testResult?.let {
                InlineNoticeRow(
                    message = it.text,
                    tone = it.tone,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    onClick = {
                        val item = buildItem()
                        val problem = Shortcuts.validate(item)
                        if (problem != null) {
                            error = problem
                            testResult = null
                        } else {
                            // 先清掉上一次的行内错误：测试成功后弹层不关，旧错误挂着误导人
                            error = null
                            testResult = onTest(item)
                        }
                    },
                ) {
                    Icon(HugeIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("测试")
                }
                if (onResetConfirmed != null) {
                    TextButton(onClick = onResetConfirmed) {
                        Icon(HugeIcons.RefreshCcw, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("重置为默认")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Icon(HugeIcons.Delete02, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("删除")
                    }
                }
                TextButton(
                    onClick = {
                        val item = buildItem()
                        val problem = Shortcuts.validate(item)
                        if (problem != null) {
                            error = problem
                        } else {
                            error = null
                            onSave(item)
                        }
                    },
                ) {
                    Text("保存")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 已安装应用选择器（DESIGN §3.8）：搜索 + 列表，点一下只填包名。
 * 不顺手填 Activity——启动页类名随版本可能变，用户需要直达时自己填更稳。
 */
@Composable
private fun InstalledAppPicker(
    apps: List<InstalledApp>?,
    search: String,
    onSearch: (String) -> Unit,
    onPick: (InstalledApp) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择应用") },
        text = {
            Column {
                OutlinedTextField(
                    value = search,
                    onValueChange = onSearch,
                    placeholder = { Text("搜索应用名或包名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                when {
                    apps == null -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    }

                    apps.isEmpty() -> Text(
                        text = "没有读到已安装的应用",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )

                    else -> {
                        val filtered = apps.filter {
                            it.label.contains(search, ignoreCase = true) ||
                                it.pkg.contains(search, ignoreCase = true)
                        }
                        if (filtered.isEmpty()) {
                            Text(
                                text = "没有匹配「$search」的应用",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            )
                        }
                        LazyColumn(modifier = Modifier.heightIn(max = 380.dp)) {
                            items(filtered, key = { it.pkg }) { app ->
                                val iconBitmap = remember(app.pkg) {
                                    drawableToBitmap(app.icon).asImageBitmap()
                                }
                                Row(
                                    modifier = Modifier
                                        // 搜索过滤时列表项平滑重排/进出场，不整列硬跳
                                        .animateItem()
                                        .fillMaxWidth()
                                        .clickable { onPick(app) }
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Image(
                                        bitmap = iconBitmap,
                                        contentDescription = null,
                                        modifier = Modifier.size(34.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = app.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            text = app.pkg,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

class ShortcutSettingsViewModel(private val repo: ScheduleRepository) : ViewModel() {

    val settings: StateFlow<ShortcutSettings> = repo.shortcutSettings.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        ShortcutSettings(),
    )

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { repo.setShortcutsEnabled(value) }
    }

    /** 新增或覆盖保存：id 已存在则原位替换，否则追加。 */
    fun upsert(item: ShortcutItem) {
        viewModelScope.launch {
            repo.updateShortcuts { list ->
                if (list.any { it.id == item.id }) {
                    list.map { if (it.id == item.id) item else it }
                } else {
                    list + item
                }
            }
        }
    }

    fun remove(id: String) {
        viewModelScope.launch { repo.updateShortcuts { list -> list.filterNot { it.id == id } } }
    }

    fun move(id: String, delta: Int) {
        viewModelScope.launch { repo.updateShortcuts { list -> ShortcutOps.move(list, id, delta) } }
    }

    fun resetPreset(presetIndex: Int) {
        viewModelScope.launch {
            repo.updateShortcuts { list -> ShortcutOps.resetPreset(list, presetIndex) }
        }
    }

    fun resetAll() {
        viewModelScope.launch {
            repo.setShortcutsEnabled(true)
            repo.updateShortcuts { ShortcutOps.resetAll() }
        }
    }

    /**
     * 直达定位用：绕开 [settings] 的种子值（种子 = 默认预设，不是用户数据），
     * 直接等落库数据的第一帧。找不到（条目已删）返回 null，调用方安静跳过。
     */
    suspend fun findItem(id: String): ShortcutItem? =
        repo.shortcutSettings.first().items.firstOrNull { it.id == id }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ShortcutSettingsViewModel(repo) as T
    }
}
