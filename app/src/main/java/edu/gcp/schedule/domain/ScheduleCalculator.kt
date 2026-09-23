package edu.gcp.schedule.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** 轻量时刻，便于 JVM 单测 */
data class LocalTimeLike(val hour: Int, val minute: Int) {
    fun toMinutes(): Int = hour * 60 + minute

    companion object {
        fun from(t: LocalTime): LocalTimeLike = LocalTimeLike(t.hour, t.minute)
        fun now(): LocalTimeLike = from(LocalTime.now())
    }
}

enum class CoursePhase { Upcoming, Ongoing, Ended, Unknown }

/**
 * 周次 / 节次计算。周次以 SemesterConfig.startDate 所在周的周一为第 1 周。
 */
object ScheduleCalculator {
    private val dateFmt: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /**
     * 大节分组（闭区间）：第一二节 / 第三四节 / 第五六节 / 第七八节 / 第九十十一节。
     * 网格里「换教室间隔」画在组与组之间，「课间休息」画在组内。
     */
    val BIG_SECTIONS: List<IntRange> = listOf(1..2, 3..4, 5..6, 7..8, 9..11)

    /** 与 ui 层调色板条数一致。12→16：理论 + 实验课的不同课名常超 12 个，12 桶取模回绕必撞色。 */
    const val PALETTE_SIZE = 16

    fun isBigSectionStart(section: Int): Boolean = BIG_SECTIONS.any { it.first == section }

    /** 该小节是不是所在大节的最后一节（决定它后面画 20 分钟间隔还是 5 分钟间隔）。 */
    fun isBigSectionEnd(section: Int): Boolean = BIG_SECTIONS.any { it.last == section }

    fun bigSectionLabel(section: Int): String? =
        BIG_SECTIONS.firstOrNull { section in it }?.let { "第${it.first}-${it.last}节" }

    fun parseDate(value: String): LocalDate = LocalDate.parse(value, dateFmt)

    fun startOfWeek(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

    fun weekNumberOf(config: SemesterConfig, date: LocalDate): Int {
        val startMonday = startOfWeek(parseDate(config.startDate))
        return ChronoUnit.WEEKS.between(startMonday, startOfWeek(date)).toInt() + 1
    }

    fun isInTerm(config: SemesterConfig, date: LocalDate): Boolean =
        weekNumberOf(config, date) in 1..config.totalWeeks

    fun currentSection(slots: List<TimeSlot>, now: LocalTimeLike): Int? {
        val minutes = now.toMinutes()
        for (slot in slots) {
            if (minutes in toMinutes(slot.startTime) until toMinutes(slot.endTime)) {
                return slot.number
            }
        }
        return null
    }

    /**
     * 课程有效起始时刻（分钟），自定义时间课以 customStartTime 为准。
     *
     * 根因：这个口径此前散落在 [coursePhase] / [dayLastEndMinutes] / 今日页各处，
     * 各写各的 `isCustomTime` 分支。今日页的排序与行内时刻还是纯作息表口径，
     * 于是自定义时间的课会排错序、显示成别的节次的时刻。收敛成唯一入口。
     *
     * 边界：自定义时间只给了一头（脏数据）→ 退回作息表；作息表也缺该节 → null。
     */
    fun courseStartMinutes(slots: List<TimeSlot>, course: Course): Int? =
        if (hasCustomBounds(course)) {
            toMinutes(course.customStartTime!!)
        } else {
            timeSlotsForSections(slots, course.startSection, course.endSection)
                ?.let { toMinutes(it.startTime) }
        }

    /** 课程有效结束时刻（分钟），与 [courseStartMinutes] 同一口径。 */
    fun courseEndMinutes(slots: List<TimeSlot>, course: Course): Int? =
        if (hasCustomBounds(course)) {
            toMinutes(course.customEndTime!!)
        } else {
            (timeSlotsForSections(slots, course.endSection, course.endSection)
                ?: timeSlotsForSections(slots, course.startSection, course.endSection))
                ?.let { toMinutes(it.endTime) }
        }

    private fun hasCustomBounds(course: Course): Boolean =
        course.isCustomTime && course.customStartTime != null && course.customEndTime != null

    fun coursePhase(
        slots: List<TimeSlot>,
        course: Course,
        now: LocalTimeLike,
    ): CoursePhase {
        val start = courseStartMinutes(slots, course) ?: return CoursePhase.Unknown
        val end = courseEndMinutes(slots, course) ?: return CoursePhase.Unknown
        val cur = now.toMinutes()
        return when {
            cur >= end -> CoursePhase.Ended
            cur >= start -> CoursePhase.Ongoing
            else -> CoursePhase.Upcoming
        }
    }

    fun timeSlotsForSections(
        slots: List<TimeSlot>,
        startSection: Int,
        endSection: Int,
    ): TimeSlot? {
        if (startSection <= 0) return null
        return slots.firstOrNull { it.number == startSection }
            ?: slots.filter { it.number in startSection..endSection }
                .minByOrNull { it.number }
    }

    fun coursesOnDay(courses: List<Course>, week: Int, day: Int): List<Course> =
        courses.filter { it.day == day && week in it.weeks }.sortedBy { it.startSection }

    /**
     * 可见的星期列，按显示顺序（1=周一 … 7=周日）。
     *
     * 根因：网格列位置是「第几列 = 可见星期序列的下标」推导出来的，不是直接用 day-1。
     * 只要允许单独隐藏周六或周日，就必须由本函数给出权威序列——列宽、课块 offset、
     * 今日列判断、表头日期全部基于它，任何一处退回「用 day-1 当列号」都会错位。
     *
     * 边界：两天都关时退化为周一至周五（不返回空表，否则列宽会除零）。
     */
    fun visibleDays(showSaturday: Boolean, showSunday: Boolean): List<Int> = buildList {
        add(1); add(2); add(3); add(4); add(5)
        if (showSaturday) add(6)
        if (showSunday) add(7)
    }

    /**
     * 星期在某可见序列里的列下标（0-based）；不可见返回 null。
     *
     * 根因：不能直接 `return visibleDays.indexOf(day)`——`indexOf` 未命中返回 **-1** 而非 null，
     * 而 `Int` 是 `Int?` 的子类型，能静默通过编译。调用点普遍写 `?: 0` 兜底，
     * 拿到 -1 就会被当成合法列号，把课块/时刻线画到网格外。必须显式把 -1 折成 null。
     */
    fun columnOf(visibleDays: List<Int>, day: Int): Int? =
        visibleDays.indexOf(day).takeIf { it >= 0 }

    fun coursesInWeek(courses: List<Course>, week: Int): List<Course> =
        courses.filter { week in it.weeks }
            .sortedWith(compareBy({ it.day }, { it.startSection }))

    /**
     * 指定星期最后一节课的结束时间（分钟），供周网格时刻线收口用。
     *
     * 根因：时刻线此前只按作息表画——当天 17:10 就上完了课，线却一直挂到 21:10 网格底，
     * 看起来像「还有课」。改为按当日课程收口：上完即消失，次日起点回到作息表第一节。
     *
     * 边界：
     * - 自定义时间的课用 customEndTime（与 [coursePhase] 同一口径）；
     * - 非自定义时间但作息表缺该节 → 无法判定结束时间，忽略该课；
     * - 没有任何可判定的课 → null（调用方整天不画线）。
     * 周次过滤由调用方负责（传进来的 courses 应是当前周的课）。
     */
    fun dayLastEndMinutes(courses: List<Course>, slots: List<TimeSlot>, day: Int): Int? =
        courses.filter { it.day == day }
            .mapNotNull { courseEndMinutes(slots, it) }
            .maxOrNull()

    fun nextCourse(
        courses: List<Course>,
        slots: List<TimeSlot>,
        day: Int,
        week: Int,
        now: LocalTimeLike,
    ): Course? {
        val cur = now.toMinutes()
        return coursesOnDay(courses, week, day)
            .filter { c -> courseStartMinutes(slots, c)?.let { it > cur } == true }
            .minByOrNull { courseStartMinutes(slots, it) ?: Int.MAX_VALUE }
    }

    fun toMinutes(hhmm: String): Int {
        val parts = hhmm.trim().split(':')
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    /**
     * 课程配色下标（回退路径）。
     *
     * 根因：旧实现用 `name.hashCode() % 12`。String.hashCode 是 31 进制多项式，
     * 中文课名长度相近、前缀相似时高位抵消明显，10 门课在 12 个桶里必然撞色
     * （实测「PLC原理及应用B」「机械制造基础A」「现代机械设计方法」三者都落在 index 2）。
     * 这里换成 FNV-1a：与 JVM/平台无关、跨进程稳定，撞色概率也比 hashCode 低。
     *
     * 边界：哈希无法保证 12 桶内不撞色（10 门课按生日问题约 98% 会撞）。
     * 因此批量入库走 [colorIndexesBySortedName]，单条新增走 [nextColorIndex]，本函数只作兜底。
     */
    fun colorIndexFor(name: String): Int {
        var hash = -0x7ee3623b // 0x811C9DC5
        for (ch in name) {
            hash = hash xor ch.code
            hash *= 0x01000193
        }
        return ((hash % PALETTE_SIZE) + PALETTE_SIZE) % PALETTE_SIZE
    }

    /** 取一个尚未占用的调色板下标；已用颜色不变，保证新增课程不会让别的课换色。 */
    fun nextColorIndex(used: Collection<Int>): Int {
        val taken = used.toHashSet()
        return (0 until PALETTE_SIZE).firstOrNull { it !in taken } ?: (taken.size % PALETTE_SIZE)
    }

    /**
     * 整批入库时按课程名排序后的名次取色。
     * 用 `sorted()`（UTF-16 码元序）而不是 locale 排序，保证同一份课表每次导入颜色完全一致。
     */
    fun colorIndexesBySortedName(names: Collection<String>): Map<String, Int> =
        names.asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
            .mapIndexed { index, name -> name to index % PALETTE_SIZE }
            .toMap()

    /**
     * 周内撞色兜底：同一周里不同课名若共用一个颜色（学期级 16 色不够分时的物理回绕，
     * 理论 + 实验课混排最容易触发），渲染时把撞色组里「课名排序靠后」的那些
     * 就地换成当周未被占用的颜色。只影响显示、不改库；映射确定性，同一周内稳定。
     * 返回 课名 → 替换色下标；无撞色时返回空 map。
     */
    fun weekColorOverrides(courses: List<Course>): Map<String, Int> {
        val stored = courses.groupBy { it.name }
            .filterKeys { it.isNotBlank() }
            .mapValues { (_, group) -> group.first().colorIndex }
        if (stored.size < 2) return emptyMap()
        val contestedColors = stored.values.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys
        if (contestedColors.isEmpty()) return emptyMap()

        // 每个撞色组里保留课名排序最前的那个，其余进入替换名单
        val needOverride = sortedSetOf<String>()
        for (color in contestedColors.sorted()) {
            stored.filterValues { it == color }.keys
                .sorted()
                .drop(1)
                .forEach { needOverride += it }
        }
        val used = stored.values.toMutableSet()
        val result = mutableMapOf<String, Int>()
        for (name in needOverride) {
            val pick = nextColorIndex(used)
            if (pick != stored[name]) result[name] = pick
            used += pick
        }
        return result
    }

    /** 周次压缩显示：`[6,7,8,9,10,11,14,15]` → `6-11,14-15`。 */
    fun formatWeeks(weeks: Collection<Int>): String {
        val sorted = weeks.filter { it in 1..40 }.distinct().sorted()
        if (sorted.isEmpty()) return ""
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var prev = start
        for (w in sorted.drop(1)) {
            if (w == prev + 1) {
                prev = w
                continue
            }
            parts += if (start == prev) "$start" else "$start-$prev"
            start = w
            prev = w
        }
        parts += if (start == prev) "$start" else "$start-$prev"
        return parts.joinToString(",")
    }

    fun sectionStart(slots: List<TimeSlot>, section: Int): String? =
        slots.firstOrNull { it.number == section }?.startTime
}
