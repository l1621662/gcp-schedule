package edu.jxslu.schedule.ui.me

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
import android.content.Context
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
import kotlinx.coroutines.launch
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.widget.WidgetSize
import edu.jxslu.schedule.ui.widget.WidgetSnapshot
import edu.jxslu.schedule.ui.widget.buildWidgetSnapshot
import edu.jxslu.schedule.ui.widget.forSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BatteryCharging01
import me.rerere.hugeicons.stroke.Energy
import java.time.LocalDate

/**
 * 「我的 → 桌面小组件」设置页（DESIGN §3.6）。
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
                    "点「添加」后在系统确认框里一键放到桌面；添加后长按可拖动调整大小"
                } else {
                    "当前桌面不支持应用内添加，请长按桌面空白处 → 小组件 → 水贝贝"
                },
            ) {
                Spacer(Modifier.height(4.dp))
                widgetEntries.forEachIndexed { index, entry ->
                    if (index > 0) {
                        Spacer(Modifier.height(4.dp))
                        Row(Modifier.fillMaxWidth()) { WidgetEntryDivider() }
                        Spacer(Modifier.height(4.dp))
                    }
                    EntryRow(
                        size = entry.size(),
                        name = entry.name,
                        summary = entry.summary,
                        added = caps.addedCount[entry.receiver] ?: 0,
                        canPin = caps.canPin,
                        onAdd = { onAddClicked(context, entry)?.let(showNotice) },
                    )
                }
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
                Bullet("跟随系统深浅色；不占用额外网络。")
                Bullet("「还有 N 分钟」按刷新时刻计算，两次刷新之间不会跳动。")
                Spacer(Modifier.height(6.dp))
            }
        }
    }

    if (showIntro) {
        IntroDialog(
            canPin = caps.canPin,
            onAdd = {
                showIntro = false
                // 弹层里的「现在添加」默认走 4×2（推荐）：横条在桌面上信息密度与可读性平衡最好。
                // 弹层刚关（它也是独立窗口），提示改由页面宿主展示，不被盖住
                onAddClicked(context, widgetEntries[1])?.let(showNotice)
            },
            onDismiss = { showIntro = false },
        )
    }
}

private fun readCaps(context: Context): WidgetCaps {
    val canPin = WidgetCapabilities.canPin(context)
    val counts = widgetEntries.associate { it.receiver to WidgetCapabilities.addedCount(context, it.receiver) }
    return WidgetCaps(
        canPin = canPin,
        batteryWhitelisted = WidgetCapabilities.isIgnoringBatteryOptimizations(context),
        addedCount = counts,
    )
}

private data class WidgetCaps(
    val canPin: Boolean,
    val batteryWhitelisted: Boolean,
    val addedCount: Map<Class<*>, Int>,
)

/** 条目 → 预览档位（与 res/xml/widget_info_*.xml 的格位一致）。 */
private fun WidgetEntry.size(): WidgetSize = when (name) {
    "2×2" -> WidgetSize.Small
    "4×2" -> WidgetSize.Wide
    else -> WidgetSize.Large
}

/**
 * 添加小组件。返回 null = 已提交系统确认框；非 null = 用户可读错误，
 * 由调用方走页面统一的 [AppSnackbarHost]（此前是系统 Toast，与本页其余提示两套观感）。
 */
private fun onAddClicked(context: android.content.Context, entry: WidgetEntry): String? =
    if (WidgetCapabilities.requestPin(context, entry.receiver)) {
        null
    } else {
        WidgetCapabilities.manualAddHint
    }

/** 卡内两行之间的换气线（与分区卡的克制风格一致）。 */
@Composable
private fun WidgetEntryDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 单个条目行：左预览缩略 + 名称/摘要/已添加徽标 + 右「添加」。 */
@Composable
private fun EntryRow(
    size: WidgetSize,
    name: String,
    summary: String,
    added: Int,
    canPin: Boolean,
    onAdd: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PreviewTile(size)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = name,
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
                text = summary,
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

// ---------- 预览缩略图：用真实课表数据画一个迷你 widget ----------

/** 设置页展示用的快照（与小组件同一套状态推导），30 秒随时间重算一次。 */
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
            buildWidgetSnapshot(
                buildTodayState(
                    semester = repo.semester.first(),
                    slots = repo.timeSlots.first(),
                    courses = repo.courses.first(),
                    today = LocalDate.now(),
                    now = LocalTimeLike.now(),
                ),
            )
        }.getOrNull()
    }.value
}

/** 迷你预览：按条目比例缩画真实数据；课表为空时给占位文案。 */
@Composable
private fun PreviewTile(size: WidgetSize) {
    val snapshot = rememberPreviewSnapshot()
    val (w, h) = when (size) {
        WidgetSize.Small -> 84.dp to 84.dp
        WidgetSize.Tall -> 64.dp to 128.dp
        WidgetSize.Wide -> 116.dp to 58.dp
        WidgetSize.Large -> 128.dp to 128.dp
    }
    Box(
        modifier = Modifier
            .width(w)
            .height(h)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(7.dp),
    ) {
        val model = snapshot?.forSize(size)
        if (model == null) {
            Text(
                text = "课表\n为空",
                fontSize = 8.sp,
                lineHeight = 9.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                if (size.showHeader) {
                    Text(
                        text = model.header,
                        fontSize = 7.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PreviewFocus(model.focus)
                model.rows.take(2).forEach { row ->
                    PreviewRow(row.colorIndex, row.name, row.clock)
                }
            }
        }
    }
}

@Composable
private fun PreviewFocus(focus: edu.jxslu.schedule.ui.widget.WidgetFocus) {
    when (focus) {
        is edu.jxslu.schedule.ui.widget.WidgetFocus.Course -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(edu.jxslu.schedule.ui.common.courseColor(focus.colorIndex).copy(alpha = 0.16f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            ) {
                Column {
                    Text(
                        text = focus.label,
                        fontSize = 6.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = edu.jxslu.schedule.ui.common.courseColor(focus.colorIndex),
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

        is edu.jxslu.schedule.ui.widget.WidgetFocus.Idle -> Text(
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
                .background(edu.jxslu.schedule.ui.common.courseColor(colorIndex).copy(alpha = 0.16f))
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
                text = "提供三种尺寸：2×2 / 4×2 只看下一节（带日期），4×4 完整今日与明日预告。\n\n" +
                    "小组件在上下课时刻自动更新。若希望刷新更及时，可在页面下方开启" +
                    "「忽略电池优化」与「允许自启动」（可选，不开也能用）。",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            if (canPin) {
                TextButton(onClick = onAdd) { Text("现在添加 4×2") }
            } else {
                TextButton(onClick = onDismiss) { Text("知道了") }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("稍后") }
        },
    )
}
