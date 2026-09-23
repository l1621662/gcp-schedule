package edu.gcp.schedule.domain

import kotlin.math.roundToInt

/**
 * 课表网格字号——以「课名的目标字号（dp）」为用户可调单位，范围 [MinDp, MaxDp] dp。
 *
 * 根因：网格行高是按可用高度算死的（11 行约 45–50dp），格子里的文字却是 sp，
 * 会跟随系统字体缩放。系统字体调到 1.4× 以上时课名 3 行会顶出色块、左侧时间轴三行互相挤压。
 * 所以网格内单独定字号，并夹在 [MinDp, MaxDp] 之间。
 *
 * 为什么单位是 dp 而不是倍率：用户关心的是「课名几个 dp」，不是「系统字号的 110%」；
 * 用绝对字号后，系统字体设置怎么变，拖出来的值都不会漂移。
 * 内部实现仍然是 CompositionLocal 的 fontScale 缩放（见 `WeekScreen.GridTypography`）：
 * 倍率 = 目标课名字号 / 当前列数模式下的课名基准字号（[baseNameSp]），
 * 网格内其余文字（时间轴、地点、教师）按同一倍率等比缩放，保证层级关系不变。
 *
 * 取值规则：**用户调过就用用户的值，没调过就跟随系统**（课名基准 × 系统倍率，再夹取）。
 * 只收住网格；页面其余部分（顶栏、弹层、今日页）仍跟随系统设置。
 */
object GridFont {

    /** 下限：再小中文笔画会糊。 */
    const val MinDp = 8f

    /**
     * 上限 14dp。根因：课名基准只有 11–12.5dp，7 列模式下单列内容宽约 40dp，
     * 12dp 起课名就开始明显换行截断（真机确认 12dp 以上观感已不可接受）；
     * 再大的值只会把网格挤爆，属于失控区，不开放。
     */
    const val MaxDp = 14f

    /**
     * 教室/教师文字（详情行）的区间：7–14dp。
     * 下限 7：详情行是白字 90% 透明度压在彩底上，比课名（实白加粗）更不耐小，7dp 是笔画可辨边界；
     * 上限与课名一致（14）：详情比课名大本身允许（用户自由），但超过 14 在 96px 格里同样失控。
     */
    const val MinDetailDp = 7f
    const val MaxDetailDp = 14f

    /**
     * 时间轴 / 日期表头文字的区间：7–14dp。
     *
     * 根因：这两处此前共用课名的 `gridScale`，课名滑块一动它们就跟着变——
     * 但关注点不同：课名是内容主体要尽量大，时间轴与日期只是定位参照，过大会挤压网格边缘。
     * 区间沿用详情行口径（7dp 是可辨下限，14dp 已与课名齐平、再大即失控）。
     */
    const val MinRailDp = 7f
    const val MaxRailDp = 14f

    /** 滑块吸附到 0.25dp：课名基准只有 11–12.5dp，1dp 已是约 9% 的视觉跳变，0.5dp 的步进太粗。 */
    private const val SnapStep = 0.25f

    /** 当前列数模式下的课名基准字号（sp）：与 [edu.gcp.schedule.ui.common.GridCourseCard] 的默认档一致。 */
    fun baseNameSp(days: Int): Float = if (days > 5) 11f else 12.5f

    /** 当前列数模式下的详情行（教室/教师）基准字号（sp）：与卡片 meta 档一致。 */
    fun baseDetailSp(days: Int): Float = if (days > 5) 9f else 10.5f

    /**
     * 时间轴基准字号（sp）：7 列时左侧栏宽 48dp，节次号 + 起止时间三行要挤在一格里，
     * 基准压到 12.5sp（节次号）；5 列时列宽宽松，放宽到 13.5sp。
     * 这两个值与 [WeekScreen.TimeRail] 的历史硬编码一致，改这里要同步改那边。
     */
    fun baseRailSp(days: Int): Float = if (days > 5) 12.5f else 13.5f

    /** 日期表头基准字号（sp）：7 列窄列取 12sp，5 列取 13sp。 */
    fun baseDateSp(days: Int): Float = if (days > 5) 12f else 13f

    /**
     * 有效课名字号（dp）：用户没调过（null）就跟随系统（基准 × 系统倍率），调过就听用户的；
     * 两者都夹进 [MinDp, MaxDp]。
     */
    fun resolveDp(systemFontScale: Float, userDp: Float?, days: Int): Float =
        resolveDp(systemFontScale, userDp, baseNameSp(days), MinDp, MaxDp)

    /**
     * 有效详情行字号（dp）：规则与课名一致，基准/区间换成详情行自己的
     * （[baseDetailSp] / [MinDetailDp, MaxDetailDp]）。
     */
    fun resolveDetailDp(systemFontScale: Float, userDp: Float?, days: Int): Float =
        resolveDp(systemFontScale, userDp, baseDetailSp(days), MinDetailDp, MaxDetailDp)

    /** 有效时间轴字号（dp）：独立于课名，规则同上。 */
    fun resolveRailDp(systemFontScale: Float, userDp: Float?, days: Int): Float =
        resolveDp(systemFontScale, userDp, baseRailSp(days), MinRailDp, MaxRailDp)

    /** 有效日期表头字号（dp）：独立于课名，规则同上。 */
    fun resolveDateDp(systemFontScale: Float, userDp: Float?, days: Int): Float =
        resolveDp(systemFontScale, userDp, baseDateSp(days), MinRailDp, MaxRailDp)

    private fun resolveDp(
        systemFontScale: Float,
        userDp: Float?,
        baseSp: Float,
        minDp: Float,
        maxDp: Float,
    ): Float = (userDp ?: baseSp * systemFontScale).coerceIn(minDp, maxDp)

    /** 课名字号（dp）→ 网格字体倍率，供 `GridTypography` 注入 fontScale 用。 */
    fun scaleFromDp(dp: Float, days: Int): Float =
        dp.coerceIn(MinDp, MaxDp) / baseNameSp(days)

    /** 滑块取值吸附到 [SnapStep]。 */
    fun snapDp(value: Float): Float = (value / SnapStep).roundToInt() * SnapStep

    /** 字号 → 展示用文案，如 `12f` → `12dp`、`12.5f` → `12.5dp`。 */
    fun dpLabel(dp: Float): String {
        val v = snapDp(dp)
        return if (v == v.toInt().toFloat()) "${v.toInt()}dp" else "${v}dp"
    }
}
