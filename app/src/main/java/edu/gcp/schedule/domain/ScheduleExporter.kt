package edu.gcp.schedule.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.util.Locale

/**
 * 课表导出（DESIGN §4.12）：把课程 × 周次展开成具体日期事件 + 生成 Google 日历导入 CSV。
 *
 * 纯 JVM 函数，无 Android 依赖。日期换算与 [WeekGrid.weekDate] 同算法
 * （学期周一 + (week−1) 周 + (day−1) 天），在 domain 重写以免 UI 层被反向依赖；
 * 时刻走 [ScheduleCalculator.courseStartMinutes] / [courseEndMinutes] 唯一口径
 * （自定义时间课自动生效）。
 */
object ScheduleExporter {

    /** 展开后的单次课程事件（一次真实日历上的上课）。 */
    data class CourseEvent(
        val name: String,
        val teacher: String,
        val position: String,
        val date: LocalDate,
        val start: LocalDateTime,
        val end: LocalDateTime,
    )

    /** 展开结果：ok 为空通常意味着没有课程，或学期未配置/作息缺节导致全部跳过。 */
    data class Expansion(val ok: List<CourseEvent> = emptyList())

    /**
     * 课程 × `course.weeks` 展开为具体日期事件（DESIGN §4.12）。
     *
     * - 周次超出 `semester.totalWeeks` 的丢弃
     * - 时刻算不出（作息表缺节 / 自定义时间脏数据）的事件跳过，不阻断整体
     * - 结果按日期 + 开始时间排序，与日历呈现顺序一致
     */
    fun expandEvents(
        courses: List<Course>,
        slots: List<TimeSlot>,
        semester: SemesterConfig,
    ): Expansion {
        val termStart = runCatching {
            ScheduleCalculator.startOfWeek(ScheduleCalculator.parseDate(semester.startDate))
        }.getOrNull() ?: return Expansion()

        val events = buildList {
            for (course in courses) {
                val startMinutes = ScheduleCalculator.courseStartMinutes(slots, course) ?: continue
                val endMinutes = ScheduleCalculator.courseEndMinutes(slots, course) ?: continue
                for (week in course.weeks) {
                    if (week < 1 || week > semester.totalWeeks) continue
                    val date = termStart
                        .plusWeeks((week - 1).toLong())
                        .plusDays((course.day - 1).toLong())
                    add(
                        CourseEvent(
                            name = course.name,
                            teacher = course.teacher,
                            position = course.position,
                            date = date,
                            start = date.atTime(startMinutes / 60, startMinutes % 60),
                            end = date.atTime(endMinutes / 60, endMinutes % 60),
                        ),
                    )
                }
            }
        }.sortedWith(compareBy({ it.date }, { it.start }, { it.name }))

        return Expansion(ok = events)
    }

    /** Google 日历导入 CSV 表头（字段顺序官方导入模板）。 */
    internal const val CSV_HEADER =
        "Subject,Start Date,Start Time,End Date,End Time,All Day Event,Description,Location"

    private val dateFormatter: DateTimeFormatter =
        DateTimeFormatter.ofPattern("M/d/yyyy", Locale.US)

    /** `h:mm AM/PM`，如 `10:15 AM`；Google 导入模板不接受 24 小时制。 */
    private val timeFormatter: DateTimeFormatter = DateTimeFormatterBuilder()
        .appendPattern("h:mm a")
        .toFormatter(Locale.US)

    /**
     * 生成 Google 日历导入 CSV（DESIGN §4.12）。
     *
     * CRLF 行尾（RFC 4180）；含逗号/引号/换行的字段加双引号并转义内部引号。
     * 调用方写文件时自行加 UTF-8 BOM（Excel 兼容，Google 导入不受影响）。
     */
    fun toGoogleCalendarCsv(events: List<CourseEvent>): String =
        buildString {
            append(CSV_HEADER).append("\r\n")
            for (e in events) {
                append(csvField(e.name)).append(',')
                append(e.date.format(dateFormatter)).append(',')
                append(e.start.format(timeFormatter)).append(',')
                append(e.date.format(dateFormatter)).append(',')
                append(e.end.format(timeFormatter)).append(',')
                append("FALSE").append(',')
                append(csvField(teacherDescription(e.teacher))).append(',')
                append(csvField(e.position))
                append("\r\n")
            }
        }

    internal fun teacherDescription(teacher: String): String =
        if (teacher.isBlank()) "" else "教师：$teacher"

    /** RFC 4180：含 `,` `"` `\n` 的字段包引号，内部 `"` 翻倍。 */
    private fun csvField(value: String): String =
        if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            value
        } else {
            "\"" + value.replace("\"", "\"\"") + "\""
        }
}
