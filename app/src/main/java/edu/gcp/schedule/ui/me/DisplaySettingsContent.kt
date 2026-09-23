package edu.gcp.schedule.ui.me

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.gcp.schedule.domain.CourseFilter
import edu.gcp.schedule.domain.GridFont
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.TimetablePrefs
import edu.gcp.schedule.ui.common.SettingSwitchRow
import edu.gcp.schedule.ui.common.rememberAppHaptics
import kotlin.math.roundToInt
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01

/**
 * 显示设置的选项面板主体（字号 / 布局尺寸 / 格子样式 / 内容与开关）。
 *
 * 唯一宿主是课表页的**覆盖弹层**（WeekScreen 的 DisplaySettingsOverlay，
 * 入口 = 课表页顶栏眼睛图标；「我的」侧跨 Tab 触发已于 2026-09-20 删除）：
 * 真实课表在面板上方保持可见，所有改动在真实网格上即时生效。
 *
 * [headerMinDp]：表头高度滑块的动态下限（随日期字号，见 minHeaderHeightForDateFont）。
 * 与课表布局的渲染兜底取同一个值——滑块从有效下限起步，拖到底也有可见效果，
 * 不再出现「下限标注 32dp、实际 38dp 起才生效」的死区。
 *
 * 排版纪律：**不放说明文字**——身后就是真实课表，效果即时可见，文字说明是冗余；
 * 行只留「标题 + 当前值」，面板保持低调，视线留在课表上。
 * 分组可折叠（[CollapsibleSection]），四组默认全展开。
 */
@Composable
fun DisplaySettingsContent(
    viewModel: MeViewModel,
    headerMinDp: Float,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val prefs = state.displayPrefs
    // 列数口径必须与真实网格一致（见 ScheduleCalculator.visibleDays）
    val days = ScheduleCalculator.visibleDays(prefs.showSaturday, prefs.showSunday).size

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
    ) {
        CollapsibleSection(title = "字号") {
            GridFontSizeRow(
                title = "课程名字号",
                dp = prefs.gridFontDp,
                baseSp = { GridFont.baseNameSp(days) },
                minDp = GridFont.MinDp,
                maxDp = GridFont.MaxDp,
                followHint = "跟随系统",
                onChange = viewModel::setGridFontDp,
            )
            GridFontSizeRow(
                title = "教室字号",
                dp = prefs.gridRoomDp,
                baseSp = { GridFont.baseDetailSp(days) },
                minDp = GridFont.MinDetailDp,
                maxDp = GridFont.MaxDetailDp,
                followHint = "跟随课名",
                onChange = viewModel::setGridRoomDp,
            )
            GridFontSizeRow(
                title = "教师字号",
                dp = prefs.gridTeacherDp,
                baseSp = { GridFont.baseDetailSp(days) },
                minDp = GridFont.MinDetailDp,
                maxDp = GridFont.MaxDetailDp,
                followHint = "跟随课名",
                onChange = viewModel::setGridTeacherDp,
            )
            GridFontSizeRow(
                title = "时间轴字号",
                dp = prefs.gridRailDp,
                baseSp = { GridFont.baseRailSp(days) },
                minDp = GridFont.MinRailDp,
                maxDp = GridFont.MaxRailDp,
                followHint = "跟随系统",
                onChange = viewModel::setGridRailDp,
            )
            GridFontSizeRow(
                title = "日期字号",
                dp = prefs.gridDateDp,
                baseSp = { GridFont.baseDateSp(days) },
                minDp = GridFont.MinRailDp,
                maxDp = GridFont.MaxRailDp,
                followHint = "跟随系统",
                onChange = viewModel::setGridDateDp,
            )
        }

        CollapsibleSection(title = "布局尺寸") {
            SliderSettingRow(
                title = "时间轴宽度",
                valueText = dpLabel(
                    prefs.railWidthDp.coerceIn(TimetablePrefs.MinRailWidthDp, TimetablePrefs.MaxRailWidthDp),
                ),
                value = prefs.railWidthDp.coerceIn(TimetablePrefs.MinRailWidthDp, TimetablePrefs.MaxRailWidthDp),
                valueRange = TimetablePrefs.MinRailWidthDp..TimetablePrefs.MaxRailWidthDp,
                onValueChange = { viewModel.setRailWidthDp(snapStep(it, 1f)) },
                onReset = { viewModel.setRailWidthDp(TimetablePrefs.DefaultRailWidthDp) },
                minText = "${TimetablePrefs.MinRailWidthDp.toInt()}dp",
                maxText = "${TimetablePrefs.MaxRailWidthDp.toInt()}dp",
            )
            // 表头高度：下限随日期字号动态抬高（与布局的渲染兜底同一个值，WeekScreen 传入）；
            // 存储值仍允许落在下限之下（渲染时取大），但滑块与读数从有效下限起步，
            // 「极大极小」两端都真实可辨
            val headerMax = TimetablePrefs.MaxDayHeaderHeightDp
            val headerMin = headerMinDp.coerceIn(TimetablePrefs.MinDayHeaderHeightDp, headerMax)
            SliderSettingRow(
                title = "表头高度",
                valueText = dpLabel(
                    prefs.dayHeaderHeightDp.coerceIn(headerMin, headerMax),
                ),
                value = prefs.dayHeaderHeightDp.coerceIn(headerMin, headerMax),
                valueRange = headerMin..headerMax,
                onValueChange = {
                    viewModel.setDayHeaderHeightDp(snapStep(it, 1f).coerceAtLeast(headerMin))
                },
                onReset = { viewModel.setDayHeaderHeightDp(TimetablePrefs.DefaultDayHeaderHeightDp) },
                minText = "${headerMin.roundToInt()}dp",
                maxText = "${headerMax.toInt()}dp",
            )
            SliderSettingRow(
                title = "格子高度",
                valueText = percentLabel(prefs.rowHeightScale.coerceIn(0.5f, 1.5f)),
                value = prefs.rowHeightScale.coerceIn(0.5f, 1.5f),
                valueRange = 0.5f..1.5f,
                onValueChange = { viewModel.setRowHeightScale(snapStep(it, 0.05f)) },
                onReset = { viewModel.setRowHeightScale(1f) },
                minText = "50%",
                maxText = "150%",
            )
        }

        CollapsibleSection(title = "格子样式") {
            SliderSettingRow(
                title = "格子圆角",
                valueText = dpLabel(prefs.cellRadiusDp.coerceIn(0f, 12f)),
                value = prefs.cellRadiusDp.coerceIn(0f, 12f),
                valueRange = 0f..12f,
                onValueChange = { viewModel.setCellRadiusDp(snapStep(it, 0.5f)) },
                onReset = { viewModel.setCellRadiusDp(6f) },
                minText = "0dp",
                maxText = "12dp",
            )
            SliderSettingRow(
                title = "格子不透明度",
                valueText = percentLabel(prefs.cellOpacity.coerceIn(0.5f, 1f)),
                value = prefs.cellOpacity.coerceIn(0.5f, 1f),
                valueRange = 0.5f..1f,
                onValueChange = { viewModel.setCellOpacity(snapStep(it, 0.05f)) },
                onReset = { viewModel.setCellOpacity(1f) },
                minText = "50%",
                maxText = "100%",
            )
        }

        CollapsibleSection(title = "内容与开关") {
            SettingSwitchRow(
                title = "文字水平居中",
                checked = prefs.cellCenterH,
                onCheckedChange = viewModel::setCellCenterH,
            )
            SettingSwitchRow(
                title = "文字竖直居中",
                checked = prefs.cellCenterV,
                onCheckedChange = viewModel::setCellCenterV,
            )
            SettingSwitchRow(
                title = "显示授课教师",
                checked = prefs.showTeacher,
                onCheckedChange = viewModel::setShowTeacher,
            )
            SettingSwitchRow(
                title = "显示时刻线",
                checked = prefs.showNowLine,
                onCheckedChange = viewModel::setShowNowLine,
            )
            SettingSwitchRow(
                title = "显示虚线描边",
                checked = prefs.showCellBorder,
                onCheckedChange = viewModel::setShowCellBorder,
            )
            SettingSwitchRow(
                title = "显示网格辅助线",
                checked = prefs.showGridLines,
                onCheckedChange = viewModel::setShowGridLines,
            )
            SettingSwitchRow(
                title = "地点显示「@」",
                checked = prefs.showAtSign,
                onCheckedChange = viewModel::setShowAtSign,
            )
            SettingSwitchRow(
                title = "点空白格新建课程",
                checked = prefs.tapBlankToAdd,
                onCheckedChange = viewModel::setTapBlankToAdd,
            )
            // 周末拆成两项（而非原来的单一「显示周六、周日」）：
            // 根因：一个布尔只能表达「都显示 / 都不显示」，
            // 而实际存在「周六有课、周日无课」这类课表，用户希望只留有用的那一列。
            SettingSwitchRow(
                title = "显示周六",
                checked = prefs.showSaturday,
                onCheckedChange = viewModel::setShowSaturday,
            )
            SettingSwitchRow(
                title = "显示周日",
                checked = prefs.showSunday,
                onCheckedChange = viewModel::setShowSunday,
            )
            SettingSwitchRow(
                title = "显示非本周课程",
                checked = prefs.showNonCurrentWeek,
                onCheckedChange = viewModel::setShowNonCurrentWeek,
            )
            CourseFilterRow(filter = prefs.courseFilter, onChange = viewModel::setCourseFilter)
        }
    }
}

/**
 * 可折叠分组：标题行常驻（含展开/收起箭头），内容随 [expanded] 显隐。
 *
 * 用 AnimatedVisibility 而不是 if：展开/收起有过渡，且收起的组不参与组合，
 * 滚动列表高度随之收缩。标题行整行可点，命中区足够大。
 * 四组默认全展开（调用方不再传 initiallyExpanded）。
 */
@Composable
private fun CollapsibleSection(
    title: String,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(
                    role = androidx.compose.ui.semantics.Role.Button,
                    onClickLabel = if (expanded) "收起$title" else "展开$title",
                ) { expanded = !expanded }
                .padding(horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            // 箭头随状态旋转：展开朝下、收起朝上。语义上「点开还有更多」
            Icon(
                imageVector = HugeIcons.ArrowDown01,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .size(18.dp)
                    .graphicsLayer { rotationZ = if (expanded) 0f else 180f },
            )
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.padding(bottom = 6.dp)) { content() }
        }
    }
}

private fun dpLabel(v: Float): String = GridFont.dpLabel(v)

private fun percentLabel(v: Float): String = "${(v * 100).roundToInt()}%"

private fun snapStep(value: Float, step: Float): Float =
    (value / step).roundToInt() * step

@Composable
private fun CourseFilterRow(
    filter: CourseFilter,
    onChange: (CourseFilter) -> Unit,
) {
    val haptics = rememberAppHaptics()
    Column(Modifier.padding(vertical = 12.dp)) {
        Text("显示哪些课程", style = MaterialTheme.typography.bodyLarge)
        Row(
            // 选项增至 4 个（考试），窄屏一行放不下时横向滚动兜底，不让 chip 挤压变形
            modifier = Modifier
                .padding(top = 10.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CourseFilter.entries.forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = {
                        haptics.toggle()
                        onChange(option)
                    },
                    label = { Text(option.label) },
                )
            }
        }
    }
}

/**
 * 字号滑块行（用户可设目标 dp，null = 跟随）。
 *
 * 排版：标题 + 当前值在滑块上方一行；滑块；下边界标注 + 跟随态/跟随按钮一行。
 *
 * 「跟随」控件的**高度恒定**是本行的关键约束：dp==null 时显示提示文案、设置过时
 * 显示可点的「跟随」按钮——两者必须占同一固定高度（[FollowSlot]），否则第一次
 * 拖动滑块时文案变按钮、行高突增，整面板内容随之下坠（真机反馈的体验问题）。
 */
@Composable
private fun GridFontSizeRow(
    title: String,
    dp: Float?,
    baseSp: () -> Float,
    minDp: Float,
    maxDp: Float,
    followHint: String,
    onChange: (Float?) -> Unit,
) {
    val systemFontScale = LocalDensity.current.fontScale
    val effective = (dp ?: baseSp() * systemFontScale).coerceIn(minDp, maxDp)
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            Text(
                GridFont.dpLabel(effective),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = effective,
            onValueChange = { onChange(GridFont.snapDp(it)) },
            valueRange = minDp..maxDp,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth().height(FollowSlotHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                GridFont.dpLabel(minDp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            Spacer(Modifier.weight(1f))
            FollowSlot(following = dp == null, followHint = followHint, onFollow = { onChange(null) })
            Spacer(Modifier.weight(1f))
            Text(
                GridFont.dpLabel(maxDp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}

/** 「跟随」槽位的固定高度：跟随态提示与可点按钮同高，切换时行高不变。 */
private val FollowSlotHeight = 32.dp

/**
 * 固定高度的跟随态槽位。
 * 跟随中（[following]=true）：显示 [followHint] 提示文案（不可点，本就处于跟随态）；
 * 设置过：显示「跟随」按钮，点按清掉用户值回到跟随态。两态占同一 [FollowSlotHeight]，
 * 切换不再引起行高变化。
 */
@Composable
private fun FollowSlot(
    following: Boolean,
    followHint: String,
    onFollow: () -> Unit,
) {
    if (following) {
        Text(
            followHint,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.height(FollowSlotHeight),
        )
    } else {
        TextButton(
            onClick = onFollow,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
            modifier = Modifier.height(FollowSlotHeight),
        ) {
            Text("跟随", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SliderSettingRow(
    title: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    minText: String,
    maxText: String,
    onReset: (() -> Unit)? = null,
) {
    Column(Modifier.padding(vertical = 10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.weight(1f))
            if (onReset != null) {
                TextButton(
                    onClick = onReset,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    modifier = Modifier.height(28.dp),
                ) { Text("重置", style = MaterialTheme.typography.labelMedium) }
            }
            Text(
                valueText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                minText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                maxText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
    }
}
