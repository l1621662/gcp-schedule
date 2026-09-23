package edu.gcp.schedule.domain

import edu.gcp.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 课表导出（DESIGN §4.12）：事件展开与 Google 日历 CSV。
 *
 * 锁的需求：周次 × 星期 × 学期起点 → 真实日期；时刻走 courseStartMinutes 唯一口径
 * （自定义时间课生效）；越界周丢弃；CSV 是 Google 导入模板口径且正确转义。
 */
class ScheduleExporterTest {

    /** 第 1 周周一 = 2026-09-07 */
    private val semester = SemesterConfig(startDate = "2026-09-07", totalWeeks = 20)
    private val slots = DefaultData.defaultTimeSlots

    private fun course(
        id: Long = 1,
        name: String = "高等数学",
        day: Int = 3,
        startSection: Int = 3,
        endSection: Int = 4,
        weeks: Set<Int> = (1..16).toSet(),
        teacher: String = "张三",
        position: String = "教学楼A-201",
        isCustomTime: Boolean = false,
        customStartTime: String? = null,
        customEndTime: String? = null,
    ) = Course(
        id = id,
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
        isCustomTime = isCustomTime,
        customStartTime = customStartTime,
        customEndTime = customEndTime,
    )

    @Test
    fun `3-4节 换算成 10点15 到 11点40`() {
        val events = ScheduleExporter.expandEvents(
            listOf(course(weeks = setOf(1))),
            slots,
            semester,
        ).ok
        assertEquals(1, events.size)
        assertEquals(LocalDate.parse("2026-09-09"), events[0].date) // 第1周周三
        assertEquals("2026-09-09T10:15", events[0].start.toString())
        assertEquals("2026-09-09T11:40", events[0].end.toString())
    }

    @Test
    fun `weeks 1和3 展开到两个周三`() {
        val events = ScheduleExporter.expandEvents(
            listOf(course(weeks = setOf(1, 3))),
            slots,
            semester,
        ).ok
        assertEquals(listOf("2026-09-09", "2026-09-23"), events.map { it.date.toString() })
    }

    @Test
    fun `第1周的周日落在 09-13`() {
        val events = ScheduleExporter.expandEvents(
            listOf(course(day = 7, weeks = setOf(1))),
            slots,
            semester,
        ).ok
        assertEquals(LocalDate.parse("2026-09-13"), events[0].date)
    }

    @Test
    fun `超出学期总周数的周次被丢弃`() {
        val events = ScheduleExporter.expandEvents(
            listOf(course(weeks = setOf(20, 21))),
            slots,
            semester,
        ).ok
        assertEquals(1, events.size)
        assertEquals(LocalDate.parse("2027-01-20"), events[0].date) // 第20周周三
    }

    @Test
    fun `自定义时间课按 custom 字段取时刻`() {
        val events = ScheduleExporter.expandEvents(
            listOf(
                course(
                    weeks = setOf(1),
                    isCustomTime = true,
                    customStartTime = "18:30",
                    customEndTime = "20:00",
                ),
            ),
            slots,
            semester,
        ).ok
        assertEquals("2026-09-09T18:30", events[0].start.toString())
        assertEquals("2026-09-09T20:00", events[0].end.toString())
    }

    @Test
    fun `作息缺节的课被跳过不阻断整体`() {
        val events = ScheduleExporter.expandEvents(
            listOf(
                course(id = 1, name = "正常课", weeks = setOf(1)),
                // 作息表没有第 12 节，时刻算不出 → 跳过
                course(id = 2, name = "脏课", startSection = 12, endSection = 12, weeks = setOf(1)),
            ),
            slots,
            semester,
        ).ok
        assertEquals(listOf("正常课"), events.map { it.name })
    }

    @Test
    fun `展开结果按日期和时间排序`() {
        val events = ScheduleExporter.expandEvents(
            listOf(
                course(id = 1, name = "周三下午", day = 3, startSection = 5, endSection = 6, weeks = setOf(1)),
                course(id = 2, name = "周三上午", day = 3, startSection = 1, endSection = 2, weeks = setOf(1)),
                course(id = 3, name = "周一", day = 1, startSection = 1, endSection = 2, weeks = setOf(1)),
            ),
            slots,
            semester,
        ).ok
        assertEquals(listOf("周一", "周三上午", "周三下午"), events.map { it.name })
    }

    @Test
    fun `CSV 含 Google 表头且时间是 12 小时制`() {
        val csv = ScheduleExporter.toGoogleCalendarCsv(
            ScheduleExporter.expandEvents(listOf(course(weeks = setOf(1))), slots, semester).ok,
        )
        val lines = csv.trimEnd('\r', '\n').split("\r\n")
        assertEquals("Subject,Start Date,Start Time,End Date,End Time,All Day Event,Description,Location", lines[0])
        // 10:15 → "10:15 AM"；11:40 → "11:40 AM"；All Day Event 固定 FALSE
        assertEquals(
            "高等数学,9/9/2026,10:15 AM,9/9/2026,11:40 AM,FALSE,教师：张三,教学楼A-201",
            lines[1],
        )
    }

    @Test
    fun `跨中午的时刻正确换算 12 小时制`() {
        val csv = ScheduleExporter.toGoogleCalendarCsv(
            ScheduleExporter.expandEvents(
                listOf(course(startSection = 5, endSection = 6, weeks = setOf(1))), // 14:00–15:25
                slots,
                semester,
            ).ok,
        )
        assertTrue(csv.contains(",2:00 PM,"))
        assertTrue(csv.contains(",3:25 PM,"))
    }

    @Test
    fun `含逗号的字段按 RFC4180 加引号`() {
        val csv = ScheduleExporter.toGoogleCalendarCsv(
            ScheduleExporter.expandEvents(
                listOf(course(name = "课,名", position = "楼,201", weeks = setOf(1))),
                slots,
                semester,
            ).ok,
        )
        assertTrue(csv.contains("\"课,名\",9/9/2026"))
        assertTrue(csv.contains("\"楼,201\"\r\n"))
    }

    @Test
    fun `空教师不生成教师前缀`() {
        val csv = ScheduleExporter.toGoogleCalendarCsv(
            ScheduleExporter.expandEvents(
                listOf(course(teacher = "", weeks = setOf(1))),
                slots,
                semester,
            ).ok,
        )
        assertTrue(csv.contains(",FALSE,,教学楼A-201"))
    }

    @Test
    fun `学期日期非法时展开为空`() {
        val bad = ScheduleExporter.expandEvents(
            listOf(course()),
            slots,
            SemesterConfig(startDate = "not-a-date", totalWeeks = 20),
        )
        assertEquals(0, bad.ok.size)
    }
}
