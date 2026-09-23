package edu.gcp.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import edu.gcp.schedule.ui.theme.semanticColors
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.InformationCircle

/**
 * 提示语义。决定图标与强调色，不改变文案来源——[Info] 是「未说明语气」的兜底，
 * 所以沿用 M3 原生 `showSnackbar("文本")` 的调用点无需改动也能拿到统一观感。
 */
enum class NoticeTone { Info, Success, Warning, Error }

/**
 * 一句话 + 语气。给「结果要显示在某个独立窗口内部」的场景当返回值用
 * （[InlineNoticeRow] 的入参），免得调用方各自定义一对 (文案, 颜色)。
 */
data class NoticeFeedback(val text: String, val tone: NoticeTone)

/**
 * 带语义的 Snackbar 数据（[SnackbarVisuals] 的唯一实现入口）。
 *
 * 为什么要有这一层：M3 原生 Snackbar 用 `inverseSurface` 当底——浅色主题下就是一块
 * 近黑的浮条，深色主题下反而发白；且它的形态与 App 里其余浮层（14dp 圆角 + 1dp 描边、
 * 无阴影）完全两套语言。这里把「长什么样」收进一个自绘卡片（见 [AppNoticeCard]），
 * 调用方只说「这条消息是什么语气」。
 *
 * 既有调用点（`snackbar.showSnackbar("文本", actionLabel = …, duration = …)`）走 M3 的
 * String 重载，落成 `SnackbarVisualsImpl` 后由 [AppNoticeCard] 兜底成 [NoticeTone.Info]，
 * 不必逐处改写。
 */
class AppNoticeVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration =
        if (actionLabel == null) SnackbarDuration.Short else SnackbarDuration.Long,
    val tone: NoticeTone = NoticeTone.Info,
) : SnackbarVisuals

/**
 * 全 App 统一的提示宿主：替代各页 Scaffold 里的 `SnackbarHost(snackbar)`。
 *
 * 只换「卡片长什么样」，不接管排队与超时——这两件事在 M3 的 [SnackbarHost] 里：
 * 它按 `visuals.duration`（配合动作有无、无障碍管理器把 Short/Long 折算成毫秒）
 * 延时后调 `data.dismiss()`，本函数把 `snackbar` 槽换成 [AppNoticeCard] 即可继承，
 * 顺带保留其进出场淡入淡出与缩放。自己起计时器会与它双份 dismiss。
 *
 * 位置由 Scaffold 的 `snackbarHost` 槽决定 → 天然落在底部导航栏上方，
 * 不需要手算底栏高度（这也是不自己摆 Box 的原因）。
 */
@Composable
fun AppSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        // 居中：窄屏卡片即满宽，宽屏被下面那张卡的上限收窄后仍然居中。
        // 顺序要紧——`fillMaxWidth` 之后再加 `widthIn` 改不动已经定死的宽度，
        // 必须让上限先夹住约束（见 [AppNoticeCard]）
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            AppNoticeCard(data)
        }
    }
}

/**
 * 提示卡片：圆角 surface 底 + 1dp 描边 + 语气图标（颜色只在图标上，文案恒用 onSurface
 * 保证对比度）。不用 `inverseSurface`，深浅色主题都跟随；不用阴影，与设置卡同语言。
 */
@Composable
private fun AppNoticeCard(data: SnackbarData) {
    val visuals = data.visuals
    val tone = (visuals as? AppNoticeVisuals)?.tone ?: NoticeTone.Info
    val scheme = MaterialTheme.colorScheme
    val accent = tone.accentColor()
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            // 先夹上限再撑满：反过来的话 fillMaxWidth 已经把宽度定成父约束上限，
            // widthIn 再想收窄也无力（约束已定），大屏上就会拉成横贯全屏的长条。
            // 560dp 与 M3 默认提示卡的最大宽度同量级
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .border(1.dp, scheme.outlineVariant, shape)
            .padding(start = 14.dp, end = if (visuals.actionLabel == null) 14.dp else 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = tone.icon(),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = visuals.message,
            style = MaterialTheme.typography.bodyMedium,
            color = scheme.onSurface,
            // 长文案（失败原因）不该被截断：给足三行，仍超才省略。
            // 不要 heightIn(min=…)：被撑到比行盒高时 Text 内容贴顶绘制，
            // 文字中心会整体高于图标中心（单行 20sp 被撑到 24dp → 偏上 2dp），
            // CenterVertically 就失准了；行高交给文本自身决定
            maxLines = 3,
            modifier = Modifier.weight(1f),
        )
        visuals.actionLabel?.let { label ->
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { data.performAction() }) { Text(label) }
        }
        // withDismissAction 是 SnackbarVisuals 的契约位：不渲染就等于静默吞掉调用方的诉求
        if (visuals.withDismissAction) {
            IconButton(
                onClick = { data.dismiss() },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    HugeIcons.Cancel01,
                    contentDescription = "关闭",
                    tint = scheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

/** 语气图标：形状先于颜色传达语气，色弱用户也分得清「成功」与「失败」。 */
internal fun NoticeTone.icon(): ImageVector = when (this) {
    NoticeTone.Info -> HugeIcons.InformationCircle
    NoticeTone.Success -> HugeIcons.CheckmarkCircle02
    NoticeTone.Warning -> HugeIcons.Alert02
    NoticeTone.Error -> HugeIcons.Cancel01
}

/**
 * 语气强调色。成功/警告 M3 没有对应角色，走 [semanticColors]（不随动态取色漂移）；
 * 信息借用主色、错误借用 error，两者本就在主题里、深浅色各自适配。
 */
@Composable
internal fun NoticeTone.accentColor(): Color {
    val scheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.semanticColors
    return when (this) {
        NoticeTone.Info -> scheme.primary
        NoticeTone.Success -> semantic.success
        NoticeTone.Warning -> semantic.warning
        NoticeTone.Error -> scheme.error
    }
}

/**
 * 行内提示行（图标 + 一句话）。
 *
 * 给「消息会落在一个独立窗口内」的场景用：M3 的 `ModalBottomSheet` / `AlertDialog`
 * 各自是比页面高一层的新窗口，页面自己的 Snackbar 在里面必然被盖住——
 * 这时正确的做法不是把 Snackbar 抬得更高，而是把结果放进用户视线所在的那个窗口。
 * 观感与 [AppSnackbarHost] 的卡片同一套语气映射（[NoticeTone]）。
 */
@Composable
fun InlineNoticeRow(
    message: String,
    tone: NoticeTone,
    modifier: Modifier = Modifier,
) {
    val accent = tone.accentColor()
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = tone.icon(),
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .padding(top = 1.dp)
                .size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        )
    }
}
