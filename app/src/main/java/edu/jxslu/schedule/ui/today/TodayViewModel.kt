package edu.jxslu.schedule.ui.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.TodayState
import edu.jxslu.schedule.domain.buildTodayState
import edu.jxslu.schedule.ui.common.UndoableMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 今日页 ViewModel。
 *
 * 状态推导本身（焦点课取舍、剩余列表、明日预告）在 `domain/TodayState.kt` 的
 * [buildTodayState]——桌面小组件（`ui/widget`）与今日页共用同一份纯函数，
 * 保证两边「哪节课算正在上 / 什么时候轮到明天上桌」的口径不会分叉。
 * 本类只负责把 Room 数据流与时间 tick 喂进去。
 */
class TodayViewModel(
    private val repo: ScheduleRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val tick = MutableStateFlow(0L)

    /**
     * 首帧门闸。
     * 根因：ensureDefaults 首次启动会写库（建默认课表/迁移/配色自愈），而 Room 流是
     * 「先吐当前值」——初始化完成前的空库帧会先发出去，UI 就在
     * 「加载中 → 尚未开学/课表为空 → 真实内容」之间连跳几帧，表现成闪屏。
     * 方案：初始化完成前不订阅数据流，对外第一帧就是写库之后的终态。
     */
    private val ready = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<TodayState> = ready.flatMapLatest { ready ->
        if (!ready) {
            flowOf(TodayState())
        } else {
            combine(
                repo.semester,
                repo.timeSlots,
                repo.courses,
                tick,
            ) { semester, slots, courses, _ ->
                buildTodayState(
                    semester = semester,
                    slots = slots,
                    courses = courses,
                    today = todayProvider(),
                    now = LocalTimeLike.now(),
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodayState())

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            ready.value = true
        }
    }

    /**
     * 今日页快捷方式（DESIGN §3.8）。不并进 [uiState]：那是课表数据的派生状态，
     * 快捷方式是纯设置值，分开订阅免得改一条快捷方式把整页状态重算一遍。
     */
    val shortcuts: StateFlow<ShortcutSettings> = repo.shortcutSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ShortcutSettings())

    /** 界面每 30 秒调一次：让「还剩 X 分钟」和课的状态跟着时间走。 */
    fun refreshTick() {
        tick.value = System.currentTimeMillis()
    }

    fun upsert(course: Course) {
        viewModelScope.launch { repo.upsertCourse(course) }
    }

    fun deleteCourse(course: Course) {
        viewModelScope.launch {
            repo.deleteCourse(course)
            _undoable.value = UndoableMessage("已删除「${course.name}」") {
                repo.upsertCourse(course)
            }
        }
    }

    // ---- 撤销（DESIGN §3.3）：删除后 5 秒内可撤销 ----

    private val _undoable = MutableStateFlow<UndoableMessage?>(null)

    /** 带撤销动作的反馈；UI 侧消费后调 [consumeUndoable] 置空。 */
    val undoable: StateFlow<UndoableMessage?> = _undoable

    fun consumeUndoable() {
        _undoable.value = null
    }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            TodayViewModel(repo) as T
    }
}
