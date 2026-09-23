package edu.gcp.schedule.ui.widget

import edu.gcp.schedule.data.DefaultData
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.CourseFilter
import edu.gcp.schedule.domain.CourseKind
import edu.gcp.schedule.domain.LocalTimeLike
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.SemesterConfig
import edu.gcp.schedule.domain.buildTodayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 小组件展示模型的口径（DESIGN §3.6）。
 *
 * 这些断言锁的是桌面上的观感：
 * - 尺寸分档按**实测 dp**、不吸附：同一份快照在 2×2 / 4×2 / 4×4 与中间尺寸下渲染不同
 *   内容（2026-09-20 改版的核心诉求——旧版三档显示一样，用户认为冗余）；
 * - 列表行数按高度算（不再写死 3 行）；
 * - **今日上完 → 明日接棒**：列表换天、标题给「明天 · 周X · N 节」，不留弹性空白；
 * - 4×4 的周网格：列取 visibleDays、跨节块去重叠、高亮列按「今天还有课 → 今天，否则明天」。
 */
class WidgetModelTest {

    /** 第 1 周 = 8/31–9/6，9/17（周四）是第 3 周 */
    private val semester = SemesterConfig(startDate = "2026-08-31", totalWeeks = 20)
    private val slots = DefaultData.defaultTimeSlots

    /** 2026-09-17 是周四 */
    private val thursday = LocalDate.parse("2026-09-17")

    private fun course(
        id: Long,
        start: Int,
        end: Int,
        name: String = "课$id",
        day: Int = 4,
        weeks: Set<Int> = (1..10).toSet(),
    ) = Course(
        id = id,
        name = name,
        teacher = "老师$id",
        position = "教学北大楼(北B10$id)",
        day = day,
        startSection = start,
        endSection = end,
        weeks = weeks,
    )

    private fun stateAt(time: LocalTimeLike, courses: List<Course>, day: LocalDate = thursday) =
        buildTodayState(semester, slots, courses, day, time)

    // ---------- 焦点：下一节（60 分钟窗口内） ----------

    @Test
    fun nextCourseWithinWindow_showsLabelAndCountdown() {
        // 9:30 距 10:15 还有 45 分钟 ≤ 60 → 正常「下一节」课程卡
        val state = stateAt(LocalTimeLike(9, 30), listOf(course(1, 3, 4)))
        val focus = buildWidgetSnapshot(state).focus as WidgetFocus.Course

        assertEquals("下一节 · 第3-4节", focus.label)
        assertEquals("课1", focus.name)
        assertEquals("还有 45 分钟上课", focus.countdown)
        assertNull(focus.fallbackNote)
        assertNull(focus.progress)
    }

    /**
     * 远课（下一节在 60 分钟窗口外）不冒充「下一节」：焦点位换成状态行
     * （「今天还有 N 节课」+「下一节 14:00 开始」），列表仍是今天剩余课程。
     * 与今日页同一取舍（DESIGN §3.3/§3.6）。
     */
    @Test
    fun farAwayCourse_showsIdleCountInsteadOfNextCard() {
        val state = stateAt(LocalTimeLike(7, 0), listOf(course(1, 3, 4), course(2, 5, 6)))
        val snapshot = buildWidgetSnapshot(state)
        val focus = snapshot.focus as WidgetFocus.Idle

        assertEquals("今天还有 2 节课", focus.title)
        assertEquals("下一节 10:15 开始", focus.detail)
        assertEquals(WidgetDay.Today, snapshot.listDay)
        assertEquals(2, snapshot.rows.size)
    }

    @Test
    fun nextCourseWithinHour_showsMinutesCountdown() {
        val state = stateAt(LocalTimeLike(9, 45), listOf(course(1, 3, 4)))
        val focus = buildWidgetSnapshot(state).focus as WidgetFocus.Course

        assertEquals("还有 30 分钟上课", focus.countdown)
        assertNull(focus.fallbackNote)
    }

    // ---------- 焦点：正在上课 ----------

    @Test
    fun ongoingCourse_showsOngoingLabelWithProgress() {
        val state = stateAt(LocalTimeLike(10, 30), listOf(course(1, 3, 4)))
        val focus = buildWidgetSnapshot(state).focus as WidgetFocus.Course

        assertEquals("正在上课 · 第3-4节", focus.label)
        // 细粒度口径优先：10:30 在第 3 节（10:15–10:55）内，说「第3节 · 还有 25 分钟」
        assertEquals("第3节 · 还有 25 分钟下课", focus.countdown)
        assertTrue(focus.progress!! in 0f..1f)
    }

    // ---------- 副行口径：不带时刻（时刻在行首/焦点位，避免出现两遍 10:15） ----------

    @Test
    fun rowMetaHasNoClockAndCompactsPosition() {
        // 9:30：3-4 节在 60 分钟窗口内 → 进焦点卡，列表只剩 5-6 节
        val state = stateAt(LocalTimeLike(9, 30), listOf(course(1, 3, 4), course(2, 5, 6)))
        val snapshot = buildWidgetSnapshot(state)

        // 焦点课在第 1 列（remaining 含焦点，listCourses 不含）
        assertEquals(1, snapshot.rows.size)
        val row = snapshot.rows.first()
        assertEquals("14:00", row.clock)
        assertEquals("@北B102 · 老师2", row.meta)
    }

    // ---------- 尺寸分档：按实测 dp 分档，不吸附 ----------

    @Test
    fun metrics_matchEntryDefaults() {
        // 2×2 与 4×2 都是紧凑档（宽窄决定课名行数），2×4 与中间尺寸进列表/周网格
        assertEquals(WidgetLayout.Compact, widgetMetricsFor(110, 110).layout)
        assertEquals(WidgetLayout.Compact, widgetMetricsFor(250, 110).layout)
        assertEquals(WidgetLayout.List, widgetMetricsFor(110, 250).layout)
        assertEquals(WidgetLayout.List, widgetMetricsFor(250, 200).layout)
        assertEquals(WidgetLayout.Week, widgetMetricsFor(250, 250).layout)
        assertEquals(WidgetLayout.Week, widgetMetricsFor(440, 440).layout)
    }

    @Test
    fun metrics_focusNameLinesAndMetaPlacement() {
        // 紧凑档课名一律 1 行（110dp 高给不出第二行），副行在够宽时才与课名同排
        assertEquals(1, widgetMetricsFor(110, 110).focusNameLines)
        assertEquals(1, widgetMetricsFor(250, 110).focusNameLines)
        assertFalse(widgetMetricsFor(110, 110).metaInline)
        assertTrue(widgetMetricsFor(250, 110).metaInline)
        // 列表档课名两行、副行竖排（高度够，观感与今日页一致）
        assertEquals(2, widgetMetricsFor(110, 250).focusNameLines)
        assertFalse(widgetMetricsFor(110, 250).metaInline)
        assertFalse(widgetMetricsFor(110, 110).showProgress)
        assertTrue(widgetMetricsFor(250, 200).showProgress)
    }

    // ---------- 列表行数按高度算 ----------

    /**
     * 预算公式（与实现同一条算术，改动任一常量都会在这里现形）：
     * `available = height − 边距20 − 日期行21 − 焦点卡88`；
     * 放得下就全放，要截断则最多 `⌊available / 45⌋` 行且必须再让出 21dp 给「还有 N 节」。
     */
    @Test
    fun rowsBudget_shrinksWithHeightAndLeavesRoomForMoreLabel() {
        // 全放得下：不截断、不留空位（不为「还有 N 节」白扣一行）
        assertEquals(4, rowsBudgetFor(heightDp = 400, totalRows = 4))
        // 250dp：available=121 → 2 行 + 提示（2×45+21=111 ≤ 121）放得下
        assertEquals(2, rowsBudgetFor(heightDp = 250, totalRows = 5))
        // 210dp：available=81 → 1 行 + 提示（45+21=66 ≤ 81）；2 行 + 提示（111）放不下
        assertEquals(1, rowsBudgetFor(heightDp = 210, totalRows = 5))
        // 160dp：available=31 < 45，一行都放不下 → 0（宁可只画焦点卡，也不让半行被裁）
        assertEquals(0, rowsBudgetFor(heightDp = 160, totalRows = 5))
        // 无课可列：0（调用方不需要为「空快照」特判）
        assertEquals(0, rowsBudgetFor(heightDp = 400, totalRows = 0))
    }

    @Test
    fun listLayout_usesHeightBudgetAndMoreLabel() {
        // 6 门 3-4 节的课：9:30 时全部未开始，第一门 10:15 在 60 分钟窗口内 →
        // 进焦点卡，列表剩 5 门（与预算公式配套的场景）
        val courses = (1..6).map { course(it.toLong(), 3, 4) }
        val snapshot = buildWidgetSnapshot(stateAt(LocalTimeLike(9, 30), courses))

        // 2×4（110×250）：焦点拿走一门，剩 5 行；预算 2 行 + 「还有 3 节」
        val narrow = snapshot.forSize(widgetMetricsFor(110, 250))
        assertEquals(2, narrow.rows.size)
        assertEquals("还有 3 节", narrow.rowsMoreLabel)

        // 4×4 是周网格档，列表整块不渲染（内容交给周网格）
        val large = snapshot.forSize(widgetMetricsFor(250, 250))
        assertEquals(0, large.rows.size)
        assertNull(large.rowsMoreLabel)
    }

    @Test
    fun compactLayout_dropsRowsOnNormalDay() {
        val courses = (1..3).map { course(it.toLong(), 3, 4) }
        val snapshot = buildWidgetSnapshot(stateAt(LocalTimeLike(9, 30), courses))

        val model = snapshot.forSize(widgetMetricsFor(110, 110))
        assertEquals(0, model.rows.size)
        // 紧凑档也带日期行（2026-09-19 起）；整列不放行时「还有 N 节」一并省掉
        assertTrue(model.header.isNotBlank())
        assertNull(model.rowsMoreLabel)
        assertNull(model.week)
    }

    // ---------- 明日接棒（2026-09-20 改版的核心） ----------

    @Test
    fun allDone_handsOffToTomorrowWithTitleAndRows() {
        // 周四的课全上完（20:00），周五有课
        val state = buildTodayState(
            semester, slots,
            listOf(course(1, 3, 4), course(2, 1, 2, day = 5)),
            thursday, LocalTimeLike(20, 0),
        )
        val snapshot = buildWidgetSnapshot(state)

        val focus = snapshot.focus as WidgetFocus.Idle
        assertEquals("今天的课都上完了", focus.title)
        // 明日有课 → 状态行不再多一句落点（标题 + 首行已交代）
        assertNull(focus.detail)

        // 列表换天：内容 = 明日课程，标题给「明天 · 周X · N 节」
        assertEquals(WidgetDay.Tomorrow, snapshot.listDay)
        assertEquals("明天 · 周五 · 1 节", snapshot.listTitle)
        assertEquals(1, snapshot.rows.size)
        assertTrue(snapshot.rows.first().name.startsWith("课2"))
    }

    @Test
    fun tomorrowEmpty_givesRestHintOnStatusLine() {
        val state = buildTodayState(
            semester, slots, listOf(course(1, 3, 4)), LocalDate.parse("2026-09-13"),
            LocalTimeLike(20, 0),
        ) // 9/13 是周日，且周日无课
        val snapshot = buildWidgetSnapshot(state)

        val focus = snapshot.focus as WidgetFocus.Idle
        assertEquals("今天没有课", focus.title)
        assertEquals("明天没有课，可以放松一下", focus.detail)
        assertNull(snapshot.listTitle)
        assertTrue(snapshot.rows.isEmpty())
    }

    @Test
    fun compactHandoff_showsTomorrowFirstRowInsteadOfBlank() {
        // 4×2 紧凑档：平时整列不放行，明日接棒时给 1–2 行明日课程
        val state = buildTodayState(
            semester, slots,
            listOf(course(1, 1, 2), course(2, 3, 4, day = 5), course(3, 5, 6, day = 5)),
            thursday, LocalTimeLike(20, 0),
        )
        val snapshot = buildWidgetSnapshot(state)
        val model = snapshot.forSize(widgetMetricsFor(250, 110))

        assertEquals(WidgetDay.Tomorrow, model.listDay)
        assertTrue("紧凑档接棒时至少要放出明天第一行", model.rows.isNotEmpty())
        assertEquals("明天 · 周五 · 2 节", model.listTitle)
    }

    @Test
    fun ongoingDay_keepsTodayListAndNoTomorrow() {
        // 今天还有课 → 主内容仍是今天，明日不上桌
        val courses = listOf(course(1, 3, 4), course(2, 5, 6, day = 5))
        val snapshot = buildWidgetSnapshot(stateAt(LocalTimeLike(7, 0), courses))

        assertEquals(WidgetDay.Today, snapshot.listDay)
        assertNull(snapshot.listTitle)
        val model = snapshot.forSize(widgetMetricsFor(250, 250))
        // 4×4 的列表让位给周网格（列表内容由周网格表达）
        assertEquals(0, model.rows.size)
    }

    @Test
    fun outOfTerm_showsVacationHintWithoutRows() {
        val state = buildTodayState(
            semester, slots, listOf(course(1, 3, 4)), LocalDate.parse("2027-02-01"),
            LocalTimeLike(10, 0),
        )
        val snapshot = buildWidgetSnapshot(state)

        val focus = snapshot.focus as WidgetFocus.Idle
        assertEquals("假期中", focus.title)
        assertEquals("未在学期内，去「我的」设置开学日期", focus.detail)
        assertNull(snapshot.listTitle)
        assertTrue(snapshot.rows.isEmpty())
    }

    // ---------- 周迷你网格 ----------

    private fun weekGrid(
        courses: List<Course>,
        now: LocalTimeLike = LocalTimeLike(9, 0),
        highlightDay: Int? = 4,
        showSaturday: Boolean = true,
        showSunday: Boolean = true,
        filter: CourseFilter = CourseFilter.All,
        markNow: Boolean = true,
        nowDay: Int? = highlightDay,
    ) = buildWidgetWeek(
        courses = courses,
        week = 3,
        showSaturday = showSaturday,
        showSunday = showSunday,
        filter = filter,
        highlightDay = highlightDay,
        slots = slots,
        now = now,
        markNow = markNow,
        nowDay = nowDay,
    )

    @Test
    fun week_daysFollowVisibleDaysNotDayMinusOne() {
        // 关掉周六：列序必须是 1,2,3,4,5,7（周日落在第 6 列，不是第 7 列）
        val grid = weekGrid(listOf(course(1, 1, 2)), showSaturday = false)
        assertEquals(listOf(1, 2, 3, 4, 5, 7), grid.days)
    }

    @Test
    fun week_blocksUseSectionNumbersAsRows() {
        val grid = weekGrid(listOf(course(1, 3, 4), course(2, 9, 11, day = 5)))

        val block = grid.blocks.single { it.day == 4 }
        assertEquals(3, block.startSection)
        assertEquals(4, block.endSection)
        val late = grid.blocks.single { it.day == 5 }
        assertEquals(9, late.startSection)
        assertEquals(11, late.endSection)
        assertEquals(11, grid.sectionCount)
    }

    @Test
    fun week_overlappingBlocksKeepEarliestOnly() {
        // 同一天 3-4 与 4-5 重叠：只留先开始的（迷你网格不做冲突排版）
        val grid = weekGrid(listOf(course(1, 3, 4, name = "先"), course(2, 4, 5, name = "后")))

        val block = grid.blocks.single { it.day == 4 }
        assertEquals("先", block.name)
        assertEquals(4, block.endSection)
    }

    @Test
    fun week_otherWeeksExcluded() {
        // 第 3 周的网格不含只在第 5–6 周上的课
        val grid = weekGrid(listOf(course(1, 1, 2, weeks = setOf(5, 6))))
        assertTrue(grid.blocks.isEmpty())
    }

    @Test
    fun week_filterByKind() {
        val theory = course(1, 1, 2)
        val lab = course(2, 3, 4).copy(kind = CourseKind.Lab)
        val exam = course(3, 5, 6).copy(kind = CourseKind.Exam)

        assertEquals(3, weekGrid(listOf(theory, lab, exam)).blocks.size)
        // 只看理论课：只剩第 1–2 节那块
        assertEquals(1, weekGrid(listOf(theory, lab, exam), filter = CourseFilter.Theory).blocks.single().startSection)
        assertEquals(3, weekGrid(listOf(theory, lab, exam), filter = CourseFilter.Lab).blocks.single().startSection)
        assertEquals(5, weekGrid(listOf(theory, lab, exam), filter = CourseFilter.Exam).blocks.single().startSection)
    }

    @Test
    fun week_highlightDayFallsBackWhenDayHidden() {
        // 明天是周日但周日列被关 → 无高亮列（不是错列到周六）
        val grid = weekGrid(emptyList(), highlightDay = 7, showSunday = false)
        assertNull(grid.highlightDay)
    }

    @Test
    fun week_nowSectionInsideAndBetweenClasses() {
        // 10:30 在第 3 节（10:15–10:55）内
        assertEquals(3, currentSectionNumber(slots, LocalTimeLike(10, 30)))
        // 10:57 在第 3 节结束与第 4 节（11:00）之间 → 归第 4 节
        assertEquals(4, currentSectionNumber(slots, LocalTimeLike(10, 57)))
        // 08:00 早于第一节 → 不标
        assertNull(currentSectionNumber(slots, LocalTimeLike(8, 0)))
        // 22:00 晚于最后一节 → 不标
        assertNull(currentSectionNumber(slots, LocalTimeLike(22, 0)))
    }

    @Test
    fun week_nowSectionRespectsMarkNowFlag() {
        assertNull(weekGrid(emptyList(), now = LocalTimeLike(10, 30), markNow = false).nowSection)
        assertEquals(3, weekGrid(emptyList(), now = LocalTimeLike(10, 30), markNow = true).nowSection)
    }

    @Test
    fun week_nowDayGatesTheNowMark() {
        // 「现在」必须同时带天与节次号：只给节次号会让周一到周日整行都被点亮
        val grid = weekGrid(emptyList(), highlightDay = 4, now = LocalTimeLike(10, 30))
        assertEquals(4, grid.nowDay)
        assertEquals(3, grid.nowSection)

        // 明天接棒（高亮列是周五）时 markNow=false → 两个字段都不给，不标记「现在」
        val handoff = weekGrid(
            emptyList(),
            highlightDay = 5,
            now = LocalTimeLike(20, 0),
            markNow = false,
            nowDay = null,
        )
        assertEquals(5, handoff.highlightDay)
        assertNull(handoff.nowDay)
        assertNull(handoff.nowSection)

        // 高亮列被显示设置隐藏（明天是周日而周日列关了）→ nowDay 也跟着不下发
        val hidden = weekGrid(
            emptyList(),
            highlightDay = 7,
            showSunday = false,
            now = LocalTimeLike(10, 30),
        )
        assertNull(hidden.highlightDay)
        assertNull(hidden.nowDay)
    }

    // ---------- 尺寸裁剪 ----------

    @Test
    fun forSize_keepsWeekOnlyOnWeekLayout() {
        val courses = (1..3).map { course(it.toLong(), 1, 2) }
        val snapshot = buildWidgetSnapshot(
            stateAt(LocalTimeLike(7, 0), courses),
            weekGrid(courses),
        )

        assertNull(snapshot.forSize(widgetMetricsFor(250, 110)).week)
        assertNull(snapshot.forSize(widgetMetricsFor(110, 250)).week)
        val week = snapshot.forSize(widgetMetricsFor(250, 250))
        assertTrue(week.week != null)
        // 摘要行也只在周网格档给（其余档焦点卡信息更全）
        assertTrue(week.summary?.isNotBlank() == true)
        assertNull(snapshot.forSize(widgetMetricsFor(250, 110)).summary)
    }

    @Test
    fun summary_prefersFocusElseTomorrowFirstCourse() {
        val today = buildWidgetSnapshot(stateAt(LocalTimeLike(10, 30), listOf(course(1, 3, 4))))
        assertTrue(today.summary!!.startsWith("正在上课 · 第3-4节"))
        assertTrue(today.summary!!.contains("课1"))

        // 远课（10:00 看 14:00 的课，窗口外）：摘要仍给今天第一门，直接时刻开头
        val far = buildWidgetSnapshot(
            stateAt(LocalTimeLike(10, 0), listOf(course(1, 5, 6), course(2, 3, 4, day = 5))),
        )
        assertTrue(far.summary!!.startsWith("14:00 课1"))

        val handoff = buildTodayState(
            semester, slots,
            listOf(course(1, 1, 2), course(2, 3, 4, day = 5)),
            thursday, LocalTimeLike(20, 0),
        ).let { buildWidgetSnapshot(it) }
        assertTrue(handoff.summary!!.startsWith("明天 10:15 课2"))
    }

    // ---------- 编解码 ----------

    @Test
    fun snapshotCodec_roundTrips() {
        val snapshot = buildWidgetSnapshot(
            stateAt(LocalTimeLike(10, 30), listOf(course(1, 3, 4))),
            weekGrid(listOf(course(1, 3, 4))),
        )
        val raw = WidgetSnapshotCodec.encode(snapshot)
        val decoded = WidgetSnapshotCodec.decode(raw)

        assertEquals(snapshot, decoded)
    }

    @Test
    fun snapshotCodec_garbageYieldsNull() {
        assertNull(WidgetSnapshotCodec.decode("not-json"))
        assertNull(WidgetSnapshotCodec.decode(null))
    }

    @Test
    fun snapshotCodec_oldPayloadWithoutNewFieldsStillDecodes() {
        // 老版本（三档尺寸 + 明日预告）写进 DataStore 的 JSON：字段形态已变，
        // 解码必须不崩——忽略未知键 + 新字段默认值兜底
        val legacy = """
            {"header":"9月18日 周四 · 第 3 周",
             "focus":{"type":"course","label":"下一节 · 第3-4节","name":"高数",
                      "meta":"@北B102 · 陈磊","colorIndex":3},
             "rows":[{"clock":"14:00","name":"英语","meta":"@南A101","colorIndex":1}],
             "tomorrow":{"type":"courses","title":"明天 · 周五","rows":[]}}
        """.trimIndent()

        val decoded = WidgetSnapshotCodec.decode(legacy)
        assertTrue(decoded != null)
        assertEquals(WidgetDay.Today, decoded!!.listDay)
        assertNull(decoded.listTitle)
        assertNull(decoded.week)
        assertEquals(1, decoded.rows.size)
    }

    // ---------- 周网格行高 ----------

    @Test
    fun weekRowHeight_fillsAvailableSpaceWithFloor() {
        // 4×4（250dp）：去掉日期行/摘要/表头/边距后均分给 11 节，每行 ≥ 8dp
        val h = weekRowHeightDp(250, 11)
        assertTrue("actual=$h", h >= MIN_ROW_HEIGHT_DP_FOR_TEST)

        // 极高时行高跟着长（内容随尺寸变，不是固定值）
        assertTrue(weekRowHeightDp(440, 11) > h)

        // 再小也不塌成 0（下限保护）
        assertEquals(MIN_ROW_HEIGHT_DP_FOR_TEST, weekRowHeightDp(150, 11))
    }

    private companion object {
        /** 与 `ScheduleWidget.kt` 的网格行高下限一致（8dp）。 */
        const val MIN_ROW_HEIGHT_DP_FOR_TEST = 8
    }

    // ---------- 与课表页同一口径的辅助断言 ----------

    @Test
    fun visibleDaysMatchesCalculator() {
        assertEquals(
            ScheduleCalculator.visibleDays(showSaturday = false, showSunday = true),
            weekGrid(emptyList(), showSaturday = false, showSunday = true).days,
        )
    }
}
