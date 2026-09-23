package edu.gcp.schedule.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.CourseFilter
import edu.gcp.schedule.domain.GridFont
import edu.gcp.schedule.domain.LocalTimeLike
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.SemesterConfig
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.ui.common.GhostCourseCard
import edu.gcp.schedule.ui.common.GridCellStyle
import edu.gcp.schedule.ui.common.GridCourseCard
import edu.gcp.schedule.ui.common.SingleSectionCard
import edu.gcp.schedule.ui.common.rememberAppHaptics
import edu.gcp.schedule.domain.SHORT_DAY_FORMAT
import java.time.LocalDate

/** 色块在格子里的内缩。只留 1dp：WakeUp 风格的紧凑感来自「块几乎填满格子」，此前 3dp×2 加上行距显得松散。 */
internal val CellGap = 1.dp

internal val dayLabels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

@Composable
internal fun TimeRail(
    layout: GridLayout,
    slots: List<TimeSlot>,
    monthLabel: String?,
    railFontSp: Float?,
    dateFontSp: Float?,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outlineVariant
    // 字号：用户设置过就用解析后的 sp（已预除课名倍率，净渲染值即目标 dp）；
    // 未设置时回落到本模块的固定基准档（原先的硬编码值），不再跟随课名缩放。
    val sectionSize = (railFontSp ?: 12.5f).sp
    val startSize = (railFontSp?.let { it * 0.76f } ?: 9.5f).sp
    val endSize = (railFontSp?.let { it * 0.72f } ?: 9f).sp
    // 栏宽/表头高随显示设置走（layout 是唯一几何来源），不再读模块常量
    Box(Modifier.width(layout.railWidth).fillMaxHeight()) {
        // 表头位置的月份角标：跟随所选周的周一所在月份，切周时跟着变
        if (monthLabel != null) {
            Box(
                Modifier
                    .width(layout.railWidth)
                    .height(layout.dayHeaderHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = monthLabel,
                    fontSize = (dateFontSp ?: 11f).sp,
                    fontWeight = FontWeight.Medium,
                    color = onSurface.copy(alpha = 0.5f),
                )
            }
        }
        // 表头下缘线（时间轴段）：与 WeekPage 网格顶的同款线拼成贯穿整行的表头边界。
        // 表头带是透明的，没有这条可见边界，「表头高度」滑块的效果只剩课表整体平移可看
        Box(
            Modifier
                .offset(y = layout.dayHeaderHeight)
                .width(layout.railWidth)
                .height(1.dp)
                .background(outline.copy(alpha = 0.35f)),
        )
        for (section in 1..layout.sections) {
            val slot = slots.firstOrNull { it.number == section }
            Column(
                modifier = Modifier
                    .offset(y = layout.dayHeaderHeight + layout.topOf(section))
                    .width(layout.railWidth)
                    .height(layout.rowHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = section.toString(),
                    fontSize = sectionSize,
                    lineHeight = sectionSize * 1.12f,
                    fontWeight = FontWeight.SemiBold,
                    color = onSurface.copy(alpha = 0.82f),
                )
                if (slot != null) {
                    Text(
                        text = slot.startTime,
                        fontSize = startSize,
                        lineHeight = startSize * 1.26f,
                        color = onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                    )
                    Text(
                        text = slot.endTime,
                        fontSize = endSize,
                        lineHeight = endSize * 1.22f,
                        color = onSurface.copy(alpha = 0.32f),
                        maxLines = 1,
                    )
                }
            }
        }
        // 左侧当前时间胶囊已移除：时刻信息在每节的起止时间上已有，胶囊盖在节次文字上反而添乱；
        // 当前时刻只保留网格内今日列的线（见 WeekPage），一条线索就够了
    }
}

@Composable
internal fun WeekPage(
    week: Int,
    layout: GridLayout,
    visibleDays: List<Int>,
    allCourses: List<Course>,
    slots: List<TimeSlot>,
    semester: SemesterConfig?,
    isTodayWeek: Boolean,
    todayDay: Int,
    now: LocalTimeLike,
    showNonCurrentWeek: Boolean,
    cellStyle: GridCellStyle,
    showNowLine: Boolean,
    showGridLines: Boolean,
    dateFontSp: Float?,
    tapBlankToAdd: Boolean,
    onAddEmpty: (day: Int, section: Int) -> Unit,
    onOpenCourse: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val outline = MaterialTheme.colorScheme.outlineVariant
    val haptics = rememberAppHaptics()

    // 列号一律由 visibleDays 的下标决定，不能用 day-1：
    // 单独隐藏周六后，周日在可见序列里是第 6 列（下标 5）而不是第 7 列。
    val days = visibleDays.size
    val weekCourses = remember(week, allCourses) {
        ScheduleCalculator.coursesInWeek(allCourses, week)
    }
    val otherWeekCourses = remember(week, allCourses) {
        allCourses.filter { week !in it.weeks }
    }
    val occupied = remember(weekCourses) {
        weekCourses.map { it.day to it.startSection }.toSet()
    }
    val todayVisible = isTodayWeek && todayDay in visibleDays

    Column(Modifier.fillMaxSize()) {
        // ---- 星期表头（WakeUp 式：周几取单字，下挂日期；今日整列加粗深色，不做圆底徽章——
        //      7 列窄列里徽章会把日期挤出对齐，今日列已有时刻线定位，不缺这一处强调）
        Row(
            Modifier
                .height(layout.dayHeaderHeight)
                .fillMaxWidth(),
        ) {
            visibleDays.forEach { day ->
                val label = dayLabels[day - 1]
                val isToday = todayVisible && day == todayDay
                val date = weekDate(semester, week, day)
                Column(
                    Modifier
                        .width(layout.dayWidth)
                        .height(layout.dayHeaderHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = label.removePrefix("周"),
                        fontSize = (dateFontSp ?: 12f).sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                        color = if (isToday) onSurface else onSurface.copy(alpha = 0.5f),
                    )
                    Text(
                        text = date?.format(SHORT_DAY_FORMAT) ?: "—",
                        fontSize = (dateFontSp?.let { it * 0.92f } ?: 11f).sp,
                        fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isToday) onSurface else onSurface.copy(alpha = 0.4f),
                    )
                }
            }
        }

        Box(
            Modifier
                .width(layout.dayWidth * days)
                .height(layout.gridHeight),
        ) {
            // 表头下缘线（日列段）：贴网格顶=表头带底部，与时间轴段的线连成一条。
            // 表头带透明，这条线是「表头高度」滑块的可见锚点（DESIGN §3.3）
            Box(
                Modifier
                    .width(layout.dayWidth * days)
                    .height(1.dp)
                    .background(outline.copy(alpha = 0.35f)),
            )
            // 今日列不再铺底色/边线：WakeUp 参考稿里今日只靠表头加粗 + 时刻线定位，
            // 铺底反而让当日卡片颜色被罩了一层，观感发闷

            // ---- 空位点击加课（显示设置可关：关掉后空白格只作留白，避免滑动/误触时弹编辑）
            if (tapBlankToAdd) {
                visibleDays.forEachIndexed { column, day ->
                    for (section in 1..layout.sections) {
                        if ((day to section) in occupied) continue
                        Box(
                            Modifier
                                .offset(
                                    x = layout.dayWidth * column,
                                    y = layout.topOf(section),
                                )
                                .width(layout.dayWidth)
                                .height(layout.bottomOf(section) - layout.topOf(section))
                                .clickable { onAddEmpty(day, section) },
                        )
                    }
                }
            }

            // ---- 行分隔：大节之间一条线，大节内一条极浅线（不画竖线）；显示设置可关
            if (showGridLines) {
                for (section in 1 until layout.sections) {
                    val isBigEnd = ScheduleCalculator.isBigSectionEnd(section)
                    val y = if (isBigEnd) {
                        layout.bottomOf(section) + InterGap / 2
                    } else {
                        layout.bottomOf(section) + IntraGap / 2
                    }
                    Box(
                        Modifier
                            .offset(y = y)
                            .width(layout.dayWidth * days)
                            .height(1.dp)
                            .background(
                                if (isBigEnd) outline.copy(alpha = 0.35f) else outline.copy(alpha = 0.16f),
                            ),
                    )
                }
            }

            // ---- 非本周灰态（默认关）
            if (showNonCurrentWeek) {
                val claimed = occupied.toMutableSet()
                otherWeekCourses
                    .sortedWith(compareBy({ it.day }, { it.startSection }, { it.name }))
                    .forEach { course ->
                        val column = ScheduleCalculator.columnOf(visibleDays, course.day)
                            ?: return@forEach
                        val span = (course.startSection..course.endSection).toList()
                        if (span.isEmpty()) return@forEach
                        if (span.any { (course.day to it) in claimed }) return@forEach
                        span.forEach { claimed += course.day to it }
                        GhostCourseCard(
                            name = course.name,
                            days = days,
                            cornerRadiusDp = cellStyle.cornerRadiusDp,
                            modifier = Modifier
                                .offset(
                                    x = layout.dayWidth * column + CellGap,
                                    y = layout.topOf(course.startSection) + CellGap,
                                )
                                .width(layout.dayWidth - CellGap * 2)
                                .height(layout.heightOf(course.startSection, course.endSection) - CellGap * 2),
                        )
                    }
            }

            // ---- 本周课程
            // 周内撞色兜底：学期级 16 色不够分时（理论 + 实验课混排），同一周里不同课名可能共用颜色，
            // 渲染时就地换成当周空闲色；onOpenCourse 仍传原始 course——存储色才是权威，避免编辑时把周内替色写回库
            val colorOverrides = remember(weekCourses) {
                ScheduleCalculator.weekColorOverrides(weekCourses)
            }
            weekCourses
                .sortedWith(compareBy({ it.day }, { it.startSection }))
                .forEach { course ->
                    val column = ScheduleCalculator.columnOf(visibleDays, course.day)
                        ?: return@forEach
                    val override = colorOverrides[course.name]
                    val display = if (override != null) course.copy(colorIndex = override) else course
                    val cardModifier = Modifier
                        .offset(
                            x = layout.dayWidth * column + CellGap,
                            y = layout.topOf(course.startSection) + CellGap,
                        )
                        .width(layout.dayWidth - CellGap * 2)
                        .height(
                            layout.heightOf(course.startSection, course.endSection) - CellGap * 2,
                        )
                    if (course.startSection == course.endSection) {
                        SingleSectionCard(
                            course = display,
                            days = days,
                            onClick = {
                                haptics.tap()
                                onOpenCourse(course)
                            },
                            modifier = cardModifier,
                            style = cellStyle,
                        )
                    } else {
                        GridCourseCard(
                            course = display,
                            days = days,
                            onClick = {
                                haptics.tap()
                                onOpenCourse(course)
                            },
                            modifier = cardModifier,
                            style = cellStyle,
                        )
                    }
                }

            // ---- 当前时刻线（显示设置可关）。列号同样走 visibleDays 下标。
            if (todayVisible && showNowLine) {
                val todayColumn = ScheduleCalculator.columnOf(visibleDays, todayDay) ?: 0
                // 终点收口到今日最后一节课：weekCourses 已是当前筛选口径，
                // 被筛掉的课不参与收口，避免「线还挂着、课却看不见」的错位
                val dayEnd = remember(weekCourses, slots, todayDay) {
                    ScheduleCalculator.dayLastEndMinutes(weekCourses, slots, todayDay)
                }
                nowMarker(slots, layout, now, dayEnd)?.let { marker ->
                    Box(
                        Modifier
                            .offset(x = layout.dayWidth * todayColumn, y = marker.offsetY)
                            .width(layout.dayWidth)
                            .height(1.5.dp)
                            .drawBehind {
                                if (marker.inBreak) {
                                    // 课间画虚线：位置贴在行边界上，虚线提示「这不在上课」
                                    val y = size.height / 2f
                                    drawLine(
                                        color = primary,
                                        start = Offset(0f, y),
                                        end = Offset(size.width, y),
                                        strokeWidth = size.height,
                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)),
                                    )
                                } else {
                                    drawRect(primary)
                                }
                            },
                    )
                    Box(
                        Modifier
                            .offset(
                                x = layout.dayWidth * todayColumn - 3.5.dp,
                                y = marker.offsetY - 2.5.dp,
                            )
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (marker.inBreak) primary.copy(alpha = 0.55f) else primary),
                    )
                }
            }
        }
    }
}

@Composable
internal fun EmptyScheduleHint(
    filter: CourseFilter,
    onOpenJwImport: () -> Unit,
    tapBlankToAdd: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val filtered = filter != CourseFilter.All
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (filtered) "没有${filter.label}" else "课表为空",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = when {
                filtered -> "当前只显示「${filter.label}」。可在「显示设置」里切回全部。"
                // 关掉空白格加课后，提示必须同步改口，否则会指向一个不会发生的动作
                tapBlankToAdd -> "左右滑动切换周次，点空白格可加课。"
                else -> "左右滑动切换周次。空白格加课已在显示设置里关闭。"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
            textAlign = TextAlign.Center,
        )
        // 筛选状态下的空网格不是「没导入」，不该出现导入引导
        if (!filtered) {
            TextButton(onClick = onOpenJwImport) { Text("从教务导入") }
        }
    }
}

internal fun weekDate(semester: SemesterConfig?, week: Int, day: Int): LocalDate? {
    if (semester == null || week < 1 || day !in 1..7) return null
    return runCatching {
        val startMonday = ScheduleCalculator.startOfWeek(
            ScheduleCalculator.parseDate(semester.startDate),
        )
        startMonday
            .plusWeeks((week - 1).toLong())
            .plusDays((day - 1).toLong())
    }.getOrNull()
}
