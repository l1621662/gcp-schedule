package edu.jxslu.schedule.data.ykt

import edu.jxslu.schedule.domain.YktKeyboard
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 一卡通登录与取码编排（DESIGN §4.19）。
 *
 * - token **仅内存缓存**（70 天有效期也不落盘，换更小的泄露面）；401 时重登一次；
 * - 无自动重试：失败直接抛分类异常（[YktException]），UI 给对应文案；
 * - 取码每次进页最多一批（反复调用由 UI 层节流，这里不拦）；
 * - 每次登录顺带跑 [YktKeyboard.looksLikeSampleInvariant] 协议自检。
 */
class YktRepository(private val client: YktClient) {

    companion object {
        /** 流水分页大小（服务端尊重 size；100/页时 726 条全量约 8 页，进页首屏 1 页覆盖近 2 个月）。 */
        const val TURNOVER_PAGE_SIZE = 100
    }

    /** 验证登录可用并返回 token（不缓存）——设置页「开启即验证」与登录共用。 */
    suspend fun loginForToken(username: String, password: String): YktToken {
        val token = doLogin(username, password)
        return YktToken(token, expiresIn = 0)
    }

    /** 取 CARD 账户 + 一批付款码（内部自动登录；[cachedToken] 存在则先试它）。 */
    suspend fun fetchPayCode(
        username: String,
        password: String,
        cachedToken: String? = null,
    ): YktBarcodeData {
        cachedToken?.let { token ->
            val data = tryLoadBarcodes(token)
            if (data != null) return data
        }
        val token = doLogin(username, password)
        return tryLoadBarcodes(token)
            ?: throw YktException.Protocol("登录成功但取码失败（非凭证问题），请稍后重试")
    }

    /** 登录并把 token 塞进内存缓存；返回 token 供上层复用（本对象内的 cached）。 */
    suspend fun login(username: String, password: String): String = doLogin(username, password)

    /** 取一批付款码（token 生命周期完全归本仓库：内存缓存 + 401 重登一次）。 */
    suspend fun payCodes(username: String, password: String): YktBarcodeData {
        cachedToken?.let { token ->
            val data = tryLoadBarcodes(token)
            if (data != null) return data
        }
        val fresh = doLogin(username, password)
        return tryLoadBarcodes(fresh)
            ?: throw YktException.Protocol("登录成功但取码失败（非凭证问题），请稍后重试")
    }

    /** 卡余额列表（DESIGN §4.19）：queryCard → data.card[]；401 重登一次。 */
    suspend fun cards(username: String, password: String): List<YktCard> {
        suspend fun load(t: String): List<YktCard>? {
            val raw = client.get("/berserker-app/ykt/tsm/queryCard", token = t)
            if (raw.httpCode == 401) return null
            val env = parse(raw)
            if (env.code != 200) {
                throw YktException.Protocol("取余额失败：${env.messageOrBlank.ifBlank { env.code.toString() }}")
            }
            return YktModels.cardsFrom(env.data)
        }
        cachedToken?.let { token ->
            load(token)?.let { return it }
        }
        val fresh = doLogin(username, password)
        return load(fresh) ?: throw YktException.Protocol("登录成功但取余额失败")
    }

    /**
     * 消费流水分页（DESIGN §4.19）。**不带任何时间参数**——该校后端对
     * `timeFrom`/`timeTo`（campus-card H5 前端发的参数）不识别：单独传被忽略、
     * 组合传直接清零（2026-09-20 实测矩阵），无参 = 按时间倒序的全量流水
     * （`size` 被服务端尊重，实测 100/页、726 条约 10 个月）。月份过滤由上层客户端完成。
     */
    suspend fun turnover(
        username: String,
        password: String,
        page: Int,
        pageSize: Int = TURNOVER_PAGE_SIZE,
    ): YktTurnoverPage {
        suspend fun load(t: String): YktTurnoverPage? {
            val qs = "size=" + pageSize + "&current=" + page
            val raw = client.get("/berserker-search/search/personal/turnover?$qs", token = t)
            if (raw.httpCode == 401) return null
            val env = parse(raw)
            if (env.code != 200) {
                throw YktException.Protocol("取流水失败：${env.messageOrBlank.ifBlank { env.code.toString() }}")
            }
            return YktModels.turnoverPageFrom(env.data)
        }
        cachedToken?.let { token ->
            load(token)?.let { return it }
        }
        val fresh = doLogin(username, password)
        return load(fresh) ?: throw YktException.Protocol("登录成功但取流水失败")
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private val loginMutex = Mutex()

    /** 登录链路：取键盘 → 字形映射 → 构造密文 → OAuth token。全程互斥（防并发双登录）。 */
    private suspend fun doLogin(username: String, password: String): String = loginMutex.withLock {
        // [1] 安全键盘
        val kbRaw = client.get("/berserker-secure/keyboard?type=Number&order=1")
        val kbEnv = parse(kbRaw)
        if (kbEnv.code != 200) throw YktException.Protocol("取安全键盘失败：${kbEnv.messageOrBlank.ifBlank { kbEnv.code.toString() }}")
        val kb = YktModels.keyboardFrom(kbEnv.data)

        // [2] 协议自检：服务端样板 password == kb + "$1$" + uuid（零成本改版探测器）
        if (!YktKeyboard.looksLikeSampleInvariant(kb.numberKeyboard, kb.uuid, kb.samplePassword)) {
            throw YktException.Protocol("登录协议已变更（键盘样板自检不过），请更新 App")
        }

        // [3] 字形映射（未知哈希/非双射在这里硬失败）
        val mapping = YktKeyboard.buildMapping(kb.numberKeyboardImage, kb.numberKeyboard)

        // [4] 密文 + 提交（表单字段与前端 axios 拦截器一致）
        val passwordField = try {
            YktKeyboard.buildPasswordField(password, mapping, kb.uuid)
        } catch (e: IllegalArgumentException) {
            throw YktException.Credential("登录密码只支持数字（安全键盘仅映射 0-9）")
        }
        val form = mapOf(
            "username" to username,
            "password" to passwordField,
            "grant_type" to "password",
            "scope" to "all",
            "loginFrom" to "h5",
            "logintype" to "sno",
            "device_token" to "h5",
            "synAccessSource" to "h5",
        )
        val tokenRaw = client.postForm("/berserker-auth/oauth/token", form)
        val tokenEnv = runCatching { parse(tokenRaw) }.getOrElse { e ->
            // HTTP 400 + 非 JSON 体 = 凭证错（服务端返回 Bad credentials 文本页）
            if (tokenRaw.httpCode == 400) {
                throw YktException.Credential("学号或密码错误")
            }
            throw e
        }
        if (tokenRaw.httpCode == 400) {
            throw YktException.Credential(
                tokenEnv.messageOrBlank.ifBlank { "学号或密码错误" },
            )
        }
        // OAuth2 成功响应的 token 字段在顶层而非 data 里
        val accessToken = YktModels.accessTokenFromTopLevel(tokenRaw.text)
        if (accessToken == null) {
            when (tokenEnv.code) {
                8001 -> throw YktException.MultiAccount(
                    "该学号绑定了多个账号，请先在网页端选择默认账号",
                    tokenEnv.data?.toString(),
                )
                8002, 8003 -> throw YktException.NeedCaptcha(tokenEnv.code)
                else -> throw YktException.Credential(
                    tokenEnv.messageOrBlank.ifBlank { "登录失败（${tokenEnv.code}）" },
                )
            }
        }
        cachedToken = accessToken
        accessToken
    }

    /** 内存 token 缓存（进程级；仅本类写）。 */
    @Volatile
    private var cachedToken: String? = null

    /** 用给定 token 试取码；401/失败返回 null（调用方重登），业务异常照抛。 */
    private suspend fun tryLoadBarcodes(token: String): YktBarcodeData? {
        val accountsRaw = client.get("/berserker-app/ykt/tsm/codebarPayinfo", token = token)
        if (accountsRaw.httpCode == 401) return null
        val accountsEnv = parse(accountsRaw)
        if (accountsEnv.code != 200) return null
        val card = YktModels.payAccountsFrom(accountsEnv.data).firstOrNull { it.code == "CARD" }
            ?: throw YktException.Protocol("没有找到卡账户（CARD），无法取付款码")

        // query 用 urlencode 拼接（口径同 Python 侧 urlencode(params)）。
        // 注意不能用 "path".toHttpUrl()：它要求绝对 URL（带 scheme），相对路径会直接
        // 抛 "Expected URL scheme 'http' or 'https' but no scheme was found"。
        val query = listOf(
            "account" to card.account,
            "payacc" to card.payacc,
            "paytype" to card.paytype,
        ).joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val barcodeRaw = client.get("/berserker-app/ykt/tsm/batchGetBarCodeGet?$query", token = token)
        if (barcodeRaw.httpCode == 401) return null
        val barcodeEnv = parse(barcodeRaw)
        if (barcodeEnv.code != 200) {
            throw YktException.Protocol("取付款码失败：${barcodeEnv.messageOrBlank.ifBlank { barcodeEnv.code.toString() }}")
        }
        val data = YktModels.barcodeFrom(barcodeEnv.data)
            ?: throw YktException.Protocol("付款码响应结构异常")
        if (data.barcode.isEmpty()) {
            throw YktException.Protocol("服务端未返回付款码：${data.errmsg.orEmpty().ifBlank { "无数据" }}")
        }
        return data
    }

    private fun parse(raw: YktClient.Raw): YktEnvelope =
        runCatching { YktModels.parseEnvelope(raw.text) }
            .getOrElse { throw YktException.Protocol("响应不是合法 JSON（HTTP ${raw.httpCode}）") }
}
