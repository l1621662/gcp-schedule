package edu.gcp.schedule.ui.common

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Droplet

/**
 * 统一加载态（DESIGN §3.2「加载态」）：水滴呼吸 + 文案，水平垂直双居中。
 *
 * 替代两种旧形态——裸 `CircularProgressIndicator`（无品牌感、与页面观感脱节）
 * 和「文字版 EmptyHint 当加载态用」（不居中、无加载感）。呼吸动画替代旋转：
 * 加载动画的水滴意象缓缩放 + 微透明度起伏，动效克制（无旋转、无跳变），
 * 仍明确传达「正在工作」。
 *
 * 居中口径：内容在 [modifier] 决定的容器内双居中。全屏态传
 * `Modifier.fillMaxSize()`；区块态传 `fillMaxWidth() + padding`。
 * 注意外层容器若在 Column 里用 `weight(1f)` 占位，必须同时 `fillMaxWidth()`
 * （Box 默认 wrap 内容、crossAxis 左对齐——今日页加载态左偏的根因）。
 */
@Composable
fun LoadingHint(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val transition = rememberInfiniteTransition(label = "loadingBreath")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingScale",
    )
    val alpha by transition.animateFloat(
        initialValue = 0.72f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "loadingAlpha",
    )

    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.material3.Icon(
                imageVector = HugeIcons.Droplet,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .size(34.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    },
            )
            Spacer(Modifier.height(14.dp))
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 空态提示（原住 `CourseUi.kt`，2026-09-20 移入本文件：空态与加载态同属
 * 「页面暂态提示」，两组件共用调用方）。宽满容器、水平居中，**垂直不居中**——
 * 垂直定位由调用方的容器决定（今日页 `TodayCenteredShell` 负责双居中，
 * 成绩页包 `Arrangement.Center` 的 Column）。
 */
@Composable
fun EmptyHint(
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (actionLabel != null && onAction != null) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
