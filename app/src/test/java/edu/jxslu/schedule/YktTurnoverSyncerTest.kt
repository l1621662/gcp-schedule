package edu.jxslu.schedule

import edu.jxslu.schedule.data.ykt.YktTurnover
import edu.jxslu.schedule.data.ykt.YktTurnoverSyncer.Companion.toEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 增量同步器的纯逻辑部分（DESIGN §4.19 L3）：jndatetime 解析、orderId 合成键兜底。
 * 「遇已入库 orderId 即停」的翻页逻辑依赖 Room/网络，由真机验收覆盖（本项目对
 * Room 层无 Robolectric 先例，口径同 AGENTS.md 单测清单）。
 */
class YktTurnoverSyncerTest {

    private fun record(
        time: String,
        fen: Long = 800,
        orderId: String? = "PO-1",
    ) = YktTurnover(
        jndatetimeStr = time,
        effectdateStr = null,
        tranamtFen = fen,
        income = false,
        turnoverType = "消费",
        remark = null,
        resume = null,
        balanceAfterFen = 1195,
        locationName = "第一食堂",
        orderId = orderId,
    )

    @Test
    fun `标准时间解析为 epoch 毫秒`() {
        val entity = record("2026-09-20 18:50:11").toEntity()
        assertEquals(
            java.time.LocalDateTime.of(2026, 9, 20, 18, 50, 11)
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            entity.jndatetime,
        )
        assertEquals("PO-1", entity.orderId)
        assertEquals(800L, entity.tranamtFen)
        assertEquals(false, entity.income)
    }

    @Test
    fun `非法时间解析失败落 0 沉底`() {
        val entity = record("2026/09/20 18:50").toEntity()
        assertEquals(0L, entity.jndatetime)
        // 原文保留（分组/展示不依赖解析成功）
        assertEquals("2026/09/20 18:50", entity.jndatetimeStr)
    }

    @Test
    fun `orderId 为空用时间加金额合成键`() {
        val entity = record("2026-09-20 18:50:11", fen = 800, orderId = null).toEntity()
        assertEquals("syn_2026-09-20 18:50:11_800", entity.orderId)
    }

    @Test
    fun `余额快照可空`() {
        val entity = record("2026-09-20 18:50:11").copy(balanceAfterFen = null).toEntity()
        assertNull(entity.balanceAfterFen)
    }

    @Test
    fun `收入方向保留`() {
        val entity = record("2026-09-19 10:00:00", fen = 10000, orderId = "PO-2")
            .copy(income = true).toEntity()
        assertTrue(entity.income)
    }

    private fun assertNull(actual: Any?) {
        org.junit.Assert.assertNull(actual)
    }
}
