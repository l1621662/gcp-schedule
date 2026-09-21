package edu.jxslu.schedule

import edu.jxslu.schedule.domain.YktRechargeSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

/**
 * 充值下单签名（DESIGN §4.19「充值」）。
 *
 * 期望值用同一 SHA256 但**独立实现**（直接按规则手工拼串），
 * 不复用 [YktRechargeSign.signOf]，避免同源实现自证。
 */
class YktRechargeSignTest {

    /** 手工拼串 + JDK SHA256（独立于被测实现）。 */
    private fun expect(params: Map<String, String>): String {
        val concat = params.entries
            .filter { it.key != "SIGN" && it.key != "SECRET_KEY" && it.value.isNotEmpty() }
            .sortedBy { it.key }
            .joinToString("") { "${it.key}=${it.value}&" } + "SECRET_KEY=" + YktRechargeSign.SECRET_KEY
        val bytes = MessageDigest.getInstance("SHA-256").digest(concat.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.uppercase()
    }

    @Test
    fun `signed 注入元参数且 SIGN 可独立复算`() {
        val business = mapOf(
            "feeitemid" to "401",
            "tranamt" to "0.01",
            "yktcard" to "24000001",
        )
        val out = YktRechargeSign.signed(
            business,
            timestamp = "20260921120000000",
            nonce = "abc123xyz09",
        )
        assertEquals("401", out["feeitemid"])
        assertEquals(YktRechargeSign.APP_ID_VALUE, out["APP_ID"])
        assertEquals("20260921120000000", out["TIMESTAMP"])
        assertEquals("abc123xyz09", out["NONCE"])
        assertEquals("SHA256", out["SIGN_TYPE"])
        assertEquals(expect(out), out["SIGN"])
        // 入参不被修改
        assertEquals(3, business.size)
        assertTrue("SIGN" !in business)
    }

    @Test
    fun `空值参数不参与拼接而零值字符串保留`() {
        val withEmpty = mapOf("a" to "1", "b" to "", "z" to "0")
        val out = YktRechargeSign.signed(withEmpty, timestamp = "T", nonce = "N")
        assertEquals(expect(out), out["SIGN"])

        // 空值去掉后与显式缺席同串
        val withoutEmpty = mapOf("a" to "1", "z" to "0")
        val out2 = YktRechargeSign.signed(withoutEmpty, timestamp = "T", nonce = "N")
        assertEquals(out["SIGN"], out2["SIGN"])
    }

    @Test
    fun `key 排序与末尾 SECRET_KEY 无分隔符`() {
        // 打乱入参顺序，结果必须一致
        val a = YktRechargeSign.signed(
            mapOf("feeitemid" to "401", "tranamt" to "0.01"),
            timestamp = "T", nonce = "N",
        )
        val b = YktRechargeSign.signed(
            mapOf("tranamt" to "0.01", "feeitemid" to "401"),
            timestamp = "T", nonce = "N",
        )
        assertEquals(a["SIGN"], b["SIGN"])

        // 已含业务键名恰好叫 SIGN_TYPE 的场景：merged 覆盖为 SHA256，算法不崩
        val c = YktRechargeSign.signed(mapOf("SIGN_TYPE" to "MD5"), timestamp = "T", nonce = "N")
        assertEquals("SHA256", c["SIGN_TYPE"])
        assertEquals(expect(c), c["SIGN"])
    }

    @Test
    fun `timestamp 14+3 位格式`() {
        val ts = YktRechargeSign.buildTimestamp(now = 0L)
        // epoch 0 在东八区是 19700101
        assertEquals(17, ts.length)
        assertTrue(ts.take(4).toInt() in 1969..1971)
        val ms = ts.takeLast(3)
        assertTrue(ms.all { it.isDigit() })
    }

    @Test
    fun `nonce 随机且不同次不同`() {
        val a = YktRechargeSign.buildNonce()
        val b = YktRechargeSign.buildNonce()
        assertEquals(11, a.length)
        assertTrue(a.all { it in 'a'..'z' || it in '0'..'9' })
        assertNotEquals(a, b)
    }

    @Test
    fun `签名对参数变化敏感`() {
        val s1 = YktRechargeSign.signed(mapOf("tranamt" to "0.01"), "T", "N")["SIGN"]
        val s2 = YktRechargeSign.signed(mapOf("tranamt" to "0.02"), "T", "N")["SIGN"]
        val s3 = YktRechargeSign.signed(mapOf("tranamt" to "0.01"), "T2", "N")["SIGN"]
        assertNotEquals(s1, s2)
        assertNotEquals(s1, s3)
    }
}
