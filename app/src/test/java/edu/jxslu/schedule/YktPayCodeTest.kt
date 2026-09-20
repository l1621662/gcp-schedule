package edu.jxslu.schedule

import edu.jxslu.schedule.domain.YktPayCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 付款码矩阵（DESIGN §4.19）：QR / Code128 尺寸参数与内容非空校验。
 * 内容一致性由 Python 侧 pyzbar 自检与真机扫码覆盖，这里钉生成参数。
 */
class YktPayCodeTest {

    private val code = "40101234567890123456" // 20 位数字（服务端口径）

    @Test
    fun `QR 尺寸与白边符合约定`() {
        val m = YktPayCode.matrices(code).qr
        assertEquals(YktPayCode.QR_SIZE_PX, m.width)
        assertEquals(YktPayCode.QR_SIZE_PX, m.height)
        // MARGIN=1：四角 1 模块静区为白
        assertFalse(m.get(0, 0))
        assertFalse(m.get(m.width - 1, 0))
        assertFalse(m.get(0, m.height - 1))
        // 有黑模块（非全白图）
        var black = 0
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) black++
        assertTrue(black > 0)
    }

    @Test
    fun `Code128 尺寸符合约定`() {
        val m = YktPayCode.matrices(code).barcode
        assertEquals(YktPayCode.BARCODE_WIDTH_PX, m.width)
        assertEquals(YktPayCode.BARCODE_HEIGHT_PX, m.height)
        var black = 0
        for (y in 0 until m.height) for (x in 0 until m.width) if (m[x, y]) black++
        assertTrue(black > 0)
    }
}
