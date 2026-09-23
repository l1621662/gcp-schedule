package edu.jxslu.schedule.ui.week

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.data.DefaultData
import edu.jxslu.schedule.domain.LocalTimeLike
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 课表网格几何。
 *
 * 这一段是最容易出错的：行高、大节间隔、课块高度、时刻线位置全是浮点加减，
 * 一旦算错就是「课块错位／跑到网格外／时刻线不在今天那一列」这类只能靠肉眼发现的问题。
 * 所以把几何提成纯函数后在这里把契约钉死。
 *
 * 数值就是 DESIGN 3.5 + 3.3 的规格：11 小节、大节内 3dp、大节之间 6dp、最小行高 40dp。
 */
class WeekGridLayoutTest {

    private val slots = DefaultData.defaultTimeSlots
    private val bigEnds = setOf(2, 4, 6, 8)
    private val dayHeaderHeight = 44.dp

    /** Dp 是 Float，浮点结合顺序不同会差 1e-5，精确相等不可靠。 */
    private fun assertDpEquals(expected: Dp, actual: Dp, message: String = "") {
        assertEquals("$message 期望 $expected 实际 $actual", expected.value, actual.value, 0.01f)
    }

    private fun layout(
        maxHeight: Dp = 612.dp,
        maxWidth: Dp = 390.dp,
        days: Int = 7,
        rowHeightScale: Float = 1f,
        railWidth: Dp = 48.dp,
        dayHeaderHeight: Dp = 44.dp,
    ) = buildGridLayout(
        maxHeight = maxHeight,
        maxWidth = maxWidth,
        sectionCount = 11,
        dayCount = days,
        rowHeightScale = rowHeightScale,
        railWidth = railWidth,
        dayHeaderHeight = dayHeaderHeight,
    )

    /**
     * maxHeight 是**含星期表头**的整个内容区，网格只占剩下部分。
     * 13 个间隔（6 个 3dp + 4 个 6dp）+ 11 行，正好把剩余高度用满——这是「自适应撑满」的契约。
     */
    @Test
    fun gridFillsSpaceBelowDayHeader() {
        val l = layout()
        assertDpEquals(3.dp * 6 + 6.dp * 4 + l.rowHeight * 11, l.gridHeight)
        assertDpEquals(612.dp - dayHeaderHeight, l.gridHeight)
    }

    /** 相邻两行不重叠，且间隔符合大节/小节区分 */
    @Test
    fun rowsDoNotOverlapAndGapsMatchBigSections() {
        val l = layout()
        for (section in 1..10) {
            val gap = l.topOf(section + 1) - l.bottomOf(section)
            val expected = if (section in bigEnds) 6.dp else 3.dp
            assertEquals("第 $section 节之后的间隔", expected, gap)
        }
    }

    /** 行号 = 小节号：第 1 节顶部为 0，且行数等于小节数 */
    @Test
    fun rowIndexMatchesSectionNumber() {
        val l = layout()
        assertEquals(11, l.sections)
        assertEquals(0.dp, l.topOf(1))
        assertEquals(l.rowHeight, l.bottomOf(1))
    }

    /** 连续小节合并后的色块高度 = 覆盖到的行高 + 中间的大节内间隔（不能少算，否则相邻课块会差 3dp） */
    @Test
    fun mergedBlockHeightIncludesInnerGap() {
        val l = layout()
        assertDpEquals(l.rowHeight * 2 + 3.dp, l.heightOf(1, 2))
        assertDpEquals(l.rowHeight * 2 + 3.dp, l.heightOf(3, 4))
        assertDpEquals(l.rowHeight * 2 + 3.dp, l.heightOf(9, 10))
    }

    /** 晚间大节 9-11 行也存在，9-10 节的课不会掉到网格外 */
    @Test
    fun eveningSectionsAreInsideGrid() {
        val l = layout()
        assertEquals(11, l.sections)
        assertTrue(l.bottomOf(10) <= l.gridHeight)
        assertDpEquals(l.gridHeight, l.bottomOf(11))
    }

    /** 屏幕不够高时不再压缩到看不清，而是取最小行高让调用方滚动 */
    @Test
    fun shortScreenClampsToMinRowHeight() {
        val l = layout(maxHeight = 300.dp)
        assertDpEquals(40.dp, l.rowHeight)
        assertTrue(l.gridHeight > 300.dp - dayHeaderHeight)
    }

    /**
     * 行高倍率乘在**自适应值**上，再吃 MinRowHeight 下限：
     * - 1.5× 时行高 = 自适应值 × 1.5（超出屏幕交给调用方竖滑）；
     * - 0.2× 时自适应值 ≈ 49dp × 0.2 ≈ 10dp，被下限托到 40dp，不会缩到看不清。
     */
    @Test
    fun rowHeightScaleMultipliesAutoHeightWithFloor() {
        val auto = layout()
        val bigger = layout(rowHeightScale = 1.5f)
        assertDpEquals(auto.rowHeight * 1.5f, bigger.rowHeight)

        val smaller = layout(rowHeightScale = 0.2f)
        assertDpEquals(40.dp, smaller.rowHeight)
        assertTrue(smaller.gridHeight >= 40.dp * 11 + 3.dp * 6 + 6.dp * 4)
    }

    /** 列宽随「显示周末」开关变化，5 天模式单列明显更宽 */
    @Test
    fun dayWidthFollowsVisibleDayCount() {
        val week = layout(days = 7)
        val workdays = layout(days = 5)
        assertEquals((390.dp - 48.dp - GridEndGap) / 7, week.dayWidth)
        assertEquals((390.dp - 48.dp - GridEndGap) / 5, workdays.dayWidth)
        assertTrue(workdays.dayWidth > week.dayWidth)
    }

    /**
     * 右缘留白契约：网格总宽 = 总宽 − 时间轴 − GridEndGap，右端不贴屏幕边界。
     * 翻周滑动时相邻两页（本周周日列 ↔ 次周周一列）之间也正好隔出这段留白。
     */
    @Test
    fun gridLeavesGapAtRightEdge() {
        val week = layout(days = 7)
        assertDpEquals(390.dp - 48.dp - GridEndGap, week.dayWidth * 7)
        val workdays = layout(days = 5)
        assertDpEquals(390.dp - 48.dp - GridEndGap, workdays.dayWidth * 5)
    }

    /**
     * 时间轴栏宽 / 表头高度可调（显示设置新增项）后的契约：
     * - 列宽 =（总宽 − 栏宽 − 右缘留白）/ 列数；
     * - 网格可用高度 = 总高 − 表头高度，表头调高行高自动收；
     * - 两者默认值与 TimetablePrefs companion 一致（单一来源）。
     */
    @Test
    fun railWidthAndHeaderHeightParticipateInGeometry() {
        val narrow = layout(railWidth = 28.dp)
        assertEquals((390.dp - 28.dp - GridEndGap) / 7, narrow.dayWidth)
        assertEquals(28.dp, narrow.railWidth)

        val wide = layout(railWidth = 72.dp)
        assertEquals((390.dp - 72.dp - GridEndGap) / 7, wide.dayWidth)
        assertTrue(narrow.dayWidth > wide.dayWidth)

        val tall = layout(dayHeaderHeight = 64.dp)
        assertEquals(64.dp, tall.dayHeaderHeight)
        // 行高收窄：可用高度少了（612 - 64） vs （612 - 44）
        assertTrue(tall.rowHeight < layout(dayHeaderHeight = 44.dp).rowHeight)
        assertDpEquals(612.dp - 64.dp, tall.gridHeight)
    }

    /**
     * 表头高度下限随日期字号抬高（防「表头拖到最小 + 日期字号拉到最大」时两行文字被截断）：
     * - 基准日期字号（7 列 12sp）下，44dp 默认表头已足够，下限不生效；
     * - 字号越大下限越高；日期 14sp 时下限 > 44dp，必须抬高；
     * - 结果始终夹在 [32, 64] 滑块范围内。
     */
    @Test
    fun minHeaderHeightFollowsDateFont() {
        // 12sp 基准：12 * 1.92 * 1.3 + 10 ≈ 40dp < 44dp 默认值，默认表头足够
        assertTrue(minHeaderHeightForDateFont(12f) <= 44f)
        // 14sp：14 * 1.92 * 1.3 + 10 ≈ 45dp > 44dp，需要抬高
        assertTrue(minHeaderHeightForDateFont(14f) > 44f)
        // 极端输入也不越界
        assertEquals(32f, minHeaderHeightForDateFont(0f), 0f)
        assertEquals(64f, minHeaderHeightForDateFont(100f), 0f)
        // 单调：字号越大下限越高
        assertTrue(minHeaderHeightForDateFont(14f) > minHeaderHeightForDateFont(7f))
    }

    /** 作息表最后一节（第 11 小节）的结束分钟数，作 dayEndMinutes 哨兵保持「挂满全天」的旧语义 */
    private val scheduleEndMinutes = 21 * 60 + 10

    /** 时刻线：第 5 节（14:00-14:40）内 14:15 落在该行 15/40 处 */
    @Test
    fun nowMarkerInterpolatesInsideSection() {
        val l = layout()
        val m = nowMarker(slots, l, LocalTimeLike(14, 15), scheduleEndMinutes)!!
        assertFalse(m.inBreak)
        val expected = l.topOf(5) + (l.bottomOf(5) - l.topOf(5)) * (15f / 40f)
        assertEquals(expected.value, m.offsetY.value, 0.01f)
    }

    /**
     * 课间吸附：09:57 距 09:55（下课）2 分钟、距 10:15（上课）18 分钟，
     * 所以贴在第 2 节的行底——而不是在 12dp 的间隔里走 2/20，那会让线看起来卡住。
     */
    @Test
    fun nowMarkerSnapsToNearestBoundaryDuringBreak() {
        val l = layout()
        val m = nowMarker(slots, l, LocalTimeLike(9, 57), scheduleEndMinutes)!!
        assertTrue(m.inBreak)
        assertDpEquals(l.bottomOf(2), m.offsetY, "刚下课应贴在上完那节的行底")
    }

    /** 晚饭 17:05→18:30：18:25 已经离 18:30 更近，应贴在第 9 节的行顶 */
    @Test
    fun nowMarkerSnapsToUpcomingSectionNearItsStart() {
        val l = layout()
        val m = nowMarker(slots, l, LocalTimeLike(18, 25), scheduleEndMinutes)!!
        assertTrue(m.inBreak)
        assertDpEquals(l.topOf(9), m.offsetY, "临近上课应贴在下一节的行顶")
    }

    /** 上课前 / 放学后不画时刻线 */
    @Test
    fun nowMarkerIsNullOutsideSchoolHours() {
        val l = layout()
        assertNull(nowMarker(slots, l, LocalTimeLike(7, 0), scheduleEndMinutes))
        assertNull(nowMarker(slots, l, LocalTimeLike(22, 0), scheduleEndMinutes))
    }

    /**
     * 终点按当日课程收口：dayEndMinutes = 今日最后一节结束时间。
     * 根因：此前只按作息表画，当天 17:10 上完课线却一直挂到 21:10，看起来像「还有课」。
     */
    @Test
    fun nowMarkerHidesAfterTodaysLastClassEnds() {
        val l = layout()
        // 假设今天最后一节是 7-8（16:30-17:10 下课）
        val lastEnd = 17 * 60 + 10
        // 16:50 还在第 8 节内 → 正常画线；17:11 下课 → 消失
        assertNotNull(nowMarker(slots, l, LocalTimeLike(16, 50), lastEnd))
        assertNull(nowMarker(slots, l, LocalTimeLike(17, 11), lastEnd))
    }

    /** 今天没课（dayEndMinutes = null）→ 整天不画线 */
    @Test
    fun nowMarkerHidesWholeDayWhenNoCoursesToday() {
        val l = layout()
        assertNull(nowMarker(slots, l, LocalTimeLike(9, 0), null))
        assertNull(nowMarker(slots, l, LocalTimeLike(15, 0), null))
    }
}
