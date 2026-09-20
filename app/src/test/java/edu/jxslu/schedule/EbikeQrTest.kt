package edu.jxslu.schedule

import edu.jxslu.schedule.domain.EbikeQr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享单车骑行二维码（DESIGN §3.9 / §4.18）：URL 拼装、车号校验、
 * BitMatrix 参数、最近车号序列化 roundtrip。
 */
class EbikeQrTest {

    // ---- bikeUrl：拼装与校验 ----

    @Test
    fun `拼装合法尾部车号`() {
        assertEquals(
            "https://www.kvcoogo.com/ebike?id=100000669",
            EbikeQr.bikeUrl("669"),
        )
        // 前导零保留：尾部是字符串不是数字，"007" 不能变 7
        assertEquals(
            "https://www.kvcoogo.com/ebike?id=100000007",
            EbikeQr.bikeUrl("007"),
        )
    }

    @Test
    fun `长度不为3位一律拒绝`() {
        assertNull(EbikeQr.bikeUrl(""))
        assertNull(EbikeQr.bikeUrl("6"))
        assertNull(EbikeQr.bikeUrl("66"))
        assertNull(EbikeQr.bikeUrl("6699"))
        assertNull(EbikeQr.bikeUrl("100000669"))
    }

    @Test
    fun `非数字字符拒绝`() {
        assertNull(EbikeQr.bikeUrl("66a"))
        assertNull(EbikeQr.bikeUrl("-69"))
        assertNull(EbikeQr.bikeUrl(" 69"))
        assertNull(EbikeQr.bikeUrl("六六九"))
    }

    // ---- qrMatrix：参数与内容 ----

    @Test
    fun `矩阵尺寸与白边符合约定`() {
        val m = EbikeQr.qrMatrix(EbikeQr.bikeUrl("669")!!)
        assertEquals(EbikeQr.QR_SIZE_PX, m.width)
        assertEquals(EbikeQr.QR_SIZE_PX, m.height)
        // MARGIN=1：四角 1 个模块宽的静区应为白
        assertFalse(m.get(0, 0))
        assertFalse(m.get(m.width - 1, 0))
        assertFalse(m.get(0, m.height - 1))
        // 中央必有黑模块（定位图案区），不是全白图
        assertTrue(m.get(m.width / 2, m.height / 2) || m.get(60, 60))
    }

    @Test
    fun `不同车号矩阵不同`() {
        val a = EbikeQr.qrMatrix(EbikeQr.bikeUrl("669")!!)
        val b = EbikeQr.qrMatrix(EbikeQr.bikeUrl("670")!!)
        var diff = false
        for (y in 0 until a.height) {
            for (x in 0 until a.width) {
                if (a.get(x, y) != b.get(x, y)) {
                    diff = true
                    break
                }
            }
            if (diff) break
        }
        assertTrue("不同车号必须产出不同二维码", diff)
    }

    // ---- 最近车号：merge / roundtrip ----

    @Test
    fun `mergeRecent 倒序去重`() {
        assertEquals(listOf("669"), EbikeQr.mergeRecent(emptyList(), "669"))
        assertEquals(listOf("670", "669"), EbikeQr.mergeRecent(listOf("669"), "670"))
        // 重复生成同一车号：提前、不重复
        assertEquals(listOf("669", "670"), EbikeQr.mergeRecent(listOf("670", "669"), "669"))
    }

    @Test
    fun `mergeRecent 上限8条最旧被挤出`() {
        var list = listOf("001", "002", "003", "004", "005", "006", "007", "008")
        list = EbikeQr.mergeRecent(list, "009")
        assertEquals(8, list.size)
        assertEquals("009", list.first())
        assertFalse(list.contains("008"))
        assertTrue(list.contains("001"))
    }

    @Test
    fun `序列化 roundtrip`() {
        val list = listOf("669", "007", "100")
        assertEquals(list, EbikeQr.decodeRecent(EbikeQr.encodeRecent(list)))
    }

    @Test
    fun `脏JSON回空列表`() {
        assertEquals(emptyList<String>(), EbikeQr.decodeRecent(null))
        assertEquals(emptyList<String>(), EbikeQr.decodeRecent(""))
        assertEquals(emptyList<String>(), EbikeQr.decodeRecent("not json"))
        assertEquals(emptyList<String>(), EbikeQr.decodeRecent("""{"a":1}"""))
    }

    @Test
    fun `decode 清理越界与非法条目`() {
        // 超上限截断 + 非法条目剔除 + 去重
        val json = """["669","669","12","abc","001","002","003","004","005","006"]"""
        assertEquals(
            listOf("669", "001", "002", "003", "004", "005", "006"),
            EbikeQr.decodeRecent(json),
        )
    }

    // ---- 扫完即焚：待焚毁 key 编码 / 解析 / 合并 ----

    @Test
    fun `待焚毁key 编码与解析 roundtrip`() {
        // MediaStore uri（API 29+）与文件路径（26–28）两条通道
        val media = EbikeQr.pendingMediaKey("content://media/external/images/media/123")
        assertEquals(
            EbikeQr.PendingKind.MediaStore to "content://media/external/images/media/123",
            EbikeQr.parsePendingKey(media),
        )
        val file = EbikeQr.pendingFileKey("/storage/emulated/0/Pictures/水贝贝/ebike-100000669.jpg")
        assertEquals(
            EbikeQr.PendingKind.FilePath to "/storage/emulated/0/Pictures/水贝贝/ebike-100000669.jpg",
            EbikeQr.parsePendingKey(file),
        )
    }

    @Test
    fun `待焚毁key 未知前缀与空目标拒绝`() {
        // 删错文件比漏删一张码严重：脏 key 一律丢弃
        assertNull(EbikeQr.parsePendingKey("x:content://media/1"))
        assertNull(EbikeQr.parsePendingKey("plain-path"))
        assertNull(EbikeQr.parsePendingKey(""))
        assertNull(EbikeQr.parsePendingKey("m:"))
        assertNull(EbikeQr.parsePendingKey("f:"))
    }

    @Test
    fun `mergePendingDelete 去重且不膨胀`() {
        var set = EbikeQr.mergePendingDelete(emptySet(), "m:1")
        assertEquals(setOf("m:1"), set)
        set = EbikeQr.mergePendingDelete(set, "m:1")
        assertEquals(setOf("m:1"), set)
        set = EbikeQr.mergePendingDelete(set, "f:/a/b.jpg")
        assertEquals(setOf("m:1", "f:/a/b.jpg"), set)
    }

    @Test
    fun `mergePendingDelete 超上限兜底不超32`() {
        var set = emptySet<String>()
        repeat(EbikeQr.PENDING_DELETE_LIMIT + 8) { i ->
            set = EbikeQr.mergePendingDelete(set, "m:$i")
        }
        assertEquals(EbikeQr.PENDING_DELETE_LIMIT, set.size)
        assertTrue(set.contains("m:39")) // 最新保留
        assertFalse(set.contains("m:0")) // 兜底挤掉旧记录
    }
}
