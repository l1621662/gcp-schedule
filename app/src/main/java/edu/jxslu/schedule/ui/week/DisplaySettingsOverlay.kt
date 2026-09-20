package edu.jxslu.schedule.ui.week

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import edu.jxslu.schedule.ui.me.DisplaySettingsContent
import edu.jxslu.schedule.ui.me.MeViewModel
import kotlin.math.abs
import kotlin.math.roundToInt

/** 面板高度档位（占屏高比例），升序。拖动松手后吸附到最近一档。 */
internal val PanelHeightFractions = listOf(0.25f, 0.40f, 0.60f)

/** 面板高度下限：低于它内容区滚动塌陷，再怎么拖也不小于它。 */
internal val PanelMinHeight = 180.dp

/** 面板高度上限（占屏高比例的最大档）。 */
internal const val PanelMaxFraction = 0.60f

/** 拖到最小档之后继续向下拉的关闭余量：超过它判定「拖到底关闭」。 */
internal val PanelCollapseOverdrag = 64.dp

/** 默认档：0.40（兼顾「看到身后课表」与内容可视面积）。 */
internal const val PanelDefaultFraction = 0.40f

/**
 * 吸附到最近档位（纯函数，JVM 可测）：返回锚点列表中与 [heightDp] 差值最小的一档。
 */
internal fun nearestAnchor(heightDp: Dp, anchors: List<Dp>): Dp =
    anchors.minByOrNull { abs(it.value - heightDp.value) } ?: heightDp

/**
 * 显示设置覆盖面板（替代 ModalBottomSheet，见调用点注释里的根因）。
 * 唯一的显示设置形态与唯一入口：课表页顶栏眼睛图标（2026-09-20 起，
 * 「我的」侧跨 Tab 入口已删，DESIGN §3.1）都落到这里，
 * 真实课表在面板上方保持可见，改动即时生效。
 *
 * **把手交互**：顶部把手条上下拖动**连续调节面板高度**（向上拖变高、向下拖变矮），
 * 松手吸附到最近档位（屏高 25% / 40% / 60%，默认 40%）；拖到最小档后继续下拉超过
 * [PanelCollapseOverdrag] 关闭弹层。
 *
 * 实现约束（「拖动抽搐/无法拖拽」的教训）：
 * - 高度用 [mutableFloatStateOf]（px）承载，拖动回调里**同步**赋值——
 *   不走 Animatable.snapTo + 每帧 launch 的异步路径（协程排队让高度滞后于手指、
 *   与松手吸附动画竞争，视觉上就是「不跟手 + 抽搐」）；
 * - 高度应用挂在 [Modifier.layout] 的 measure 阶段（读 state 只触发重测、
 *   不触发重组），AnimatedVisibility 的进入动画与高度变化互不干扰；
 * - 拖动手势用官方 [draggable]（与 Slider 同源的分发模型），只挂把手命中区。
 */
@Composable
internal fun DisplaySettingsOverlay(
    viewModel: MeViewModel,
    onDismiss: () -> Unit,
    /** 表头高度滑块的动态下限（随日期字号，见 minHeaderHeightForDateFont），透传给设置内容 */
    headerMinDp: Float,
) {
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    val density = LocalDensity.current

    // 档位换算成 px，并夹进 [PanelMinHeight, 上限档]：小屏上 25% 可能不足 180dp
    val maxPanelHeightPx = with(density) { (screenHeightDp * PanelMaxFraction).dp.toPx() }
    val minPanelHeightPx = with(density) { PanelMinHeight.toPx() }
    val anchorPx = remember(screenHeightDp) {
        PanelHeightFractions.map { fraction ->
            with(density) { (screenHeightDp * fraction).dp.toPx() }.coerceIn(minPanelHeightPx, maxPanelHeightPx)
        }
    }
    val startHeightPx = remember(screenHeightDp) {
        with(density) { (screenHeightDp * PanelDefaultFraction).dp.toPx() }
            .coerceIn(minPanelHeightPx, maxPanelHeightPx)
    }
    val collapseOverdragPx = with(density) { PanelCollapseOverdrag.toPx() }

    BackHandler { onDismiss() }

    // 面板高度（px）：拖动回调同步赋值，measure 阶段读取
    var panelHeightPx by remember(screenHeightDp) { mutableFloatStateOf(startHeightPx) }

    Box(Modifier.fillMaxSize()) {
        // 遮罩：点它就关。用无波纹 clickable，避免整屏按下时出现大面积涟漪
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f))
                .clickable(
                    onClickLabel = "关闭显示设置",
                    role = Role.Button,
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onDismiss,
                ),
        )

        // 进入动画：visibleState 初值 false、挂载后目标 true，滑入只播一遍；
        // 退出直接走 if 移除，不播动画
        val enterState = remember { MutableTransitionState(false).apply { targetState = true } }

        AnimatedVisibility(
            visibleState = enterState,
            enter = slideInVertically(
                // 从面板自身高度的 40% 滑入：能看出「从底部来」，又不拖沓
                initialOffsetY = { it * 2 / 5 },
                animationSpec = tween(220),
            ) + fadeIn(tween(180)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    // 高度在 measure 阶段从 state 读取：拖动每帧只重测、不重组
                    .panelHeight(panelHeightPx),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(Modifier.fillMaxSize()) {
                    // 把手独立居中在面板顶部：上下拖动调高度，拖到底关闭
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CollapseHandle(
                            onDelta = { dragAmount ->
                                // 向上拖（dragAmount < 0）增高；被钳到最小档后的
                                // 继续下拉计入关闭判定
                                val desired = panelHeightPx - dragAmount
                                panelHeightPx = if (desired >= minPanelHeightPx) {
                                    desired
                                } else {
                                    minPanelHeightPx
                                }
                                if (desired < minPanelHeightPx) {
                                    val overdrag = minPanelHeightPx - desired
                                    if (overdrag >= collapseOverdragPx) {
                                        onDismiss()
                                    }
                                }
                            },
                            onDragStop = {
                                // 吸附最近档位：px 域比较直接取最近锚
                                panelHeightPx = anchorPx.minByOrNull { abs(it - panelHeightPx) }
                                    ?: panelHeightPx
                            },
                        )
                    }
                    // 标题行不挂任何垂直手势
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 20.dp, end = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "显示设置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = onDismiss) { Text("完成") }
                    }
                    DisplaySettingsContent(
                        viewModel = viewModel,
                        headerMinDp = headerMinDp,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 在 measure 阶段把面板高度钉在 [heightPx]：只约束自身高度，宽度沿用父约束。
 * 读 state 发生在 measure 阶段，拖动每帧只触发重测，不进入组合/重组管线。
 */
private fun Modifier.panelHeight(heightPx: Float): Modifier = layout { measurable, constraints ->
    val h = heightPx.roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(
        Constraints(minWidth = constraints.maxWidth, maxWidth = constraints.maxWidth, minHeight = h, maxHeight = h),
    )
    layout(placeable.width, h) { placeable.place(0, 0) }
}

/**
 * 拖拽调高把手：视觉 40×4dp 圆角条，命中区经 padding 扩到约 56×28dp。
 *
 * 用官方 [draggable]（Vertical）驱动：onDelta 在手势协程内**每帧同步**回调，
 * 直接更新高度 state，零协程延迟（此前 snapTo+launch 每帧排队的异步路径
 * 造成高度滞后与抽搐，见类注释）。松手回调里吸附最近档位。
 */
@Composable
private fun CollapseHandle(
    onDelta: (Float) -> Unit,
    onDragStop: () -> Unit,
) {
    val dragState = rememberDraggableState { delta -> onDelta(delta) }
    Box(
        Modifier
            .width(56.dp)
            .height(28.dp)
            .draggable(
                orientation = Orientation.Vertical,
                state = dragState,
                onDragStarted = { /* 无需记录起点：onDelta 是增量语义 */ },
                onDragStopped = { onDragStop() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}
