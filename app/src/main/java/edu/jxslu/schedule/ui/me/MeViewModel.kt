package edu.jxslu.schedule.ui.me

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.data.repo.ImportPreview
import edu.jxslu.schedule.data.repo.ImportResult
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.TimeSlotRules
import edu.jxslu.schedule.domain.Timetable
import edu.jxslu.schedule.ui.common.ImportTarget
import edu.jxslu.schedule.ui.common.readTextFromUri
import edu.jxslu.schedule.ui.common.resolveImportTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

data class MeUiState(
    val loading: Boolean = true,
    val semester: SemesterConfig? = null,
    val courseCount: Int = 0,
    val currentWeek: Int = 0,
    /** 当前作息表，供课表设置子页展示与编辑 */
    val timeSlots: List<TimeSlot> = emptyList(),
    /** 主题模式，供设置页三选一 */
    val themeMode: ThemeMode = ThemeMode.System,
    /** 合并后的显示偏好（2026-09-19 起全部为全局项），供显示设置子页 */
    val displayPrefs: DisplayPrefs = DisplayPrefs(),
    /** 当前课表全量课程：显示设置页上半区实时预览用（已按 courseFilter 过滤） */
    val courses: List<Course> = emptyList(),
    /** 当前课表名与全部课表：设置页归属标注与导入目标弹窗用（DESIGN §4.9） */
    val timetableName: String = "",
    val timetables: List<Timetable> = emptyList(),
    val currentTimetableId: Long = 0L,
    /** 课表设置子页：正在配置的课表（默认 = 当前课表）及其学期口径（DESIGN §4.9）。 */
    val configTargetId: Long = 0L,
    val configSemester: SemesterConfig? = null,
    val configCourseCount: Int = 0,
    val configWeek: Int = 0,
    val message: String? = null,
)

sealed interface OneShot {
    /** [undo] 非 null 时 UI 以「撤销」Snackbar 呈现，用户点撤销后执行。 */
    data class Message(val text: String, val undo: (suspend () -> Unit)? = null) : OneShot
    data class ConfirmImport(val preview: ImportPreview.Ok, val text: String) : OneShot
}

@OptIn(ExperimentalCoroutinesApi::class)
class MeViewModel(private val repo: ScheduleRepository) : ViewModel() {

    /** 六源合成：combine 无类型安全重载超过 5 个，课表配置先收进一个 data class。 */
    private val configFlow = combine(
        repo.semester,
        repo.courses,
        repo.timeSlots,
        repo.displayPrefs,
        repo.timetables,
    ) { semester, courses, timeSlots, prefs, timetables ->
        MeConfig(semester, courses, timeSlots, prefs, timetables)
    }

    /** 课表设置子页的编辑目标：0 = 跟随当前课表（进页默认）；用户点 chips 后固定选中。 */
    private val configTargetOverride = MutableStateFlow(0L)

    fun selectConfigTarget(timetableId: Long) {
        configTargetOverride.value = timetableId
    }

    /** 解析后的编辑目标：override 优先，否则跟随当前课表。 */
    private val configTargetId: Flow<Long> = configTargetOverride.flatMapLatest { override ->
        if (override == 0L) repo.currentTimetableId else flowOf(override)
    }

    /** 目标课表的学期配置与课程数：chips 切到哪张就观察哪张（DESIGN §4.9）。 */
    private val targetConfigFlow = configTargetId.flatMapLatest { id ->
        combine(repo.observeSemesterFor(id), repo.observeCourseCountFor(id)) { semester, count ->
            TargetConfig(id, semester, count)
        }
    }

    val uiState: StateFlow<MeUiState> = combine(
        configFlow,
        repo.currentTimetableId,
        targetConfigFlow,
    ) { config, currentTimetableId, target ->
        val week = config.semester
            ?.let { ScheduleCalculator.weekNumberOf(it, LocalDate.now()) } ?: 0
        MeUiState(
            loading = false,
            semester = config.semester,
            courseCount = config.courses.size,
            currentWeek = week,
            timeSlots = config.timeSlots,
            themeMode = config.prefs.themeMode,
            displayPrefs = config.prefs,
            courses = config.courses.filter { config.prefs.courseFilter.matches(it.kind) },
            timetableName = config.timetables.firstOrNull { it.id == currentTimetableId }?.name.orEmpty(),
            timetables = config.timetables,
            currentTimetableId = currentTimetableId,
            configTargetId = target.id,
            configSemester = target.semester,
            configCourseCount = target.courseCount,
            configWeek = target.semester
                ?.let { ScheduleCalculator.weekNumberOf(it, LocalDate.now()) } ?: 0,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MeUiState())

    private val _oneShot = MutableStateFlow<OneShot?>(null)
    val oneShot: StateFlow<OneShot?> = _oneShot

    private val _pendingImportText = MutableStateFlow<String?>(null)

    fun consumeOneShot() {
        _oneShot.value = null
    }

    /**
     * 保存学期配置到**当前选中的课表**（课表设置页可切换编辑目标，DESIGN §4.9）：
     * 目标课表没配过学期时以本次输入直接建行。
     */
    fun saveSemester(startDate: String, totalWeeks: Int) {
        viewModelScope.launch {
            val ok = runCatching { ScheduleCalculator.parseDate(startDate) }.isSuccess
            if (!ok) {
                _oneShot.value = OneShot.Message("开学日期格式应为 yyyy-MM-dd，例如 2026-09-07")
                return@launch
            }
            val weeks = totalWeeks.coerceIn(1, 30)
            val state = uiState.value
            val targetId = state.configTargetId.takeIf { it != 0L } ?: state.currentTimetableId
            val old = repo.semesterFor(targetId) ?: SemesterConfig(startDate, weeks, 1)
            repo.updateSemesterFor(targetId, old.copy(startDate = startDate, totalWeeks = weeks))
            val name = state.timetables.firstOrNull { it.id == targetId }?.name.orEmpty()
            _oneShot.value = OneShot.Message("已保存「${name.ifBlank { "课表" }}」的学期设置")
        }
    }

    fun exportJson(context: Context, uri: Uri) {
        viewModelScope.launch {
            runCatching {
                val json = repo.exportJson()
                context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                    out.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("无法打开输出流")
            }.onSuccess {
                _oneShot.value = OneShot.Message("已导出课表 JSON")
            }.onFailure {
                _oneShot.value = OneShot.Message("导出失败：${it.message}")
            }
        }
    }

    fun importFromUri(context: Context, uri: Uri) {
        viewModelScope.launch {
            val text = readTextFromUri(context, uri)
            if (text.isNullOrBlank()) {
                _oneShot.value = OneShot.Message("读取文件失败")
                return@launch
            }
            prepareImport(text)
        }
    }

    fun importFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
        if (text.isNullOrBlank()) {
            _oneShot.value = OneShot.Message("剪贴板为空")
            return
        }
        viewModelScope.launch { prepareImport(text) }
    }

    private suspend fun prepareImport(text: String) {
        when (val preview = repo.previewImport(text)) {
            is ImportPreview.Error -> _oneShot.value = OneShot.Message(preview.message)
            is ImportPreview.Ok -> {
                _pendingImportText.value = text
                _oneShot.value = OneShot.ConfirmImport(preview, text)
            }
        }
    }

    /**
     * 按弹窗选定的目标导入（DESIGN §4.9）：目标可为已有课表，也可新建
     * （新建时配置取默认配置源）；导入后切到目标课表让用户直接看到结果。
     */
    fun confirmImport(target: ImportTarget, merge: Boolean) {
        val text = _pendingImportText.value ?: return
        _pendingImportText.value = null
        _oneShot.value = null
        viewModelScope.launch {
            val targetId = resolveImportTarget(repo, target)
            when (val r = repo.importJson(text, merge, targetId)) {
                is ImportResult.Success -> {
                    repo.setCurrentTimetable(targetId)
                    val base = if (merge) {
                        "合并完成：新增 ${r.added} / 文件共 ${r.total}"
                    } else {
                        "已覆盖导入 ${r.total} 门课"
                    }
                    _oneShot.value = OneShot.Message(
                        buildString {
                            append(base)
                            if (r.restoredScores > 0) append("；成绩已整体替换（${r.restoredScores} 条）")
                            val configParts = buildList {
                                if (r.restoredSemester) add("学期配置")
                                if (r.restoredSlots > 0) add("作息 ${r.restoredSlots} 节")
                            }
                            if (configParts.isNotEmpty()) {
                                append("；已恢复目标课表的${configParts.joinToString("与")}")
                            }
                        },
                    )
                }
                is ImportResult.Failure ->
                    _oneShot.value = OneShot.Message(r.message)
            }
        }
    }

    fun cancelPendingImport() {
        _pendingImportText.value = null
        consumeOneShot()
    }

    fun clearCourses() {
        viewModelScope.launch {
            // 快照先行：清空后 Snackbar 里给「撤销」，恢复按原 id/颜色逐门写回
            val snapshot = repo.courses.first()
            repo.clearCourses()
            _oneShot.value = OneShot.Message(
                if (snapshot.isEmpty()) "课表已经是空的" else "已清空 ${snapshot.size} 门课程",
                undo = snapshot.takeIf { it.isNotEmpty() }?.let { list ->
                    { repo.restoreCourses(list) }
                },
            )
        }
    }

    /**
     * 保存作息表。
     * 校验不通过只提示、不写入：脏作息不会让页面崩，只会让课表安静地显示错的时间。
     */
    fun saveTimeSlots(slots: List<TimeSlot>) {
        viewModelScope.launch {
            val err = TimeSlotRules.validate(slots)
            if (err != null) {
                _oneShot.value = OneShot.Message(err)
                return@launch
            }
            repo.saveTimeSlots(slots)
            _oneShot.value = OneShot.Message("作息表已保存")
        }
    }

    fun resetTimeSlots() {
        viewModelScope.launch {
            repo.resetTimeSlotsToDefault()
            _oneShot.value = OneShot.Message("已恢复默认作息")
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { repo.setThemeMode(mode) }
    }

    /** 触感反馈开关（全局，写入 DataStore）。 */
    fun setHapticsEnabled(value: Boolean) {
        viewModelScope.launch { repo.setHapticsEnabled(value) }
    }

    /** 动态取色开关（全局，Material You）。 */
    fun setDynamicColor(value: Boolean) {
        viewModelScope.launch { repo.setDynamicColor(value) }
    }


    // ---- 显示设置子页写入口（2026-09-19 起全部写全局，见 DESIGN §4.9 / §3.3） ----

    fun setShowSaturday(value: Boolean) = viewModelScope.launch { repo.setShowSaturday(value) }

    fun setShowSunday(value: Boolean) = viewModelScope.launch { repo.setShowSunday(value) }

    fun setShowAtSign(value: Boolean) = viewModelScope.launch { repo.setShowAtSign(value) }

    fun setTapBlankToAdd(value: Boolean) = viewModelScope.launch { repo.setTapBlankToAdd(value) }


    /** 兼容入口：老调用点一次改两天。 */
    fun setShowWeekend(value: Boolean) = viewModelScope.launch { repo.setShowWeekend(value) }

    fun setShowNonCurrentWeek(value: Boolean) =
        viewModelScope.launch { repo.setShowNonCurrentWeek(value) }

    fun setCourseFilter(value: CourseFilter) = viewModelScope.launch { repo.setCourseFilter(value) }

    fun setGridFontDp(value: Float?) = viewModelScope.launch { repo.setGridFontDp(value) }

    fun setGridRoomDp(value: Float?) = viewModelScope.launch { repo.setGridRoomDp(value) }

    fun setGridTeacherDp(value: Float?) = viewModelScope.launch { repo.setGridTeacherDp(value) }

    fun setGridRailDp(value: Float?) = viewModelScope.launch { repo.setGridRailDp(value) }

    fun setGridDateDp(value: Float?) = viewModelScope.launch { repo.setGridDateDp(value) }

    fun setRowHeightScale(value: Float) = viewModelScope.launch { repo.setRowHeightScale(value) }

    fun setRailWidthDp(value: Float) = viewModelScope.launch { repo.setRailWidthDp(value) }

    fun setDayHeaderHeightDp(value: Float) = viewModelScope.launch { repo.setDayHeaderHeightDp(value) }

    fun setCellRadiusDp(value: Float) = viewModelScope.launch { repo.setCellRadiusDp(value) }

    fun setCellOpacity(value: Float) = viewModelScope.launch { repo.setCellOpacity(value) }

    fun setCellCenterH(value: Boolean) = viewModelScope.launch { repo.setCellCenterH(value) }

    fun setCellCenterV(value: Boolean) = viewModelScope.launch { repo.setCellCenterV(value) }

    fun setShowTeacher(value: Boolean) = viewModelScope.launch { repo.setShowTeacher(value) }

    fun setShowNowLine(value: Boolean) = viewModelScope.launch { repo.setShowNowLine(value) }

    fun setShowCellBorder(value: Boolean) = viewModelScope.launch { repo.setShowCellBorder(value) }

    fun setShowGridLines(value: Boolean) = viewModelScope.launch { repo.setShowGridLines(value) }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MeViewModel(repo) as T
    }
}

/** [MeViewModel.uiState] 的配置切片：combine 类型安全重载最多 5 个参数。 */
private data class MeConfig(
    val semester: SemesterConfig?,
    val courses: List<Course>,
    val timeSlots: List<TimeSlot>,
    val prefs: DisplayPrefs,
    val timetables: List<Timetable>,
)

/** [MeViewModel.targetConfigFlow] 的切片：正在配置的课表及其学期口径。 */
private data class TargetConfig(
    val id: Long,
    val semester: SemesterConfig?,
    val courseCount: Int,
)

fun meFactory(context: Context) = MeViewModel.Factory(Graph.repository(context))
