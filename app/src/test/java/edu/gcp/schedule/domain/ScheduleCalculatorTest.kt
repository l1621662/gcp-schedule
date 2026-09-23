package edu.gcp.schedule.domain

import edu.gcp.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScheduleCalculatorTest {

    private val config = SemesterConfig(
        startDate = "2026-09-07", // Monday
        totalWeeks = 20,
        firstDayOfWeek = 1,
    )

    /**
     * 直接引用真实作息，不要在这里另手写一份。
     * 之前这里写的是 08:00/10:00/14:00/16:00/19:00 那套旧的 5 条大节，
     * 逻辑上也能跑过，但下一个人翻到会以为这就是学校作息。
     */
    private val slots = DefaultData.defaultTimeSlots

    @Test
    fun weekNumber_startMondayIsWeek1() {
        assertEquals(1, ScheduleCalculator.weekNumberOf(config, LocalDate.parse("2026-09-07")))
        assertEquals(1, ScheduleCalculator.weekNumberOf(config, LocalDate.parse("2026-09-13")))
        assertEquals(2, ScheduleCalculator.weekNumberOf(config, LocalDate.parse("2026-09-14")))
        assertEquals(2, ScheduleCalculator.weekNumberOf(config, LocalDate.parse("2026-09-17")))
        assertEquals(3, ScheduleCalculator.weekNumberOf(config, LocalDate.parse("2026-09-21")))
    }

    @Test
    fun weekNumber_beforeStartIsZero() {
        assertEquals(0, ScheduleCalculator.weekNumberOf(config, LocalDate.parse("2026-09-06")))
    }

    @Test
    fun isInTerm() {
        assertTrue(ScheduleCalculator.isInTerm(config, LocalDate.parse("2026-09-20")))
        assertFalse(ScheduleCalculator.isInTerm(config, LocalDate.parse("2026-09-06")))
        assertFalse(
            ScheduleCalculator.isInTerm(
                config,
                LocalDate.parse("2026-09-07").plusWeeks(20),
            ),
        )
    }

    @Test
    fun weeksFilter() {
        val c1 = Course(1, "高数", "张", "A101", 1, 1, 2, setOf(1, 3, 5))
        val c2 = Course(2, "英语", "李", "B202", 1, 3, 4, setOf(1, 2, 3))
        val week1 = ScheduleCalculator.coursesInWeek(listOf(c1, c2), 1)
        assertEquals(2, week1.size)
        val week2 = ScheduleCalculator.coursesInWeek(listOf(c1, c2), 2)
        assertEquals(1, week2.size)
        assertEquals("英语", week2.first().name)
    }

    /** 节次判定按小节 1–11；课间与午休不在任何一节内 */
    @Test
    fun currentSection() {
        assertEquals(1, ScheduleCalculator.currentSection(slots, LocalTimeLike(8, 30)))
        assertEquals(2, ScheduleCalculator.currentSection(slots, LocalTimeLike(9, 50)))
        assertEquals(5, ScheduleCalculator.currentSection(slots, LocalTimeLike(14, 0)))
        // 10:00 落在第 2 节（09:55 结束）与第 3 节（10:15 开始）之间
        assertNull(ScheduleCalculator.currentSection(slots, LocalTimeLike(10, 0)))
        // 午休
        assertNull(ScheduleCalculator.currentSection(slots, LocalTimeLike(12, 0)))
        // 晚自习段（第 11 节 20:00–20:40）
        assertEquals(11, ScheduleCalculator.currentSection(slots, LocalTimeLike(20, 10)))
        assertNull(ScheduleCalculator.currentSection(slots, LocalTimeLike(21, 0)))
    }

    @Test
    fun coursePhase() {
        val course = Course(1, "高数", "张", "A", 1, 1, 2, setOf(1))
        // 第 1-2 节 = 08:30–09:55
        assertEquals(CoursePhase.Upcoming, ScheduleCalculator.coursePhase(slots, course, LocalTimeLike(8, 10)))
        assertEquals(CoursePhase.Ongoing, ScheduleCalculator.coursePhase(slots, course, LocalTimeLike(8, 35)))
        assertEquals(CoursePhase.Ongoing, ScheduleCalculator.coursePhase(slots, course, LocalTimeLike(9, 30)))
        assertEquals(CoursePhase.Ended, ScheduleCalculator.coursePhase(slots, course, LocalTimeLike(9, 55)))
        assertEquals(CoursePhase.Ended, ScheduleCalculator.coursePhase(slots, course, LocalTimeLike(12, 0)))
        assertEquals(CoursePhase.Upcoming, ScheduleCalculator.coursePhase(slots, course, LocalTimeLike(7, 0)))
    }

    /**
     * 有效时间口径：courseStartMinutes / courseEndMinutes 是今日页排序、行内时刻、
     * 倒计时、进度条的公共来源。自定义时间课以 custom 字段为准；只给一头（脏数据）
     * 退回作息表；作息表也缺该节 → null（调用方据此显示 `--:--` 而不是编一个时刻）。
     */
    @Test
    fun courseStartEndMinutes_preferCustomTimeWhenComplete() {
        val normal = Course(1, "高数", "", "", 1, 3, 4, setOf(1)) // 10:15–11:40
        assertEquals(10 * 60 + 15, ScheduleCalculator.courseStartMinutes(slots, normal))
        assertEquals(11 * 60 + 40, ScheduleCalculator.courseEndMinutes(slots, normal))

        val custom = Course(
            2, "自定", "", "", 1, 3, 4, setOf(1),
            isCustomTime = true, customStartTime = "18:00", customEndTime = "20:00",
        )
        assertEquals(18 * 60, ScheduleCalculator.courseStartMinutes(slots, custom))
        assertEquals(20 * 60, ScheduleCalculator.courseEndMinutes(slots, custom))

        // 脏数据：isCustomTime 为真但只给了一头 → 整门课退回作息表，不半路取一只
        val halfCustom = Course(
            3, "半自定", "", "", 1, 3, 4, setOf(1),
            isCustomTime = true, customStartTime = "18:00",
        )
        assertEquals(10 * 60 + 15, ScheduleCalculator.courseStartMinutes(slots, halfCustom))
        assertEquals(11 * 60 + 40, ScheduleCalculator.courseEndMinutes(slots, halfCustom))

        // 作息表缺该节、又没自定义时间 → null
        val orphan = Course(4, "孤儿", "", "", 1, 99, 99, setOf(1))
        assertNull(ScheduleCalculator.courseStartMinutes(slots, orphan))
        assertNull(ScheduleCalculator.courseEndMinutes(slots, orphan))
        assertEquals(CoursePhase.Unknown, ScheduleCalculator.coursePhase(slots, orphan, LocalTimeLike(9, 0)))
    }

    @Test
    fun nextCourse() {
        val c1 = Course(1, "早课", "", "", 1, 1, 1, setOf(1))
        val c2 = Course(2, "午课", "", "", 1, 3, 3, setOf(1))
        val next = ScheduleCalculator.nextCourse(listOf(c1, c2), slots, 1, 1, LocalTimeLike(8, 50))
        assertNotNull(next)
        assertEquals("午课", next!!.name)
    }

    /** 自定义时间的课按有效时刻参与 nextCourse：18:00 那门不该在 08:00 时被当成「下一节」。 */
    @Test
    fun nextCourse_respectsCustomStartTime() {
        val early = Course(1, "早课", "", "", 1, 1, 1, setOf(1)) // 08:30
        val custom = Course(
            2, "晚课", "", "", 1, 1, 1, setOf(1),
            isCustomTime = true, customStartTime = "18:00", customEndTime = "20:00",
        )
        // 07:00：下一节是 08:30 的早课，而不是 startSection 同为 1 但实际 18:00 的自定义课
        assertEquals(
            "早课",
            ScheduleCalculator.nextCourse(listOf(custom, early), slots, 1, 1, LocalTimeLike(7, 0))?.name,
        )
        // 09:00（早课已开始）：下一节是 18:00 的自定义课
        assertEquals(
            "晚课",
            ScheduleCalculator.nextCourse(listOf(custom, early), slots, 1, 1, LocalTimeLike(9, 0))?.name,
        )
    }

    /**
     * 时刻线收口用：dayLastEndMinutes 取指定星期最后一节课的结束时间。
     * 根因：时刻线此前只按作息表画，当天 17:10 上完课线却挂到 21:10。
     * 覆盖：普通课按作息表、自定义时间课用 customEndTime、没课的星期 → null。
     */
    @Test
    fun dayLastEndMinutesTakesLatestEndOfTheDay() {
        val normal = Course(1, "早课", "", "", 1, 1, 2, setOf(1)) // 08:30-09:55
        val late = Course(2, "晚课", "", "", 1, 9, 11, setOf(1)) // 18:30-20:40
        val custom = Course(
            3, "自定", "", "", 1, 1, 1, setOf(1),
            isCustomTime = true, customStartTime = "18:00", customEndTime = "20:00",
        )
        // 最晚的是晚课 20:40，自定义的 20:00 不参与取最大
        assertEquals(20 * 60 + 40, ScheduleCalculator.dayLastEndMinutes(listOf(normal, late, custom), slots, 1))
        // 自定义时间晚于作息表最后一节：以 customEndTime 为准
        val beyond = Course(
            4, "超晚", "", "", 2, 1, 1, setOf(1),
            isCustomTime = true, customStartTime = "21:00", customEndTime = "22:00",
        )
        assertEquals(22 * 60, ScheduleCalculator.dayLastEndMinutes(listOf(beyond), slots, 2))
        // 该日没课 → null（时刻线整天不画）
        assertNull(ScheduleCalculator.dayLastEndMinutes(listOf(normal), slots, 5))
    }

    @Test
    fun colorIndexIsStableAndInRange() {
        val a = ScheduleCalculator.colorIndexFor("课程A")
        val b = ScheduleCalculator.colorIndexFor("课程A")
        assertEquals(a, b)
        assertTrue(a in 0 until ScheduleCalculator.PALETTE_SIZE)
    }

    /** 周内无撞色 → 不需要任何替换 */
    @Test
    fun weekColorOverridesEmptyWhenUnique() {
        val a = Course(1, "甲课", "", "", 1, 1, 2, setOf(1), colorIndex = 0)
        val b = Course(2, "乙课", "", "", 2, 1, 2, setOf(1), colorIndex = 1)
        assertTrue(ScheduleCalculator.weekColorOverrides(listOf(a, b)).isEmpty())
    }

    /**
     * 周内撞色 → 撞色组里课名排序（码点序）靠后的被换到当周空闲色，组内第一个与无关课程不动。
     * 注意「乙(U+4E59) < 甲(U+7532)」：排序最前的是乙课（保留原色），被替换的是甲课。
     * 这是「同一周内不同课不撞色」的渲染兜底契约。
     */
    @Test
    fun weekColorOverridesRemapsLaterNameOnly() {
        val a = Course(1, "乙课", "", "", 1, 1, 2, setOf(1), colorIndex = 5)
        val b = Course(2, "甲课", "", "", 2, 1, 2, setOf(1), colorIndex = 5)
        val c = Course(3, "丙课", "", "", 3, 1, 2, setOf(1), colorIndex = 2)
        val overrides = ScheduleCalculator.weekColorOverrides(listOf(a, b, c))
        assertEquals(setOf("甲课"), overrides.keys)
        // 换成的颜色不能与当周任何已用色（5 与 2）相同
        assertTrue(overrides["甲课"]!! !in setOf(5, 2))
    }

    /**
     * 可见星期序列 = 网格列位置的**唯一权威来源**。
     *
     * 根因：此前用单个 showWeekend 布尔表达周末，只能「7 列或 5 列」，
     * 无法表达「周六有课、周日无课」；一旦只隐藏周六，任何 `day - 1` 当列号的写法
     * 都会把周日挤到第 7 列去（实际只有 6 列）→ 课块错位/丢失。
     * 因此列定位一律走 visibleDays 的下标。
     */
    @Test
    fun visibleDays_coversAllFourWeekendCombinations() {
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), ScheduleCalculator.visibleDays(true, true))
        assertEquals(listOf(1, 2, 3, 4, 5), ScheduleCalculator.visibleDays(false, false))
        // 只留周六 / 只留周日：这是旧模型表达不了的两态，也是本次拆分的直接动因
        assertEquals(listOf(1, 2, 3, 4, 5, 6), ScheduleCalculator.visibleDays(true, false))
        assertEquals(listOf(1, 2, 3, 4, 5, 7), ScheduleCalculator.visibleDays(false, true))
    }

    /** columnOf 必须给出「在可见序列中的下标」，周日不能假定位列 6。 */
    @Test
    fun columnOf_usesVisibleDayIndexNotDayMinusOne() {
        val all = ScheduleCalculator.visibleDays(true, true)
        assertEquals(6, ScheduleCalculator.columnOf(all, 7))

        val satOnly = ScheduleCalculator.visibleDays(true, false)
        assertEquals(5, ScheduleCalculator.columnOf(satOnly, 6))
        assertNull("隐藏的周日不应有列位", ScheduleCalculator.columnOf(satOnly, 7))

        val sunOnly = ScheduleCalculator.visibleDays(false, true)
        assertEquals("周日必须落在第 5 列而不是第 6 列", 5, ScheduleCalculator.columnOf(sunOnly, 7))
        assertNull(ScheduleCalculator.columnOf(sunOnly, 6))
    }

    /** 工作日恒定在列 0–4，隐藏周末不会挪动它们。 */
    @Test
    fun columnOf_weekdaysAreStable() {
        val sunOnly = ScheduleCalculator.visibleDays(false, true)
        for (day in 1..5) assertEquals(day - 1, ScheduleCalculator.columnOf(sunOnly, day))
    }
}
