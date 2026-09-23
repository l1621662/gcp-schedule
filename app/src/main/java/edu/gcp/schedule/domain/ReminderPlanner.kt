package edu.gcp.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.abs

/**
 * 上课提醒的取值域单一来源（模式对齐 [CalendarSyncDefaults]）。
 * 提前量的候选与夹取只在这里写，设置页滚轮与存储读路径都引用它。
 */
object ReminderDefaults {
    const val DEFAULT_LEAD_MINUTES = 10
    val LEAD_CHOICES = listOf(5, 10, 15, 20, 30)

    /** 存储值落在候选之外（手改数据/旧值）时吸附到最近的候选档。 */
    fun coerceLead(value: Int): Int =
        LEAD_CHOICES.minByOrNull { abs(it - value) } ?: DEFAULT_LEAD_MINUTES

    fun leadLabel(minutes: Int): String = "$minutes 分钟"
}

/**
 * 一条已定的提醒：某天某节课在 [triggerAt] 触发。
 *
 * [startMinutes] 与 TimeSlot 同口径（自午夜起的分钟数）；时刻文本的渲染走
 * `domain/TodayFormat`，这里只做计算不生产文案。
 */
data class ReminderPlan(
    val course: Course,
    val date: LocalDate,
    val startMinutes: Int,
    /** 上课时刻。 */
    val startAt: LocalDateTime,
    /** 触发时刻 = 上课时刻 − 提前量。 */
    val triggerAt: LocalDateTime,
) {
    /** 去重键：同一节课同一天只提醒一次（闹钟与周期核对共用，DESIGN §3.7）。 */
    val dedupKey: String get() = "$date|${course.id}|$startMinutes"
}

/**
 * 现在（含今天往后 [maxDaysAhead] 天）内所有「还没开始」的上课时刻，按上课时间升序。
 *
 * 课程时间只有一条口径：[ScheduleCalculator.courseStartMinutes]（自定义时间课以
 * custom 字段为准），这里不另写分支。学期未配置返回空。
 */
private fun upcomingPlans(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    now: LocalDateTime,
    leadMinutes: Int,
    maxDaysAhead: Int,
): List<ReminderPlan> {
    if (semester == null) return emptyList()
    val result = mutableListOf<ReminderPlan>()
    var date = now.toLocalDate()
    repeat(maxDaysAhead) {
        val week = ScheduleCalculator.weekNumberOf(semester, date)
        if (week in 1..semester.totalWeeks) {
            for (course in ScheduleCalculator.coursesOnDay(courses, week, date.dayOfWeek.value)) {
                val start = ScheduleCalculator.courseStartMinutes(slots, course) ?: continue
                val startAt = date.atStartOfDay().plusMinutes(start.toLong())
                if (!startAt.isAfter(now)) continue
                result += ReminderPlan(
                    course = course,
                    date = date,
                    startMinutes = start,
                    startAt = startAt,
                    triggerAt = startAt.minusMinutes(leadMinutes.toLong()),
                )
            }
        }
        date = date.plusDays(1)
    }
    return result.sortedBy { it.startAt }
}

/**
 * 「现在就该提醒」的课：已进入提前量窗口（上课时刻 − now ≤ 提前量）且还没上课。
 * 闹钟晚触发、周期核对补发都靠它判断；窗口已过（迟到的提醒）返回 null，不补发噪音。
 */
fun dueReminderPlan(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    now: LocalDateTime,
    leadMinutes: Int,
): ReminderPlan? = upcomingPlans(semester, slots, courses, now, leadMinutes, maxDaysAhead = 1)
    .firstOrNull { it.startAt <= now.plusMinutes(leadMinutes.toLong()) }

/**
 * 下一个还没错过的提醒（触发时刻严格在 now 之后），供排闹钟；没有则返回 null
 * （提醒关、课表空、不在学期内的兜底判断由调用方读偏好完成）。
 * 最多向后找 14 天：一个学期不会连着两周没课还没到头。
 */
fun nextReminderPlan(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    now: LocalDateTime,
    leadMinutes: Int,
    maxDaysAhead: Int = 14,
): ReminderPlan? = upcomingPlans(semester, slots, courses, now, leadMinutes, maxDaysAhead)
    .firstOrNull { it.triggerAt.isAfter(now) }
