package edu.jxslu.schedule.ui.water

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.qiekj.BalanceData
import edu.jxslu.schedule.data.qiekj.DeviceItem
import edu.jxslu.schedule.data.qiekj.OrderHistoryItem
import edu.jxslu.schedule.data.qiekj.QiekjRepository
import edu.jxslu.schedule.data.qiekj.TokenExpiredException
import edu.jxslu.schedule.data.qiekj.UnlockException
import edu.jxslu.schedule.domain.UnlockFlowState
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

data class WaterUiState(
    // ── 登录 ──
    val loggedIn: Boolean = false,
    val phone: String = "",
    val code: String = "",
    val phoneError: String? = null,
    val sendingCode: Boolean = false,
    val loggingIn: Boolean = false,
    val showTokenLogin: Boolean = false,
    val tokenLoginInput: String = "",
    val tokenLoggingIn: Boolean = false,
    // ── 资产 / 设备 ──
    val balance: BalanceData? = null,
    val devices: List<DeviceItem> = emptyList(),
    val selectedDevice: DeviceItem? = null,
    val loadingDevices: Boolean = false,
    val loadingBalance: Boolean = false,
    // ── 开水流程 ──
    val flow: UnlockFlowState = UnlockFlowState.Idle,
    val usePoints: Boolean = true,
    // ── 订单 ──
    val orderHistory: List<OrderHistoryItem> = emptyList(),
)

sealed interface WaterEvent {
    /** 一次性结果提示（登录/验证码/出水超时/查询失败…）；[tone] 决定页面提示卡的语气。 */
    data class Notice(val text: String, val tone: NoticeTone) : WaterEvent
}

/**
 * 胖乖开水页状态（DESIGN §4.10）。
 *
 * 开水流程生命周期与参考实现对齐：
 * - Mutex 防重入，进行中再点直接忽略；
 * - Working 阶段计时；165s（AUTO_SETTLE_SECONDS）仍在 Working → 视为服务端超时自动关阀，
 *   状态归 Idle 并刷新余额（此时轮询协程仍在后台收尾，完成后 Success 会重新落卡）；
 * - TokenExpiredException 统一清登录态并弹 Toast，不进 Failed 状态机。
 */
class WaterViewModel(private val repo: QiekjRepository) : ViewModel() {

    private val unlockMutex = Mutex()
    private var unlockJob: Job? = null
    private var timerJob: Job? = null
    private var timeoutJob: Job? = null
    private var codeSentAt = 0L

    private val _uiState = MutableStateFlow(
        WaterUiState(
            loggedIn = repo.localToken() != null,
            phone = repo.readPhone() ?: "",
            orderHistory = if (repo.localToken() != null) repo.orderHistory() else emptyList(),
        ),
    )
    val uiState: StateFlow<WaterUiState> = _uiState.asStateFlow()

    private val _events = Channel<WaterEvent>(Channel.BUFFERED)

    /**
     * 一次性提示事件。页面（开水页 / 今日页开水卡）负责收集并展示——
     * 两边都必须有收集者，否则事件会留在缓冲里直到下一次有人收，
     * 或随 ViewModel 一起消失（今日页此前就不收，出水超时与登录失效在那里是静默的）。
     */
    val events = _events.receiveAsFlow()

    /** 提示（默认中性语气）。失败路径一律给 [NoticeTone.Error]，见各调用点。 */
    private fun notice(text: String, tone: NoticeTone = NoticeTone.Info) =
        _events.trySend(WaterEvent.Notice(text, tone))

    init {
        if (_uiState.value.loggedIn) {
            refreshBalance()
            refreshDevices()
        }
    }

    // ── 登录 ──

    fun updatePhone(value: String) = _uiState.update {
        val digits = value.filter(Char::isDigit).take(11)
        it.copy(phone = digits, phoneError = if (digits.isNotEmpty() && digits.length < 11) "请输入 11 位手机号" else null)
    }

    fun updateCode(value: String) = _uiState.update { it.copy(code = value.filter(Char::isDigit).take(6)) }

    fun toggleTokenLogin() = _uiState.update {
        it.copy(showTokenLogin = !it.showTokenLogin, tokenLoginInput = "")
    }

    fun updateTokenLoginInput(value: String) = _uiState.update { it.copy(tokenLoginInput = value) }

    fun sendCode() = viewModelScope.launch {
        val phone = _uiState.value.phone.trim()
        val elapsed = System.currentTimeMillis() - codeSentAt
        if (elapsed < 60_000) {
            notice("验证码已发送，请 ${(60 - elapsed / 1000).toInt()} 秒后再试", NoticeTone.Warning)
            return@launch
        }
        if (phone.length != 11) {
            _uiState.update { it.copy(phoneError = "请输入 11 位手机号") }
            return@launch
        }
        runCatching {
            _uiState.update { it.copy(sendingCode = true) }
            repo.sendCode(phone)
        }.onSuccess {
            codeSentAt = System.currentTimeMillis()
            notice("验证码已发送", NoticeTone.Success)
        }.onFailure {
            notice(it.message ?: "验证码发送失败", NoticeTone.Error)
        }
        _uiState.update { it.copy(sendingCode = false) }
    }

    fun login() = viewModelScope.launch {
        val s = _uiState.value
        if (s.phone.length != 11) {
            _uiState.update { it.copy(phoneError = "请输入 11 位手机号") }
            return@launch
        }
        if (s.code.isBlank()) {
            notice("请输入验证码", NoticeTone.Warning)
            return@launch
        }
        runCatching {
            _uiState.update { it.copy(loggingIn = true) }
            repo.login(s.phone, s.code)
        }.onSuccess {
            repo.savePhone(s.phone)
            onLoginSuccess("登录成功")
        }.onFailure {
            _uiState.update { it.copy(loggingIn = false) }
            notice(it.message ?: "登录失败", NoticeTone.Error)
        }
    }

    fun loginWithToken() = viewModelScope.launch {
        val token = _uiState.value.tokenLoginInput.trim()
        if (token.isBlank()) {
            notice("请输入 Token", NoticeTone.Warning)
            return@launch
        }
        runCatching {
            _uiState.update { it.copy(tokenLoggingIn = true) }
            repo.saveToken(token)
            repo.validateToken()
        }.onSuccess {
            onLoginSuccess("登录成功")
        }.onFailure {
            repo.logout()
            _uiState.update { it.copy(tokenLoggingIn = false) }
            notice(it.message ?: "Token 无效或已过期", NoticeTone.Error)
        }
    }

    private fun onLoginSuccess(message: String) {
        _uiState.update {
            it.copy(
                loggedIn = true,
                loggingIn = false,
                tokenLoggingIn = false,
                showTokenLogin = false,
                tokenLoginInput = "",
                code = "",
                phoneError = null,
                orderHistory = repo.orderHistory(),
            )
        }
        notice(message, NoticeTone.Success)
        refreshBalance()
        refreshDevices()
    }

    fun logout() {
        cancelFlowJobs()
        repo.logout()
        _uiState.update { WaterUiState() }
        notice("已退出胖乖登录")
    }

    private fun handleTokenExpired() {
        cancelFlowJobs()
        repo.logout()
        _uiState.update { WaterUiState() }
        notice("登录已失效，请重新登录", NoticeTone.Warning)
    }

    // ── 资产 / 设备 ──

    fun refreshBalance() = viewModelScope.launch {
        if (!_uiState.value.loggedIn) return@launch
        runCatching {
            _uiState.update { it.copy(loadingBalance = true) }
            repo.queryBalance()
        }.onSuccess { balance ->
            _uiState.update { it.copy(balance = balance, loadingBalance = false) }
        }.onFailure {
            _uiState.update { it.copy(loadingBalance = false) }
            if (it is TokenExpiredException) {
                handleTokenExpired()
            } else {
                notice(it.message ?: "查询余额失败", NoticeTone.Error)
            }
        }
    }

    fun refreshDevices() = viewModelScope.launch {
        if (!_uiState.value.loggedIn) return@launch
        runCatching {
            _uiState.update { it.copy(loadingDevices = true) }
            repo.latestDevices()
        }.onSuccess { devices ->
            _uiState.update { s ->
                // 默认选第一台；原选中的设备若还在列表里则保持
                val keep = s.selectedDevice?.let { cur -> devices.firstOrNull { it.effectiveGoodsId == cur.effectiveGoodsId } }
                s.copy(devices = devices, loadingDevices = false, selectedDevice = keep ?: devices.firstOrNull())
            }
        }.onFailure {
            _uiState.update { it.copy(loadingDevices = false) }
            if (it is TokenExpiredException) {
                handleTokenExpired()
            } else {
                notice(it.message ?: "查询历史设备失败", NoticeTone.Error)
            }
        }
    }

    fun selectDevice(device: DeviceItem) = _uiState.update { it.copy(selectedDevice = device) }

    fun toggleUsePoints() = _uiState.update { it.copy(usePoints = !it.usePoints) }

    // ── 开水 ──

    fun unlock() {
        val device = _uiState.value.selectedDevice ?: run {
            notice("请先选择设备", NoticeTone.Warning)
            return
        }
        unlockJob = viewModelScope.launch {
            if (!unlockMutex.tryLock()) return@launch
            try {
                _uiState.update {
                    it.copy(flow = UnlockFlowState.PreChecking("准备开水"))
                }
                startTimer()
                startTimeout()
                runCatching {
                    repo.unlockDevice(device, usePoints = _uiState.value.usePoints) { step ->
                        val working = step.contains("等待") || step.contains("设备工作")
                        _uiState.update { s ->
                            s.copy(
                                flow = if (working) {
                                    UnlockFlowState.Working(step, (s.flow as? UnlockFlowState.Working)?.elapsedSeconds ?: 0)
                                } else {
                                    UnlockFlowState.PreChecking(step)
                                },
                            )
                        }
                    }
                }.onSuccess { result ->
                    cancelFlowJobs()
                    _uiState.update {
                        it.copy(
                            flow = UnlockFlowState.Success(result),
                            orderHistory = repo.orderHistory(),
                        )
                    }
                    refreshBalance()
                }.onFailure { e ->
                    cancelFlowJobs()
                    if (e is TokenExpiredException) {
                        handleTokenExpired()
                        return@launch
                    }
                    val failed = if (e is UnlockException) {
                        UnlockFlowState.Failed(e.message ?: "开水失败", e.diagnosis.step, e.diagnosis.rawError, e.diagnosis.suggestions)
                    } else {
                        UnlockFlowState.Failed(e.message ?: "开水失败", "未知", e.message ?: "")
                    }
                    _uiState.update { it.copy(flow = failed) }
                }
            } finally {
                unlockMutex.unlock()
            }
        }
    }

    fun dismissFlow() {
        cancelFlowJobs()
        _uiState.update { it.copy(flow = UnlockFlowState.Idle) }
    }

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                val flow = _uiState.value.flow
                if (flow is UnlockFlowState.Working) {
                    _uiState.update { it.copy(flow = flow.copy(elapsedSeconds = flow.elapsedSeconds + 1)) }
                } else if (flow !is UnlockFlowState.PreChecking) {
                    return@launch
                }
            }
        }
    }

    /**
     * 165s 兜底：对齐参考实现——服务端超时会自动关阀结算，本地把状态收回 Idle 并刷新余额；
     * 仓库层轮询协程不取消，收尾完成后 Success 会重新落卡（订单快照不丢）。
     */
    private fun startTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(AUTO_SETTLE_SECONDS * 1_000L)
            if (_uiState.value.flow is UnlockFlowState.Working) {
                cancelTimerOnly()
                _uiState.update { it.copy(flow = UnlockFlowState.Idle) }
                notice("出水超时，饮水机已自动关闭并结算", NoticeTone.Warning)
                refreshBalance()
                refreshDevices()
            }
        }
    }

    private fun cancelTimerOnly() {
        timerJob?.cancel()
        timeoutJob?.cancel()
        timerJob = null
        timeoutJob = null
    }

    private fun cancelFlowJobs() {
        timerJob?.cancel()
        timeoutJob?.cancel()
        timerJob = null
        timeoutJob = null
    }

    override fun onCleared() {
        cancelFlowJobs()
        super.onCleared()
    }

    companion object {
        const val AUTO_SETTLE_SECONDS = 165
    }

    class Factory(private val repo: QiekjRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = WaterViewModel(repo) as T
    }
}
