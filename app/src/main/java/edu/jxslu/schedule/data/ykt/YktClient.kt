package edu.jxslu.schedule.data.ykt

import edu.jxslu.schedule.domain.YktKeyboard
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 一卡通平台 HTTP 客户端（DESIGN §4.19）。裸 OkHttp，只做请求与状态码归一，
 * 不做任何业务判断；业务错误码由 [YktRepository] 分类。
 *
 * 红线：**无任何日志拦截器**（密码/token/付款码不进 logcat）；响应文本一律先过
 * [YktModels.stripBom]。UA 与 H5 端一致（前端 axios 的浏览器 UA）。
 */
class YktClient private constructor(private val http: OkHttpClient) {

    companion object {
        const val BASE = "https://yktwx.juwp.edu.cn"

        /** 前端硬编码的 OAuth2 客户端凭据（仅标识客户端，非机密，所有人可见）。 */
        private const val BASIC =
            "Basic bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm06bW9iaWxlX3NlcnZpY2VfcGxhdGZvcm1fc2VjcmV0"

        private const val UA =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"

        fun create(): YktClient = YktClient(
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                // 充值下单（thirdOrder）靠 **302 Location** 下发收银台 URL——必须拿到原始
                // 302 而不是跟随后的落地页；其余端点全部 200 JSON，不依赖自动跟随。
                .followRedirects(false)
                .followSslRedirects(false)
                .build(),
        )
    }

    /** 一次请求的原始结果：HTTP 状态码 + 响应头（常用）+ 响应文本（已剥 BOM）。 */
    data class Raw(
        val httpCode: Int,
        val text: String,
        /** 302 场景需要 Location；其余场景常为 null。 */
        val location: String? = null,
    )

    /**
     * GET。header 需要鉴权时传 [token]（拼 `synjones-auth: bearer <token>`）；
     * [absoluteUrl] 请求站外 URL（微信 H5 支付中间页）；
     * [referer] 覆盖默认 Referer（微信 checkmweb 硬校验 referer 为商户域名）。
     * 网络异常抛 [YktException.Network]；不自动重试。
     */
    suspend fun get(
        path: String,
        token: String? = null,
        absoluteUrl: String? = null,
        referer: String? = null,
    ): Raw = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(absoluteUrl ?: (BASE + path))
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("synAccessSource", "h5")
            .header("Referer", referer ?: "$BASE/plat/login")
        if (token != null) builder.header("synjones-auth", "bearer " + token)
        try {
            http.newCall(builder.build()).execute().use { resp ->
                Raw(
                    resp.code,
                    YktModels.stripBom(resp.body?.string().orEmpty()),
                    location = resp.header("Location"),
                )
            }
        } catch (e: java.io.IOException) {
            throw YktException.Network(e)
        }
    }

    /**
     * POST 表单（x-www-form-urlencoded）。登录专用：带 Basic 凭据与表单 Content-Type。
     */
    suspend fun postForm(path: String, form: Map<String, String>): Raw =
        withContext(Dispatchers.IO) {
            postFormInternal(path, form, basicAuth = true, referer = "$BASE/plat/login")
        }

    /**
     * POST 表单（无 Basic 凭据）。充值下单/删单（`/charge/order/` 系列）用：
     * 前端是 HTML form 同步提交，不带 OAuth Basic 头，鉴权靠表单里的 `synjones-auth` 字段。
     */
    suspend fun postFormPlain(path: String, form: Map<String, String>, referer: String): Raw =
        withContext(Dispatchers.IO) {
            postFormInternal(path, form, basicAuth = false, referer = referer)
        }

    /**
     * POST 表单 + `synjones-auth` **header**（`/blade-pay/pay` 用：收银台 axios 把登录态
     * 放 header，放表单字段服务端不认，报「未获取到用户信息」，2026-09-21 实测）。
     */
    suspend fun postFormAuth(path: String, form: Map<String, String>, referer: String, token: String): Raw =
        withContext(Dispatchers.IO) {
            val body = form.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
            val req = Request.Builder()
                .url(BASE + path)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("synAccessSource", "h5")
                .header("Referer", referer)
                .header("synjones-auth", "bearer " + token)
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    Raw(
                        resp.code,
                        YktModels.stripBom(resp.body?.string().orEmpty()),
                        location = resp.header("Location"),
                    )
                }
            } catch (e: java.io.IOException) {
                throw YktException.Network(e)
            }
        }

    private suspend fun postFormInternal(
        path: String,
        form: Map<String, String>,
        basicAuth: Boolean,
        referer: String,
    ): Raw = withContext(Dispatchers.IO) {
        val body = form.entries.joinToString("&") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }
        val builder = Request.Builder()
            .url(BASE + path)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("synAccessSource", "h5")
            .header("Referer", referer)
            .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
        if (basicAuth) builder.header("Authorization", BASIC)
        val req = builder.build()
        try {
            http.newCall(req).execute().use { resp ->
                Raw(
                    resp.code,
                    YktModels.stripBom(resp.body?.string().orEmpty()),
                    location = resp.header("Location"),
                )
            }
        } catch (e: java.io.IOException) {
            throw YktException.Network(e)
        }
    }
}
