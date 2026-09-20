package edu.jxslu.schedule.data.qiekj

import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import edu.jxslu.schedule.domain.DiagnosisResult
import edu.jxslu.schedule.domain.PromotionLine
import edu.jxslu.schedule.domain.UnlockResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

class NotLoggedInException(message: String = "请先登录") : Exception(message)

/**
 * 胖乖生活仓库：登录态、余额、历史设备与开水全流程（DESIGN §4.10）。
 *
 * 开水调用链的顺序以参考实现 light-life unlockDevice 为准，**不可调整**：
 * latestUsed → skus → sync(预检，可失败) → details(imei) → 积分风控 → 开后付 →
 * 位置风控 → unlock → 轮询 sync(等 workStatus 离开 2) → afterPay/creating → order/detail →
 * 写本地订单快照。前置步骤里风控/后付/位置任一失败都阻断；预检失败仅记录不阻断。
 */
class QiekjRepository(
    private val tokenStore: QiekjTokenStore,
    private val orderHistoryStore: QiekjOrderHistoryStore,
) {
    private val api: QiekjApi
    private val apiClient: OkHttpClient

    init {
        // BASIC 级日志只打请求行不打 header，token/sign 不会进 Logcat（DESIGN 5 隐私要求）
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(QiekjHeaderInterceptor { tokenStore.readToken() })
            .addInterceptor(logging)
            .build()
        apiClient = client
        api = Retrofit.Builder()
            .baseUrl(QiekjApiConfig.BASE_URL)
            .client(client)
            .addConverterFactory(QiekjJson.json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(QiekjApi::class.java)
    }

    // ── 登录态 ──

    fun localToken(): String? = tokenStore.readToken()
    fun readPhone(): String? = tokenStore.readPhone()
    fun saveToken(token: String) = tokenStore.saveToken(token)
    fun savePhone(phone: String) = tokenStore.savePhone(phone)

    /** 退出：token 与快照一并清（对齐参考实现；快照依赖登录态存在意义不大）。 */
    fun logout() {
        tokenStore.clear()
        orderHistoryStore.clearAll()
    }

    private fun requireToken(): String =
        tokenStore.readToken()?.takeIf { it.isNotBlank() } ?: throw NotLoggedInException()

    /** Token 粘贴登录的唯一校验方式：直接查余额，余额能查通即 token 有效。 */
    suspend fun validateToken() {
        api.queryBalance(requireToken()).throwIfFailed()
    }

    // ── 登录 / 资产 / 设备 ──

    suspend fun sendCode(phone: String) {
        api.sendCode(phone = phone).throwIfFailed()
    }

    suspend fun login(phone: String, code: String): String {
        val token = api.login(phone = phone, verify = code).requireData().token
            ?: error("登录成功但未返回 token")
        tokenStore.saveToken(token)
        return token
    }

    suspend fun queryBalance(): BalanceData {
        val resp = api.queryBalance(requireToken())
        resp.throwIfFailed()
        return resp.requireData()
    }

    suspend fun latestDevices(): List<DeviceItem> {
        val resp = api.getLatestUsed(token = requireToken())
        resp.throwIfFailed()
        return resp.data ?: emptyList()
    }

    fun orderHistory(): List<OrderHistoryItem> = orderHistoryStore.list()

    /**
     * 【临时诊断，验证后删除】用历史 orderId 回放 order/detail，返回原始响应体并落本地诊断档。
     * 只读接口，不产生新订单；用于排查「实付为0」时确认 promotionList 真实语义。
     * 原始响应含订单号/金额，只落设备 prefs，不进日志、不进仓库。
     */
    suspend fun rawOrderDetailResponse(orderId: String): String? = withContext(Dispatchers.IO) {
        val token = requireToken()
        val form = okhttp3.FormBody.Builder().add("orderId", orderId).add("token", token).build()
        val request = okhttp3.Request.Builder()
            .url(QiekjApiConfig.BASE_URL + "order/detail")
            .post(form)
            .build()
        apiClient.newCall(request).execute().use { it.body?.string() }
    }

    /** 【临时诊断，验证后删除】诊断结果落盘（普通 prefs，run-as 可导出）。 */
    fun saveDiagPayload(key: String, value: String, context: android.content.Context) {
        context.applicationContext
            .getSharedPreferences("qiekj_diag", android.content.Context.MODE_PRIVATE)
            .edit().putString(key, value).apply()
    }

    // ── 开水全流程 ──

    /**
     * 一键开水。挂在调用方协程上（页面级生命周期），轮询期间协程取消即停止观察，
     * 但服务端会继续按 165s 超时自动关阀结算，不会出现"关不上水"。
     */
    suspend fun unlockDevice(
        device: DeviceItem,
        usePoints: Boolean,
        onStep: suspend (String) -> Unit,
    ): UnlockResult = withContext(Dispatchers.IO) {
        val token = requireToken()
        val goodsId = device.effectiveGoodsId ?: error("设备缺少 goodsId")

        onStep("正在获取 SKU")
        val skuId = api.goodsid2sku(goodsId = goodsId, token = token).requireData()
            .firstOrNull()?.skuId ?: error("未获取到 skuId")

        // 预检失败不阻断流程：设备忙/离线等情况在 unlock 或轮询阶段有更准确的报错
        onStep("正在检测设备状态")
        runCatching { api.syncWater(skuId = skuId, token = token) }

        onStep("正在获取 IMEI")
        val imei = api.getImei(goodsId = goodsId, token = token).requireData().imei
            ?: error("未获取到 imei")

        onStep("正在检查积分")
        api.checkUserIsRisk(token).throwIfFailed()

        onStep("正在开通后付")
        api.addUserAfterPayChannel(token = token).throwIfFailed()

        onStep("正在检查位置风控")
        api.isCheckLocation(imei = imei, token = token).throwIfFailed()

        onStep("正在启动出水")
        val promotions = if (usePoints) PROMOTIONS_WITH_POINTS else PROMOTIONS_WITHOUT_POINTS
        val unlock = api.unlockWater(skuId = skuId, promotions = promotions, token = token)
            .requireData()
        val orderNo = unlock.orderNo ?: error("未获取到订单号")
        onStep("设备已启动，等待使用结束")

        delay(2_000)

        // 轮询：workStatus==2 表示出水中；必须记录 everWorked，
        // 否则设备从未启动时会被误判为"用完了"（对齐参考实现）。
        var lastStatus: SyncData
        var everWorked = false
        var attempts = 0
        do {
            lastStatus = diagnosed(
                step = "设备状态轮询",
            ) { api.syncWater(skuId = skuId, token = token).requireData() }
            if (lastStatus.workStatus == 2) everWorked = true
            attempts++
            if (attempts >= MAX_POLL_ATTEMPTS) {
                throw diagnosedOf(Exception("设备使用超时（${MAX_POLL_ATTEMPTS} 秒）"), "设备状态轮询")
            }
            onStep("设备工作中，正在等待完成")
            delay(1_000)
        } while (lastStatus.workStatus == 2)

        if (!everWorked) {
            throw diagnosedOf(Exception("设备未启动或未响应，可能已离线"), "设备状态轮询")
        }

        onStep("正在创建后付订单")
        val finalOrderNo = lastStatus.identify ?: orderNo
        val orderId = api.createAfterPay(orderNo = finalOrderNo, token = token)
            .requireData().orderId ?: error("未获取到 orderId")

        onStep("正在查询订单详情")
        val detail = api.orderDetail(orderId = orderId, token = token).requireData()
        val ticketCost = detail.promotionList.firstOrNull { it.promotionType == 4 }
            ?.discountAmount ?: "-"
        val integralCost = detail.promotionList.firstOrNull { it.promotionType == 8 }
            ?.discountAmount ?: "-"
        val otherPromotions = detail.promotionList
            .filter { it.promotionType != 4 && it.promotionType != 8 }
            .map { PromotionLine(promotionType = it.promotionType, discountAmount = it.discountAmount) }

        val result = UnlockResult(
            orderNo = finalOrderNo,
            orderId = orderId,
            originPrice = detail.tradeOrderItem.firstOrNull()?.originPrice ?: "-",
            ticketCost = ticketCost,
            integralCost = integralCost,
            otherPromotions = otherPromotions,
            completedAt = System.currentTimeMillis(),
        )
        orderHistoryStore.add(
            OrderHistoryItem(
                orderNo = result.orderNo,
                orderId = result.orderId,
                goodsName = device.goodsName.ifBlank { "未命名设备" },
                originPrice = result.originPrice,
                ticketCost = result.ticketCost,
                integralCost = result.integralCost,
                otherPromotions = result.otherPromotions,
                completedAt = result.completedAt,
            ),
        )
        result
    }

    // ── 错误包装 ──

    private suspend fun <T> diagnosed(step: String, block: suspend () -> T): T =
        try {
            block()
        } catch (e: UnlockException) {
            throw e
        } catch (e: Throwable) {
            throw diagnose(e, step)
        }

    private fun diagnose(error: Throwable, step: String): Nothing {
        val code = if (error.message?.matches(Regex("HTTP \\d+.*")) == true) {
            error.message?.substringAfter("HTTP ")?.substringBefore(":")?.trim()?.toIntOrNull()
        } else null
        val diagnosis = QiekjErrorDiagnosis.diagnose(code, error.message, step)
        throw UnlockException(diagnosis.primaryReason, diagnosis, error)
    }

    private fun diagnosedOf(error: Exception, step: String): UnlockException {
        val diagnosis: DiagnosisResult = QiekjErrorDiagnosis.diagnose(null, error.message, step)
        return UnlockException(diagnosis.primaryReason, diagnosis, error)
    }

    private companion object {
        /** 轮询上限与参考实现一致：1s × 300 次；UI 层另有 165s 自动结算兜底。 */
        const val MAX_POLL_ATTEMPTS = 300

        /**
         * promotions 两套常量取自 light-life unlockDevice，字段原样保留：
         * 带 "-6/-7/-8"（用积分）与不带 "-8"（不用积分）两档。
         */
        const val PROMOTIONS_WITH_POINTS =
            """[{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-6"},{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-7"},{"assetId":"0","oldPromotionId":"0","orgId":"0","promotionId":"0","promotionType":"8"}]"""
        const val PROMOTIONS_WITHOUT_POINTS =
            """[{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-6"},{"assetId":"0","oldPromotionId":"","orgId":"0","promotionId":"0","promotionType":"-7"}]"""
    }
}
