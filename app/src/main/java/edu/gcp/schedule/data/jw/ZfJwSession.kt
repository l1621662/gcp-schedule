package edu.gcp.schedule.data.jw

import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.ScheduleCalculator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import java.io.IOException
import java.math.BigInteger
import java.net.Inet4Address
import java.net.InetAddress
import java.security.KeyFactory
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/**
 * 正方教务「无界面」会话（DESIGN §4.17）：调课检测不拉 WebView，直接走 HTTP。
 *
 * 链路（2026-09-23 对 `jwcjw.gcp.edu.cn` 实测）：
 *   [1] GET  `/jwglxt/xtgl/login_slogin.html` → 拿会话 cookie 与 `#csrftoken`
 *   [2] GET  `/jwglxt/kaptcha?time=<ms>`      → 验证码 JPEG（实测 108×34、约 4KB）
 *   [3] POST `/jwglxt/xtgl/login_slogin.html` → csrftoken / language / yhm / mm / yzm
 *   [4] POST `/jwglxt/kbcx/xskbcx_cxKbxx.html` → 课表 JSON（kbList[]），用于比对
 *
 * 现场结论（都钉进了单测）：
 * - **验证码必填**：登录页 `#yzmDiv` 可见（没有 display:none），页面 JS 提交前校验
 *   `#yzm` 非空；`dlmmsfknt=1` 说明验证码区分大小写。
 * - **密码默认不加密**：`mmsfjm=0` → `mm` 明文 POST；若某校置 1，则先取
 *   `/jwglxt/xtgl/login_getPublicKey.html` 的 modulus/exponent 做 RSA(PKCS#1 v1.5)，
 *   再按页面 `hex2b64(rsaKey.encrypt(...))` 的口径输出。
 * - **一次会话只登一次**：账号密码只在内存里活一次，落盘由 [JwCredentialStore] 负责。
 *
 * 与旧的强智 CAS 链路（已随功能迁移删除）不共用：正方是单站点表单，没有票据预热。
 */
class ZfJwSession private constructor(
    private val client: OkHttpClient,
    private val jar: InMemoryCookieJar,
) {

    sealed class ZfException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        /** 账号/密码/验证码不对。连续失败会触发自动停用，见 JwDetectRunner。 */
        class Credential(message: String) : ZfException(message)

        /** 网络/超时/DNS。静默重试口径，不发通知。 */
        class Network(cause: Throwable) : ZfException(cause.message ?: "网络错误", cause)

        /** 链路异常：登录页缺 csrftoken、落点不对、课表接口结构变更。 */
        class Protocol(message: String) : ZfException(message)
    }

    /** 登录页现场：登录要用的参数 + 一张验证码图片。 */
    data class LoginPage(
        val csrfToken: String,
        /** 是否需要填验证码（本校准为 true；字段整体缺失时按不需要处理） */
        val captchaRequired: Boolean,
        /** 密码是否需要 RSA 加密后提交（`mmsfjm=1`） */
        val passwordEncrypted: Boolean,
        /** 验证码 JPEG 原始字节；不需要验证码时为 null */
        val captcha: ByteArray?,
    ) {
        // data class 带 ByteArray 必须手写 equals/hashCode，否则内容比较恒为 false
        override fun equals(other: Any?): Boolean {
            if (other !is LoginPage) return false
            val mine = captcha
            val theirs = other.captcha
            val sameCaptcha = if (mine == null) theirs == null else theirs != null && mine.contentEquals(theirs)
            return csrfToken == other.csrfToken &&
                captchaRequired == other.captchaRequired &&
                passwordEncrypted == other.passwordEncrypted &&
                sameCaptcha
        }

        override fun hashCode(): Int {
            var result = csrfToken.hashCode()
            result = 31 * result + captchaRequired.hashCode()
            result = 31 * result + passwordEncrypted.hashCode()
            result = 31 * result + (captcha?.contentHashCode() ?: 0)
            return result
        }
    }

    /** 正方课表：课程 + 学期（学期用于「是否换了学期」的判断与提示）。 */
    data class ZfSchedule(val term: String?, val courses: List<Course>)

    private var csrfToken: String = ""
    private var passwordEncrypted: Boolean = false

    /** 拉登录页 + 验证码图片（不含任何凭证，随时可重刷验证码）。 */
    suspend fun prepareLogin(): LoginPage = withContext(Dispatchers.IO) {
        val html = getText(LOGIN_URL)
        val csrf = extractCsrfToken(html)
            ?: throw ZfException.Protocol("登录页缺少 csrftoken（正方页面结构可能变了）")
        csrfToken = csrf
        passwordEncrypted = extractHiddenValue(html, "mmsfjm") == "1"
        val needCaptcha = captchaRequired(html)
        val captcha = if (needCaptcha) {
            getBytes("$BASE/jwglxt/kaptcha?time=" + System.currentTimeMillis(), referer = LOGIN_URL)
        } else {
            null
        }
        LoginPage(csrf, needCaptcha, passwordEncrypted, captcha)
    }

    /**
     * 提交登录：成功即返回，失败抛 [ZfException.Credential] 并带上可读原因
     * （验证码错误 / 用户名或密码错误 / 账号被锁）。
     */
    suspend fun login(username: String, password: String, captcha: String) = withContext(Dispatchers.IO) {
        if (csrfToken.isBlank()) throw ZfException.Protocol("请先调用 prepareLogin()")
        val encrypted = if (passwordEncrypted) rsaEncryptPassword(password) else password
        val form = FormBody.Builder()
            .add("csrftoken", csrfToken)
            .add("language", "zh_CN")
            .add("yhm", username)
            .add("mm", encrypted)
            .add("yzm", captcha)
            .build()
        val response = try {
            client.newCall(
                Request.Builder()
                    .url(LOGIN_URL)
                    .header("User-Agent", UA)
                    .header("Referer", LOGIN_URL)
                    .header("Origin", BASE)
                    .post(form)
                    .build(),
            ).execute()
        } catch (e: IOException) {
            throw ZfException.Network(e)
        }
        response.use { resp ->
            val body = resp.body?.string().orEmpty()
            val landing = resp.request.url.toString()
            if (landing.contains("index_init") || body.contains("index_init")) return@withContext
            if (landing.contains("login_slogin") || body.contains("csrftoken")) {
                throw ZfException.Credential(extractLoginError(body) ?: "登录失败：账号或密码不正确")
            }
            throw ZfException.Protocol("登录落点异常：$landing")
        }
    }

    /**
     * 抓课表（调课检测的比对源）。
     *
     * 学期参数优先取课表查询页里选中的 `xnm/xqm`；抓不到就传空值，让正方回落到默认
     * 学期（多数部署空值即当前学期）。空课表不算错误——可能只是这学期没课。
     */
    suspend fun fetchSchedule(): ZfSchedule = withContext(Dispatchers.IO) {
        // 课表页带 gnmkdm（菜单号）：不带时正方会把它当非法入口，页面里的学期下拉就读不到
        val indexHtml = runCatching { getText(SCHEDULE_LIST_GNNKDM) }.getOrDefault("")
        val selection = parseTermSelection(indexHtml)
        // 学期候选：页面选中的优先；读不到就按当前日期推（学年起始年 + 第一/第二学期），
        // 再退上一学期。2026-09-23 真机：参数空着时接口返回字面量 null（不是 901），
        // 说明会话是通的，纯粹是学期没对上。
        val candidates = termCandidates(selection)

        var lastError: ZfException? = null
        for (endpoint in SCHEDULE_DATA_ENDPOINTS) {
            for ((xnm, xqm) in candidates) {
                try {
                    val body = postText(
                        endpoint,
                        FormBody.Builder()
                            .add("xnm", xnm)
                            .add("xqm", xqm)
                            .add("kzlx", "ck")
                            .add("kbs", "1")
                            .add("xsdm", "")
                            .build(),
                    )
                    val root = try {
                        json.parseToJsonElement(body) as? JsonObject
                    } catch (e: Exception) {
                        null
                    }
                    if (root == null) {
                        lastError = ZfException.Protocol("课表接口返回的不是 JSON：" + snippet(body))
                        continue
                    }
                    val rows = root["kbList"]?.jsonArray ?: root["items"]?.jsonArray
                    if (rows == null) {
                        lastError = ZfException.Protocol("课表接口里没有 kbList：" + snippet(body))
                        continue
                    }
                    val courses = rows.mapNotNull { el -> rowToCourse(el as? JsonObject ?: return@mapNotNull null) }
                    if (courses.isEmpty()) {
                        lastError = ZfException.Protocol("学期 $xnm-$xqm 返回 0 条课程（若这学期确实没课可忽略）")
                        continue
                    }
                    return@withContext ZfSchedule(buildTerm(xnm, xqm), courses)
                } catch (e: ZfException) {
                    lastError = e
                }
            }
        }
        throw lastError ?: ZfException.Protocol("课表接口全部尝试失败")
    }

    fun shutdown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    // ------------------------------------------------------------ 会话复用（后台检测）

    /**
     * 把当前会话 cookie 导出成 `name=value; …`，交给加密存储保管。
     *
     * 为什么需要：本校准登录**必须填验证码**，后台定时检测没法自己登录；用户手动验证
     * 一次后把会话留着，后台在有效期内复用（过期就跳过并提示手动验证，见 JwDetectRunner）。
     */
    fun exportSession(): String? = jar.export(BASE.toHttpUrl().host)

    /** 还原会话 cookie；空串/坏串返回 false（调用方据此走「需要手动验证」分支）。 */
    fun importSession(raw: String?): Boolean = jar.import(BASE.toHttpUrl().host, raw)

    // ------------------------------------------------------------------ 解析

    private fun rowToCourse(row: JsonObject): Course? {
        val name = row.str("kcmc") ?: return null
        val day = parseWeekday(row.str("xqj")) ?: return null
        if (day !in 1..7) return null
        val sections = parseSections(row.str("jcs")) ?: return null
        val weeks = ZhengfangScheduleParser.parseWeeks(row.str("zcd").orEmpty())
        if (weeks.isEmpty()) return null
        return Course(
            id = 0,
            name = name,
            teacher = row.str("xm").orEmpty(),
            position = row.str("cdmc").orEmpty(),
            day = day,
            startSection = sections.first,
            endSection = sections.second,
            weeks = weeks,
            colorIndex = ScheduleCalculator.colorIndexFor(name),
        )
    }

    /** `xqj`：多数是 `"1"`–`"7"`，也有部署给 `"星期一"`；认不出来返回 null。 */
    private fun parseWeekday(raw: String?): Int? {
        val text = raw?.trim().orEmpty()
        text.toIntOrNull()?.let { return it.takeIf { d -> d in 1..7 } }
        val digit = Regex("([1-7])").find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (digit != null) return digit
        return when {
            "一" in text -> 1
            "二" in text -> 2
            "三" in text -> 3
            "四" in text -> 4
            "五" in text -> 5
            "六" in text -> 6
            "日" in text || "天" in text -> 7
            else -> null
        }
    }

    /** 正方 `jcs` 形如 `"1-2"` / `"3"` / `"第1-2节"`；非法返回 null。 */
    private fun parseSections(raw: String?): Pair<Int, Int>? {
        val text = raw?.replace("节", "")?.replace("第", "")?.trim().orEmpty()
        if (text.isEmpty()) return null
        val parts = text.split('-')
        val first = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
        val last = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: first
        if (first !in 1..20 || last !in first..20) return null
        return first to last
    }

    private fun extractTermFromJson(root: JsonObject): String? {
        val xsxx = root["xsxx"] as? JsonObject
        val xn = xsxx?.str("xnm") ?: root.str("xnm")
        val xq = xsxx?.str("xqm") ?: root.str("xqm")
        return buildTerm(xn, xq)
    }

    private fun rsaEncryptPassword(password: String): String {
        val keyJson = getText("$BASE/jwglxt/xtgl/login_getPublicKey.html?time=" + System.currentTimeMillis())
        val root = try {
            json.parseToJsonElement(keyJson) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw ZfException.Protocol("取公钥失败：返回不是 JSON")
        val modulus = root.str("modulus") ?: throw ZfException.Protocol("公钥里没有 modulus")
        val exponent = root.str("exponent") ?: throw ZfException.Protocol("公钥里没有 exponent")
        val key = KeyFactory.getInstance("RSA").generatePublic(
            RSAPublicKeySpec(BigInteger(modulus, 16), BigInteger(exponent, 16)),
        )
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
        // 页面口径 hex2b64：RSA 输出按小端逆序再 Base64
        return Base64.getEncoder().encodeToString(encrypted.reversedArray())
    }

    private fun getText(url: String): String {
        val response = try {
            client.newCall(Request.Builder().url(url).header("User-Agent", UA).get().build()).execute()
        } catch (e: IOException) {
            throw ZfException.Network(e)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ZfException.Protocol("HTTP " + resp.code + "：" + url + " " + snippet(text))
            }
            return text
        }
    }

    private fun getBytes(url: String, referer: String?): ByteArray {
        val builder = Request.Builder().url(url).header("User-Agent", UA).get()
        if (referer != null) builder.header("Referer", referer)
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw ZfException.Network(e)
        }
        response.use { resp ->
            if (!resp.isSuccessful) {
                throw ZfException.Protocol("验证码接口 HTTP " + resp.code + "：" + snippet(resp.body?.string().orEmpty()))
            }
            return resp.body?.bytes() ?: throw ZfException.Protocol("验证码接口返回空 body")
        }
    }

    private fun postText(url: String, form: FormBody): String {
        val response = try {
            client.newCall(
                Request.Builder()
                    .url(url)
                    .header("User-Agent", UA)
                    .header("Referer", SCHEDULE_LIST)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .post(form)
                    .build(),
            ).execute()
        } catch (e: IOException) {
            throw ZfException.Network(e)
        }
        response.use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ZfException.Protocol("HTTP " + resp.code + "：" + url + " " + snippet(text))
            }
            return text
        }
    }

    internal class InMemoryCookieJar : CookieJar {
        private val store = HashMap<String, HashMap<String, Cookie>>()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(store) {
                val bucket = store.getOrPut(url.host) { HashMap() }
                cookies.forEach { bucket[it.name] = it }
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> = synchronized(store) {
            store[url.host]?.values?.filter { it.matches(url) }.orEmpty()
        }

        /** `name=value; …`；没存过任何 cookie 时返回 null。 */
        fun export(host: String): String? = synchronized(store) {
            store[host]?.values?.takeIf { it.isNotEmpty() }
                ?.sortedBy { it.name }
                ?.joinToString("; ") { it.name + "=" + it.value }
        }

        /** 解析 `name=value; …` 并挂到 host 上（path=/，与正方实际下发一致）。 */
        fun import(host: String, raw: String?): Boolean {
            if (raw.isNullOrBlank()) return false
            var restored = 0
            raw.split(';').forEach { part ->
                val nv = part.trim().split('=', limit = 2)
                if (nv.size == 2 && nv[0].isNotBlank()) {
                    val cookie = Cookie.Builder()
                        .domain(host)
                        .path("/")
                        .name(nv[0].trim())
                        .value(nv[1].trim())
                        .build()
                    synchronized(store) { store.getOrPut(host) { HashMap() }[cookie.name] = cookie }
                    restored++
                }
            }
            return restored > 0
        }
    }

    companion object {
        private const val BASE = JwUrls.BASE_URL
        private const val LOGIN_URL = JwUrls.ENTRY
        private const val SCHEDULE_LIST = JwUrls.SCHEDULE_LIST

        /** 带菜单号的课表查询页（HTTP 抓取用；WebView 导入那条链路仍用不带参数的 URL）。 */
        private const val SCHEDULE_LIST_GNNKDM =
            JwUrls.SCHEDULE_LIST + "?gnmkdm=N2151&layout=default"
        private const val SCHEDULE_DATA_API = JwUrls.SCHEDULE_DATA_API

        /**
         * 学期候选（去重保序）：页面选中的 → 按今天推的当前学期 → 上一学期。
         *
         * xqm 口径：3 = 第一学期、12 = 第二学期、16 = 第三学期。学年起始年 8 月切换：
         * 9 月开学就是 (今年, 3)，到次年 2–7 月是 (去年, 12)。
         */
        internal fun termCandidates(
            selected: Triple<String, String, String?>?,
            today: java.time.LocalDate = java.time.LocalDate.now(),
        ): List<Pair<String, String>> {
            val startYear = if (today.monthValue >= 8) today.year else today.year - 1
            val current = if (today.monthValue >= 8 || today.monthValue == 1) 3 else 12
            val previous = if (current == 3) 12 else 3
            val previousStart = if (current == 3) startYear - 1 else startYear
            val ordered = buildList {
                selected?.let { add(it.first to it.second) }
                add(startYear.toString() to current.toString())
                add(previousStart.toString() to previous.toString())
            }
            return ordered.distinct()
        }

        /** 课表数据端点候选：新版 / 旧版命名各试一次。 */
        private val SCHEDULE_DATA_ENDPOINTS = listOf(
            SCHEDULE_DATA_API,
            "$BASE/jwglxt/kbcx/xskbcx_cxXsKb.html",
        )

        /** 错误信息里带一小段响应正文：真机截图就能看出是登录页、错误页还是参数被拒。 */
        internal fun snippet(body: String, limit: Int = 120): String =
            body.replace(Regex("\\s+"), " ").trim().take(limit)

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val json = Json { ignoreUnknownKeys = true }

        fun create(): ZfJwSession {
            val jar = InMemoryCookieJar()
            return ZfJwSession(
                OkHttpClient.Builder()
                    .cookieJar(jar)
                // 优先 IPv4：校园域名常同时有 A 与 AAAA，而校园 IPv6 在移动网络下实测是黑
                // （要等 TCP 超时才回落 v4，表现为「检测一直转圈」）。
                .dns(Ipv4FirstDns)
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build(),
                jar,
            )
        }

        /** IPv4 优先 DNS；不丢弃 AAAA（只有 v6 的域名仍能用）。纯逻辑、可 JVM 单测。 */
        object Ipv4FirstDns : Dns {
            override fun lookup(hostname: String): List<InetAddress> = ipv4First(Dns.SYSTEM.lookup(hostname))
        }

        internal fun ipv4First(all: List<InetAddress>): List<InetAddress> {
            val v4 = all.filterIsInstance<Inet4Address>()
            if (v4.isEmpty()) return all
            return v4 + all.filterNot { it is Inet4Address }
        }

        /** 登录页 `#csrftoken`（形如 `uuid,hex`）。 */
        internal fun extractCsrfToken(html: String): String? =
            Regex("(?is)id\\s*=\\s*[\"']csrftoken[\"'][^>]*value\\s*=\\s*[\"']([^\"']+)[\"']")
                .find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        /** 取登录页隐藏标记值（`mmsfjm` 密码是否加密、`dlmmsfknt` 验证码是否区分大小写）。 */
        internal fun extractHiddenValue(html: String, id: String): String? =
            Regex("(?is)id\\s*=\\s*[\"']$id[\"'][^>]*value\\s*=\\s*[\"']?([^\"'>\\s]+)")
                .find(html)?.groupValues?.get(1)?.trim()

        /**
         * 是否需要验证码：`#yzmDiv` 存在且没被 `display:none` 藏起来。
         * 标签整体缺失时按「不需要」处理，免得页面结构一变就把登录堵死。
         */
        internal fun captchaRequired(html: String): Boolean {
            val tag = Regex("(?is)<div[^>]*id\\s*=\\s*[\"']yzmDiv[\"'][^>]*>").find(html)?.value
                ?: return false
            val style = Regex("(?is)style\\s*=\\s*[\"']([^\"']*)[\"']").find(tag)?.groupValues?.get(1)
            return style == null || !style.replace(" ", "").contains("display:none", ignoreCase = true)
        }

        /** 正方登录失败页里的可读原因（`#tips` 文案或常见关键字），取不到返回 null。 */
        internal fun extractLoginError(html: String): String? {
            Regex("(?is)id\\s*=\\s*[\"']tips[\"'][^>]*>([\\s\\S]*?)</").find(html)
                ?.groupValues?.get(1)
                ?.replace(Regex("(?is)<[^>]+>"), "")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { return it }
            return listOf(
                "验证码错误", "验证码不正确", "用户名或密码错误", "密码错误",
                "账号不存在", "用户不存在", "账号被锁定", "密码已过期",
            ).firstOrNull { it in html }
        }

        /**
         * 课表查询页里选中的学期：返回 (xnm, xqm, 展示口径 term)。
         * 先读 `<select id="xnm">` 的 selected，再退同名隐藏域（正方两种部署都见过）。
         * 抓不到返回 null，交给接口默认学期。
         */
        internal fun parseTermSelection(html: String): Triple<String, String, String?>? {
            if (html.isBlank()) return null
            val xnm = selectValue(html, "xnm") ?: hiddenValue(html, "xnm") ?: return null
            val xqm = selectValue(html, "xqm") ?: hiddenValue(html, "xqm") ?: return null
            return Triple(xnm, xqm, buildTerm(xnm, xqm))
        }

        private fun selectValue(html: String, id: String): String? {
            val select = Regex("(?is)<select[^>]*id\\s*=\\s*[\"']$id[\"'][^>]*>([\\s\\S]*?)</select>")
                .find(html)?.groupValues?.get(1) ?: return null
            val options = Regex("(?is)<option\\b[^>]*>").findAll(select).map { it.value }.toList()
            val chosen = options.firstOrNull { "selected" in it.lowercase() } ?: options.firstOrNull()
            return chosen
                ?.let { Regex("(?is)value\\s*=\\s*[\"']([^\"']*)[\"']").find(it)?.groupValues?.get(1) }
                ?.takeIf { it.isNotBlank() }
        }

        private fun hiddenValue(html: String, name: String): String? =
            Regex("(?is)<input[^>]*name\\s*=\\s*[\"']$name[\"'][^>]*value\\s*=\\s*[\"']([^\"']*)[\"']")
                .find(html)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

        /**
         * xnm/xqm → `2026-2027-1`。xqm 口径：3=第一学期、12=第二学期、16=第三学期
         * （正方通用编码）；识别不了返回 null，学期只做展示所以不阻塞。
         */
        internal fun buildTerm(xnm: String?, xqm: String?): String? {
            val year = xnm?.trim()?.takeIf { it.matches(Regex("\\d{4}")) } ?: return null
            val termNo = when (xqm?.trim()) {
                "3" -> "1"
                "12" -> "2"
                "16" -> "3"
                else -> return null
            }
            val start = year.toInt()
            return "$start-" + (start + 1) + "-$termNo"
        }
    }
}

private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }
