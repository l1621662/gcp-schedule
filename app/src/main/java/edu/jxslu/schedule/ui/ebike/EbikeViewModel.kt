package edu.jxslu.schedule.ui.ebike

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.domain.EbikeQr
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.ui.common.NoticeTone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class EbikeUiState(
    /** 输入框里的尾部车号（只允许数字进这个字段，UI 层过滤）。 */
    val tailInput: String = "",
    /** 已生成的二维码（null = 未生成）；与 [generatedBikeId] 成对。 */
    val generatedBitmap: Bitmap? = null,
    /** 已生成的完整车号（`100000669` 形态），存相册命名与提示用。 */
    val generatedBikeId: String? = null,
    /** 输入校验行内提示；null = 无。 */
    val inputError: String? = null,
)

/** 页面级偏好快照（自动保存/扫完即焚开关、深浅色、最近车号）。 */
data class EbikePrefsSnapshot(
    val autoSave: Boolean = false,
    val burnAfterScan: Boolean = true,
    val dark: Boolean = false,
    val recentIds: List<String> = emptyList(),
)

sealed interface EbikeEvent {
    /** 一次性结果提示（保存成功/失败等）；[tone] 决定提示语气。 */
    data class Notice(val text: String, val tone: NoticeTone) : EbikeEvent
}

/**
 * 共享单车出码（DESIGN §3.9 / §4.18）。
 *
 * 生成 = 拼 URL（[EbikeQr.bikeUrl] 校验，非法输入不出码）→ zxing 矩阵 → 位图；
 * 自动保存开关开着时，生成即落相册（后台线程，结果经 [events] 提示）；
 * 扫完即焚开着时，保存成功记录待焚毁 key，回到 App（页面 ON_RESUME）后
 * 由 [burnPending] 从相册删除；最近车号历史随生成更新（DataStore，上限 8）。
 * 二维码内容不含个人信息，历史也不出本机。
 */
class EbikeViewModel(private val prefs: DisplayPrefsStore) : ViewModel() {

    private val _uiState = MutableStateFlow(EbikeUiState())
    val uiState: StateFlow<EbikeUiState> = _uiState.asStateFlow()

    private val _events = Channel<EbikeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 骑行相关偏好（卡开关不归本页管，但自动保存/焚毁/深浅色/历史在本页用）。 */
    val ebikePrefs: StateFlow<EbikePrefsSnapshot> = combine(
        prefs.ebikeAutoSave,
        prefs.ebikeBurnAfterScan,
        prefs.themeMode,
        prefs.ebikeRecentIds,
    ) { autoSave, burnAfterScan, themeMode, recent ->
        EbikePrefsSnapshot(
            autoSave = autoSave,
            burnAfterScan = burnAfterScan,
            dark = themeMode == ThemeMode.Dark,
            recentIds = recent,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EbikePrefsSnapshot())

    /** 输入尾部车号（UI 已过滤为数字，这里再做一次长度上限防线）。 */
    fun onTailInput(value: String) {
        val filtered = value.filter { it in '0'..'9' }.take(EbikeQr.TAIL_LENGTH)
        _uiState.update { it.copy(tailInput = filtered, inputError = null) }
    }

    /** 点击最近车号 chip 回填。 */
    fun onPickRecent(tail: String) {
        if (EbikeQr.bikeUrl(tail) != null) onTailInput(tail)
    }

    /**
     * 生成二维码。非法车号只给行内提示，不发事件；合法则出码、
     * 视自动保存开关落相册、并写最近历史。
     */
    fun generate() {
        val tail = _uiState.value.tailInput
        val url = EbikeQr.bikeUrl(tail)
        if (url == null) {
            _uiState.update {
                it.copy(inputError = "请输入 ${EbikeQr.TAIL_LENGTH} 位数字车号（车身二维码后三位）")
            }
            return
        }
        val prefsSnapshot = ebikePrefs.value
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                EbikeQrBitmaps.render(EbikeQr.qrMatrix(url), prefsSnapshot.dark)
            }
            _uiState.update {
                it.copy(generatedBitmap = bitmap, generatedBikeId = EbikeQr.TEMPLATE + tail)
            }
            if (prefsSnapshot.autoSave) saveCurrent()
            viewModelScope.launch {
                prefs.updateEbikeRecentIds { EbikeQr.mergeRecent(it, tail) }
            }
        }
    }

    /**
     * 手动把当前展示的码存相册（自动保存关闭时的兜底动作）。
     * 保存成功且扫完即焚开着时，记录待焚毁 key——回来时由 [burnPending] 清除。
     */
    fun saveCurrent() {
        val state = _uiState.value
        val bitmap = state.generatedBitmap ?: return
        val bikeId = state.generatedBikeId ?: return
        viewModelScope.launch {
            val appContext = Graph.appContext
            val result = withContext(Dispatchers.IO) {
                EbikeQrBitmaps.saveToGallery(appContext, bitmap, bikeId)
            }
            when (result) {
                is EbikeQrBitmaps.SaveResult.Saved -> {
                    if (ebikePrefs.value.burnAfterScan) {
                        prefs.updateEbikePendingDelete {
                            EbikeQr.mergePendingDelete(it, result.pendingKey)
                        }
                    }
                    _events.send(EbikeEvent.Notice("已保存到相册「水贝贝」", NoticeTone.Success))
                }
                is EbikeQrBitmaps.SaveResult.Failed ->
                    _events.send(EbikeEvent.Notice(result.message, NoticeTone.Error))
            }
        }
    }

    /**
     * 扫完即焚（DESIGN §3.9）：删除所有记录在案的待焚毁二维码，成功才移出记录。
     * 由页面 ON_RESUME 触发（从微信/桌面回到 App 时）；开关关闭时不删不清——
     * 关掉 = 完全回到旧语义。防重入：进行中的焚毁不叠跑。
     */
    @Volatile
    private var burning = false

    fun burnPending() {
        if (burning) return
        burning = true
        viewModelScope.launch {
            try {
                // 开关真值必须读原始流：ebikePrefs 的 stateIn 快照在 DataStore
                // 首次发射前是默认值（true），冷启动恢复的首帧竞态下会误删
                // 「用户已关闭焚毁」时留下的记录。
                if (!prefs.ebikeBurnAfterScan.first()) return@launch
                val appContext = Graph.appContext
                while (true) {
                    val pending = prefs.ebikePendingDelete.first()
                    if (pending.isEmpty()) break
                    val deleted = pending.filter { key ->
                        withContext(Dispatchers.IO) { EbikeQrBitmaps.deletePending(appContext, key) }
                    }
                    prefs.updateEbikePendingDelete { it - deleted.toSet() }
                    if (deleted.isEmpty()) break // 全部失败（如文件已不在），保留记录别空转
                    _events.send(
                        EbikeEvent.Notice("已清除存入相册的二维码（扫完即焚）", NoticeTone.Success),
                    )
                    if (deleted.size == pending.size) break
                    // 有失败项：下轮重试剩余的；连续失败会在下一轮走 break
                }
            } finally {
                burning = false
            }
        }
    }

    /** 离开页面时清掉大位图引用，别等 GC 兜底。 */
    override fun onCleared() {
        _uiState.value.generatedBitmap?.recycle()
        super.onCleared()
    }

    class Factory(private val prefs: DisplayPrefsStore) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = EbikeViewModel(prefs) as T
    }
}
