package edu.gcp.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 字号解耦契约（回归钉子）。
 *
 * 需求背景：调「课程名字号」滑块时，顶部表头与左侧时间轴的文字不得跟着变；
 * 表头/时间轴默认跟随系统字体设置（未拖过自己的滑块时 = 基准 sp × 系统倍率），
 * 拖过则以用户值为准，同样与课名无关。
 *
 * 根因：时间轴/表头曾与课名共用同一个 gridScale（由 gridFontDp 换算），
 * 课名一动它们一起缩放。修复后它们是独立设置项（gridRailDp / gridDateDp），
 * 这组用例钉死 resolveRailDp / resolveDateDp 输出**只由自身设置与系统倍率决定**。
 */
class GridFontDecouplingTest {

    /** 时间轴解析值不受课名设置影响的断言骨架。 */
    private fun assertRailIndependentOfName(userRailDp: Float?) {
        for (nameDp in listOf<Float?>(null, GridFont.MinDp, 11f, GridFont.MaxDp)) {
            // 模拟课名被拖到不同值：resolveRailDp 的入参里根本没有课名，天然隔离；
            // 这里用「系统倍率固定、课名变化、时间轴结果不变」表达契约。
            val rail = GridFont.resolveRailDp(1f, userRailDp, days = 7)
            val railOther = GridFont.resolveRailDp(1f, userRailDp, days = 7)
            assertEquals(rail, railOther, 1e-6f)
        }
    }

    /** 未拖时间轴滑块：跟随系统（基准 12.5sp × 系统倍率），与课名无关。 */
    @Test
    fun railFollowsSystemWhenUnset() {
        assertRailIndependentOfName(null)
        assertEquals(12.5f * 1f, GridFont.resolveRailDp(1f, null, 7), 1e-4f)
        // 1.1× 时 13.75 < MaxRailDp(14)，未被夹取；再大就会被上限收口（见下方夹取断言）
        assertEquals(12.5f * 1.1f, GridFont.resolveRailDp(1.1f, null, 7), 1e-4f)
        assertEquals(GridFont.MaxRailDp, GridFont.resolveRailDp(1.3f, null, 7), 1e-4f)
        // 7 列与 5 列基准不同
        assertEquals(13.5f, GridFont.resolveRailDp(1f, null, 5), 1e-4f)
    }

    /** 拖过时间轴滑块：用用户值，系统倍率怎么变都不漂移。 */
    @Test
    fun railUsesUserValueOnceSet() {
        assertEquals(10f, GridFont.resolveRailDp(1f, 10f, 7), 1e-4f)
        assertEquals(10f, GridFont.resolveRailDp(1.3f, 10f, 7), 1e-4f)
        assertEquals(10f, GridFont.resolveRailDp(2f, 10f, 5), 1e-4f)
    }

    /** 日期表头同理：未设置跟随系统（7 列基准 12sp），独立于课名。 */
    @Test
    fun dateFollowsSystemWhenUnset() {
        assertEquals(12f, GridFont.resolveDateDp(1f, null, 7), 1e-4f)
        // 1.1× 时 13.2 < MaxRailDp(14)，未被夹取
        assertEquals(12f * 1.1f, GridFont.resolveDateDp(1.1f, null, 7), 1e-4f)
        assertEquals(13f, GridFont.resolveDateDp(1f, null, 5), 1e-4f)
        assertEquals(11f, GridFont.resolveDateDp(1f, 11f, 7), 1e-4f)
    }

    /** 课名解析本身的行为钉子：null 跟随系统（11sp 基准 × 倍率，夹进 8–14）。 */
    @Test
    fun nameResolutionBaseline() {
        assertEquals(11f, GridFont.resolveDp(1f, null, 7), 1e-4f)
        assertEquals(11f * 1.1f, GridFont.resolveDp(1.1f, null, 7), 1e-4f)
        assertEquals(12.5f, GridFont.resolveDp(1f, null, 5), 1e-4f)
        // 夹取：系统倍率再大，未设置态也被夹在区间内
        assertEquals(GridFont.MaxDp, GridFont.resolveDp(3f, null, 7), 1e-4f)
        assertEquals(GridFont.MinDp, GridFont.resolveDp(0.5f, null, 7), 1e-4f)
    }

    /** 倍率换算：scaleFromDp = 目标课名 / 基准，供 GridTypography 注入 fontScale。 */
    @Test
    fun scaleFromDpMatchesBase() {
        assertEquals(1f, GridFont.scaleFromDp(11f, 7), 1e-4f)
        assertEquals(1f, GridFont.scaleFromDp(12.5f, 5), 1e-4f)
        assertEquals(14f / 11f, GridFont.scaleFromDp(14f, 7), 1e-4f)
    }
}
