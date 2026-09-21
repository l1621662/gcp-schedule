package edu.jxslu.schedule.ui.widget

import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.TodayState
import edu.jxslu.schedule.domain.clockOf
import edu.jxslu.schedule.domain.compactPosition
import edu.jxslu.schedule.domain.dayLabel
import edu.jxslu.schedule.domain.sectionRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 桌面小组件的**展示模型**（DESIGN §3.6）。
 *
 * 分两层：
 * 1. [WidgetSnapshot]：与尺寸无关的全量快照（可序列化，存进 Glance 的 DataStore 状态）。
 *    widget 的组合只读这份快照——组合里不能直接读 Room（suspend），快照由后台协程刷新。
 * 2. [WidgetSnapshot.forSize]：按实测尺寸裁出真正渲染的 [WidgetModel]。
 *
 * **课程口径完全来自 [TodayState]**（今日页同源）：哪节课算正在上、什么时候轮到明天上桌，
 * 由 `domain/buildTodayState` 决定，这里只做「信息怎么摆」。
 *
 * ## 尺寸自适应（2026-09-20 改版）
 *
 * 旧版是 `SizeMode.Responsive` 声明四档、条目拆成 2×2 / 4×2 / 4×4 三个，渲染按
 * 「就近取档」——拖动后尺寸对不上档位就被吸到别档，三个条目最终显示一样，纯冗余。
 * 现改为单条目 + `SizeMode.Exact`：宿主给多少 dp 就按多少算（[WidgetMetrics]），
 * 拖动过程中内容跟着变。
 *
 * ## 明日接棒（2026-09-20 改版）
 *
 * 旧版把明日块用弹性空白推到卡片底部，今天上完时中间一大段空白，像渲染坏了。
 * 现在快照里直接给出「列表属于哪天」（[WidgetSnapshot.listDay]）：
 * 今天还有待上课程 → 今天（焦点卡 + 剩余）；今天上完 / 没课 → 明天（状态行 + 明日列表）。
 */

/** 一行课程：时刻 + 课名 + 副行（`@地点 · 教师`）。 */
@Serializable
data class WidgetCourseRow(
    val clock: String,
    val name: String,
    val meta: String,
    val colorIndex: Int,
)

/** 焦点位。 */
@Serializable
sealed interface WidgetFocus {
    /** 正在上课 / 下一节。 */
    @Serializable
    @SerialName("course")
    data class Course(
        /** `正在上课 · 第3-4节` / `下一节 · 第3-4节`（与今日页同款标签） */
        val label: String,
        val name: String,
        /** `@南B208 · 陈磊` */
        val meta: String,
        /** `还有 25 分钟下课` / `课间 · 还有 4 分钟上课`；没有则 null */
        val countdown: String? = null,
        /** 没有倒计时时的兜底文案（`10:15 上课`） */
        val fallbackNote: String? = null,
        val colorIndex: Int,
        /** 正在上课时的整门课进度 0f..1f；不画进度条时 null */
        val progress: Float? = null,
    ) : WidgetFocus

    /**
     * 主体位的一句话：假期中 / 今天上完 / 今天没课。
     *
     * [detail] 只在没有列表可接（假期、明日也无课）时给落点；明日接棒时由列表标题
     * 与首行承担，不再重复一句。
     */
    @Serializable
    @SerialName("idle")
    data class Idle(val title: String, val detail: String? = null) : WidgetFocus
}

/** 列表属于哪一天。 */
@Serializable
enum class WidgetDay { Today, Tomorrow }

/**
 * 与尺寸无关的全量快照。
 *
 * @param header 顶栏一行：`9月20日 周日 · 第 3 周`
 * @param focus 焦点位（正在上 / 下一节 / 一句话状态）
 * @param rows 焦点位下方列表：今日剩余（不含焦点那门）或**明日全部**（[listDay] = Tomorrow）
 * @param listDay [rows] 属于哪天——渲染层据此决定要不要打「明天 · 周五 · 3 节」标题
 * @param listTitle 明日列表标题（`明天 · 周五 · 3 节`）；今日列表或明日无课时 null
 * @param summary 4×4 摘要行（`正在上课 · 第3-4节 高数 @北B102` /
 *   `明天 10:15 高数 @北B102`）；仅 [WidgetLayout.Week] 渲染
 * @param week 本周迷你网格（4×4 及以上渲染）；不在学期内 / 课表为空时 null
 */
@Serializable
data class WidgetSnapshot(
    val header: String,
    val focus: WidgetFocus,
    val rows: List<WidgetCourseRow> = emptyList(),
    val listDay: WidgetDay = WidgetDay.Today,
    val listTitle: String? = null,
    val summary: String? = null,
    val week: WidgetWeek? = null,
)

// ---------------------------------------------------------------------------
// 周迷你网格（4×4 及以上）
// ---------------------------------------------------------------------------

/** 迷你网格里的一块课（位置与颜色；文案渲染时再截断）。 */
@Serializable
data class WidgetWeekBlock(
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val name: String,
    val colorIndex: Int,
)

/**
 * 本周迷你网格。
 *
 * @param days 可见星期序列（1=周一 … 7=周日），**列序即此表下标**（与课表页同一口径，
 *   见 `ScheduleCalculator.visibleDays`）；渲染时不要用 `day - 1` 当列号
 * @param blocks 已按天分组、同日重叠去重（只留最先开始的那门）的课块
 * @param highlightDay 高亮列：今天还有待上课程时是今天，否则是明天（回答「接下来」）；
 *   不在 [days] 里则为 null（例如显示设置关了周日而明天正是周日）
 * @param nowDay / @param nowSection 此刻在哪一天的哪一小节（1-based）；
 *   **只有这两者同时命中才标「现在」**——[nowSection] 单独给会让周一到周日整行都被点亮。
 *   不在课内/课间窗口内、或今天上完 / 没课时为 null（那时高亮列是明天，
 *   在今天标「现在」没意义）。
 *   Glance 没有绝对定位（无 `offset`），逐格着色是唯一可行的时刻线表达。
 * @param sectionCount 网格行数（= 小节数，默认 11）
 */
@Serializable
data class WidgetWeek(
    val days: List<Int>,
    val blocks: List<WidgetWeekBlock>,
    val highlightDay: Int? = null,
    val nowDay: Int? = null,
    val nowSection: Int? = null,
    val sectionCount: Int = DefaultSectionCount,
)

/** 默认小节数（与 DESIGN §3.5 的 11 小节一致；自定义作息更多时按实际取大）。 */
internal const val DefaultSectionCount = 11

/**
 * 周网格数据。
 *
 * 取舍（与课表页一致的地方刻意一致，不一致的地方在注释里写明）：
 * - 列 = [ScheduleCalculator.visibleDays]（尊重「显示周六 / 周日」）；
 *   行 = 小节号 1..sectionCount；`week !in course.weeks` 的课不进网格（迷你网格只看本周）
 * - 过滤 = [CourseFilter]（与课表页的「显示哪些课程」同一份偏好）
 * - **同一天重叠的课只画最先开始的那门**：迷你网格不做冲突排版（课表页会并排缩窄，
 *   这里没有那个宽度预算；画上去会互相盖住）
 * - 高亮列：今天还有待上课程 → 今天；否则 → 明天（跨周时仍高亮次日星期几，
 *   网格内容是本周的——这是刻意取舍：告诉用户「下次看哪一列」比严格对齐周次有用）
 */
fun buildWidgetWeek(
    courses: List<Course>,
    week: Int,
    showSaturday: Boolean,
    showSunday: Boolean,
    filter: CourseFilter,
    highlightDay: Int?,
    slots: List<TimeSlot>,
    now: LocalTimeLike,
    /** 是否标记「此刻在第几节」；今天还有课可上才传 true（见 [WidgetWeek.nowSection]）。 */
    markNow: Boolean = true,
    /** 「现在」落在哪一天；默认与 [highlightDay] 同源（今天还有课 → 今天）。 */
    nowDay: Int? = highlightDay,
): WidgetWeek {
    val sectionCount = maxOf(DefaultSectionCount, slots.maxOfOrNull { it.number } ?: 0)
    val days = ScheduleCalculator.visibleDays(showSaturday, showSunday)
    val inWeek = courses.filter { week in it.weeks && filter.matches(it.kind) }

    val blocks = days.flatMap { day ->
        var lastEnd = 0
        inWeek.filter { it.day == day }
            .sortedWith(compareBy({ it.startSection }, { it.endSection }))
            .mapNotNull { course ->
                val start = course.startSection.coerceIn(1, sectionCount)
                val end = course.endSection.coerceIn(start, sectionCount)
                // 与上一块重叠（含共享边界）就跳过：迷你网格只画最先开始的那门
                if (start <= lastEnd) return@mapNotNull null
                lastEnd = end
                WidgetWeekBlock(
                    day = day,
                    startSection = start,
                    endSection = end,
                    name = course.name,
                    colorIndex = course.colorIndex,
                )
            }
    }

    return WidgetWeek(
        days = days,
        blocks = blocks,
        highlightDay = highlightDay?.takeIf { it in days },
        nowDay = nowDay?.takeIf { it in days },
        nowSection = if (markNow) currentSectionNumber(slots, now) else null,
        sectionCount = sectionCount,
    )
}

/**
 * 此刻落在哪一小节（1-based）；不在任何一节或课间窗口内为 null。
 *
 * 与课表页 `nowMarker` 同源语义（那里返回纵向偏移，这里返回节次号）：课内归本节，
 * 课间归**下一节**（说「现在在第 4 节」不如说「马上要上第 5 节」有用）；
 * 早于第一节、晚于最后一节不标。
 */
fun currentSectionNumber(slots: List<TimeSlot>, now: LocalTimeLike): Int? {
    val minutes = now.toMinutes()
    val ordered = slots.sortedBy { it.number }
    if (ordered.isEmpty()) return null
    val firstStart = ScheduleCalculator.toMinutes(ordered.first().startTime)
    val lastEnd = ScheduleCalculator.toMinutes(ordered.last().endTime)
    if (minutes < firstStart || minutes > lastEnd) return null

    ordered.forEachIndexed { index, slot ->
        val start = ScheduleCalculator.toMinutes(slot.startTime)
        val end = ScheduleCalculator.toMinutes(slot.endTime)
        if (end <= start) return@forEachIndexed
        if (minutes in start until end) return slot.number
        // 课间（本节结束到下一节开始之间）算下一节
        val next = ordered.getOrNull(index + 1)
        if (next == null) return slot.number
        val nextStart = ScheduleCalculator.toMinutes(next.startTime)
        if (minutes in end until nextStart) return next.number
    }
    return null
}

// ---------------------------------------------------------------------------
// 尺寸度量：实测 dp → 布局档
// ---------------------------------------------------------------------------

/**
 * 布局档（DESIGN §3.6）。判据是「够不够放」而不是「等于哪一档」——
 * 国产 ROM 给的尺寸不一定对齐整数格位。
 */
enum class WidgetLayout {
    /** 任一边太窄：日期行 + 焦点卡（2×2 / 4×2）。 */
    Compact,

    /** 够放焦点卡 + 若干行列表，但不够放周网格（如 2×4、4×3）。 */
    List,

    /** 4×4 及以上：日期行 + 摘要行 + 本周迷你网格。 */
    Week,
}

/**
 * 实测尺寸 → 渲染参数（[WidgetLayout] 与排版细节）。
 *
 * 判据只有「够不够放」两问：
 * - 宽高都 ≥ 230dp → [WidgetLayout.Week]（4×4 及以上的周网格）；
 * - 高度 ≥ 170dp → [WidgetLayout.List]（含 2×4 这类「窄但高」——旧版也有竖条档）；
 * - 否则 → [WidgetLayout.Compact]（2×2 与 4×2：日期 + 焦点卡）。
 *
 * 刻意**不看宽度决定 Compact**：110×250 的竖条虽然窄，但高度足够把列表铺开，
 * 按宽度判会让「拖高」这个动作什么都不变（旧版三档看起来一样的原因之一）。
 */
data class WidgetMetrics(
    val widthDp: Int,
    val heightDp: Int,
    val layout: WidgetLayout,
    /** 焦点课课名行数上限：紧凑档 1 行（110dp 高只够一行名 + 副行），其余 2 行。 */
    val focusNameLines: Int,
    /**
     * 焦点卡的副行（`@北B102 · 陈磊`）是否**与课名同行**（右对齐小字）。
     *
     * 紧凑档的宽矮形态（4×2：250×110）只有 ~65dp 给焦点卡，竖着排
     * 「标签 + 课名 + 副行」需要 ~66dp，副行必被裁掉最后一行；改成同行后
     * 把富余的**宽度**用起来，副行完整可见。2×2（110dp 宽）同行放不下，
     * 仍走竖排（此时副行确实拥挤，但宽度也没别的办法）。
     */
    val metaInline: Boolean,
    /** 是否画正在上课的进度条（紧凑档 110dp 高放不下）。 */
    val showProgress: Boolean,
) {
    /** 列表行数预算（[WidgetLayout.List] 才有意义；已为「还有 N 节」留位）。 */
    fun rowsBudget(totalRows: Int): Int =
        if (layout == WidgetLayout.List) rowsBudgetFor(heightDp, totalRows) else 0

/**
 * 紧凑档「明日接棒」能放几行明日课程。
 *
 * 紧凑档平时整列不放行（焦点卡已占满 110dp）；但今天上完/没课时焦点位换成
 * 一行状态文字，省下的高度够放 1–2 行**单行式**明日课程（`10:15 高数 @北B102`）——
 * 用户最想知道的是「明天第一节几点上什么」，只给一句「今天上完了」等于没说。
 * 这里按单行式的行距（[CompactRowPitchDp]）算，与渲染层一致。
 */
val compactHandoffRows: Int
    get() {
        val available = heightDp - VerticalPaddingDp - HeaderLineDp - StatusLineHeightDp
        return (available / CompactRowPitchDp).coerceIn(0, 2)
    }
}

/** 分档阈值（dp）。给宽不要求精确——宁可提前降档，也不要内容被裁。 */
private const val CompactSideDp = 170
private const val WeekSideDp = 230

fun widgetMetricsFor(widthDp: Int, heightDp: Int): WidgetMetrics {
    val layout = when {
        widthDp >= WeekSideDp && heightDp >= WeekSideDp -> WidgetLayout.Week
        heightDp >= CompactSideDp -> WidgetLayout.List
        else -> WidgetLayout.Compact
    }
    // 紧凑档的焦点卡竖向预算（110 − 边距20 − 日期行21 − 间距4 ≈ 65dp）只够
    // 「标签 15 + 课名 19 + 副行 14 + 间距 5」——课名一律 1 行，副行宽时同行
    val compact = layout == WidgetLayout.Compact
    return WidgetMetrics(
        widthDp = widthDp,
        heightDp = heightDp,
        layout = layout,
        focusNameLines = if (compact) 1 else 2,
        metaInline = compact && widthDp >= WideMetaDp,
        showProgress = layout == WidgetLayout.List,
    )
}

/** 副行与课名同排所需的最小宽度（`课名` 至少留 ~90dp + 副行 ~110dp）。 */
private const val WideMetaDp = 200

// ---------------------------------------------------------------------------
// 列表行数预算（纯函数，单测钉死）
// ---------------------------------------------------------------------------

/**
 * 一行课程（时刻 + 色卡两行文字）的纵向占位：色卡内两行文字 ≈39.5dp + 5dp 间距。
 * 按实测字高取整到 45——宁可少放一行，也不要最后一行被桌面裁掉半截。
 */
internal const val RowPitchDp = 45

/** 紧凑档单行式课程行（`10:15 高数 · @北B102` 一行）的行距：约 15dp 文字 + 5dp 间距。 */
internal const val CompactRowPitchDp = 20

/** 顶栏日期行（11sp 一行 + 6dp 间距）。渲染层与周网格的竖向预算共用同一常量。 */
internal const val HeaderLineDp = 21

/**
 * 焦点卡占位：取「两行课名」的常见高度（色卡内边距 16 + 标签 15 + 间距 3 + 课名 38 +
 * 间距 2 + 副行 14）。单行课名时实际更矮，多出的余量正好容下明日接棒时的标题行。
 */
private const val FocusHeightDp = 88

/** 截断提示行「还有 N 节」（4dp 间距 + 10sp 一行）。 */
private const val MoreLabelHeightDp = 21

/** 卡片上下内边距之和。渲染层与周网格的竖向预算共用同一常量。 */
internal const val VerticalPaddingDp = 20

/**
 * 高度 [heightDp] 下放得下的列表行数。
 *
 * 留位规则：**只有确实要截断时才为「还有 N 节」留位**——
 * 全部放得下就全放（不留空），要截断时最多放到「N 行 + 提示」也装得下的那个 N。
 * 一行（含提示）都放不下时返回 0：宁可只显示焦点卡，也不让半行内容被桌面裁掉。
 */
internal fun rowsBudgetFor(heightDp: Int, totalRows: Int): Int {
    if (totalRows <= 0) return 0
    val available = heightDp - VerticalPaddingDp - HeaderLineDp - FocusHeightDp
    if (available < RowPitchDp) return 0
    // 先看能不能全放下（此时不需要「还有 N 节」，别为它白扣一行）
    val maxRows = available / RowPitchDp
    if (maxRows >= totalRows) return totalRows
    var rows = maxRows
    while (rows > 1 && rows * RowPitchDp + MoreLabelHeightDp > available) rows--
    return if (rows * RowPitchDp + MoreLabelHeightDp > available) 0 else rows
}

/**
 * 紧凑档「明日接棒」时的状态行高度：一行 13sp 标题 + 间距。
 * 与 [FocusHeightDp] 分开——状态行比焦点卡矮得多，正是这点高度容下了明日首行。
 */
private const val StatusLineHeightDp = 30

// ---------------------------------------------------------------------------
// 按尺寸裁剪
// ---------------------------------------------------------------------------

/**
 * 一档尺寸的渲染输入（由 [WidgetSnapshot.forSize] 裁出）。
 *
 * @param rowsMoreLabel 列表被截断时的提示（`还有 2 节`）；未截断为 null
 * @param compactRows rows 是否用**单行式**渲染（`10:15 高数 · @北B102`）。
 *   只有紧凑档的明日接棒会带行（见 [WidgetMetrics.compactHandoffRows]），
 *   110dp 高里放不下两行的色卡式行，只能压成一行。
 */
data class WidgetModel(
    val header: String,
    val focus: WidgetFocus,
    val rows: List<WidgetCourseRow>,
    val rowsMoreLabel: String?,
    val listDay: WidgetDay,
    val listTitle: String?,
    val summary: String?,
    val week: WidgetWeek?,
    val compactRows: Boolean = false,
)

/** 按实测尺寸裁剪快照：只决定「放得下多少」，不改任何课程取舍。 */
fun WidgetSnapshot.forSize(metrics: WidgetMetrics): WidgetModel {
    val (shown, moreLabel, compactRows) = when (metrics.layout) {
        // 紧凑档平时整列不放行（焦点卡已占满 110dp），也不放「还有 N 节」——
        // 一行提示挤不掉，信息以日期行 + 焦点卡为准（DESIGN §3.6）。
        // 例外：明日接棒时焦点位只有一行状态文字，省下的高度够放 1–2 行明日课程
        WidgetLayout.Compact -> if (listDay == WidgetDay.Tomorrow) {
            Triple(rows.take(metrics.compactHandoffRows), null, true)
        } else {
            Triple(emptyList<WidgetCourseRow>(), null, false)
        }

        // 周网格档：列表整块让位给网格（网格本身就是列表的另一种表达），
        // 标题 / 「还有 N 节」一并省——110dp 的网格区放不进两套内容
        WidgetLayout.Week -> Triple(emptyList<WidgetCourseRow>(), null, false)

        WidgetLayout.List -> {
            val take = rows.take(metrics.rowsBudget(rows.size))
            val hidden = rows.size - take.size
            Triple(take, if (hidden > 0) "还有 $hidden 节" else null, false)
        }
    }
    return WidgetModel(
        header = header,
        focus = focus,
        rows = shown,
        rowsMoreLabel = moreLabel,
        listDay = listDay,
        listTitle = listTitle,
        // 摘要行只服务周网格档（其余档的焦点卡信息更全，再放一行是重复）
        summary = if (metrics.layout == WidgetLayout.Week) summary else null,
        week = if (metrics.layout == WidgetLayout.Week) week else null,
        compactRows = compactRows,
    )
}

// ---------------------------------------------------------------------------
// 今日状态 → 快照
// ---------------------------------------------------------------------------

/**
 * 今日状态 → 全量快照。
 *
 * 分支（DESIGN §3.6，与今日页同一口径）：
 * - 今天还有待上课程 → 焦点卡（正在上课带进度条）+ 今日剩余列表
 * - 下一节课在 60 分钟窗口外（远课）→ 焦点位给状态行（「今天还有 N 节课」+
 *   「下一节 14:00 开始」），不冒充「下一节」；列表仍是今天剩余课程
 * - 今天上完 / 今天没课 → **明日接棒**：状态行 + 「明天 · 周五 · 3 节」+ 明日列表；
 *   明日也无课 → 状态行 + 「明天没有课，可以放松一下」
 * - 不在学期内 → 假期中，没有列表
 *
 * @param week 本周迷你网格（[buildWidgetWeek] 的结果）；调用方按尺寸档决定是否构建
 */
fun buildWidgetSnapshot(state: TodayState, week: WidgetWeek? = null): WidgetSnapshot {
    val header = buildString {
        append("${state.date.monthValue}月${state.date.dayOfMonth}日")
        append(" 周${dayLabel(state.day)}")
        if (state.week > 0) append(" · 第 ${state.week} 周")
    }
    val handoff = state.focus == null && state.tomorrowVisible
    return WidgetSnapshot(
        header = header,
        focus = buildFocus(state),
        rows = if (handoff) {
            state.tomorrowCourses.map { it.toRow(state.slots) }
        } else {
            state.listCourses.map { it.toRow(state.slots) }
        },
        listDay = if (handoff) WidgetDay.Tomorrow else WidgetDay.Today,
        listTitle = if (handoff && state.tomorrowCourses.isNotEmpty()) {
            "明天 · 周${dayLabel(state.tomorrowDay)} · ${state.tomorrowCourses.size} 节"
        } else {
            null
        },
        summary = buildSummary(state),
        week = week,
    )
}

private fun buildFocus(state: TodayState): WidgetFocus {
    val course = state.focus
    if (course == null) {
        // 焦点为空：假期中 / 远课 / 今天上完 / 今天没课，四种文案
        return when {
            !state.inTerm -> WidgetFocus.Idle(
                title = "假期中",
                detail = "未在学期内，去「我的」设置开学日期",
            )

            // 远课：今天还有待上课，但下一节在 60 分钟窗口外（与今日页同一取舍，
            // 不冒充「下一节」）——状态行交代「还有几节 + 什么时候开始」
            state.remaining.isNotEmpty() -> {
                val first = state.remaining.first()
                WidgetFocus.Idle(
                    title = "今天还有 ${state.remaining.size} 节课",
                    detail = "下一节 ${clockOf(state.slots, first)} 开始",
                )
            }

            state.todayAllDone -> WidgetFocus.Idle(
                title = "今天的课都上完了",
                // 明日有课由列表标题 + 首行承担；明日也无课才补一句落点
                detail = if (state.tomorrowVisible && state.tomorrowCourses.isEmpty()) {
                    "明天没有课，可以放松一下"
                } else {
                    null
                },
            )

            else -> WidgetFocus.Idle(
                title = "今天没有课",
                detail = if (state.tomorrowVisible && state.tomorrowCourses.isEmpty()) {
                    "明天没有课，可以放松一下"
                } else {
                    null
                },
            )
        }
    }

    val ongoing = state.ongoing != null
    val countdown: String?
    val fallback: String?
    if (ongoing) {
        // 与今日页同一取舍：优先细粒度倒计时，退整门口径
        countdown = state.ongoingCountdown
            ?: state.minutesToOngoingEnd?.let { "还有 $it 分钟下课" }
        fallback = null
    } else {
        // 「下一节」进焦点卡的前提就是 ≤60 分钟（TodayState 准入窗口），直接说分钟数
        countdown = state.minutesToNext?.let { "还有 $it 分钟上课" }
        fallback = if (countdown == null) "${clockOf(state.slots, course)} 上课" else null
    }

    return WidgetFocus.Course(
        label = (if (ongoing) "正在上课" else "下一节") + " · " + sectionRange(course),
        name = course.name,
        meta = metaOf(course),
        countdown = countdown,
        fallbackNote = fallback,
        colorIndex = course.colorIndex,
        progress = if (ongoing) state.ongoingProgress else null,
    )
}

/**
 * 4×4 摘要行：正在上 / 下一节一行，或明日接棒时明天第一节。
 *
 * 都给完整信息（含地点）——周网格占了大半张卡，摘要行是唯一能读出
 * 「现在/接下来上什么、在哪」的地方。
 */
private fun buildSummary(state: TodayState): String? {
    val course = state.focus
    if (course != null) {
        val ongoing = state.ongoing != null
        val head = (if (ongoing) "正在上课" else "下一节") + " · " + sectionRange(course)
        return listOf(head, course.name, metaOf(course))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
    if (!state.inTerm) return null
    // 远课：焦点空但今天还有课，摘要给今天第一门（不冒充「下一节」，直接给时刻）
    state.remaining.firstOrNull()?.let { first ->
        return listOf(clockOf(state.slots, first), first.name, metaOf(first))
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }
    val first = state.tomorrowCourses.firstOrNull() ?: return null
    return listOf("明天", clockOf(state.slots, first), first.name, metaOf(first))
        .filter { it.isNotBlank() }
        .joinToString(" ")
}

private fun Course.toRow(slots: List<TimeSlot>): WidgetCourseRow = WidgetCourseRow(
    clock = clockOf(slots, this),
    name = name,
    meta = metaOf(this),
    colorIndex = colorIndex,
)

/**
 * 小组件的副行文案：`@南B208 · 陈磊`。
 *
 * 与今日页 `metaLine` 的差别：widget 不放时刻（时刻在行首或焦点位已给出），
 * 否则小尺寸里会出现两遍 `10:15`。
 */
private fun metaOf(course: Course): String {
    val location = compactPosition(course.position)
    return listOfNotNull(
        location.takeIf { it.isNotBlank() }?.let { "@$it" },
        course.teacher.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** 快照与 Glance 状态之间的编解码；解码失败返回 null（宁可重读 Room，也不崩在组合里）。 */
internal object WidgetSnapshotCodec {
    private val json = Json { ignoreUnknownKeys = true }

    // 显式传 serializer：reified 版是扩展函数，需要额外 import，
    // 在 object 里容易漏（漏了就会把 snapshot 当成 SerializationStrategy 报类型错）
    fun encode(snapshot: WidgetSnapshot): String =
        json.encodeToString(WidgetSnapshot.serializer(), snapshot)

    fun decode(raw: String?): WidgetSnapshot? =
        raw?.let { runCatching { json.decodeFromString(WidgetSnapshot.serializer(), it) }.getOrNull() }
}
