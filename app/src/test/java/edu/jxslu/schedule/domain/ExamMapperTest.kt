package edu.jxslu.schedule.domain

import edu.jxslu.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 考试 → 课条目映射（DESIGN §4.14）。
 * 作息表用 §3.5 的默认口径（08:30 起，11 小节），考试时段与节次边界对齐是实测事实。
 */
class ExamMapperTest {

    private val slots = DefaultData.defaultTimeSlots
    private val config = SemesterConfig(startDate = "2026-03-02", totalWeeks = 20, firstDayOfWeek = 1)

    // ---- 时刻 → 节次 ----

    @Test
    fun alignedExamTimesMapToOverlappingSections() {
        // 实测考试时间全部与节次边界对齐
        assertEquals(1..2, ExamMapper.sectionsForTimeRange(slots, "08:30", "09:55"))
        assertEquals(3..4, ExamMapper.sectionsForTimeRange(slots, "10:15", "11:40"))
        assertEquals(5..6, ExamMapper.sectionsForTimeRange(slots, "14:00", "15:25"))
        assertEquals(7..8, ExamMapper.sectionsForTimeRange(slots, "15:40", "17:05"))
        // 第 5 个大节 9/10/11 = 18:30–20:40（2026-09-23 作息）
        assertEquals(9..10, ExamMapper.sectionsForTimeRange(slots, "18:30", "19:55"))
        assertEquals(9..11, ExamMapper.sectionsForTimeRange(slots, "18:30", "20:40"))
    }

    @Test
    fun gapTimeFallsBackToNearestFollowingSection() {
        // 午饭空档：取开始时刻之后的最近一节（14:00 → 第 5 节）
        assertEquals(5..5, ExamMapper.sectionsForTimeRange(slots, "12:00", "13:00"))
        // 早于第一节：取第一节
        assertEquals(1..1, ExamMapper.sectionsForTimeRange(slots, "07:00", "07:30"))
        // 晚于所有节次：取最后一节兜底
        assertEquals(11..11, ExamMapper.sectionsForTimeRange(slots, "23:00", "23:30"))
    }

    @Test
    fun invalidTimeReturnsNull() {
        assertNull(ExamMapper.sectionsForTimeRange(slots, "", "09:55"))
        assertNull(ExamMapper.sectionsForTimeRange(slots, "08:30", "bad"))
        assertNull(ExamMapper.sectionsForTimeRange(slots, "25:00", "26:00"))
        assertNull(ExamMapper.sectionsForTimeRange(emptyList(), "08:30", "09:55"))
    }

    // ---- 日期 → (周, 星期) ----

    @Test
    fun dateMapsToWeekAndDay() {
        // 2026-03-02 是周一（第 1 周）；周六 2026-03-07、周日 2026-03-08
        assertEquals(1 to 1, ExamMapper.weekAndDay(config, "2026-03-02"))
        assertEquals(1 to 6, ExamMapper.weekAndDay(config, "2026-03-07"))
        assertEquals(1 to 7, ExamMapper.weekAndDay(config, "2026-03-08"))
        // 2026-03-02 + 77 天 = 2026-05-18，第 12 周周一
        assertEquals(12 to 1, ExamMapper.weekAndDay(config, "2026-05-18"))
    }

    @Test
    fun outOfTermOrInvalidDateReturnsNull() {
        // 学期开始前（第 0 周）
        assertNull(ExamMapper.weekAndDay(config, "2026-03-01"))
        // 第 20 周的周日是 2026-07-19（在学期内），第 21 周周一 2026-07-20 超界
        assertEquals(20 to 7, ExamMapper.weekAndDay(config, "2026-07-19"))
        assertNull(ExamMapper.weekAndDay(config, "2026-07-20"))
        // 非法日期串
        assertNull(ExamMapper.weekAndDay(config, "2026/05/18"))
        assertNull(ExamMapper.weekAndDay(config, ""))
    }

    // ---- 考试 → Course ----

    private val entry = ExamMapper.ExamEntry(
        courseNo = "050221005",
        name = "跨文化交际英语（中国水文化英译）",
        teacher = "李红梅",
        room = "南C303",
        campus = "瑶湖校区",
        date = "2026-05-18",
        startTime = "08:30",
        endTime = "09:55",
        seatNo = "65",
        sessionNo = "3112522010113",
    )

    @Test
    fun examEntryBecomesExamCourse() {
        val c = ExamMapper.toExamCourse(entry, slots, config)!!
        assertEquals(CourseKind.Exam, c.kind)
        assertEquals("跨文化交际英语（中国水文化英译）", c.name)
        assertEquals(12, c.weeks.single()) // 日期定位到第 12 周
        assertEquals(1, c.day)            // 周一
        assertEquals(1, c.startSection)   // 08:30~09:55 → 1-2 节
        assertEquals(2, c.endSection)
        assertEquals(true, c.isCustomTime)
        assertEquals("08:30", c.customStartTime)
        assertEquals("09:55", c.customEndTime)
        assertEquals("南C303 瑶湖校区", c.position)
        assertEquals("李红梅", c.teacher)
        assertEquals(0, c.colorIndex)     // 颜色由导入路径统一重排
    }

    @Test
    fun unwappableExamReturnsNull() {
        // 考试日期超出学期范围
        val outOfTerm = entry.copy(date = "2026-08-01")
        assertNull(ExamMapper.toExamCourse(outOfTerm, slots, config))
        // 无名字
        assertNull(ExamMapper.toExamCourse(entry.copy(name = "  "), slots, config))
    }

    @Test
    fun batchConversionCountsSkipped() {
        val ok = entry.copy(name = "热工基础", date = "2026-05-26")
        val r = ExamMapper.toExamCourses(
            listOf(entry, ok, entry.copy(date = "2026-08-01")),
            slots,
            config,
        )
        assertEquals(2, r.courses.size)
        assertEquals(1, r.skipped)
        assertEquals(0, r.estimated) // 日期都在配置学期内，无需估算
    }

    @Test
    fun nullSemesterSkipsEverythingWithoutTerm() {
        val r = ExamMapper.toExamCourses(listOf(entry), slots, null)
        assertEquals(0, r.courses.size)
        assertEquals(1, r.skipped)
    }

    // ---- 历史/未来学期的估算定位（DESIGN §4.14）----

    @Test
    fun estimatedTermStartFollowsCalendarConvention() {
        // 第 1 学期：含 9 月 1 日那一周的周一（2026-09-01 是周二 → 2026-08-31）
        assertEquals(java.time.LocalDate.of(2026, 8, 31), ExamMapper.estimatedTermStart("2026-2027-1"))
        // 第 2 学期：含 3 月 1 日那一周的周一（2026-03-01 是周日 → 2026-02-23）
        assertEquals(java.time.LocalDate.of(2026, 2, 23), ExamMapper.estimatedTermStart("2025-2026-2"))
        // 学期号无法识别
        assertNull(ExamMapper.estimatedTermStart("2025-2026-3"))
        assertNull(ExamMapper.estimatedTermStart("bad"))
        assertNull(ExamMapper.estimatedTermStart(null))
    }

    @Test
    fun outOfConfigExamFallsBackToEstimatedTermStart() {
        // 配置是当前秋季学期（2026-08-31 开学）；2025-2026-2 的考试日期（2026-05）对它
        // 而言落在范围外，按该学期估算开学日 2026-02-23 定位：2026-05-18 = 第 13 周周一
        val fallConfig = SemesterConfig(startDate = "2026-08-31", totalWeeks = 20, firstDayOfWeek = 1)
        val r = ExamMapper.toExamCourses(listOf(entry), slots, fallConfig, term = "2025-2026-2")
        assertEquals(1, r.courses.size)
        assertEquals(0, r.skipped)
        assertEquals(1, r.estimated)
        assertEquals(13, r.courses.single().weeks.single())
        assertEquals(1, r.courses.single().day)
    }

    @Test
    fun estimateWorksWithoutConfiguredSemester() {
        // 未配置开学日也能靠估算定位（学期号可识别时）
        val r = ExamMapper.toExamCourses(listOf(entry), slots, null, term = "2025-2026-2")
        assertEquals(1, r.courses.size)
        assertEquals(1, r.estimated)
        assertEquals(13, r.courses.single().weeks.single())
    }

    @Test
    fun estimateOutOfRangeStillSkipped() {
        // 2026-11-01 距估算开学日 2026-02-23 已超过 30 周，估算口径也救不了
        val r = ExamMapper.toExamCourses(
            listOf(entry.copy(date = "2026-11-01")),
            slots,
            null,
            term = "2025-2026-2",
        )
        assertEquals(0, r.courses.size)
        assertEquals(1, r.skipped)
    }
}
