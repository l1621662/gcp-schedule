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
                .build(),
        )
    }

    /** 一次请求的原始结果：HTTP 状态码 + 响应文本（已剥 BOM）。 */
    data class Raw(val httpCode: Int, val text: String)

    /**
     * GET。header 需要鉴权时传 [token]（拼 `synjones-auth: bearer <token>`）。
     * 网络异常抛 [YktException.Network]；不自动重试。
     */
    suspend fun get(path: String, token: String? = null): Raw = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url(BASE + path)
            .header("User-Agent", UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("synAccessSource", "h5")
            .header("Referer", "$BASE/plat/login")
        if (token != null) builder.header("synjones-auth", "bearer " + token)
        try {
            http.newCall(builder.build()).execute().use { resp ->
                Raw(resp.code, YktModels.stripBom(resp.body?.string().orEmpty()))
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
            val body = form.entries.joinToString("&") { (k, v) ->
                java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
            }
            val req = Request.Builder()
                .url(BASE + path)
                .header("User-Agent", UA)
                .header("Accept", "application/json, text/plain, */*")
                .header("synAccessSource", "h5")
                .header("Referer", "$BASE/plat/login")
                .header("Authorization", BASIC)
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            try {
                http.newCall(req).execute().use { resp ->
                    Raw(resp.code, YktModels.stripBom(resp.body?.string().orEmpty()))
                }
            } catch (e: java.io.IOException) {
                throw YktException.Network(e)
            }
        }
}
