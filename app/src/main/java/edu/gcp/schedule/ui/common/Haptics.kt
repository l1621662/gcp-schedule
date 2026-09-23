package edu.gcp.schedule.ui.common

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.gcp.schedule.Graph

/**
 * 触感反馈封装。
 *
 * 根因：Compose 的 LocalHapticFeedback 只暴露 LongPress/TextHandleMove 两种语义，
 * 盖不住「轻点按钮」这类普通点击；而设置里的触感开关是全局偏好（DisplayPrefs.hapticsEnabled），
 * 分散在各个调用点各自查开关会漏。所以统一在这里收口：
 * 组件只管调 [tap]/[toggle]，开关状态由 rememberAppHaptics 每次 recompose 时从仓库读，
 * 关闭时直接短路，不产生振动。
 */
class AppHaptics(private val view: View, private val enabled: () -> Boolean) {

    /** 普通点击（设置入口、图标按钮、课程块）。 */
    fun tap() {
        if (!enabled()) return
        // 根因：HyperOS/MIUI 把 VIRTUAL_KEY 挂在系统「触摸时振动」设置下，
        // 该设置默认关（Redmi K70 实测 VIRTUAL_KEY 无振动、CONTEXT_CLICK 有），
        // 所以两种语义统一走 CONTEXT_CLICK，不再依赖那个系统开关。
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** 状态切换（开关、选项选中），比 tap 略清脆。 */
    fun toggle() {
        if (!enabled()) return
        // CONTEXT_CLICK：语义即「上下文点按」，API 23+，MIUI/HyperOS 上有明显但不重的反馈
        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    /** 极轻的刻度感（滚轮停稳到新档位）。CLOCK_TICK 语义就是钟表走格，比 tap 弱一档。 */
    fun tick() {
        if (!enabled()) return
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }
}

/**
 * 组装 [AppHaptics]：开关状态来自全局显示偏好（默认开）。
 * prefs 初始为 null（流尚未发射）时按「开」处理——宁可多振一下，不要开关失效。
 */
@Composable
fun rememberAppHaptics(): AppHaptics {
    val view = LocalView.current
    val context = LocalContext.current
    val prefs by remember { Graph.repository(context).displayPrefs }
        .collectAsStateWithLifecycle(initialValue = null)
    // remember 只缓存 AppHaptics 实例；enabled lambda 闭包读的是委托属性，
    // 每次调用都取最新开关值，不需要因 prefs 变化而重建
    return remember(view) { AppHaptics(view) { prefs?.hapticsEnabled != false } }
}
