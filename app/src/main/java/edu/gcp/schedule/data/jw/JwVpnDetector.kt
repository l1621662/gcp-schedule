package edu.gcp.schedule.data.jw

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * 当前是否经由 VPN/代理出网。
 *
 * 根因（2026-09-18 真机复现）：学校对**直连出口与代理出口区别对待**——
 * 移动端开着第三方 VPN/代理时，统一认证 `eapp2:9443` 超时、SSO 落点返回 500；
 * 关闭后立刻恢复。`DESIGN.md` §7.6 记过脚本侧同一现象（代理导致
 * `LoginToXk` 404），当时的结论「App 端不受影响」已被真机推翻，故 App 侧显式探测，
 * 命中时在错误提示里点名，让用户不必自己猜。
 *
 * 判定 `TRANSPORT_VPN`：只表示「有 VPN 类型的网络在活动」，**不判断校方 VPN 是否合法**
 * ——本提示仅在失败路径出现，不阻断正常使用。
 */
object JwVpnDetector {

    fun isVpnActive(context: Context): Boolean = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    } catch (_: Exception) {
        false
    }
}
