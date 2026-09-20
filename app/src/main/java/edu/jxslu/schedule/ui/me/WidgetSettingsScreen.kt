package edu.jxslu.schedule.ui.me

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.buildTodayState
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingsIconBadge
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.courseColor
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.widget.WidgetDay
import edu.jxslu.schedule.ui.widget.WidgetFocus
import edu.jxslu.schedule.ui.widget.WidgetLayout
import edu.jxslu.schedule.ui.widget.WidgetModel
import edu.jxslu.schedule.ui.widget.WidgetSnapshot
import edu.jxslu.schedule.ui.widget.WidgetSnapshotStore
import edu.jxslu.schedule.ui.widget.buildWidgetSnapshot
import edu.jxslu.schedule.ui.widget.buildWidgetWeek
import edu.jxslu.schedule.ui.widget.forSize
import edu.jxslu.schedule.ui.widget.widgetMetricsFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BatteryCharging01
import me.rerere.hugeicons.stroke.Energy
import java.time.LocalDate

/**
 * 「我的 → 桌面小组件」设置页（DESIGN §3.6）。
 *
 * 2026-09-20 改版：可添加的条目从三条（2×2 / 4×2 / 4×4）合并为**一条**
 * 「水贝贝 · 课表」——三者都能自由拖动改大小、落到同一形态后内容相同，选择器里纯冗余。
 * 原来的三行变成「一条添加行 + 三张形态预览」，预览仍按三档尺寸各画一个缩略图，
 * 但只作说明用（拖到多大长什么样），不再各自可添加。
 *
 * 交互规则（用户已拍板「自动检测 + 逐项申请」）：
 * - 进页面**只读检测**各能力/权限状态并展示；
 * - **首次进入**自动弹一次说明弹层（含一键添加），此后不再自动弹；
 * - 不主动拉任何系统框——「去开启」「添加」都是用户点了才动。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WidgetSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    // 能力/权限状态：从系统设置返回（onResume）时重读，保证徽标与真实状态一致
    var caps by remember { mutableStateOf(readCaps(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) caps = readCaps(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 首次进入的说明弹层（只弹一次，DataStore 标记）
    var showIntro by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val prefs = Graph.displayPrefs(context)
        if (!prefs.widgetSetupSeen()) {
            showIntro = true
            prefs.setWidgetSetupSeen()
        }
    }

    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // 添加失败（桌面不支持应用内添加）的提示出口，走全 App 统一卡片
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showSnackbar(AppNoticeVisuals(message, tone = NoticeTone.Warning))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("桌面小组件") },
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
                title = "添加到桌面",
                subtitle = if (caps.canPin) {
                    "点「添加」后在系统确认框里一键放到桌面；之后拖动边缘可任意改大小，" +
                        "内容随尺寸变——小的是「下一节」，大了自动变本周课表"
                } else {
                    "当前桌面不支持应用内添加，请长按桌面空白处 → 小组件 → 水贝贝"
                },
            ) {
                Spacer(Modifier.height(4.dp))
                AddRow(
                    added = caps.addedCount,
                    canPin = caps.canPin,
                    onAdd = { onAddClicked(context)?.let(showNotice) },
                )
                Spacer(Modifier.height(6.dp))
            }

            SettingsSection(
                title = "尺寸形态",
                subtitle = "拖到以下大致尺寸时的样子；中间尺寸会自动落在最合适的一档",
            ) {
                Spacer(Modifier.height(6.dp))
                WidgetPreviewRow(WidgetPreviewSize.Small, "紧凑：日期 + 正在上 / 下一节")
                Spacer(Modifier.height(10.dp))
                WidgetPreviewRow(WidgetPreviewSize.Wide, "横条：日期 + 焦点课（课名更宽，地名全显示）")
                Spacer(Modifier.height(10.dp))
                WidgetPreviewRow(WidgetPreviewSize.Tall, "竖条：焦点课 + 今日剩余（行数随高度）")
                Spacer(Modifier.height(10.dp))
                WidgetPreviewRow(WidgetPreviewSize.Large, "大方：摘要 + 本周课表（今天 / 明天列高亮）")
                Spacer(Modifier.height(6.dp))
            }

            SettingsSection(
                title = "后台及时性（可选）",
                subtitle = "小组件在上下课时刻自动更新；不开也能用，开启后刷新更及时",
            ) {
                Spacer(Modifier.height(4.dp))
                PermRow(
                    title = "忽略电池优化",
                    detail = "防止系统冻结后台刷新；不同手机叫「电池优化白名单 / 省电策略无限制」",
                    granted = caps.batteryWhitelisted,
                    icon = HugeIcons.BatteryCharging01,
                    actionText = if (caps.batteryWhitelisted) "查看" else "去开启",
                    onClick = { WidgetCapabilities.jumpBatteryOptimization(context) },
                )
                WidgetEntryDivider()
                PermRow(
                    title = "允许自启动",
                    detail = "开机与被杀后能自行恢复刷新；在厂商设置里找「自启动 / 允许后台运行」",
                    granted = null,
                    icon = HugeIcons.Energy,
                    actionText = "去设置",
                    onClick = { WidgetCapabilities.jumpAutoStart(context) },
                )
                Spacer(Modifier.height(4.dp))
            }

            SettingsSection(title = "说明") {
                Spacer(Modifier.height(6.dp))
                Bullet("内容与「今日」页同一套口径：已结束的课不显示，今天上完自动换明日预告。")
                Bullet("拖动可任意调整大小，内容随尺寸变化（2×2 到 5×5 都放得下）。")
                Bullet("跟随系统深浅色；不占用额外网络。")
                Bullet("「还有 N 分钟」按刷新时刻计算，两次刷新之间不会跳动。")
                Spacer(Modifier.height(6.dp))
                LauncherNoteRow()
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    if (showIntro) {
        IntroDialog(
            canPin = caps.canPin,
            onAdd = {
                showIntro = false
                // 弹层里的「现在添加」默认钉一个 4×2（推荐）：横条在桌面上信息密度与
                // 可读性平衡最好，之后可随时拖动改大小。
                // 弹层刚关（它也是独立窗口），提示改由页面宿主展示，不被盖住
                onAddClicked(context)?.let(showNotice)
            },
            onDismiss = { showIntro = false },
        )
    }
}

private fun readCaps(context: Context): WidgetCaps = WidgetCaps(
    canPin = WidgetCapabilities.canPin(context),
    batteryWhitelisted = WidgetCapabilities.isIgnoringBatteryOptimizations(context),
    addedCount = WidgetCapabilities.addedCount(context),
)

private data class WidgetCaps(
    val canPin: Boolean,
    val batteryWhitelisted: Boolean,
    val addedCount: Int,
)

/**
 * 添加小组件。返回 null = 已提交系统确认框；非 null = 用户可读错误，
 * 由调用方走页面统一的 [AppSnackbarHost]（此前是系统 Toast，与本页其余提示两套观感）。
 */
private fun onAddClicked(context: Context): String? =
    if (WidgetCapabilities.requestPin(context)) null else WidgetCapabilities.manualAddHint

/** 卡内两行之间的换气线（与分区卡的克制风格一致）。 */
@Composable
private fun WidgetEntryDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/**
 * 唯一一条添加行：名称 + 摘要 + 已添加徽标 + 「添加」。
 *
 * 不再有三行（旧版每档尺寸一行）——单条目后只有一个 receiver，多行会重复指向它。
 */
@Composable
private fun AddRow(
    added: Int,
    canPin: Boolean,
    onAdd: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "水贝贝 · 课表",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (added > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (added > 1) "已添加 $added 个" else "已添加",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                shape = MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = "小尺寸看正在上 / 下一节与今日剩余，拖到 4×4 变本周课表",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (canPin) {
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    haptics.tap()
                    onAdd()
                },
            ) { Text("添加") }
        }
        // 桌面不支持应用内 pin（DESIGN §3.6）：按钮隐藏，只留分区副标题里的手动引导
    }
}

/** 权限行：图标 + 标题/说明 + 状态徽标 + 动作按钮。 */
@Composable
private fun PermRow(
    title: String,
    detail: String,
    granted: Boolean?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionText: String,
    onClick: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SettingsIconBadge(icon = icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge)
                if (granted != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (granted) "已开启" else "未开启",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (granted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                        },
                        modifier = Modifier
                            .background(
                                color = if (granted) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                } else {
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
                                },
                                shape = MaterialTheme.shapes.small,
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
        Spacer(Modifier.width(8.dp))
        TextButton(
            onClick = {
                haptics.tap()
                onClick()
            },
        ) { Text(actionText) }
    }
}

@Composable
private fun Bullet(text: String) {
    Text(
        text = "· $text",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        modifier = Modifier.padding(vertical = 2.dp),
    )
}

/**
 * 负一屏说明行（DESIGN §3.6「负一屏」）。
 *
 * 单独成块而不是混在 Bullet 里：这是用户明确问过的点，值得给一句能照着做的话
 * （去哪儿搜、搜不到怎么办），而不是一句「不支持」。
 */
@Composable
private fun LauncherNoteRow() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = "负一屏",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = WidgetCapabilities.launcherNote,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

// ---------- 预览缩略图：用真实课表数据画一个迷你 widget ----------

/**
 * 设置页展示用的快照（与小组件同一套状态推导），30 秒随时间重算一次。
 *
 * 与 widget 侧的差别只有一处：**周网格直接构建**（不按尺寸跳过），
 * 因为三张预览里有一张就是周网格档。渲染仍走 `forSize`，与桌面所见同源。
 */
@Composable
private fun rememberPreviewSnapshot(): WidgetSnapshot? {
    val context = LocalContext.current
    val repo = remember { Graph.repository(context) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            tick++
        }
    }
    return produceState<WidgetSnapshot?>(initialValue = null, tick) {
        value = runCatching {
            val semester = repo.semester.first()
            val slots = repo.timeSlots.first()
            val courses = repo.courses.first()
            val prefs = repo.displayPrefs.first()
            val today = LocalDate.now()
            val now = LocalTimeLike.now()
            val state = buildTodayState(semester, slots, courses, today, now)
            val week = if (state.inTerm) state.week else 1
            val highlight = state.focus?.day
                ?: state.tomorrowDay.takeIf { state.tomorrowVisible }
            buildWidgetSnapshot(
                state,
                buildWidgetWeek(
                    courses = courses,
                    week = week,
                    showSaturday = prefs.showSaturday,
                    showSunday = prefs.showSunday,
                    filter = prefs.courseFilter,
                    highlightDay = highlight,
                    slots = slots,
                    now = now,
                    markNow = state.focus != null,
                    nowDay = state.focus?.let { state.day },
                ),
            )
        }.getOrNull()
    }.value
}

/** 一行预览：缩略图 + 名称 + 说明（不是可添加条目，纯形态参考）。 */
@Composable
private fun WidgetPreviewRow(size: WidgetPreviewSize, caption: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PreviewTile(size)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = size.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 迷你预览：按该档实测 dp 走同一套 [widgetMetricsFor] 分档；课表为空时给占位文案。 */
@Composable
private fun PreviewTile(size: WidgetPreviewSize) {
    val snapshot = rememberPreviewSnapshot()
    val (w, h) = when (size) {
        WidgetPreviewSize.Small -> 84.dp to 84.dp
        WidgetPreviewSize.Wide -> 116.dp to 58.dp
        WidgetPreviewSize.Tall -> 58.dp to 116.dp
        WidgetPreviewSize.Large -> 128.dp to 128.dp
    }
    Box(
        modifier = Modifier
            .width(w)
            .height(h)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(7.dp),
    ) {
        val model = snapshot?.forSize(widgetMetricsFor(size.widthDp, size.heightDp))
        if (model == null) {
            Text(
                text = "课表\n为空",
                fontSize = 8.sp,
                lineHeight = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = model.header,
                    fontSize = 7.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PreviewBody(model)
            }
        }
    }
}

@Composable
private fun PreviewBody(model: WidgetModel) {
    // 周网格档：画一个极简的 7×5 网格示意（真实网格在 128dp 缩略图里画不出可读信息，
    // 这里只表达「大尺寸会给周课表」这件事；实际效果以桌面为准）
    val week = model.week
    if (week != null) {
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                week.days.forEach { day ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(6.dp)
                            .background(
                                if (day == week.highlightDay) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                                },
                            ),
                    ) {}
                }
            }
            repeat(4) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    week.days.forEach { day ->
                        val hasCourse = week.blocks.any {
                            it.day == day && row == (it.startSection - 1) / 3
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(7.dp)
                                .background(
                                    if (hasCourse) {
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.06f)
                                    },
                                ),
                        ) {}
                    }
                }
            }
        }
        return
    }
    PreviewFocus(model.focus)
    // 明日接棒时列表标题也画出来（正是「不留空白」的落点）
    model.listTitle?.let { title ->
        Text(
            text = title,
            fontSize = 6.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    model.rows.take(2).forEach { row ->
        PreviewRow(row.colorIndex, row.name, row.clock)
    }
}

@Composable
private fun PreviewFocus(focus: WidgetFocus) {
    when (focus) {
        is WidgetFocus.Course -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(courseColor(focus.colorIndex).copy(alpha = 0.16f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Column {
                    Text(
                        text = focus.label,
                        fontSize = 6.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = courseColor(focus.colorIndex),
                    )
                    Text(
                        text = focus.name,
                        fontSize = 8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }

        is WidgetFocus.Idle -> Text(
            text = focus.title,
            fontSize = 8.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PreviewRow(colorIndex: Int, name: String, clock: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = clock,
            fontSize = 6.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(3.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(4.dp))
                .background(courseColor(colorIndex).copy(alpha = 0.16f))
                .padding(horizontal = 3.dp, vertical = 1.dp),
        ) {
            Text(
                text = name,
                fontSize = 7.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 首次进入的说明弹层（DESIGN §3.6「进入设置界面自动申请」的落点）。 */
@Composable
private fun IntroDialog(
    canPin: Boolean,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("把课表放上桌面") },
        text = {
            Text(
                text = "一个组件，尺寸随意：2×2 看正在上和下一节，4×2 多几节，拖到 4×4 " +
                    "自动变成本周课表。\n\n" +
                    "小组件在上下课时刻自动更新。若希望刷新更及时，可在页面下方开启" +
                    "「忽略电池优化」与「允许自启动」（可选，不开也能用）。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            if (canPin) {
                TextButton(onClick = onAdd) { Text("现在添加") }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}
