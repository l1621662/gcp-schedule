package edu.gcp.schedule.ui.week

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.gcp.schedule.domain.LocalTimeLike
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.domain.TimetablePrefs

/**
 * 大节之间的空隙。根因：此前 12dp（加上课块两侧各 1dp 内缩，同日相邻课块实际空 14dp），
 * 真机反馈同一天两节课之间的空白偏大——课块本身已内缩 1dp，12:3 的「20 分钟:5 分钟」
 * 设计比例在视觉上只贡献了松散感。收成 6dp：保留「大节间 > 大节内」的层级即可，
 * 不再追求与作息分钟数成比例。
 */
internal val IntraGap = 3.dp
internal val InterGap = 6.dp

/** 再挤不能低于这个高度，否则课程名放不下；不足时整表竖滑。 */
internal val MinRowHeight = 40.dp

/**
 * 课表右缘与屏幕右边的留白。
 *
 * 根因：此前列宽 =（总宽 − 时间轴宽度）/ 列数，网格精确贴满到屏幕右缘——
 * 静态看右侧顶死边界，翻周滑动时本周周日列与次周周一列之间零间隔、几乎连成一片。
 * 收进 16dp 后：网格右端离屏 16dp，滑动途中相邻两周的课也由它隔开。
 */
internal val GridEndGap = 16.dp

/**
 * 网格几何：行号 = 小节号（1–11）。
 *
 * 根因：旧实现只有 5 行（大节），却拿小节号当行号用，
 * 于是 7-8 节的课落到第 7 行、9-10 节落到第 9 行，直接被画到网格外。
 * 现在行数与 `TimeSlot.number` 一一对应，不会再错位。
 */
internal data class GridLayout(
    val rowHeight: Dp,
    /** 下标 0 是第 1 节的顶部 */
    val rowTops: List<Dp>,
    val rowBottoms: List<Dp>,
    val dayWidth: Dp,
    /** 顶部表头（星期+日期）实际使用的高度，随显示设置可调 */
    val dayHeaderHeight: Dp,
    /** 左侧时间轴实际使用的栏宽，随显示设置可调 */
    val railWidth: Dp,
) {
    val gridHeight: Dp get() = rowBottoms.last()
    val sections: Int get() = rowTops.size

    fun topOf(section: Int): Dp = rowTops[(section - 1).coerceIn(0, rowTops.lastIndex)]
    fun bottomOf(section: Int): Dp = rowBottoms[(section - 1).coerceIn(0, rowBottoms.lastIndex)]
    fun heightOf(startSection: Int, endSection: Int): Dp = bottomOf(endSection) - topOf(startSection)
}

internal fun buildGridLayout(
    maxHeight: Dp,
    maxWidth: Dp,
    sectionCount: Int,
    dayCount: Int,
    rowHeightScale: Float = 1f,
    railWidth: Dp = TimetablePrefs.DefaultRailWidthDp.dp,
    dayHeaderHeight: Dp = TimetablePrefs.DefaultDayHeaderHeightDp.dp,
): GridLayout {
    val groups = ScheduleCalculator.BIG_SECTIONS
    val intraCount = groups.sumOf { it.last - it.first }
    val interCount = (groups.size - 1).coerceAtLeast(0)

    val available = maxHeight - dayHeaderHeight
    val rawRow = (available - IntraGap * intraCount - InterGap * interCount) / sectionCount
    // 倍率乘在自适应值上（不是乘在 MinRowHeight 上）：>1 时超出屏幕走竖滑，<1 时仍受下限保护
    val rowHeight = maxOf(rawRow * rowHeightScale, MinRowHeight)

    val tops = ArrayList<Dp>(sectionCount)
    val bottoms = ArrayList<Dp>(sectionCount)
    var y = 0.dp
    for (section in 1..sectionCount) {
        tops += y
        y += rowHeight
        bottoms += y
        if (section < sectionCount) {
            y += if (ScheduleCalculator.isBigSectionEnd(section)) InterGap else IntraGap
        }
    }
    return GridLayout(
        rowHeight = rowHeight,
        rowTops = tops,
        rowBottoms = bottoms,
        dayWidth = (maxWidth - railWidth - GridEndGap) / dayCount.coerceAtLeast(1),
        dayHeaderHeight = dayHeaderHeight,
        railWidth = railWidth,
    )
}

/**
 * 当前时刻在网格里的位置。
 *
 * [inBreak]=false 表示正处在一节课内，位置按 40 分钟线性插值；
 * [inBreak]=true 表示落在课间/午休/晚休，此时**贴到离得近的那一端**
 * （下课的下一行顶部，或下一节的行顶），而不是在整段间隔里线性走。
 *
 * 根因：网格的纵向比例是设计比例而非时间比例——17:10→19:00 这 110 分钟的晚饭
 * 只占 12dp，而 40 分钟一节课占约 50dp。若在间隔里线性插值，17:36 的时刻线
 * 只会落在 17:10 下方 2.8dp，看起来像「卡住/偏了」；而它其实是准确的，
 * 是「把 110 分钟压进 12dp」这件事让它没法同时表示准确与直观。
 * 改成吸附后：下课边界与上课边界二选一，位置稳定且语义清楚（已上完 / 即将开始）。
 */
internal data class NowMarker(val offsetY: Dp, val inBreak: Boolean)

internal fun nowMarker(
    slots: List<TimeSlot>,
    layout: GridLayout,
    now: LocalTimeLike,
    dayEndMinutes: Int?,
): NowMarker? {
    val minutes = now.toMinutes()
    // 终点按当日课程收口：上完最后一节就消失，而不是挂到作息表 21:10；
    // dayEndMinutes == null = 今天没课（或结束时间无法判定），整天不画线。
    // 起点仍按作息表：次日到第一节开始（默认 08:30）线自动回来。
    if (dayEndMinutes == null || minutes >= dayEndMinutes) return null
    val first = slots.minByOrNull { it.number } ?: return null
    // 还没到第一节：不画线，免得凌晨时刻线贴在网格顶部像个 bug
    if (minutes < ScheduleCalculator.toMinutes(first.startTime)) return null

    for (section in 1..layout.sections) {
        val slot = slots.firstOrNull { it.number == section } ?: continue
        val start = ScheduleCalculator.toMinutes(slot.startTime)
        val end = ScheduleCalculator.toMinutes(slot.endTime)
        if (end <= start) continue

        if (minutes in start until end) {
            val frac = (minutes - start).toFloat() / (end - start)
            val top = layout.topOf(section)
            return NowMarker(top + (layout.bottomOf(section) - top) * frac, inBreak = false)
        }
        if (minutes < start) {
            if (section == 1) return null
            val prev = slots.firstOrNull { it.number == section - 1 } ?: return null
            val prevEnd = ScheduleCalculator.toMinutes(prev.endTime)
            if (minutes < prevEnd) continue
            // 距离哪一端近就贴哪一端：刚下课贴行底，快上课贴下一节行顶
            val offset = if (minutes - prevEnd <= start - minutes) {
                layout.bottomOf(section - 1)
            } else {
                layout.topOf(section)
            }
            return NowMarker(offset, inBreak = true)
        }
    }
    return null
}

/**
 * 表头高度下限：装得下「星期 + 日期」两行文字所需的最小高度。
 *
 * 根因：表头高度与日期字号是两个独立滑块，用户可以把表头压到 32dp 再把日期字号
 * 拉到 14dp——两行文字会被压出行外（顶部截断）。布局侧用
 * `max(用户表头高度, 本函数结果)` 兜底：**存储值不被改写**（滑块停在用户拖到的位置），
 * 只是渲染时抬高，保证文字永远完整可见。
 *
 * 两行文字：主行 [dateFontSp] + 副行 0.92×，行高按 1.3 倍估，加上下呼吸空间 10dp。
 */
internal fun minHeaderHeightForDateFont(dateFontSp: Float): Float =
    (dateFontSp * (1f + 0.92f) * 1.3f + 10f)
        .coerceIn(TimetablePrefs.MinDayHeaderHeightDp, TimetablePrefs.MaxDayHeaderHeightDp)
