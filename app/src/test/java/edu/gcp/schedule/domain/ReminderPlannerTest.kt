package edu.gcp.schedule.domain

import edu.gcp.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 上课提醒的时刻计算（DESIGN §3.7）。
 *
 * 提醒算错的表现是「迟提醒 / 不提醒 / 上课了才提醒」——后台链路上肉眼不可见，
 * 纯函数口径必须在 JVM 单测里钉死。与 [TodayBoundaryTest] 用同一份学期与课程布置。
 */
class ReminderPlannerTest {

    /** 第 1 周 = 8/31–9/6，9/17（周四）是第 3 周 */
    private val semester = SemesterConfig(startDate = "2026-08-31", totalWeeks = 20)
    private val slots = DefaultData.defaultTimeSlots
    private val thursday = LocalDate.parse("2026-09-17")

    private fun course(id: Long, start: Int, end: Int) = Course(
        id = id,
        name = "课$id",
        teacher = "",
        position = "",
        day = 4,
        startSection = start,
        endSection = end,
        weeks = (1..10).toSet(),
    )

    /** 周四三节连排：3-4（10:15–11:40）、5-6（14:00–15:25）、7-8（15:45–17:10） */
    private val courses = listOf(
        course(1, 3, 4),
        course(2, 5, 6),
        course(3, 7, 8),
    )

    private fun at(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(thursday, LocalTime.of(hour, minute))

    @Test
    fun nextReminder_isStartMinusLead() {
        val plan = nextReminderPlan(semester, slots, courses, at(7, 0), leadMinutes = 10)
        assertEquals(10 * 60 + 15, plan?.startMinutes)
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(10, 5)), plan?.triggerAt)
    }

    @Test
    fun dueReminder_insideWindow() {
        // 10:08，提前量 10：3-4 节 10:15 上课，还有 7 分钟 → 该提醒
        val plan = dueReminderPlan(semester, slots, courses, at(10, 8), leadMinutes = 10)
        assertEquals(1L, plan?.course?.id)
        // 去重键 = 日期|课程id|起始分钟，闹钟与周期核对靠它去重
        assertEquals("2026-09-17|1|615", plan?.dedupKey)
    }

    @Test
    fun dueReminder_outsideWindow_returnsNull() {
        // 9:50：下一节 10:15 还有 25 分钟，超出提前量窗口
        assertNull(dueReminderPlan(semester, slots, courses, at(9, 50), leadMinutes = 10))
    }

    @Test
    fun dueReminder_afterStart_returnsNull() {
        // 已上课不补发：10:15 整 3-4 节已开始
        assertNull(dueReminderPlan(semester, slots, courses, at(10, 15), leadMinutes = 10))
    }

    @Test
    fun nextReminder_skipsPassedTrigger() {
        // 10:08：3-4 节的触发点 10:05 已过（闹钟丢失场景），下一个是 5-6 节
        val plan = nextReminderPlan(semester, slots, courses, at(10, 8), leadMinutes = 10)
        assertEquals(2L, plan?.course?.id)
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(13, 50)), plan?.triggerAt)
    }

    @Test
    fun nextReminder_crossesDay() {
        // 周四放学后：下一节在下周四（同一门课，第 4 周）
        val plan = nextReminderPlan(semester, slots, courses, at(18, 0), leadMinutes = 10)
        assertEquals(LocalDate.parse("2026-09-24"), plan?.date)
        assertEquals(1L, plan?.course?.id)
    }

    @Test
    fun nextReminder_customTimeCourse_usesCustomBounds() {
        // 课程时间只有一条口径：自定义时间课以 custom 字段为准，提醒不另算
        val custom = Course(
            id = 9,
            name = "自定义课",
            teacher = "",
            position = "",
            day = 4,
            startSection = 1,
            endSection = 2,
            weeks = (1..10).toSet(),
            isCustomTime = true,
            customStartTime = "12:30",
            customEndTime = "13:10",
        )
        val plan = nextReminderPlan(semester, slots, listOf(custom), at(7, 0), leadMinutes = 10)
        assertEquals(LocalDateTime.of(thursday, LocalTime.of(12, 20)), plan?.triggerAt)
    }

    @Test
    fun noSemester_or_emptyCourses_returnsNull() {
        assertNull(nextReminderPlan(null, slots, courses, at(7, 0), 10))
        assertNull(dueReminderPlan(null, slots, courses, at(7, 0), 10))
        assertNull(nextReminderPlan(semester, slots, emptyList(), at(7, 0), 10))
    }

    @Test
    fun outOfTerm_returnsNull() {
        val afterTerm = LocalDateTime.of(LocalDate.parse("2027-02-01"), LocalTime.of(7, 0))
        assertNull(nextReminderPlan(semester, slots, courses, afterTerm, 10))
    }

    @Test
    fun coerceLead_snapsToNearestChoice() {
        assertEquals(10, ReminderDefaults.coerceLead(10))
        assertEquals(5, ReminderDefaults.coerceLead(3))
        // 7 离 5 差 2、离 10 差 3 → 吸附 5
        assertEquals(5, ReminderDefaults.coerceLead(7))
        assertEquals(30, ReminderDefaults.coerceLead(45))
    }
}
