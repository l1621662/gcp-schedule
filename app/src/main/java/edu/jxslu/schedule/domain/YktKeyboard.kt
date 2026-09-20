package edu.jxslu.schedule.domain

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.oned.Code128Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.security.MessageDigest

/**
 * 校园卡「安全键盘」字形映射与密码构造（DESIGN §3.10 / §4.19）。纯 JVM，无 Android 依赖。
 *
 * 新中新「慧新e校」登录密码的所谓加密 = 一次字符替换：服务端每次下发 10 张数字字形图
 * （跨会话固定同一组、顺序随机）与等长的乱序字符集，点第 i 个键发第 i 个字符；
 * 提交时拼 `密文$1$uuid`，服务端凭 uuid 还原。因此只要认得每张字形图是哪个数字，
 * 就能现场算出「数字 → 密文字符」的映射。
 *
 * 识别与校验纪律（2026-09-20 Python 侧实测口径）：
 * - 字形按**全量 32 位 MD5** 查表——表错了宁可报错也绝不猜，静默错配等于拿错密码去撞登录；
 * - 解出的 10 个密文字符必须构成 0-9 双射，不满足即协议漂移；
 * - 服务端响应自带的 `password` 样板字段（= numberKeyboard + "$1$" + uuid）每次登录
 *   顺带校验，是零成本的改版探测器。
 */
object YktKeyboard {

    /** 平台协议异常：字形表过期 / 响应结构变了。上层给「协议变更，请升级或去网页端登录」。 */
    class YktProtocolException(message: String) : Exception(message)

    /**
     * 字形图（全量 MD5）→ 真实数字。2026-09-20 实测两轮会话一致；
     * 平台换字体后此表失效，[buildMapping] 会因未知哈希抛 [YktProtocolException]。
     */
    val HASH2DIGIT: Map<String, Char> = mapOf(
        "6ac2c1d871bd55c1eccd336cec447954" to '0',
        "17bab0dee8b8bda4fe05190c8ab2cdfe" to '1',
        "674126a625e8ea4969d44b7bd494c3ab" to '2',
        "178d201cfcd9b2c3dcb8935ffdbcfd00" to '3',
        "1de9de01cdaeb4636b0e8b56d3e74f88" to '4',
        "baa1c973210c6ddf73f475567f413d29" to '5',
        "3db631d021b09948714cbfc0d5e88d6d" to '6',
        "bcfcf6a9606e6930cc2b0b74cc57e53a" to '7',
        "487b062c6dae3c6f2f0738e865a715a4" to '8',
        "cbe6d35b4680915b040ab31b9bd47d18" to '9',
    )

    /** 登录密码最大长度：前端提交前 `slice(0, 16)`，构造时对齐同一行为。 */
    const val MAX_PASSWORD_LENGTH = 16

    /** 字形 PNG 字节的全量 MD5（hex 小写）。 */
    fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * 由本次键盘响应构建「数字 → 密文字符」映射（字节入参：先算 MD5 再查表）。
     *
     * [imageBytes] 与 [chars] 按响应数组的同一顺序一一对应（第 i 张图在第 i 位，
     * 点它发 [chars][i]）。任何未知字形、非双射结果都抛 [YktProtocolException]。
     *
     * @return 键为 '0'..'9'、值为密文字符的映射（10 项）
     */
    @JvmName("buildMappingFromBytes")
    fun buildMapping(
        imageBytes: List<ByteArray>,
        chars: String,
        table: Map<String, Char> = HASH2DIGIT,
    ): Map<Char, Char> = buildMapping(imageBytes.map(::md5Hex), chars, table)

    /**
     * 按已算好的哈希查表构建映射（[buildMapping] 的核心；测试注入假表用）。
     * 纪律同上：未知哈希/非双射一律抛 [YktProtocolException]，绝不猜。
     */
    fun buildMapping(
        imageHashes: List<String>,
        chars: String,
        table: Map<String, Char> = HASH2DIGIT,
    ): Map<Char, Char> {
        require(imageHashes.size == chars.length) {
            "键盘数据不一致：字形 ${imageHashes.size} 张 vs 字符 ${chars.length} 个"
        }
        val mapping = HashMap<Char, Char>(10)
        imageHashes.forEachIndexed { i, hash ->
            val digit = table[hash]
                ?: throw YktProtocolException(
                    "未知字形（md5=$hash），平台可能已更换字体，映射表需重新识别",
                )
            mapping[digit] = chars[i]
        }
        if (mapping.size != 10) {
            throw YktProtocolException("键盘映射不完整：只解出 ${mapping.size} 个数字")
        }
        if (mapping.values.toSet().size != 10) {
            throw YktProtocolException("键盘密文字符重复，不是 0-9 双射")
        }
        return mapping
    }

    /**
     * 构造提交用的 `password` 字段：逐位替换 + `"$1$"` + uuid。
     * 与前端一致先截到 [MAX_PASSWORD_LENGTH] 位（6 位数字密码不受影响）；
     * 含非数字字符抛 [IllegalArgumentException]——登录键盘只映射 0-9。
     */
    fun buildPasswordField(password: String, mapping: Map<Char, Char>, uuid: String): String {
        val truncated = password.take(MAX_PASSWORD_LENGTH)
        require(truncated.all { it in '0'..'9' }) {
            "登录密码只支持数字（安全键盘类型为 Number，仅映射 0-9）"
        }
        val cipher = truncated.map { c ->
            mapping[c] ?: throw YktProtocolException("映射表中缺少数字 $c")
        }.joinToString("")
        return cipher + "$1$" + uuid
    }

    /**
     * 协议自检：服务端响应自带的样板 `password` 应等于 `numberKeyboard + "$1$" + uuid`。
     * 这是服务端递到手里的 ground truth；不一致说明拼装格式已变，硬失败。
     *
     * @return true = 通过；false = 协议漂移（调用方应报错并停止登录）
     */
    fun looksLikeSampleInvariant(numberKeyboard: String, uuid: String, samplePassword: String?): Boolean {
        if (samplePassword.isNullOrBlank()) return false
        return samplePassword == numberKeyboard + "$1$" + uuid
    }
}
