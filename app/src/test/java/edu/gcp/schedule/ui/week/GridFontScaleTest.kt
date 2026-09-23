package edu.gcp.schedule.ui.week

import edu.gcp.schedule.domain.GridFont
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 课表字号的收敛行为。
 *
 * 背景：网格行高是按可用高度算死的（11 行约 45–50dp），格子里的文字却是 sp。
 * 系统字体放大到 1.5× 时课名 3 行顶出色块、左侧时间轴三行互相挤压，
 * 所以网格内单独定字号：用户可调的是**课名目标字号（dp）**，夹进
 * [GridFont.MinDp, GridFont.MaxDp]（8–32dp），内部再换算成网格统一的字体倍率。
 *
 * 这组断言要保住三件事：dp 区间不被改宽；用户设置是绝对字号、
 * 不随列数模式漂移；dp → 倍率换算与列数模式下的课名基准自洽。
 */
class GridFontScaleTest {

    private val min = GridFont.MinDp
    private val max = GridFont.MaxDp

    /** 没拖过滑块（null）→ 跟随系统 = 课名基准 × 系统倍率，只受区间约束 */
    @Test
    fun nullUserDpFollowsSystem() {
        // 7 天模式课名基准 11sp：系统 1.0 → 11dp
        assertEquals(11f, GridFont.resolveDp(1f, null, 7), 0.0001f)
        // 5 天模式课名基准 12.5sp：系统 1.0 → 12.5dp
        assertEquals(12.5f, GridFont.resolveDp(1f, null, 5), 0.0001f)
        assertEquals(11f * 0.9f, GridFont.resolveDp(0.9f, null, 7), 0.0001f)
        // 11sp × 3.2 = 35.2dp，超出上限才触发夹取；1.5× 只到 16.5dp，不会夹
        assertEquals(max, GridFont.resolveDp(3.2f, null, 7), 0.0001f)
        assertEquals(max, GridFont.resolveDp(3f, null, 5), 0.0001f)
        assertEquals(min, GridFont.resolveDp(0.5f, null, 7), 0.0001f)
    }

    /** 用户显式设置后就是权威值（dp），系统倍率与列数模式都不再影响它 */
    @Test
    fun userDpOverridesSystem() {
        assertEquals(14f, GridFont.resolveDp(1.5f, 14f, 7), 0.0001f)
        assertEquals(14f, GridFont.resolveDp(0.8f, 14f, 5), 0.0001f)
        assertEquals(10f, GridFont.resolveDp(1.3f, 10f, 7), 0.0001f)
    }

    /** 越界的用户值也要夹回区间（防止手改存储后把网格撑爆） */
    @Test
    fun userDpIsClamped() {
        assertEquals(min, GridFont.resolveDp(1f, 4f, 7), 0.0001f)
        assertEquals(max, GridFont.resolveDp(1f, 40f, 7), 0.0001f)
    }

    /** dp → 倍率：倍率 × 该模式的课名基准必须还原出目标 dp（这是「字号以课名为准」的契约） */
    @Test
    fun scaleFromDpRestoresNameSize() {
        for (days in intArrayOf(5, 7)) {
            val base = GridFont.baseNameSp(days)
            assertEquals(12f / base, GridFont.scaleFromDp(12f, days), 0.0001f)
            assertEquals(min / base, GridFont.scaleFromDp(min, days), 0.0001f)
            assertEquals(max / base, GridFont.scaleFromDp(max, days), 0.0001f)
        }
    }

    /** 滑块取值吸附到 0.25dp（基准 11dp 下 1dp 已是约 9% 跳变，步进必须细）。只吸附不夹取：滑块 valueRange 已保证区间 */
    @Test
    fun snapRoundsToQuarterDp() {
        assertEquals(8.25f, GridFont.snapDp(8.24f), 0.0001f)
        assertEquals(8.5f, GridFont.snapDp(8.45f), 0.0001f)
        assertEquals(31.5f, GridFont.snapDp(31.6f), 0.0001f)
    }

    @Test
    fun dpLabel() {
        assertEquals("12dp", GridFont.dpLabel(12f))
        assertEquals("12.5dp", GridFont.dpLabel(12.49f))
        assertEquals("8dp", GridFont.dpLabel(8f))
    }

    /** 详情行（教室/教师）字号：null 跟随系统（基准 × 系统倍率），基准与课名不同档 */
    @Test
    fun detailNullUserDpFollowsSystem() {
        // 7 天详情基准 9sp：系统 1.0 → 9dp
        assertEquals(9f, GridFont.resolveDetailDp(1f, null, 7), 0.0001f)
        // 5 天详情基准 10.5sp
        assertEquals(10.5f, GridFont.resolveDetailDp(1f, null, 5), 0.0001f)
        assertEquals(9f * 1.4f, GridFont.resolveDetailDp(1.4f, null, 7), 0.0001f)
    }

    /** 详情行用户显式值是权威值，且夹在自己的 7–14dp 区间（与课名 8–14 的下限不同） */
    @Test
    fun detailUserDpOverridesAndClamps() {
        assertEquals(11f, GridFont.resolveDetailDp(1.5f, 11f, 7), 0.0001f)
        assertEquals(GridFont.MinDetailDp, GridFont.resolveDetailDp(1f, 5f, 7), 0.0001f)
        assertEquals(GridFont.MaxDetailDp, GridFont.resolveDetailDp(1f, 20f, 5), 0.0001f)
    }
}
