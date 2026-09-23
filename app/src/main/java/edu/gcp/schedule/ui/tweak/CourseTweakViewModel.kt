package edu.gcp.schedule.ui.tweak

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.gcp.schedule.data.repo.ScheduleRepository
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.SemesterConfig
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.domain.TweakMode
import edu.gcp.schedule.domain.TweakPlan
import edu.gcp.schedule.ui.common.UndoableMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

/** 某一天的课表切片，供两侧预览卡展示。 */
data class DaySlice(
    val date: LocalDate,
    /** 日期所在周次；null = 不在学期范围内。 */
    val week: Int?,
    /** 星期（1=周一 … 7=周日）。 */
    val day: Int,
    val courses: List<Course>,
)

data class TweakUiState(
    val loading: Boolean = true,
    val timetableName: String = "",
    val fromDate: LocalDate = LocalDate.now(),
    val toDate: LocalDate = LocalDate.now(),
    val mode: TweakMode = TweakMode.Merge,
    val from: DaySlice = DaySlice(LocalDate.now(), null, 1, emptyList()),
    val to: DaySlice = DaySlice(LocalDate.now(), null, 1, emptyList()),
    val slots: List<TimeSlot> = emptyList(),
    val semester: SemesterConfig? = null,
    /** 输入不合法时的原因；null = 参数没问题。**不含**「正在执行」——那是忙碌，不是错误。 */
    val blockReason: String? = null,
    val running: Boolean = false,
) {
    /** 能否执行：参数合法且不在执行中。 */
    val canExecute: Boolean get() = blockReason == null && !running
}

private data class TweakConfig(
    val semester: SemesterConfig? = null,
    val courses: List<Course> = emptyList(),
    val slots: List<TimeSlot> = emptyList(),
    val timetableName: String = "",
)

/**
 * 调课页状态（DESIGN §4.11）。
 *
 * 日期与模式是页面自己的输入，与库里的数据流合成：日期一改，两侧预览立刻重算。
 * 默认两个日期都取今天——用户多数场景是「把今天的课挪走」，少点两次。
 */
class CourseTweakViewModel(
    private val repo: ScheduleRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val fromDate = MutableStateFlow(todayProvider())
    private val toDate = MutableStateFlow(todayProvider())
    private val mode = MutableStateFlow(TweakMode.Merge)
    private val running = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val config = combine(
        repo.semester,
        repo.courses,
        repo.timeSlots,
        repo.timetables,
        repo.currentTimetableId,
    ) { semester, courses, slots, timetables, currentId ->
        TweakConfig(
            semester = semester,
            courses = courses,
            slots = slots,
            timetableName = timetables.firstOrNull { it.id == currentId }?.name.orEmpty(),
        )
    }

    val uiState: StateFlow<TweakUiState> = combine(
        config,
        fromDate,
        toDate,
        mode,
        running,
    ) { config, from, to, mode, running ->
        val fromSlice = slice(config, from)
        val toSlice = slice(config, to)
        TweakUiState(
            loading = false,
            timetableName = config.timetableName,
            fromDate = from,
            toDate = to,
            mode = mode,
            from = fromSlice,
            to = toSlice,
            slots = config.slots,
            semester = config.semester,
            blockReason = blockReason(fromSlice, toSlice, config.semester),
            running = running,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TweakUiState())

    /** 一次性提示（执行失败等纯提示）；UI 取走后调用 [consumeMessage]。 */
    val messages: StateFlow<String?> = message

    fun consumeMessage() {
        message.value = null
    }

    // ---- 撤销（DESIGN §3.3）：调课执行成功后给可撤销的 Snackbar ----

    private val _undoableMessage = MutableStateFlow<UndoableMessage?>(null)

    /** 成功执行的调课；UI 侧消费后调 [consumeUndoableMessage] 置空。 */
    val undoableMessage: StateFlow<UndoableMessage?> = _undoableMessage

    fun consumeUndoableMessage() {
        _undoableMessage.value = null
    }

    fun setFromDate(date: LocalDate) {
        fromDate.value = date
    }

    fun setToDate(date: LocalDate) {
        toDate.value = date
    }

    fun setMode(value: TweakMode) {
        mode.value = value
    }

    /**
     * 执行调课。
     * 周次换算在这里一次算清：日期 → 周次走 [ScheduleCalculator.weekNumberOf]（周一为首日），
     * 与全项目其余处同口径。
     */
    fun execute() {
        val state = uiState.value
        if (state.blockReason != null || state.running) return
        val semester = state.semester ?: return
        val fromWeek = state.from.week ?: return
        val toWeek = state.to.week ?: return
        viewModelScope.launch {
            running.value = true
            // 快照先于执行：撤销 = 按原 id/颜色整批恢复
            val snapshot = runCatching { repo.courses.first() }.getOrDefault(emptyList())
            val plan = runCatching {
                repo.tweakCourses(state.mode, fromWeek, state.from.day, toWeek, state.to.day)
            }.getOrElse {
                running.value = false
                message.value = "调课失败：${it.message ?: "未知错误"}"
                return@launch
            }
            running.value = false
            _undoableMessage.value = UndoableMessage(describe(state, plan)) {
                repo.restoreCourses(snapshot)
            }
            // 预览随库流自动刷新，这里不手动改日期：用户多半要接着做下一次调课，
            // 替他改日期等于替他做决定。
        }
    }

    /** 执行结果文案。数字全部来自 plan，不重查库。 */
    private fun describe(state: TweakUiState, plan: TweakPlan): String {
        val dates = "${state.fromDate.monthValue}月${state.fromDate.dayOfMonth}日 → " +
            "${state.toDate.monthValue}月${state.toDate.dayOfMonth}日"
        return buildString {
            append("$dates：已调整 ${plan.movedCount} 门课")
            if (state.mode.destructive && plan.targetCount > 0) {
                append("，目标日期原有 ${plan.targetCount} 门")
                if (state.mode == TweakMode.Overwrite) {
                    if (plan.targetPurgedCount > 0) append("（其中 ${plan.targetPurgedCount} 门整门删除）")
                    append("已清空当周")
                } else {
                    append("已换到源日期")
                }
            }
        }
    }

    private fun slice(config: TweakConfig, date: LocalDate): DaySlice {
        val day = date.dayOfWeek.value
        val semester = config.semester
        val week = semester?.let { ScheduleCalculator.weekNumberOf(it, date) }
            ?.takeIf { it in 1..semester.totalWeeks }
        val courses = if (week == null) {
            emptyList()
        } else {
            ScheduleCalculator.coursesOnDay(config.courses, week, day)
        }
        return DaySlice(date, week, day, courses)
    }

    /**
     * 输入不合法（不可执行）的原因。文案要能直接指导用户改哪个输入，不能只说「不可执行」。
     * 判断顺序 = 用户改起来的自然顺序：先看学期是否设了，再看两个日期，最后看有没有课。
     */
    private fun blockReason(
        from: DaySlice,
        to: DaySlice,
        semester: SemesterConfig?,
    ): String? = when {
        semester == null -> "尚未设置学期开学日期，请先到「课表设置」填写"
        from.week == null -> "被调整日期不在本学期（第 1-${semester.totalWeeks} 周）"
        to.week == null -> "调整到日期不在本学期（第 1-${semester.totalWeeks} 周）"
        from.date == to.date -> "两个日期相同，请选择不同的日期"
        from.courses.isEmpty() -> "被调整日期当天没有课，无需调课"
        else -> null
    }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CourseTweakViewModel(repo) as T
    }
}

/** 星期文案（1=周一 … 7=周日），与课表页表头同口径。 */
internal fun weekdayLabel(day: Int): String =
    DayOfWeek.of(day.coerceIn(1, 7)).let {
        listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[it.value - 1]
    }
