package edu.gcp.schedule.data.jw

/**
 * 教务导入失败诊断（WebView 侧）。
 *
 * 根因（2026-09-18 真机截图定位）：教务链路上的失败有三种**完全不同的表现**，
 * 而旧实现只处理了一种，另外两种都被错误地呈现成别的样子：
 *
 * 1. **HTTP 5xx**：服务端吐「500 error System Error.Please Wait…」错误页，
 *    WebView 把它当普通页面渲染，`onPageFinished` 照常回调 → 状态条显示
 *    「已登录教务，可从下方入口打开课表页」（与事实相反）。旧实现没有重写
 *    `WebViewClient.onReceivedHttpError`，HTTP 层错误根本没被看见。
 * 2. **传输层失败**（`ERR_CONNECTION_TIMED_OUT` 等）：`onReceivedError` 确实置了错误态，
 *    但 Chromium 的失败页随后照样回调 `onPageFinished`，那里无条件置 `Ready`
 *    → 错误浮层被覆盖，只剩浏览器原生页 +「页面已加载但内容为空」的探针提示。
 * 3. **会话失效**：强智回退到 860 字节「用户没有登录」页，HTTP 200、URL 正常，
 *    只能按页面正文识别（旧实现同样走成「已登录教务…」）。
 *
 * 1、2 有一个共同诱因：**学校对直连出口与代理出口区别对待**。移动端开着第三方
 * VPN/代理时，统一认证超时、SSO 落点 500；关闭后立即恢复。脚本侧同一现象
 * （`DESIGN.md` §7.6 有记录）曾被误判成"教务挂了"。
 * 故各诊断工厂都接受 `vpnActive`，命中时在正文里点名让用户先关代理。
 *
 * 本对象**不依赖 android.* / WebView**，是纯逻辑，可 JVM 单测
 * （错误码用下面这组本地常量，数值与 `WebViewClient.ERROR_*` 一一对应）。
 */
object JwImportDiagnosis {

    // 与 android.webkit.WebViewClient 的 ERROR_* 常量同值；复制一份以免纯逻辑层依赖 Android。
    const val ERROR_HOST_LOOKUP = -2
    const val ERROR_CONNECT = -6
    const val ERROR_IO = -7
    const val ERROR_TIMEOUT = -8
    const val ERROR_REDIRECT_LOOP = -9
    const val ERROR_FAILED_SSL_HANDSHAKE = -11
    const val ERROR_PROXY_AUTH = -5

    /**
     * 失败诊断结果。
     *
     * `retryUrl` 语义：`null` = 就地 reload 当前页；非空 = 必须改走该 URL。
     * CAS ticket 是一次性票据，重放必然失败（实测恒定 500），所以认证链上的失败统一回
     * [JwUrls.SSO_WARMUP] 重走一次（该入口先写 `bzb_njw`，见其注释）。
     */
    data class Diagnosis(
        val title: String,
        val body: String,
        val retryUrl: String? = null,
    )

    /**
     * 重试目标：认证链上的失败回预热入口，其余就地重放。
     *
     * - 空白/未知 URL：无从重放 → 入口
     * - 含 `ticket=`：票据已消费，重放必然失败（实测 500 恒定复现）→ 入口
     * - 统一认证/SSO 页：认证链未走完 → 入口
     * - 其它（如已登录后的课表页自身 5xx）：就地重放
     *
     * 回的是 [JwUrls.SSO_WARMUP] 而不是普通 reload：强智链路上它先落教务域写 `bzb_njw`
     * （票据校验依赖它，见该常量注释），再由页面 JS 跳到**同一个** CAS 地址；
     * 正方链路上它与 [JwUrls.ENTRY] 同为登录页，回入口就是让用户重新登录一次。
     */
    fun retryUrl(failedUrl: String?): String? = when {
        failedUrl.isNullOrBlank() -> JwUrls.SSO_WARMUP
        "ticket=" in failedUrl -> JwUrls.SSO_WARMUP
        "cas/login" in failedUrl || "sso.jsp" in failedUrl -> JwUrls.SSO_WARMUP
        else -> null
    }

    /** 传输层失败（`WebViewClient.onReceivedError`，仅主 frame）。 */
    fun networkFailure(
        errorCode: Int,
        description: String,
        url: String?,
        vpnActive: Boolean,
    ): Diagnosis {
        val host = hostOf(url)
        val (title, reason) = when (errorCode) {
            ERROR_TIMEOUT ->
                "连接超时" to if (host.isBlank()) {
                    "在超时时间内没能连上学校服务器，多为瞬时故障或出口问题。"
                } else {
                    "在超时时间内没能连上 $host，多为瞬时故障或出口问题。"
                }

            ERROR_CONNECT ->
                "连接被拒绝" to "TCP 连接未建立，服务器可能短暂不可用。"

            ERROR_HOST_LOOKUP ->
                "域名解析失败" to "无法解析学校域名，请检查当前网络与 DNS。"

            ERROR_IO ->
                "网络中断" to "连接中途断开，多为瞬时故障。"

            ERROR_PROXY_AUTH ->
                "代理要求认证" to "当前网络经由代理，且代理拒绝了请求。"

            ERROR_REDIRECT_LOOP ->
                "重定向过多" to "统一认证与教务之间来回跳转，通常意味着会话状态异常。"

            ERROR_FAILED_SSL_HANDSHAKE ->
                "HTTPS 握手失败" to "TLS 阶段失败，若开着代理则证书可能被中间替换。"

            else ->
                "无法连接认证/教务" to
                    "请确认能访问 ${hostOf(JwUrls.ENTRY)} 与 ${JwUrls.hostOf(JwUrls.SCHEDULE_LIST)}。"
        }
        return Diagnosis(
            title = title,
            body = bodyText(reason, url, vpnActive, errorCode, description),
            retryUrl = retryUrl(url),
        )
    }

    /** HTTP 状态码失败（`WebViewClient.onReceivedHttpError`，仅主 frame）。 */
    fun httpFailure(statusCode: Int, url: String?, vpnActive: Boolean): Diagnosis {
        val title = when {
            statusCode == 404 -> "教务返回 404"
            statusCode in 500..599 -> "教务返回 $statusCode"
            else -> "教务返回 HTTP $statusCode"
        }
        val reason = when {
            // 脚本侧实测：代理出口会让 SSO 落点 LoginToXk 返回 404 通用错误页
            statusCode == 404 -> "登录落点未命中，可能认证链未走完，或请求出口不被教务接受（代理出口是已知诱因）。"
            statusCode in 500..599 -> "教务服务端内部错误，多为瞬时故障。"
            statusCode == 403 -> "教务拒绝访问，可能会话已失效。"
            else -> "教务返回了异常状态码。"
        }
        return Diagnosis(
            title = title,
            body = bodyText(reason, url, vpnActive, statusCode, null),
            retryUrl = retryUrl(url),
        )
    }

    /**
     * 会话失效 / 尚未登录：强智的登录页。
     *
     * 实测（2026-09-18，手机 5G 无代理）：未登录访问 `xsMainV` 或 `xskb_list` 时，
     * 教务**不跳转**，而是就地把登录页渲染在**同一个 URL** 下（HTTP 200、`<title>登录</title>`、
     * 含 `#loginDiv` 与密码输入框）。因此从 URL 完全看不出未登录，
     * 旧逻辑会把登录页当「课表页已打开」并通过 `xsMainV` 分支自动跳课表 → 课表又是登录页
     * → 两者互跳成环（实测 18 次导航）。判别只能靠页面特征，见 [probeSessionLost]。
     *
     * 正方不适用这一条：未登录时它跳转到独立的 `login_slogin` 页（URL 就看得出来），
     * 用户在那一页正常登录即可，不该盖「会话已失效」浮层（见 JwImportScreen.isLoginLikeUrl）。
     */
    fun sessionLost(vpnActive: Boolean): Diagnosis = Diagnosis(
        title = "未登录 / 会话已失效",
        body = bodyText("教务在当前页面显示了登录框，说明登录未生效。", null, vpnActive),
        retryUrl = JwUrls.SSO_WARMUP,
    )

    /**
     * 加载完成但页面为空。
     *
     * 一律回入口：空页没有可重放的内容（`about:blank` 重放还是空），
     * 而从入口重走认证即使会话仍有效也只是多一次跳转，是更稳的路径。
     */
    fun blankPage(vpnActive: Boolean): Diagnosis = Diagnosis(
        title = "页面变为空白",
        body = bodyText("已加载到空页，可能登录会话未就绪。", null, vpnActive),
        retryUrl = JwUrls.SSO_WARMUP,
    )

    /**
     * 判定教务页是不是登录页（会话失效）。
     *
     * 判据是**同时**具备两个特征：`loginDiv` 与 `password`（探针输出形如
     * `"loginDiv password title=登录"`）。实测该登录页两者齐备
     * （`#loginDiv` + `input[type=password]` + `<title>登录</title>`）。
     *
     * 为什么用「与」而不是「或」：本判定会拦截后续流程，误报会把正常页当会话失效。
     * 实测已登录的课表/主页页面里这两者均为 0 处，
     * 单看任一特征的误报面更大，双特征齐备才算。
     *
     * ⚠️ 判据必须与探针输出的 token **逐字对齐**。本轮踩过一次：探针输出单词 `password`，
     * 而匹配器找的是 `type="password"`，两边不一致 → 判定恒为 false，且不报错（静默失效）。
     * `looksLikeLoginPage_detectsLoginMarkers` 专门钉住这条对齐关系。
     *
     * ⚠️ 不要改回按文案判定：曾据「用户没有登录」写过判据，而该文案在真实登录页上
     * 出现 **0 次**，那种写法永远不会命中。
     */
    fun looksLikeLoginPage(probeText: String?): Boolean {
        val t = probeText ?: return false
        return "loginDiv" in t && "password" in t
    }

    /**
     * 是否值得自动重试一次（UI 层负责「只重试一次」的闸门）。
     *
     * 依据实测：`sso.jsp?ticket=` 的 500 是**自愈型**——票据校验依赖教务域
     * cookie `bzb_njw`，而该 500 响应本身就会把 `bzb_njw` 写下来；重走一遍
     * [JwUrls.SSO_WARMUP] 即通过。这正是用户此前"过一会刷新又可以"的机制，
     * 现在交给 App 自动完成。
     *
     * 只对认证链上的 5xx 自动重试：
     * - 4xx 是确定性失败（如 404 出口被拒），重试无意义；
     * - 已登录页面（课表页自身 5xx）不自动重试，避免在真故障时反复打服务端。
     */
    fun shouldAutoRetry(statusCode: Int, failedUrl: String?): Boolean {
        if (statusCode !in 500..599) return false
        if (failedUrl.isNullOrBlank()) return false
        // 认证链上的失败才自愈；已登录后的页面重试交给用户显式触发
        return "ticket=" in failedUrl ||
            "sso.jsp" in failedUrl ||
            "cas/login" in failedUrl
    }

    private fun bodyText(
        reason: String,
        url: String?,
        vpnActive: Boolean,
        code: Int? = null,
        description: String? = null,
    ): String = buildString {
        append(reason)
        if (code != null) {
            append("\n错误码 ")
            append(code)
            if (!description.isNullOrBlank()) {
                append("：")
                append(description)
            }
        }
        if (!url.isNullOrBlank()) {
            append("\n")
            append(url)
        }
        if (vpnActive) {
            append("\n")
            append(VPN_HINT)
        }
        append("\n")
        append(RETRY_HINT)
    }

    private fun hostOf(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return url.substringAfter("://", url).substringBefore('/')
    }

    /** 移动端非学校官方代理（VPN/加速器）开着：学校对代理出口超时或 500。 */
    const val VPN_HINT: String =
        "检测到 VPN/代理正在启用：学校对直连与代理出口区别对待，请先关闭它再重试。"

    const val RETRY_HINT: String = "这类失败多为瞬时，点「重试」会重新走一次认证。"
}
