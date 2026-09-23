package edu.gcp.schedule.domain

import java.time.LocalDate

/**
 * 今天下一个「上课 / 下课」时刻（自午夜起的分钟数）；今天没有剩余边界时返回 null。
 *
 * 用途：桌面小组件的边界闹钟——桌面内容真正需要变样子的只有上下课那几个瞬间，
 * 按边界排闹钟比每 15 分钟无脑刷更准也更省电。
 *
 * 放在 domain 而不是 widget 包里：它是纯查询（给定学期/作息/课程/时刻算出边界），
 * 与 Android 无关，需要能被 JVM 单测直接钉住——边界算错的表现是
 * 「桌面下课后还显示正在上课」，只在真机上很难发现。
 */
fun nextTodayBoundaryMinutes(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    today: LocalDate,
    now: LocalTimeLike,
): Int? {
    if (semester == null) return null
    val week = ScheduleCalculator.weekNumberOf(semester, today)
    if (week !in 1..semester.totalWeeks) return null
    val day = today.dayOfWeek.value
    val nowMinutes = now.toMinutes()
    return ScheduleCalculator.coursesOnDay(courses, week, day)
        .flatMap { course ->
            listOfNotNull(
                ScheduleCalculator.courseStartMinutes(slots, course),
                ScheduleCalculator.courseEndMinutes(slots, course),
            )
        }
        .filter { it > nowMinutes }
        .minOrNull()
}
