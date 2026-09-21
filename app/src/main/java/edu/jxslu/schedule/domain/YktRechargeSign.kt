package edu.jxslu.schedule.domain

/**
 * 校园卡充值下单签名（DESIGN §4.19「充值」）。
 *
 * 还原自官方前端公开 JS（充值页 chunk `d98c9872` 的 `confirm()` 与收银台 chunk
 * `6affa2d0` 的请求封装，两处一致）：`appid` 与 `SECRET_KEY` 是前端硬编码常量，
 * 平台改版即失效——下单报错时 UI 兜底文案「平台可能已改版」。
 *
 * 算法：
 * 1. 业务参数并上 [APP_ID]/[TIMESTAMP]/[NONCE]/[SIGN_TYPE] 四个元参数；
 * 2. key 字典序排序，跳过空值（`0` 与 `"0"` 保留）与 `SIGN`/`SECRET_KEY` 两键；
 * 3. 依次拼 `k=v&`，末尾拼 `SECRET_KEY=<密钥>`（无 `&`）；
 * 4. UTF-8 SHA256 十六进制**大写** = `SIGN`。
 */
object YktRechargeSign {

    /** 前端硬编码的应用标识（充值页 `appid:"56321"`）。 */
    const val APP_ID_VALUE = "56321"

    /** 前端硬编码的签名密钥（公开 JS 常量，非逆向所得）。 */
    const val SECRET_KEY = "0osTIhce7uPvDKHz6aa67bhCukaKoYl4"

    private const val KEY_APP_ID = "APP_ID"
    private const val KEY_TIMESTAMP = "TIMESTAMP"
    private const val KEY_NONCE = "NONCE"
    private const val KEY_SIGN_TYPE = "SIGN_TYPE"
    private const val KEY_SIGN = "SIGN"
    private const val KEY_SECRET_KEY = "SECRET_KEY"

    /** 元参数键集合。 */
    private val META_KEYS = setOf(KEY_APP_ID, KEY_TIMESTAMP, KEY_NONCE, KEY_SIGN_TYPE)

    /**
     * 给业务参数补元参数与 [SIGN]（返回**新 map**，不改入参）。
     * [timestamp] 可注入（单测定死）；[nonce] 同。
     */
    fun signed(
        params: Map<String, String>,
        timestamp: String = buildTimestamp(),
        nonce: String = buildNonce(),
    ): Map<String, String> {
        val merged = LinkedHashMap<String, String>(params.size + 4)
        merged.putAll(params)
        merged[KEY_APP_ID] = APP_ID_VALUE
        merged[KEY_TIMESTAMP] = timestamp
        merged[KEY_NONCE] = nonce
        merged[KEY_SIGN_TYPE] = "SHA256"
        merged[KEY_SIGN] = signOf(merged)
        return merged
    }

    /** 对已含元参数的 map 算 SIGN（见类 KDoc 步骤 2–4）。 */
    fun signOf(params: Map<String, String>): String {
        val keys = params.keys
            .filter { it != KEY_SIGN && it != KEY_SECRET_KEY }
            .filter { (params[it] ?: "").isNotEmpty() }
            .sorted()
        val sb = StringBuilder()
        for (k in keys) {
            sb.append(k).append('=').append(params.getValue(k)).append('&')
        }
        sb.append(KEY_SECRET_KEY).append('=').append(SECRET_KEY)
        return sha256Hex(sb.toString()).uppercase()
    }

    private fun sha256Hex(text: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** `yyyyMMddHHmmssSSS`（本地时区；前端 `new Date()` 拼接口径）。 */
    fun buildTimestamp(now: Long = System.currentTimeMillis()): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = now }
        fun p(v: Int, w: Int = 2) = v.toString().padStart(w, '0')
        return buildString {
            append(c.get(java.util.Calendar.YEAR))
            append(p(c.get(java.util.Calendar.MONTH) + 1))
            append(p(c.get(java.util.Calendar.DAY_OF_MONTH)))
            append(p(c.get(java.util.Calendar.HOUR_OF_DAY)))
            append(p(c.get(java.util.Calendar.MINUTE)))
            append(p(c.get(java.util.Calendar.SECOND)))
            append(p(c.get(java.util.Calendar.MILLISECOND), 3))
        }
    }

    /** 随机小写字母数字串（前端 `Math.random().toString(36).substring(2)` 口径，11 位）。 */
    fun buildNonce(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            repeat(11) { append(alphabet.random()) }
        }
    }
}
