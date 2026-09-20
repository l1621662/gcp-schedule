package edu.jxslu.schedule.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * M3 色彩方案里没有的语义色。
 *
 * 为什么单独一组而不是塞进 `colorScheme`：`ColorScheme` 是 M3 的完整契约，硬塞需要
 * 伪造副本；而这些是「语气」而非「组件角色」，只有提示卡在用。
 * 走 [staticCompositionLocalOf]（值随主题切换整体替换，不做细粒度订阅）。
 */
data class SemanticColors(
    val success: Color,
    val warning: Color,
)

// 水电意象下的语气色：绿偏青（成功）与琥珀（警告），与课程色板的中低饱和基调一致，
// 不做刺眼的纯绿/纯黄——提示卡是信息，不是警报灯
private val LightSemantic = SemanticColors(
    success = Color(0xFF1F7A4D),
    warning = Color(0xFF9A6400),
)

// 深色主题下提亮：同样的色相在暗底上直接沿用会糊成一团
private val DarkSemantic = SemanticColors(
    success = Color(0xFF6DD49B),
    warning = Color(0xFFE0B45C),
)

/** 主题内部持有；页面请用 [MaterialTheme.semanticColors]。 */
internal val LocalSemanticColors: ProvidableCompositionLocal<SemanticColors> =
    staticCompositionLocalOf { LightSemantic }

/** 供 [JuwTheme] 在解析出深浅色后统一提供。 */
@Composable
internal fun ProvideSemanticColors(darkTheme: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalSemanticColors provides if (darkTheme) DarkSemantic else LightSemantic,
        content = content,
    )
}

/** 语气色入口，与 `MaterialTheme.colorScheme` 同形的读法。 */
val MaterialTheme.semanticColors: SemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalSemanticColors.current
