package edu.jxslu.schedule.data.qiekj

import edu.jxslu.schedule.domain.PromotionLine
import kotlinx.serialization.Serializable

/**
 * 胖乖接口统一响应包与线上 DTO（DESIGN §4.10）。
 * 端点、字段名与参考实现 light-life Models.kt 一致；可能返回非字符串形态的字段
 * 统一挂 [LenientStringSerializer]。业务异常模型（TokenExpired 等）也在这里。
 */

@Serializable
data class ApiEnvelope<T>(
    val code: Int? = null,
    val msg: String? = null,
    val message: String? = null,
    val data: T? = null,
) {
    fun requireData(): T {
        if (data != null) return data
        val rawMsg = message ?: msg ?: "接口未返回 data"
        if (TokenExpiredException.isTokenExpired(code, rawMsg)) {
            throw TokenExpiredException(rawMsg)
        }
        error(rawMsg)
    }

    fun throwIfFailed() {
        if (code != null && code != 0 && code != 200) {
            val errorMsg = message ?: msg ?: "请求失败"
            if (TokenExpiredException.isTokenExpired(code, errorMsg)) {
                throw TokenExpiredException(errorMsg)
            }
            error(errorMsg)
        }
    }
}

// ── 登录 ──

@Serializable
data class LoginData(
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val token: String? = null,
)

// ── 资产 ──

@Serializable
data class BalanceData(
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val tokenCoin: String? = null,
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val integral: String? = null,
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val integralAmount: String? = null,
) {
    /** tokenCoin 为「分」，展示转元；解析失败显示 "-"（对齐参考实现）。 */
    val ticketText: String
        get() = tokenCoin?.toDoubleOrNull()?.let { "%.2f".format(it / 100.0) } ?: "-"

    val pointsText: String get() = integral ?: "-"
}

// ── 设备 ──

@Serializable
data class DeviceItem(
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val goodsId: String? = null,
    @kotlinx.serialization.Serializable(LenientStringNonNullSerializer::class) val goodsName: String = "",
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val id: String? = null,
) {
    /** 列表选择/解锁流程统一用 goodsId，旧字段 id 作回退（对齐参考实现）。 */
    val effectiveGoodsId: String? get() = goodsId ?: id
}

@Serializable
data class SkuData(
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val skuId: String? = null,
)

@Serializable
data class ImeiData(
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val imei: String? = null,
)

// ── 开水流程 ──

@Serializable
data class UnlockData(
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val msgId: String? = null,
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val orderNo: String? = null,
)

@Serializable
data class SyncData(
    val workStatus: Int? = null,
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val identify: String? = null,
)

@Serializable
data class AfterPayCreatingData(
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val orderId: String? = null,
)

@Serializable
data class OrderDetailData(
    val tradeOrderItem: List<TradeOrderItem> = emptyList(),
    val promotionList: List<PromotionItem> = emptyList(),
    /** 服务端实付口径（DESIGN §4.10 账单口径）；缺失时回退 tradeOrderItem.realPrice。 */
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val payPrice: String? = null,
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val payTypeName: String? = null,
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val tokenCoinDiscount: String? = null,
)

@Serializable
data class TradeOrderItem(
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val originPrice: String? = null,
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val realPrice: String? = null,
)

@Serializable
data class PromotionItem(
    val promotionType: Int? = null,
    @kotlinx.serialization.Serializable(LenientStringSerializer::class) val discountAmount: String? = null,
) {
    fun toLine() = PromotionLine(promotionType = promotionType, discountAmount = discountAmount)
}

// ── 订单快照（本地存储模型，含领域优惠明细） ──

@Serializable
data class OrderHistoryItem(
    val orderNo: String,
    val orderId: String,
    val goodsName: String,
    val originPrice: String,
    val ticketCost: String,
    val integralCost: String,
    val otherPromotions: List<PromotionLine> = emptyList(),
    val completedAt: Long,
    /** 服务端实付口径（2026-09-20 起）；旧快照无此字段，decode 为 null 后回退本地公式。 */
    val realPrice: String? = null,
    /** 支付方式名（如「支付宝-代扣」），旧快照为 null。 */
    val payTypeName: String? = null,
    /** 平台侧自动优惠金额（用户未主动用券也会出现），旧快照为 null。 */
    val tokenCoinDiscount: String? = null,
)

// ── 异常 ──

class UnlockException(
    message: String,
    val diagnosis: edu.jxslu.schedule.domain.DiagnosisResult,
    cause: Throwable? = null,
) : Exception(message, cause)

class TokenExpiredException(
    message: String = "登录已失效，请重新登录",
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        /**
         * 根因：服务端过期判定不返回统一错误码，靠 401/403 加中文文案关键词识别
         * （对齐参考实现启发式，误判面可控：只影响重新登录引导）。
         */
        fun isTokenExpired(code: Int?, message: String?): Boolean {
            if (code == 401 || code == 403) return true
            val msg = message?.lowercase() ?: return false
            if (msg.contains("未登录") || msg.contains("未登陆")) return true
            if (msg.contains("请先登录") || msg.contains("请先登陆")) return true
            if (msg.contains("token") && (msg.contains("过期") || msg.contains("无效")
                        || msg.contains("expired") || msg.contains("invalid"))
            ) return true
            return false
        }
    }
}
