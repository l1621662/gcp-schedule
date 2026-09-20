package edu.jxslu.schedule.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.Timetable
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.courseColor
import edu.jxslu.schedule.ui.detect.DetectNotice
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileExport
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Import
import me.rerere.hugeicons.stroke.RefreshCcwDot
import me.rerere.hugeicons.stroke.CalendarSync

/**
 * 周次选择器：只管「看哪一周」。
 *
 * 根因：此前周次网格和全部显示开关挤在同一个弹层里，顶栏胶囊与眼睛图标又指向同一个入口——
 * 用户想改一个显示选项也得从周次弹层里翻。现在拆开：胶囊（周次区）→ 本弹层；
 * 眼睛图标 → 课表页内覆盖面板（WeekScreen.DisplaySettingsOverlay，复用 DisplaySettingsContent）。
 * 「我的 → 显示设置」跨 Tab 触发同一个覆盖面板（见 MainActivity），显示设置只有一个形态。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekPickerSheet(
    currentWeek: Int,
    /** 真实的「本周」周次（todayWeek）：与正在查看的 [currentWeek] 是两回事，徽标用它。 */
    todayWeek: Int,
    totalWeeks: Int,
    weeksWithCourses: Set<Int>,
    onPickWeek: (Int) -> Unit,
    onBackToCurrentWeek: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("选择周次", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onBackToCurrentWeek) { Text("回到本周") }
            }
            Spacer(Modifier.height(4.dp))
            WeekChipGrid(
                totalWeeks = totalWeeks,
                currentWeek = currentWeek,
                todayWeek = todayWeek,
                weeksWithCourses = weeksWithCourses,
                onPick = onPickWeek,
            )
        }
    }
}

@Composable
private fun WeekChipGrid(
    totalWeeks: Int,
    currentWeek: Int,
    todayWeek: Int,
    weeksWithCourses: Set<Int>,
    onPick: (Int) -> Unit,
) {
    val perRow = 6
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        (1..totalWeeks.coerceAtLeast(1)).chunked(perRow).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chunk.forEach { week ->
                    WeekChip(
                        week = week,
                        selected = week == currentWeek,
                        isThisWeek = week == todayWeek,
                        hasCourse = week in weeksWithCourses,
                        onClick = { onPick(week) },
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(perRow - chunk.size) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun WeekChip(
    week: Int,
    selected: Boolean,
    isThisWeek: Boolean,
    hasCourse: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val bg = when {
        selected -> scheme.primary
        hasCourse -> scheme.surfaceVariant
        else -> scheme.surface
    }
    val fg = when {
        selected -> scheme.onPrimary
        hasCourse -> scheme.onSurface
        else -> scheme.onSurface.copy(alpha = 0.35f)
    }
    Column(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            // 「本周」描边：翻走几周后打开面板也找得回今天在哪一周（选中态自绘 primary 底，
            // 不叠描边）
            .then(
                if (isThisWeek && !selected) {
                    Modifier.border(1.5.dp, scheme.primary, RoundedCornerShape(10.dp))
                } else {
                    Modifier
                },
            )
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = week.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = fg,
        )
        if (hasCourse && !selected) {
            Box(
                Modifier
                    .padding(top = 2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(scheme.primary.copy(alpha = 0.6f)),
            )
        }
    }
}

/**
 * 课表页顶栏「导入」入口弹层：手动加课 / JSON 文件 / 教务导入 / 检测课表更新。
 *
 * 根因：此前三种导入散在三处（+ 图标只管加课、JSON 在「我的」页深处、教务导入靠空态提示），
 * 新用户拿到空课表找不到入口。收进一个弹层后，导入路径都在课表页顶栏一眼可见。
 *
 * 「检测课表更新」（DESIGN §4.17）2026-09-19 从「我的」页迁来：手动检测与导入同属
 * 「把教务数据弄进来」这一类动作，放同一弹层里用户不必跨页找；[detectChecking] 为 true 时
 * 该行文案变「正在检测…」且不可点——网络操作 1–3 秒，行内反馈足够，不弹进度框。
 *
 * 检测结果同样走**行内**（[detectNotice]）：弹层是独立窗口，比页面高一层，
 * 结果若只发 Snackbar 就会被本弹层盖住（此前正是如此）。弹层按设计保持打开，
 * 结果就落在发起检测的那一行下面。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportEntrySheet(
    onManualAdd: () -> Unit,
    onJsonImport: () -> Unit,
    onJwImport: () -> Unit,
    /** 手动检测调课（DESIGN §4.17）；结果内联在弹层里，有差异时才关弹层进「更新课表」 */
    onDetectUpdate: () -> Unit,
    /** 检测进行中：行内文案切换 + 防重复点击 */
    detectChecking: Boolean,
    /** 上一次检测的结果（null=还没检测过）；有差异的结果不在这里，直接跳页了 */
    detectNotice: DetectNotice? = null,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "导入课表",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ImportEntryRow(
                icon = { Icon(HugeIcons.Add01, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = "手动添加课程",
                subtitle = "在网格空白处新增或编辑单门课程",
                onClick = onManualAdd,
            )
            ImportEntryRow(
                icon = { Icon(HugeIcons.FileImport, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = "导入 JSON 文件",
                subtitle = "从导出的课表 JSON 恢复或合并",
                onClick = onJsonImport,
            )
            ImportEntryRow(
                icon = { Icon(HugeIcons.Import, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = "从教务导入",
                subtitle = "登录教务系统，解析学期理论 / 实验课表",
                onClick = onJwImport,
            )
            ImportEntryRow(
                icon = {
                    Icon(HugeIcons.RefreshCcwDot, contentDescription = null, modifier = Modifier.size(22.dp))
                },
                title = if (detectChecking) "正在检测…" else "检测课表更新",
                subtitle = "按教务最新课表检查是否被调课",
                enabled = !detectChecking,
                onClick = onDetectUpdate,
            )
            // 结果行只在检测结束后出现；再次点检测时由调用方清空
            if (detectNotice != null && !detectChecking) {
                InlineNoticeRow(
                    message = detectNotice.text,
                    tone = detectNotice.tone,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * 课表页顶栏「分享」入口弹层（DESIGN §4.12）：日历同步 / CSV / JSON。
 *
 * 形态与 [ImportEntrySheet] 一致（标题 + 三行入口）；具体动作在 WeekScreen 接线。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareEntrySheet(
    onSyncCalendar: () -> Unit,
    onExportCsv: () -> Unit,
    onExportJson: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                "分享课表",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            ImportEntryRow(
                icon = { Icon(HugeIcons.CalendarSync, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = "一键同步到手机日历",
                subtitle = "写入系统日历并按设置提醒；重复同步自动去重",
                onClick = onSyncCalendar,
            )
            ImportEntryRow(
                icon = { Icon(HugeIcons.FileExport, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = "导出为 CSV（日历格式）",
                subtitle = "Google 日历可直接导入的通用格式",
                onClick = onExportCsv,
            )
            ImportEntryRow(
                icon = { Icon(HugeIcons.File02, contentDescription = null, modifier = Modifier.size(22.dp)) },
                title = "导出为 JSON",
                subtitle = "完整课表数据，可在其他设备导入水贝贝",
                onClick = onExportJson,
            )
        }
    }
}

@Composable
private fun ImportEntryRow(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    /** false = 进行中/不可点：文案照常显示，点击与按压反馈一起禁用 */
    enabled: Boolean = true,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val contentAlpha = if (enabled) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f * contentAlpha)),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = onSurface.copy(alpha = contentAlpha),
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f * contentAlpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 课表切换弹层（DESIGN §4.9）：顶栏课表名入口 → 单选切换 + 「管理课表」。
 *
 * 单选即切换：courses / 作息 / 学期 / 显示偏好全部随当前课表换流，
 * 点选项后立刻关弹层让用户看到结果，不做「选中再确认」的多余一步。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableSwitchSheet(
    timetables: List<Timetable>,
    currentTimetableId: Long,
    onSelect: (Long) -> Unit,
    onOpenManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("切换课表", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            HorizontalDivider()
            timetables.forEach { t ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(t.id) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = t.id == currentTimetableId,
                        onClick = { onSelect(t.id) },
                    )
                    Text(
                        t.name,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            HorizontalDivider()
            TextButton(onClick = onOpenManage) { Text("管理课表…") }
        }
    }
}
