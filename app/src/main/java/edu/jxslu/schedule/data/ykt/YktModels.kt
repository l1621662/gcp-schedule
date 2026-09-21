package edu.jxslu.schedule.data.ykt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 一卡通（慧新e校）响应模型（DESIGN §4.19）。字段对齐 2026-09-20 Python 实测抓包，
 * 只建模用到的部分；消息字段 `msg`/`message`/`error_description` 三处都可能出，统一收口。
 */
@Serializable
data class YktEnvelope(
    val code: Int = 0,
    val msg: String? = null,
    val message: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
    val data: kotlinx.serialization.json.JsonElement? = null,
) {
    /** 业务失败的用户可读消息（三种字段名取第一个非空）。 */
    val messageOrBlank: String
        get() = (msg ?: message ?: errorDescription).orEmpty().trim()
}

/** 登录成功响应（token 仅内存持有，永不落盘，DESIGN §4.19 红线）。 */
data class YktToken(val accessToken: String, val expiresIn: Long)

/** `/berserker-secure/keyboard` 的 data：uuid + 乱序字符集 + 10 张字形图（base64 PNG）。 */
data class YktKeyboardData(
    val uuid: String,
    val numberKeyboard: String,
    val numberKeyboardImage: List<ByteArray>,
    /** 服务端自带的样板 password（= numberKeyboard + "$1$" + uuid），协议自检用。 */
    val samplePassword: String?,
)

/** `/berserker-app/ykt/tsm/codebarPayinfo` 里的一行账户。 */
@Serializable
data class YktPayAccount(
    val code: String = "",
    val name: String? = null,
    val account: String = "",
    val payacc: String = "",
    val paytype: String = "",
    @SerialName("db_balance") val dbBalance: Double = 0.0,
    @SerialName("unsettle_amount") val unsettleAmount: Double = 0.0,
    @SerialName("accinfo_balance") val accinfoBalance: Double = 0.0,
    val expdate: String? = null,
)

/** `/berserker-app/ykt/tsm/batchGetBarCodeGet` 的 data。 */
@Serializable
data class YktBarcodeData(
    val retcode: String? = null,
    val errmsg: String? = null,
    val account: String = "",
    val expires: Long = 0,
    val barcode: List<String> = emptyList(),
)

/** 单张卡余额（`queryCard` 的 `data.card[]` 元素，只建模展示所需子集）。 */
data class YktCard(
    val cardName: String,
    val account: String,
    /** 卡账户余额（分）= db_balance + unsettle_amount。 */
    val cardBalanceFen: Long,
    /** 水电/电费账户余额（分）。 */
    val elecBalanceFen: Long,
    val expdate: String?,
    val lostflag: String?,
)

/** 充值下单结果（DESIGN §4.19「充值」）。 */
sealed interface YktRechargeOrder {

    /** 服务端订单号（收银台兜底跳转与日志排查用；不进任何日志）。 */
    val orderId: String

    /**
     * 直拉微信：`weixin://wap/pay?prepay_id=…`（微信 H5 支付，实测免密渠道）。
     * `ACTION_VIEW` 即可拉起微信支付界面；prepay_id 约 5 分钟有效。
     */
    data class WechatPay(
        override val orderId: String,
        val wechatUrl: String,
    ) : YktRechargeOrder

    /** 兜底：官方收银台 URL（服务端 302 Location 下发；直拉链路失败时浏览器打开）。 */
    data class Cashier(
        override val orderId: String,
        val cashierUrl: String,
    ) : YktRechargeOrder
}

/** 单条消费流水（`personal/turnover` 的 records 元素，只建模展示所需子集）。 */
data class YktTurnover(
    /** 交易时间原文（`2026-09-20 18:50:xx`）。 */
    val jndatetimeStr: String,
    /** 记账时间原文；可能为空。 */
    val effectdateStr: String?,
    /** 交易金额（分）。 */
    val tranamtFen: Long,
    /** 方向：true = 收入（typeFrom "1"），false = 支出。 */
    val income: Boolean,
    /** 类型名（消费 / 二维码支付 / 充值…）。 */
    val turnoverType: String,
    val remark: String?,
    val resume: String?,
    /** 交易后余额快照（分）；缺失为 null。 */
    val balanceAfterFen: Long?,
    val locationName: String?,
    val orderId: String?,
)

/** 流水分页（当期查询结果）。 */
data class YktTurnoverPage(
    val records: List<YktTurnover>,
    val total: Long,
    val pages: Long,
)

/** 当期汇总（`statistics/turnover/count`，分）。 */
data class YktTurnoverSummary(val expensesFen: Long, val incomeFen: Long)

/** 业务异常分类：UI 据此给不同文案；[NeedCaptcha] 绝不重试（防撞风控）。 */
sealed class YktException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    /** 凭证错（HTTP 400 `Bad credentials`）。 */
    class Credential(message: String) : YktException(message)

    /** 学号绑定多个账号（code 8001）。 */
    class MultiAccount(message: String, val options: String?) : YktException(message)

    /** 触发图形验证码（code 8002/8003）：引导去浏览器登录一次，脚本端绝不硬试。 */
    class NeedCaptcha(val code: Int) : YktException("触发图形验证码（$code），请先用浏览器正常登录一次校园卡平台")

    /** 响应结构/字形表/样板自检问题：协议漂移，需升级映射表。 */
    class Protocol(message: String) : YktException(message)

    /** 网络不可达 / 超时。 */
    class Network(cause: Throwable) : YktException("网络不可达：${cause.message ?: "未知错误"}", cause)
}

/** 一卡通 JSON 解析（BOM 剥离 + 容错）。纯函数，JVM 可测。 */
object YktModels {

    /** 与 data/qiekj/QiekjJson 同款容错口径：未知字段忽略、宽数字。 */
    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** 剥离 UTF-8 BOM（`\uFEFF`，部分一卡通接口响应带 BOM，直接 parse 会在首字符报错）。 */
    fun stripBom(text: String): String = text.removePrefix("\uFEFF")

    /** 解析响应体（含 BOM 容错）为 [YktEnvelope]；HTTP 层已解为文本。 */
    fun parseEnvelope(text: String): YktEnvelope =
        json.decodeFromString(stripBom(text))

    /** 从 envelope.data 抽键盘结构；结构缺失抛 [YktException.Protocol]。 */
    fun keyboardFrom(data: kotlinx.serialization.json.JsonElement?): YktKeyboardData {
        val obj = data as? JsonObject
            ?: throw YktException.Protocol("键盘响应缺 data 对象")
        val uuid = obj["uuid"]?.jsonPrimitive?.content
            ?: throw YktException.Protocol("键盘响应缺 uuid")
        val kb = obj["numberKeyboard"]?.jsonPrimitive?.content
            ?: throw YktException.Protocol("键盘响应缺 numberKeyboard")
        val images = obj["numberKeyboardImage"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: throw YktException.Protocol("键盘响应缺 numberKeyboardImage")
        return YktKeyboardData(
            uuid = uuid,
            numberKeyboard = kb,
            numberKeyboardImage = images.map { android.util.Base64.decode(it, android.util.Base64.DEFAULT) },
            samplePassword = obj["password"]?.jsonPrimitive?.content,
        )
    }

    /** 从 envelope.data 抽付款账户列表；`data` 可能是数组或包一层的对象。 */
    fun payAccountsFrom(data: kotlinx.serialization.json.JsonElement?): List<YktPayAccount> {
        val arr = data?.jsonArray ?: return emptyList()
        return runCatching { json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(YktPayAccount.serializer()), arr) }
            .getOrElse { emptyList() }
    }

    /** 从 envelope.data 抽付款码批次。 */
    fun barcodeFrom(data: kotlinx.serialization.json.JsonElement?): YktBarcodeData? {
        val obj = data as? JsonObject ?: return null
        return runCatching { json.decodeFromJsonElement(YktBarcodeData.serializer(), obj) }.getOrNull()
    }

    /**
     * OAuth2 成功响应的顶层 `access_token`（token 字段在顶层而非 data 里）。
     * 解析失败 / 字段缺失返回 null。
     */
    fun accessTokenFromTopLevel(text: String): String? = runCatching {
        val obj = json.parseToJsonElement(stripBom(text)) as? JsonObject ?: return null
        obj["access_token"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
    }.getOrNull()

    // ---- 余额与流水（2026-09-20 实测口径，DESIGN §4.19） ----

    /** 金额字段：Long/Double/字符串数字都容（服务端混排），归一到分。 */
    private fun jsonToFen(element: kotlinx.serialization.json.JsonElement?): Long? =
        runCatching {
            when {
                element == null -> null
                element is JsonNull -> null
                element is JsonPrimitive && element.isString ->
                    element.content.trim().toDoubleOrNull()?.toLong()
                else -> element.jsonPrimitive.longOrNull
                    ?: element.jsonPrimitive.doubleOrNull?.toLong()
            }
        }.getOrNull()

    /** 字符串字段（null 安全）。 */
    private fun jsonToStr(element: kotlinx.serialization.json.JsonElement?): String? =
        runCatching {
            when {
                element == null -> null
                element is JsonNull -> null
                else -> element.jsonPrimitive.content.takeIf { it.isNotBlank() }
            }
        }.getOrNull()

    /** `queryCard` 的 `data.card[]` → 卡余额列表。 */
    fun cardsFrom(data: kotlinx.serialization.json.JsonElement?): List<YktCard> {
        val arr = (data as? JsonObject)?.get("card")?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val db = jsonToFen(obj["db_balance"]) ?: return@mapNotNull null
            val unsettle = jsonToFen(obj["unsettle_amount"]) ?: 0L
            YktCard(
                cardName = jsonToStr(obj["card_name"] ?: obj["cardname"]) ?: "校园卡",
                account = jsonToStr(obj["account"]) ?: "",
                cardBalanceFen = db + unsettle,
                elecBalanceFen = jsonToFen(obj["elec_accamt"]) ?: 0L,
                expdate = jsonToStr(obj["expdate"]),
                lostflag = jsonToStr(obj["lostflag"]),
            )
        }
    }

    /** `personal/turnover` 的 data（records + total + pages）→ 分页。 */
    fun turnoverPageFrom(data: kotlinx.serialization.json.JsonElement?): YktTurnoverPage {
        val obj = data as? JsonObject ?: return YktTurnoverPage(emptyList(), 0, 0)
        val records = (obj["records"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val amt = jsonToFen(o["tranamt"]) ?: return@mapNotNull null
                YktTurnover(
                    jndatetimeStr = jsonToStr(o["jndatetimeStr"]) ?: jsonToStr(o["jndatetime"]) ?: "",
                    effectdateStr = jsonToStr(o["effectdateStr"]),
                    tranamtFen = amt,
                    income = jsonToStr(o["typeFrom"]) == "1",
                    turnoverType = jsonToStr(o["turnoverType"]) ?: "",
                    remark = jsonToStr(o["remark"]),
                    resume = jsonToStr(o["resume"]),
                    balanceAfterFen = jsonToFen(o["cardBalance"]) ?: jsonToFen(o["ebagamt"]),
                    locationName = jsonToStr(o["locationName"]),
                    orderId = jsonToStr(o["orderId"]),
                )
            }.orEmpty()
        return YktTurnoverPage(
            records = records,
            total = jsonToFen(obj["total"]) ?: 0L,
            pages = jsonToFen(obj["pages"]) ?: 0L,
        )
    }

    /** `statistics/turnover/count` 的 data → 支出/收入汇总（分）。 */
    fun turnoverSummaryFrom(data: kotlinx.serialization.json.JsonElement?): YktTurnoverSummary {
        val obj = data as? JsonObject
        return YktTurnoverSummary(
            expensesFen = jsonToFen(obj?.get("expenses")) ?: 0L,
            incomeFen = jsonToFen(obj?.get("income")) ?: 0L,
        )
    }
}
