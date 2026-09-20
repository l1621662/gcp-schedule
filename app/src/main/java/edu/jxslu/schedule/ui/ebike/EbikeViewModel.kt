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

/** 页面级偏好快照（自动保存开关、深浅色、最近车号）。 */
data class EbikePrefsSnapshot(
    val autoSave: Boolean = false,
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
 * 最近车号历史随生成更新（DataStore，上限 8）。
 * 二维码内容不含个人信息，历史也不出本机。
 */
class EbikeViewModel(private val prefs: DisplayPrefsStore) : ViewModel() {

    private val _uiState = MutableStateFlow(EbikeUiState())
    val uiState: StateFlow<EbikeUiState> = _uiState.asStateFlow()

    private val _events = Channel<EbikeEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    /** 骑行相关偏好（卡开关不归本页管，但自动保存/深浅色/历史在本页用）。 */
    val ebikePrefs: StateFlow<EbikePrefsSnapshot> = combine(
        prefs.ebikeAutoSave,
        prefs.themeMode,
        prefs.ebikeRecentIds,
    ) { autoSave, themeMode, recent ->
        EbikePrefsSnapshot(
            autoSave = autoSave,
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

    /** 手动把当前展示的码存相册（自动保存关闭时的兜底动作）。 */
    fun saveCurrent() {
        val state = _uiState.value
        val bitmap = state.generatedBitmap ?: return
        val bikeId = state.generatedBikeId ?: return
        viewModelScope.launch {
            val appContext = Graph.appContext
            val error = withContext(Dispatchers.IO) {
                EbikeQrBitmaps.saveToGallery(appContext, bitmap, bikeId)
            }
            if (error == null) {
                _events.send(EbikeEvent.Notice("已保存到相册「水贝贝」", NoticeTone.Success))
            } else {
                _events.send(EbikeEvent.Notice(error, NoticeTone.Error))
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
