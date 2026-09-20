package edu.jxslu.schedule.domain

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 共享单车骑行二维码（DESIGN §3.9 / §4.18）。
 *
 * 车身码 = 普通链接二维码 `https://www.kvcoogo.com/ebike?id=<完整车号>`；微信扫码后
 * 命中运营方「扫普通链接二维码打开小程序」规则，**完整 URL 经 `q` 参数透传给小程序**，
 * 小程序自行解析 id 加载车辆。因此只要前缀不变、改 id 依然命中——App 内输入尾部
 * 车号拼 URL 出码即可。本文件只做纯 JVM 的拼装与矩阵生成，不含任何 UI 与网络。
 */
object EbikeQr {

    /** 链接模板：`100000` 为车队编号段（实测样例 `100000669`），尾部 3 位才是车身号。 */
    const val TEMPLATE = "100000"

    private const val BASE_URL = "https://www.kvcoogo.com/ebike"

    /** 尾部车号位数（用户口径：改后面三位即可）。 */
    const val TAIL_LENGTH = 3

    /** QR 输出像素边长（存相册用大图，扫码距离近，大了不亏）。 */
    const val QR_SIZE_PX = 720

    /** 最近车号历史上限（DESIGN §3.9：8 个，倒序去重）。 */
    const val RECENT_LIMIT = 8

    /** 待焚毁二维码记录上限（扫完即焚；正常使用到不了，只防异常膨胀）。 */
    const val PENDING_DELETE_LIMIT = 32

    /** 待焚毁记录前缀：MediaStore uri（API 29+）。 */
    const val PENDING_MEDIA_PREFIX = "m:"

    /** 待焚毁记录前缀：公共目录文件绝对路径（API 26–28）。 */
    const val PENDING_FILE_PREFIX = "f:"

    /** 待焚毁记录的删除通道：决定走 MediaStore 还是 File。 */
    enum class PendingKind { MediaStore, FilePath }

    /** MediaStore 保存条目 → 待焚毁 key。 */
    fun pendingMediaKey(uri: String): String = PENDING_MEDIA_PREFIX + uri

    /** 公共目录保存文件 → 待焚毁 key。 */
    fun pendingFileKey(path: String): String = PENDING_FILE_PREFIX + path

    /**
     * 待焚毁 key → 删除通道与目标。未知前缀（脏数据/旧版本）返回 null，
     * 调用方丢弃即可——删错文件比漏删一张码严重得多。
     */
    fun parsePendingKey(key: String): Pair<PendingKind, String>? = when {
        key.startsWith(PENDING_MEDIA_PREFIX) ->
            key.removePrefix(PENDING_MEDIA_PREFIX).takeIf { it.isNotBlank() }
                ?.let { PendingKind.MediaStore to it }
        key.startsWith(PENDING_FILE_PREFIX) ->
            key.removePrefix(PENDING_FILE_PREFIX).takeIf { it.isNotBlank() }
                ?.let { PendingKind.FilePath to it }
        else -> null
    }

    /**
     * 把刚保存的待焚毁 key 并入记录集：去重、防膨胀（超 [PENDING_DELETE_LIMIT]
     * 丢最旧的——Set 无序，这里只是兜底防膨胀，不承诺淘汰顺序）。
     */
    fun mergePendingDelete(current: Set<String>, key: String): Set<String> =
        (current + key).let { merged ->
            if (merged.size > PENDING_DELETE_LIMIT) {
                merged.toList().takeLast(PENDING_DELETE_LIMIT).toSet()
            } else merged
        }

    /**
     * 拼完整骑行链接。尾部必须是恰好 3 位数字，否则返回 null——
     * 非法输入宁可拒掉也不出一张扫不开的码。
     */
    fun bikeUrl(tail: String): String? {
        if (tail.length != TAIL_LENGTH) return null
        if (tail.any { it !in '0'..'9' }) return null
        return "$BASE_URL?id=$TEMPLATE$tail"
    }

    /**
     * 生成 QR 位阵。容错取 M（15%，打印/屏幕亮度损失下仍有余量）；
     * 白边 1 模块（zxing 约定：margin 是模块数不是像素，1 已满足扫码器的静区要求，
     * UI 展示时再由外层容器给视觉留白）。
     */
    fun qrMatrix(url: String): com.google.zxing.common.BitMatrix =
        QRCodeWriter().encode(
            url,
            BarcodeFormat.QR_CODE,
            QR_SIZE_PX,
            QR_SIZE_PX,
            mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            ),
        )

    /**
     * 把刚生成的车号并入最近历史：倒序去重、上限 [RECENT_LIMIT]。
     * 纯函数，输入输出都是不可变列表，测试与调用方共用同一口径。
     */
    fun mergeRecent(recent: List<String>, tail: String): List<String> =
        (listOf(tail) + recent.filter { it != tail }).take(RECENT_LIMIT)

    /** 最近车号列表 → JSON（DataStore 存储）。 */
    fun encodeRecent(recent: List<String>): String = Json.encodeToString(recent.toList())

    /**
     * JSON → 最近车号列表。脏 JSON / 结构不符一律回空列表：
     * 历史是锦上添花的回填入口，坏了不该让页面读不到偏好。
     */
    fun decodeRecent(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val list = Json.decodeFromString<List<String>>(json)
            // 存储层防线：越界/脏条目就地清理，不指望写入方永远规矩
            list.filter { bikeUrl(it) != null }.distinct().take(RECENT_LIMIT)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
