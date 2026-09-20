package edu.jxslu.schedule

import edu.jxslu.schedule.domain.YktKeyboard
import edu.jxslu.schedule.domain.YktKeyboard.YktProtocolException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校园卡安全键盘（DESIGN §4.19）：字形 MD5 表形状、buildMapping 双射硬校验、
 * 密码字段构造与 16 位截断、协议样板自检。
 */
class YktKeyboardTest {

    /** 表形状：10 项、键值都齐 0-9、无重复哈希。 */
    @Test
    fun `全量表形状正确`() {
        assertEquals(10, YktKeyboard.HASH2DIGIT.size)
        assertEquals(('0'..'9').toList(), YktKeyboard.HASH2DIGIT.values.sorted())
        assertEquals(10, YktKeyboard.HASH2DIGIT.keys.toSet().size)
        // 全部是 32 位小写 hex
        YktKeyboard.HASH2DIGIT.keys.forEach {
            assertTrue(it.matches(Regex("[0-9a-f]{32}")))
        }
    }

    @Test
    fun `md5Hex 与已知值一致`() {
        assertEquals(
            "d41d8cd98f00b204e9800998ecf8427e",
            YktKeyboard.md5Hex(ByteArray(0)),
        )
    }

    // ---- buildMapping（哈希入参 + 假表；字节入参在字节重测里覆盖） ----

    /**
     * 构造一次「假键盘响应」：positions[digit] = 该数字字形所在的键盘位，
     * 字符集取 charsPool 对应位——模拟服务端每次同步打乱。
     */
    private val positions = listOf(3, 7, 0, 9, 1, 5, 8, 2, 6, 4)
    private val pool = "abcdefghij"

    private fun fakeHashes(): List<String> =
        Array(10) { "" }.also { arr ->
            for (digit in 0..9) arr[positions[digit]] = "hash-$digit"
        }.toList()

    private fun fakeChars(): String =
        Array(10) { "" }.also { arr ->
            for (digit in 0..9) arr[positions[digit]] = pool[digit].toString()
        }.joinToString("")

    private fun fakeTable(): Map<String, Char> =
        (0..9).associate { "hash-$it" to ('0' + it) }

    @Test
    fun `buildMapping 乱序键盘还原正确`() {
        val mapping = YktKeyboard.buildMapping(fakeHashes(), fakeChars(), fakeTable())
        assertEquals(10, mapping.size)
        assertEquals(('0'..'9').toSet(), mapping.keys.toSet())
        for (digit in 0..9) {
            // '0' + digit 才是数字字符；digit.toChar() 是码点 0-9 的控制字符（经典陷阱）
            assertEquals('a' + digit, mapping['0' + digit])
        }
    }

    @Test
    fun `buildMapping 字节入参走 md5 查表`() {
        // 真表 + 真 MD5：0 字节恒为 d41d8cd9…，不在表里 → 未知字形抛错（证明字节路径真的算了 MD5）
        val ex = runCatching {
            YktKeyboard.buildMapping(List(10) { ByteArray(0) }, "0123456789")
        }.exceptionOrNull()
        assertTrue(ex is YktProtocolException)
        assertTrue(ex!!.message!!.contains("d41d8cd98f00b204e9800998ecf8427e"))
    }

    @Test
    fun `buildMapping 长度不一致抛错`() {
        val ex = runCatching {
            YktKeyboard.buildMapping(List(9) { "hash-$it" }, "abcdefghij", fakeTable())
        }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    @Test
    fun `buildMapping 未知字形抛协议异常且消息含哈希`() {
        val hashes = fakeHashes().toMutableList().also { it[0] = "unknown-glyph" }
        val ex = runCatching {
            YktKeyboard.buildMapping(hashes, fakeChars(), fakeTable())
        }.exceptionOrNull()
        assertTrue(ex is YktProtocolException)
        assertTrue(ex!!.message!!.contains("unknown-glyph"))
    }

    @Test
    fun `buildMapping 密文字符重复抛协议异常`() {
        // 10 张图覆盖 0-9，但字符集全同 → 非双射
        val ex = runCatching {
            YktKeyboard.buildMapping(fakeHashes(), "aaaaaaaaaa", fakeTable())
        }.exceptionOrNull()
        assertTrue(ex is YktProtocolException)
    }

    // ---- buildPasswordField ----

    /** 已知映射：0->q 1->2 2->L 3->! 4->^ 5->N 6->} 7->O 8->v 9->$（复刻文档 §3.4 样板键盘）。 */
    private val known: Map<Char, Char> = "q2L!^N}Ov$".mapIndexed { i, c -> ('0' + i) to c }.toMap()

    @Test
    fun `构造密文字段用例`() {
        val field = YktKeyboard.buildPasswordField("0123456789", known, "uuid-1")
        assertEquals("q2L!^N}Ov\$\$1\$uuid-1", field)
        // 乱序输入按位替换：1->2 0->q 9->$ 4->^
        assertEquals("2q\$^\$1\$uuid-2", YktKeyboard.buildPasswordField("1094", known, "uuid-2"))
    }

    @Test
    fun `密码超过16位截断到16`() {
        val pwd = "12345678901234567890" // 20 位
        val field = YktKeyboard.buildPasswordField(pwd, known, "u")
        // 截前 16 位 1234567890123456 → 2L!^N}Ov$q2L!^N} + $1$ + u
        assertEquals(16 + 3 + 1, field.length)
        assertTrue(field.startsWith("2L!^N}Ov\$q2L!^N}\$1\$u"))
    }

    @Test
    fun `非数字密码抛 IllegalArgumentException`() {
        val ex = runCatching { YktKeyboard.buildPasswordField("12ab", known, "u") }.exceptionOrNull()
        assertTrue(ex is IllegalArgumentException)
    }

    // ---- 样板自检 ----

    @Test
    fun `样板自检通过`() {
        assertTrue(
            YktKeyboard.looksLikeSampleInvariant(
                numberKeyboard = "q2L!^N}Ov$",
                uuid = "cd562e99",
                samplePassword = "q2L!^N}Ov\$\$1\$cd562e99",
            ),
        )
    }

    @Test
    fun `样板缺失或不等判漂移`() {
        assertFalse(YktKeyboard.looksLikeSampleInvariant("abc", "u", null))
        assertFalse(YktKeyboard.looksLikeSampleInvariant("abc", "u", ""))
        assertFalse(YktKeyboard.looksLikeSampleInvariant("abc", "u", "abc\$1\$other"))
        assertFalse(YktKeyboard.looksLikeSampleInvariant("abc", "u", "plainpassword"))
    }
}
