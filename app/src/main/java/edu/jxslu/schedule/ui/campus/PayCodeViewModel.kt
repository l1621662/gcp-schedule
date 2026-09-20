package edu.jxslu.schedule.ui.campus

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.ykt.YktBarcodeData
import edu.jxslu.schedule.data.ykt.YktCard
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.data.ykt.YktException
import edu.jxslu.schedule.data.ykt.YktRepository
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.YktPayCode
import edu.jxslu.schedule.ui.common.CodeBitmaps
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** 付款码页三态。 */
sealed class PayCodeUiState {

    /** 取码中（进页自动触发）。 */
    data object Loading : PayCodeUiState()

    /** 取码失败：[message] 用户可读原因；[canRetry] = 允许重试（凭证类错误引导去设置页）。 */
    data class Error(val message: String, val canRetry: Boolean = true) : PayCodeUiState()

    /**
     * 取码成功。[codes] 为本批全部码（含当前），[index] 当前展示位；
     * 位图由 ViewModel 异步渲染在 [PayCodeViewModel.bitmaps]，不进状态类。
     */
    data class Success(
        val codes: List<String>,
        val index: Int,
        val expiresSeconds: Long,
        val accountMasked: String,
    ) : PayCodeUiState() {
        val current: String get() = codes[index]
        val hasMore: Boolean get() = index < codes.lastIndex
    }
}

/** 当前展示码的渲染位图（null = 渲染中）。 */
data class PayCodeBitmaps(val qr: Bitmap, val barcode: Bitmap)

/** 一次性事件（换批失败等）。 */
sealed interface PayCodeEvent {
    data class Notice(val text: String, val tone: NoticeTone) : PayCodeEvent
}

/**
 * 校园卡付款码页（DESIGN §3.10 / §4.19）。
 *
 * 进页自动取码：内存 token → 401/无 token 自动重登 → CARD 账户 → 一批 10 码。
 * 「下一个」只在**批内**递增；耗尽才重新取一批（防反复申请攒码）。
 * 码矩阵在 Default 线程渲染，位图随 ViewModel 生命周期回收。
 */
class PayCodeViewModel(
    private val appContext: Context,
    private val repo: YktRepository,
    private val credentialStore: YktCredentialStore,
    private val prefs: DisplayPrefsStore,
) : ViewModel() {

    private val _uiState = MutableStateFlow<PayCodeUiState>(PayCodeUiState.Loading)
    val uiState: StateFlow<PayCodeUiState> = _uiState.asStateFlow()

    private val _bitmaps = MutableStateFlow<PayCodeBitmaps?>(null)
    val bitmaps: StateFlow<PayCodeBitmaps?> = _bitmaps.asStateFlow()

    /**
     * 卡余额快照（DESIGN §4.19 余额展示）：null = 未取到 / 取失败（静默，不挡出码主流程）。
     * cardBalanceFen 元素为每卡「卡账户余额」，elecBalanceFen 为水电账户余额。
     */
    data class BalanceSnapshot(val cards: List<YktCard>, val totalFen: Long, val elecFen: Long)

    private val _balance = MutableStateFlow<BalanceSnapshot?>(null)
    val balance: StateFlow<BalanceSnapshot?> = _balance.asStateFlow()

    /** 深色主题（码配色与页面观感一致）。 */
    val dark: StateFlow<Boolean> = combine(prefs.themeMode) { theme ->
        theme.first() == ThemeMode.Dark
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _events = Channel<PayCodeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var loaded = false

    /** 进页自动取码；[force] = 手动重试。取码成功后顺带取余额（失败静默不挡主流程）。 */
    fun load(force: Boolean = false) {
        if (loaded && !force) return
        loaded = true
        val credentials = credentialStore.read()
        if (credentials == null) {
            _uiState.value = PayCodeUiState.Error(
                "凭证未配置或已清除，请在「我的 → 校园卡」重新开启",
                canRetry = false,
            )
            return
        }
        _uiState.update { PayCodeUiState.Loading }
        viewModelScope.launch {
            try {
                // 整链总超时兜底（DESIGN §4.19 小优化）：键盘+登录+账户+取码 4 跳，
                // 单跳各有 15/20s，最坏叠加 80s——宁可明确报超时也不无限转圈（口径同 §4.17）
                val data = withTimeoutOrNull(LOGIN_TOTAL_TIMEOUT_MS) {
                    repo.payCodes(credentials.username, credentials.password)
                }
                if (data == null) {
                    _uiState.value = PayCodeUiState.Error(
                        "登录超时（${LOGIN_TOTAL_TIMEOUT_MS / 1000} 秒无响应），请检查网络后重试",
                        canRetry = true,
                    )
                    return@launch
                }
                _uiState.value = PayCodeUiState.Success(
                    codes = data.barcode,
                    index = 0,
                    expiresSeconds = data.expires,
                    accountMasked = maskAccount(data.account),
                )
                renderBitmaps(data.barcode.first())
                loadBalance(credentials.username, credentials.password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException.NeedCaptcha) {
                _uiState.value = PayCodeUiState.Error(e.message ?: "触发图形验证码", canRetry = false)
            } catch (e: YktException.Credential) {
                _uiState.value = PayCodeUiState.Error(
                    "${e.message}。若密码已修改，请在「我的 → 校园卡」重新验证",
                    canRetry = true,
                )
            } catch (e: YktException) {
                _uiState.value = PayCodeUiState.Error(e.message ?: "取码失败", canRetry = true)
            } catch (e: Exception) {
                _uiState.value = PayCodeUiState.Error("取码异常：${e.message ?: "未知错误"}", canRetry = true)
            }
        }
    }

    /** 余额快照（静默）：失败不提示——出码是主流程，余额只是锦上添花。 */
    private fun loadBalance(username: String, password: String) {
        viewModelScope.launch {
            try {
                val cards = repo.cards(username, password)
                if (cards.isNotEmpty()) {
                    _balance.value = BalanceSnapshot(
                        cards = cards,
                        totalFen = cards.sumOf { it.cardBalanceFen },
                        elecFen = cards.sumOf { it.elecBalanceFen },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 静默：余额取不到不影响出示付款码
            }
        }
    }

    /** 下拉/手动刷新余额。 */
    fun refreshBalance() {
        val credentials = credentialStore.read() ?: return
        loadBalance(credentials.username, credentials.password)
    }

    /** 展示下一个备用码（批内递增 + 本地渲染）；批内耗尽时重新取一批。 */
    fun next() {
        val current = _uiState.value as? PayCodeUiState.Success ?: return
        if (current.hasMore) {
            val newIndex = current.index + 1
            _uiState.update { state ->
                (state as? PayCodeUiState.Success)?.copy(index = newIndex) ?: state
            }
            renderBitmaps(current.codes[newIndex])
            return
        }
        val credentials = credentialStore.read() ?: return
        viewModelScope.launch {
            try {
                val data = repo.payCodes(credentials.username, credentials.password)
                _uiState.value = PayCodeUiState.Success(
                    codes = data.barcode,
                    index = 0,
                    expiresSeconds = data.expires,
                    accountMasked = maskAccount(data.account),
                )
                renderBitmaps(data.barcode.first())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(PayCodeEvent.Notice("换批失败：${e.message ?: "未知错误"}", NoticeTone.Error))
            }
        }
    }

    /** 渲染当前码（Default 线程）；换码时回收旧位图。 */
    private fun renderBitmaps(code: String) {
        val darkNow = dark.value
        viewModelScope.launch {
            val (qr, bc) = withContext(Dispatchers.Default) {
                val matrices = YktPayCode.matrices(code)
                CodeBitmaps.render(matrices.qr, darkNow) to CodeBitmaps.render(matrices.barcode, darkNow)
            }
            val old = _bitmaps.value
            _bitmaps.value = PayCodeBitmaps(qr, bc)
            old?.qr?.recycle()
            old?.barcode?.recycle()
        }
    }

    /** 卡号掩码：前 2 后 2，其余打星。 */
    private fun maskAccount(account: String): String {
        val a = account.trim()
        if (a.length <= 4) return a
        return a.take(2) + "*".repeat(a.length - 4) + a.takeLast(2)
    }

    override fun onCleared() {
        _bitmaps.value?.qr?.recycle()
        _bitmaps.value?.barcode?.recycle()
        _bitmaps.value = null
        super.onCleared()
    }

    companion object {
        /** 取码整链总超时（键盘+登录+账户+取码 4 跳；口径同 §4.17 的总超时兜底）。 */
        private const val LOGIN_TOTAL_TIMEOUT_MS = 30_000L
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = PayCodeViewModel(
            context.applicationContext,
            Graph.yktRepository(context.applicationContext),
            Graph.yktCredentialStore(context.applicationContext),
            Graph.displayPrefs(context.applicationContext),
        ) as T
    }
}
