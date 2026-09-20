package edu.jxslu.schedule.qiekj

import edu.jxslu.schedule.data.qiekj.ApiEnvelope
import edu.jxslu.schedule.data.qiekj.BalanceData
import edu.jxslu.schedule.data.qiekj.LoginData
import edu.jxslu.schedule.data.qiekj.OrderDetailData
import edu.jxslu.schedule.data.qiekj.OrderHistoryItem
import edu.jxslu.schedule.data.qiekj.QiekjJson
import edu.jxslu.schedule.data.qiekj.TokenExpiredException
import edu.jxslu.schedule.domain.PromotionLine
import edu.jxslu.schedule.domain.UnlockResult
import edu.jxslu.schedule.domain.calculateActualCost
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 响应包与脏数据容错测试（DESIGN §4.10 决策 1）：
 * 服务端会把 `data` 回成空串、数字字段回成裸数字，解析必须不炸且语义正确。
 */
class QiekjModelsTest {

    // ── ApiEnvelope ──

    @Test
    fun requireData_有data直接返回() {
        assertEquals("ok", ApiEnvelope(data = "ok").requireData())
    }

    @Test(expected = IllegalStateException::class)
    fun requireData_空data抛业务错误() {
        ApiEnvelope<String>(code = 400, msg = "参数错误").requireData()
    }

    @Test
    fun throwIfFailed_0与200都算成功() {
        ApiEnvelope<String>(code = 0).throwIfFailed()
        ApiEnvelope<String>(code = 200).throwIfFailed()
        ApiEnvelope<String>(code = null).throwIfFailed()
    }

    @Test(expected = TokenExpiredException::class)
    fun throwIfFailed_401抛TokenExpired() {
        ApiEnvelope<String>(code = 401, msg = "token invalid").throwIfFailed()
    }

    @Test
    fun isTokenExpired_按码与文案启发式判定() {
        assertTrue(TokenExpiredException.isTokenExpired(401, null))
        assertTrue(TokenExpiredException.isTokenExpired(403, null))
        assertTrue(TokenExpiredException.isTokenExpired(null, "请先登录"))
        assertTrue(TokenExpiredException.isTokenExpired(null, "Token已过期"))
        assertTrue(TokenExpiredException.isTokenExpired(null, "token 无效"))
        assertFalse(TokenExpiredException.isTokenExpired(null, "今日次数已用完"))
        assertFalse(TokenExpiredException.isTokenExpired(0, "成功"))
    }

    // ── 脏数据容错 ──

    @Test
    fun envelope_data为空串时EmptyData正常解码() {
        val env = QiekjJson.json.decodeFromString<ApiEnvelope<edu.jxslu.schedule.data.qiekj.EmptyData>>(
            """{"code":0,"msg":"ok","data":""}""",
        )
        assertEquals(0, env.code)
        assertEquals(edu.jxslu.schedule.data.qiekj.EmptyData(), env.data)
    }

    @Test
    fun envelope_data为对象或null时EmptyData正常解码() {
        QiekjJson.json.decodeFromString<ApiEnvelope<edu.jxslu.schedule.data.qiekj.EmptyData>>(
            """{"code":0,"data":{"anything":1}}""",
        )
        QiekjJson.json.decodeFromString<ApiEnvelope<edu.jxslu.schedule.data.qiekj.EmptyData>>(
            """{"code":0,"data":null}""",
        )
    }

    @Test
    fun loginData_token为裸数字时按字符串读() {
        val data = QiekjJson.json.decodeFromString<ApiEnvelope<LoginData>>(
            """{"code":0,"data":{"token":123456}}""",
        )
        assertEquals("123456", data.requireData().token)
    }

    @Test
    fun balanceData_分转元与未知字段忽略() {
        val data = QiekjJson.json.decodeFromString<ApiEnvelope<BalanceData>>(
            """{"code":0,"data":{"tokenCoin":"1234","integral":500,"extraField":"ignored"}}""",
        )
        val balance = data.requireData()
        assertEquals("12.34", balance.ticketText)
        assertEquals("500", balance.pointsText)
    }

    @Test
    fun balanceData_空值显示占位符() {
        val balance = BalanceData()
        assertEquals("-", balance.ticketText)
        assertEquals("-", balance.pointsText)
    }

    // ── 订单快照序列化往返 ──

    @Test
    fun orderHistoryItem_roundTrip() {
        val item = OrderHistoryItem(
            orderNo = "NO001",
            orderId = "ID001",
            goodsName = "教学楼饮水机",
            originPrice = "0.30",
            ticketCost = "0.20",
            integralCost = "0.10",
            otherPromotions = listOf(PromotionLine(promotionType = 12, discountAmount = "0.05")),
            completedAt = 1_726_570_000_000L,
        )
        val json = QiekjJson.json.encodeToString(
            ListSerializer(OrderHistoryItem.serializer()),
            listOf(item),
        )
        val restored = QiekjJson.json.decodeFromString(
            ListSerializer(OrderHistoryItem.serializer()),
            json,
        )
        assertEquals(listOf(item), restored)
    }

    // ── 金额计算 ──

    private fun result(origin: String, integral: String, ticket: String = "-") = UnlockResult(
        orderNo = "NO", orderId = "ID", originPrice = origin,
        ticketCost = ticket, integralCost = integral, completedAt = 0L,
    )

    @Test
    fun actualCost_浮点误差回归_0点12减0点11等于0点01() {
        assertEquals("0.01", calculateActualCost(result("0.12", "0.11")))
    }

    @Test
    fun actualCost_全免与超减夹零() {
        assertEquals("0.00", calculateActualCost(result("0.12", "0.12")))
        assertEquals("0.00", calculateActualCost(result("0.05", "0.11")))
    }

    @Test
    fun actualCost_原价不可解析时原样返回() {
        assertEquals("-", calculateActualCost(result("-", "0.10")))
    }

    // ── 服务端账单口径（DESIGN §4.10，2026-09-20 真机实测回填） ──

    @Test
    fun orderDetail_真实响应形态解析() {
        // 脱敏后的 order/detail 真实结构：后付单实付 0.00，平台自动优惠 0.09
        val data = QiekjJson.json.decodeFromString<ApiEnvelope<OrderDetailData>>(
            """
            {"code":0,"msg":"成功","data":{
              "orderNo":"NO","orderType":3,"orderStatus":2,
              "markPrice":"0.09","payPrice":"0.00","payType":15,"payTypeName":"支付宝-代扣",
              "tokenCoinDiscount":"0.09",
              "tradeOrderItem":[{"originPrice":"0.09","realPrice":"0.00"}],
              "promotionList":[{"promotionType":4,"discountAmount":"0.09","subsidyAmount":"0.00"}]
            }}
            """.trimIndent(),
        )
        val detail = data.requireData()
        assertEquals("0.00", detail.payPrice)
        assertEquals("支付宝-代扣", detail.payTypeName)
        assertEquals("0.09", detail.tokenCoinDiscount)
        assertEquals("0.09", detail.tradeOrderItem.first().originPrice)
        assertEquals("0.00", detail.tradeOrderItem.first().realPrice)
        assertEquals(4, detail.promotionList.first().promotionType)
    }

    @Test
    fun actualCost_服务端realPrice优先于本地公式() {
        // 真实场景：原价 0.09、券抵 0.09、实付 0.00 —— 服务端口径就是 0.00
        val r = result("0.09", "-").copy(realPrice = "0.00", payTypeName = "支付宝-代扣")
        assertEquals("0.00", calculateActualCost(r))
        // 服务端 realPrice 与本地公式结果不同时，以服务端为准
        val r2 = result("0.30", "-").copy(realPrice = "0.10")
        assertEquals("0.10", calculateActualCost(r2))
    }

    @Test
    fun actualCost_realPrice缺失回退本地公式() {
        assertEquals("0.30", calculateActualCost(result("0.30", "-").copy(realPrice = null)))
    }

    @Test
    fun orderHistoryItem_旧快照无新字段可解码() {
        // 2026-09-20 之前的快照没有 realPrice/payTypeName/tokenCoinDiscount，decode 必须兼容
        val legacy = """[{"orderNo":"NO","orderId":"ID","goodsName":"设备","originPrice":"0.09",
            "ticketCost":"0.09","integralCost":"-","otherPromotions":[],"completedAt":100}]"""
        val restored = QiekjJson.json.decodeFromString(
            ListSerializer(OrderHistoryItem.serializer()),
            legacy,
        )
        assertEquals(1, restored.size)
        assertEquals(null, restored[0].realPrice)
        assertEquals(null, restored[0].payTypeName)
    }

    @Test
    fun orderHistoryItem_新字段往返() {
        val item = OrderHistoryItem(
            orderNo = "NO", orderId = "ID", goodsName = "设备",
            originPrice = "0.09", ticketCost = "-", integralCost = "-",
            completedAt = 0L, realPrice = "0.00",
            payTypeName = "支付宝-代扣", tokenCoinDiscount = "0.09",
        )
        val json = QiekjJson.json.encodeToString(
            ListSerializer(OrderHistoryItem.serializer()),
            listOf(item),
        )
        val restored = QiekjJson.json.decodeFromString(
            ListSerializer(OrderHistoryItem.serializer()),
            json,
        )
        assertEquals(listOf(item), restored)
    }
}
