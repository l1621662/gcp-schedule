package edu.gcp.schedule.ui.week

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import edu.gcp.schedule.data.calendar.CalendarSyncer
import edu.gcp.schedule.data.prefs.DisplayPrefs
import edu.gcp.schedule.data.repo.ScheduleRepository
import edu.gcp.schedule.domain.CalendarSyncDefaults
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.CourseFilter
import edu.gcp.schedule.domain.LocalTimeLike
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.ScheduleExporter
import edu.gcp.schedule.domain.SemesterConfig
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.domain.Timetable
import edu.gcp.schedule.domain.TimetablePrefs
import edu.gcp.schedule.ui.common.NoticeFeedback
import edu.gcp.schedule.ui.common.NoticeTone
import edu.gcp.schedule.ui.common.UndoableMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class WeekUiState(
    val loading: Boolean = true,
    val week: Int = 1,
    val todayWeek: Int = 1,
    val semester: SemesterConfig? = null,
    val slots: List<TimeSlot> = emptyList(),
    /** 当前周课程 */
    val courses: List<Course> = emptyList(),
    /** 全量课程，供 Pager 邻页即时过滤，也用来算非本周灰态 */
    val allCourses: List<Course> = emptyList(),
    val totalCourseCount: Int = 0,
    /**
     * 可见的星期列（1=周一 … 7=周日），按显示顺序排列。
     *
     * 根因：此前只有一个 `showWeekend` 布尔，列数只能是 7 或 5 两种，
     * 「显示周日、不显示周六」表达不出来；而列宽与课块定位又都依赖「第几列」，
     * 所以不能用「列数」近似，必须显式给出可见星期序列（见 ScheduleCalculator.visibleDays）。
     */
    val visibleDays: List<Int> = (1..7).toList(),
    val showSaturday: Boolean = true,
    val showSunday: Boolean = true,
    val showNonCurrentWeek: Boolean = false,
    /**
     * 只看某类课程。
     * 过滤发生在 ViewModel 而不是渲染层：`courses` / `allCourses` / `totalCourseCount` 三处
     * 必须口径一致，否则会出现「网格是空的、但提示说课表不为空」这种自相矛盾的状态。
     */
    val courseFilter: CourseFilter = CourseFilter.All,
    /** 课名目标字号（dp）；null = 跟随系统。范围与换算见 `GridFont`。 */
    val gridFontDp: Float? = null,
    /** 教室（地点）行目标字号（dp）；null = 跟随课名等比缩放。 */
    val gridRoomDp: Float? = null,
    /** 教师行目标字号（dp）；null = 跟随课名等比缩放。 */
    val gridTeacherDp: Float? = null,
    /** 时间轴（节次 + 起止时间）目标字号（dp）；null = 跟随系统。独立于课名字号。 */
    val gridRailDp: Float? = null,
    /** 月份 / 日期表头目标字号（dp）；null = 跟随系统。独立于课名字号。 */
    val gridDateDp: Float? = null,
    /** 格子高度倍率（乘在自适应行高上），1.1f = 默认。 */
    val rowHeightScale: Float = 1.1f,
    /** 左侧时间轴栏宽（dp）。范围与默认值见 TimetablePrefs companion。 */
    val railWidthDp: Float = TimetablePrefs.DefaultRailWidthDp,
    /** 顶部表头高度（dp），参与自适应行高计算。 */
    val dayHeaderHeightDp: Float = TimetablePrefs.DefaultDayHeaderHeightDp,
    /** 格子圆角半径（dp）。 */
    val cellRadiusDp: Float = 6f,
    /** 格子不透明度（0.3–1）。 */
    val cellOpacity: Float = 1f,
    /** 格子文字水平居中。 */
    val cellCenterH: Boolean = false,
    /** 格子文字竖直居中。 */
    val cellCenterV: Boolean = false,
    /** 色块内显示授课教师。 */
    val showTeacher: Boolean = true,
    /** 显示当前时刻线。 */
    val showNowLine: Boolean = true,
    /** 显示课程色块虚线描边。 */
    val showCellBorder: Boolean = true,
    /** 显示网格行分隔辅助线。 */
    val showGridLines: Boolean = true,
    /** 地点前显示「@」前缀。 */
    val showAtSign: Boolean = true,
    /** 点击空白格新建课程。 */
    val tapBlankToAdd: Boolean = true,
    /** 画当前时刻线用，每 30 秒刷新一次 */
    val now: LocalTimeLike = LocalTimeLike.now(),

    // ---- 多课表（DESIGN §4.9）----
    /** 当前课表名，顶栏切换入口显示用。 */
    val timetableName: String = "",
    /** 全部课表，切换弹层用。 */
    val timetables: List<Timetable> = emptyList(),
    val currentTimetableId: Long = 0L,
)

private data class WeekConfig(
    val semester: SemesterConfig? = null,
    val slots: List<TimeSlot> = emptyList(),
    val prefs: DisplayPrefs = DisplayPrefs(),
    val timetables: List<Timetable> = emptyList(),
    val currentTimetableId: Long = 0L,
)

class WeekViewModel(
    private val repo: ScheduleRepository,
    private val todayProvider: () -> LocalDate = { LocalDate.now() },
) : ViewModel() {

    private val weekFlow = MutableStateFlow(1)
    private val nowFlow = MutableStateFlow(LocalTimeLike.now())

    /**
     * 首帧门闸。
     * 根因：初始周次要等学期配置从库里读出来才能算，此前数据流不设防——
     * 先以「第 1 周」发帧、init 里再改周次，Pager 先渲染第 1 周再平移到本周，肉眼可见跳变。
     * 方案：ensureDefaults + 初始周次都就绪后才对外发第一帧，首帧直接落在本周
     * （配合 WeekScreen 侧「loading 期间不创建 Pager」，initialPage 一次到位）。
     */
    private val ready = MutableStateFlow(false)

    /** 五个「配置类」源先合成一个：combine 的类型安全重载最多到 5 个参数。 */
    private val configFlow = combine(
        repo.semester,
        repo.timeSlots,
        repo.displayPrefs,
        repo.timetables,
        repo.currentTimetableId,
    ) { semester, slots, prefs, timetables, currentTimetableId ->
        WeekConfig(semester, slots, prefs, timetables, currentTimetableId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<WeekUiState> = ready.flatMapLatest { ready ->
        if (!ready) {
            flowOf(WeekUiState())
        } else {
            combine(
                weekFlow,
                repo.courses,
                configFlow,
                nowFlow,
            ) { week, loaded, config, now ->
                val semester = config.semester
                val slots = config.slots
                val prefs = config.prefs
                val todayWeek = semester?.let { ScheduleCalculator.weekNumberOf(it, todayProvider()) } ?: 1
                val visible = loaded.filter { prefs.courseFilter.matches(it.kind) }
                WeekUiState(
                    loading = false,
                    week = week,
                    todayWeek = todayWeek,
                    semester = semester,
                    slots = slots,
                    courses = ScheduleCalculator.coursesInWeek(visible, week),
                    allCourses = visible,
                    totalCourseCount = visible.size,
                    showSaturday = prefs.showSaturday,
                    showSunday = prefs.showSunday,
                    visibleDays = ScheduleCalculator.visibleDays(
                        prefs.showSaturday,
                        prefs.showSunday,
                    ),
                    showNonCurrentWeek = prefs.showNonCurrentWeek,
                    courseFilter = prefs.courseFilter,
                    gridFontDp = prefs.gridFontDp,
                    gridRoomDp = prefs.gridRoomDp,
                    gridTeacherDp = prefs.gridTeacherDp,
                    gridRailDp = prefs.gridRailDp,
                    gridDateDp = prefs.gridDateDp,
                    rowHeightScale = prefs.rowHeightScale,
                    railWidthDp = prefs.railWidthDp,
                    dayHeaderHeightDp = prefs.dayHeaderHeightDp,
                    cellRadiusDp = prefs.cellRadiusDp,
                    cellOpacity = prefs.cellOpacity,
                    cellCenterH = prefs.cellCenterH,
                    cellCenterV = prefs.cellCenterV,
                    showTeacher = prefs.showTeacher,
                    showNowLine = prefs.showNowLine,
                    showCellBorder = prefs.showCellBorder,
                    showGridLines = prefs.showGridLines,
                    showAtSign = prefs.showAtSign,
                    tapBlankToAdd = prefs.tapBlankToAdd,
                    now = now,
                    timetableName = config.timetables
                        .firstOrNull { it.id == config.currentTimetableId }?.name.orEmpty(),
                    timetables = config.timetables,
                    currentTimetableId = config.currentTimetableId,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeekUiState())

    init {
        viewModelScope.launch {
            repo.ensureDefaults()
            // 打开课表默认落在本周。此前固定从第 1 周开始，
            // 开学几周后每次进课表都要手动点一次「回到本周」，与主流课表习惯不符。
            // 周次必须在 ready 置位（对外发第一帧）之前定好，否则首帧仍是第 1 周。
            val semester = repo.semester.first()
            if (semester != null) {
                val week = ScheduleCalculator.weekNumberOf(semester, todayProvider())
                if (week in 1..semester.totalWeeks) {
                    weekFlow.value = week
                }
            }
            ready.value = true
        }
    }

    /** 当前时刻线要跟着时间走：界面侧每 30 秒调一次。 */
    fun refreshNow() {
        nowFlow.value = LocalTimeLike.now()
    }

    fun setShowWeekend(value: Boolean) {
        viewModelScope.launch { repo.setShowWeekend(value) }
    }

    fun setShowNonCurrentWeek(value: Boolean) {
        viewModelScope.launch { repo.setShowNonCurrentWeek(value) }
    }

    fun setCourseFilter(filter: CourseFilter) {
        viewModelScope.launch { repo.setCourseFilter(filter) }
    }

    fun setGridFontDp(dp: Float?) {
        viewModelScope.launch { repo.setGridFontDp(dp) }
    }

    fun setGridRoomDp(dp: Float?) {
        viewModelScope.launch { repo.setGridRoomDp(dp) }
    }

    fun setGridTeacherDp(dp: Float?) {
        viewModelScope.launch { repo.setGridTeacherDp(dp) }
    }

    fun setRowHeightScale(value: Float) {
        viewModelScope.launch { repo.setRowHeightScale(value) }
    }

    fun setRailWidthDp(value: Float) {
        viewModelScope.launch { repo.setRailWidthDp(value) }
    }

    fun setDayHeaderHeightDp(value: Float) {
        viewModelScope.launch { repo.setDayHeaderHeightDp(value) }
    }

    fun setCellRadiusDp(value: Float) {
        viewModelScope.launch { repo.setCellRadiusDp(value) }
    }

    fun setCellOpacity(value: Float) {
        viewModelScope.launch { repo.setCellOpacity(value) }
    }

    fun setCellCenterH(value: Boolean) {
        viewModelScope.launch { repo.setCellCenterH(value) }
    }

    fun setCellCenterV(value: Boolean) {
        viewModelScope.launch { repo.setCellCenterV(value) }
    }

    fun setShowTeacher(value: Boolean) {
        viewModelScope.launch { repo.setShowTeacher(value) }
    }

    fun setShowNowLine(value: Boolean) {
        viewModelScope.launch { repo.setShowNowLine(value) }
    }

    fun setShowCellBorder(value: Boolean) {
        viewModelScope.launch { repo.setShowCellBorder(value) }
    }

    fun setShowGridLines(value: Boolean) {
        viewModelScope.launch { repo.setShowGridLines(value) }
    }

    fun setWeek(week: Int) {
        val total = uiState.value.semester?.totalWeeks ?: 20
        weekFlow.value = week.coerceIn(1, total.coerceAtLeast(1))
    }

    /** 切换当前课表：courses/slots/semester/prefs 全部随之换流（DESIGN §4.9）。 */
    fun selectTimetable(id: Long) {
        viewModelScope.launch { repo.setCurrentTimetable(id) }
    }

    fun shiftWeek(delta: Int) = setWeek(weekFlow.value + delta)

    fun goToToday() {
        val sem = uiState.value.semester ?: return
        weekFlow.value = ScheduleCalculator.weekNumberOf(sem, todayProvider()).coerceAtLeast(1)
    }

    fun upsert(course: Course) {
        viewModelScope.launch { repo.upsertCourse(course) }
    }

    fun delete(course: Course) {
        viewModelScope.launch {
            repo.deleteCourse(course)
            _undoableMessage.value = UndoableMessage("已删除「${course.name}」") {
                // 原课程对象带着原 id/颜色，upsertCourse 对 id>0 原样落库
                repo.upsertCourse(course)
            }
        }
    }

    // ---- 撤销（DESIGN §3.3）：破坏性动作执行后给可撤销的 Snackbar ----

    private val _undoableMessage = MutableStateFlow<UndoableMessage?>(null)

    /** 带撤销动作的反馈；UI 侧消费后调 [consumeUndoableMessage] 置空。 */
    val undoableMessage: StateFlow<UndoableMessage?> = _undoableMessage

    fun consumeUndoableMessage() {
        _undoableMessage.value = null
    }

    // ---- 分享（DESIGN §4.12）：日历同步 / CSV / JSON ----

    // 结果带语气（成功/失败/警告），提示卡据此选图标与强调色；
    // 只传文案的话 UI 侧就得靠字符串猜哪条是失败，那是把判断塞回展示层
    private val _shareMessage = MutableStateFlow<NoticeFeedback?>(null)

    /** 分享动作结果，UI 侧消费后置空（提示卡一次性展示）。 */
    val shareMessage: StateFlow<NoticeFeedback?> = _shareMessage

    fun consumeShareMessage() {
        _shareMessage.value = null
    }

    private suspend fun expandedEvents(): List<ScheduleExporter.CourseEvent>? =
        repo.expandedCalendarEvents()

    fun exportCsv(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val events = expandedEvents() ?: error("请先在「我的 → 课表设置」配置学期，并确认课表不为空")
                val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
                val bytes = bom + ScheduleExporter.toGoogleCalendarCsv(events).toByteArray(Charsets.UTF_8)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(bytes)
                    } ?: error("无法打开输出流")
                }
                "已导出 ${events.size} 条课程（CSV）"
            }
            _shareMessage.value = result.fold(
                onSuccess = { NoticeFeedback(it, NoticeTone.Success) },
                onFailure = { NoticeFeedback("导出失败：${it.message}", NoticeTone.Error) },
            )
        }
    }

    fun exportJson(context: Context, uri: Uri) {
        viewModelScope.launch {
            val result = runCatching {
                val json = repo.exportJson()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                        out.write(json.toByteArray(Charsets.UTF_8))
                    } ?: error("无法打开输出流")
                }
                "已导出课表 JSON"
            }
            _shareMessage.value = result.fold(
                onSuccess = { NoticeFeedback(it, NoticeTone.Success) },
                onFailure = { NoticeFeedback("导出失败：${it.message}", NoticeTone.Error) },
            )
        }
    }

    fun syncToCalendar(context: Context) {
        viewModelScope.launch {
            val events = expandedEvents()
            if (events == null) {
                _shareMessage.value = NoticeFeedback(
                    "请先在「我的 → 课表设置」配置学期，并确认课表不为空",
                    NoticeTone.Warning,
                )
                return@launch
            }
            val reminder = repo.calendarReminderMinutes.first()
            _shareMessage.value = when (val r = CalendarSyncer.sync(context, events, reminder)) {
                is CalendarSyncer.CalendarSyncResult.Success -> NoticeFeedback(
                    "已同步 ${r.count} 条课程到手机日历（${CalendarSyncDefaults.reminderLabel(reminder)}）",
                    NoticeTone.Success,
                )
                is CalendarSyncer.CalendarSyncResult.Deleted -> NoticeFeedback(
                    "已删除 ${r.count} 条课程日历",
                    NoticeTone.Success,
                )
                is CalendarSyncer.CalendarSyncResult.NoCalendarAccount -> NoticeFeedback(
                    "手机上没有可用日历账户，请先在系统日历中登录或添加账户",
                    NoticeTone.Warning,
                )
                is CalendarSyncer.CalendarSyncResult.NoPermission -> NoticeFeedback(
                    "日历权限未授予，无法同步",
                    NoticeTone.Warning,
                )
                is CalendarSyncer.CalendarSyncResult.Error -> NoticeFeedback(
                    "同步失败：${r.message}",
                    NoticeTone.Error,
                )
            }
        }
    }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            WeekViewModel(repo) as T
    }
}
