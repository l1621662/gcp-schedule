package edu.jxslu.schedule.data.repo

import edu.jxslu.schedule.data.DefaultData
import edu.jxslu.schedule.data.local.CourseEntity
import edu.jxslu.schedule.data.local.DetectBaselineEntity
import edu.jxslu.schedule.data.local.DetectReportEntity
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.local.ScoreEntity
import edu.jxslu.schedule.data.local.SemesterConfigEntity
import edu.jxslu.schedule.data.local.TimeSlotEntity
import edu.jxslu.schedule.data.local.TimetableEntity
import edu.jxslu.schedule.data.local.courseKindFromName
import edu.jxslu.schedule.data.prefs.DisplayPrefs
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.CourseTweaker
import edu.jxslu.schedule.domain.DetectGroup
import edu.jxslu.schedule.domain.DetectReportPayload
import edu.jxslu.schedule.domain.DetectSnapshotPayload
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.ScheduleExporter
import edu.jxslu.schedule.domain.ScheduleExporter.CourseEvent
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.Timetable
import edu.jxslu.schedule.domain.TimetablePrefs
import edu.jxslu.schedule.domain.TimeSlotRules
import edu.jxslu.schedule.domain.TweakMode
import edu.jxslu.schedule.domain.TweakPlan
import androidx.room.withTransaction
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CourseJson(
    val id: Long = 0,
    val name: String,
    val teacher: String = "",
    val position: String = "",
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: List<Int> = emptyList(),
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
    val colorIndex: Int = 0,
    /** [CourseKind] 的小写名。带默认值，保证加字段前导出的旧 JSON 仍能读进来。 */
    val kind: String = "theory",
) {
    fun toDomain(): Course = Course(
        id = id,
        name = name,
        teacher = teacher,
        position = position,
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks.toSet(),
        isCustomTime = isCustomTime,
        customStartTime = customStartTime,
        customEndTime = customEndTime,
        colorIndex = colorIndex,
        kind = courseKindFromName(kind),
    )
}

@Serializable
data class CourseExport(
    val courses: List<CourseJson> = emptyList(),
    /** 数据所属学年学期（如 2026-2027-1），来自教务/脚本导出；旧文件或手工编辑没有则缺省。 */
    val term: String? = null,
    /**
     * 成绩备份段（DESIGN §4.3/§4.15）：换机/重装随课表一起恢复。
     * 读取侧 ignoreUnknownKeys——旧版 App 忽略该段、新版读旧文件缺省为空，两版互不破坏。
     */
    val scores: List<ScoreBackupJson> = emptyList(),
    /**
     * 学期配置备份段（DESIGN §4.3）：导出当前课表、导入恢复到目标课表。
     * 旧文件没有此键 → 不动目标课表的学期配置。
     */
    val semester: SemesterBackupJson? = null,
    /**
     * 作息备份段（DESIGN §4.3）：一条一个 40 分钟小节（小节号口径 §3.5）。
     * 旧文件没有此键 → 不动目标课表的作息。
     */
    val timeSlots: List<TimeSlotBackupJson> = emptyList(),
)

/**
 * 备份文件里的单条成绩。字段与 [ScoreRecord] 对齐、全带默认值：
 * 手工编辑或旧格式缺字段时降级为空值而不是解码失败。
 */
/** 备份文件里的学期配置段（DESIGN §4.3）：全带默认值，异构文件缺字段时降级而不是解码失败。 */
@Serializable
data class SemesterBackupJson(
    /** yyyy-MM-dd。 */
    val startDate: String = "",
    val totalWeeks: Int = 20,
    val firstDayOfWeek: Int = 1,
)

/** 备份文件里的作息段：一条一个 40 分钟小节（小节号口径见 DESIGN §3.5）。 */
@Serializable
data class TimeSlotBackupJson(
    val number: Int,
    val startTime: String = "",
    val endTime: String = "",
)

@Serializable
data class ScoreBackupJson(
    val term: String = "",
    val courseNo: String = "",
    val name: String = "",
    val unit: String = "",
    val credit: Double = 0.0,
    val hours: Double = 0.0,
    val examForm: String = "",
    val courseAttr: String = "",
    val category: String = "",
    /** 数值分；等级制成绩为 null。 */
    val score: Double? = null,
    val scoreStr: String = "",
    val gradePoint: Double? = null,
    val status: String = "",
    val pendingReview: Boolean = false,
)

fun ScoreBackupJson.toScoreRecord(): ScoreRecord = ScoreRecord(
    term = term.trim(),
    courseNo = courseNo,
    name = name,
    unit = unit,
    credit = credit,
    hours = hours,
    examForm = examForm,
    courseAttr = courseAttr,
    category = category,
    score = score,
    scoreStr = scoreStr,
    gradePoint = gradePoint,
    status = status,
    pendingReview = pendingReview,
)

fun ScoreRecord.toBackupJson(): ScoreBackupJson = ScoreBackupJson(
    term = term,
    courseNo = courseNo,
    name = name,
    unit = unit,
    credit = credit,
    hours = hours,
    examForm = examForm,
    courseAttr = courseAttr,
    category = category,
    score = score,
    scoreStr = scoreStr,
    gradePoint = gradePoint,
    status = status,
    pendingReview = pendingReview,
)

/**
 * 导入/导出共用的 JSON 配置。
 *
 * 提到顶层是为了让测试能复用**同一份**配置：之前测试只断言字符串里含某些键名，
 * 这里把 `ignoreUnknownKeys` 改掉、或者字段加/改默认值，测试都不会红。
 * 现在测试直接拿它 decode，配置漂移会被测出来。
 *
 * `encodeDefaults = true`：kotlinx 默认不写等于默认值的键，于是 `teacher=""`、`kind="theory"`
 * 这类字段会直接从导出文件里消失。读取侧有默认值兜底、我们自己来回转换不会出错，
 * 但文件是要给兼容工具互导的（见设置页说明），键时有时无会让对方必须猜默认值。
 * 全量写出后导出结果自解释，代价只是文件略大。
 */
internal val CourseJsonFormat: Json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * 校验一条待导入的课程。
 * 返回 null 表示通过，否则返回给用户看的错误文案（含第几门课，便于对文件定位）。
 *
 * 与解码分离：`Json` 只保证结构能读出来，字段取值范围要靠这一层，
 * 否则 day=9 这种脏数据会一路写进库里，最后表现成「课程不见了」。
 */
internal fun validateCourseJson(index: Int, c: CourseJson): String? {
    val label = "第 ${index + 1} 门课"
    if (c.name.isBlank()) return "$label：缺少 name"
    if (c.day !in 1..7) return "$label「${c.name}」：day 应在 1–7，实际为 ${c.day}"
    if (c.startSection < 1) return "$label「${c.name}」：startSection 应 ≥1"
    if (c.endSection < c.startSection) {
        return "$label「${c.name}」：endSection(${c.endSection}) 不能小于 startSection"
    }
    if (c.weeks.isEmpty()) return "$label「${c.name}」：缺少 weeks（如 [1,2,3]）"
    if (c.weeks.any { it !in 1..40 }) return "$label「${c.name}」：weeks 含非法周次"
    return null
}

/** 目标课表的导入统计：弹窗按所选目标实时展示（覆盖会清掉 existing 门，合并新增 newCount 门）。 */
data class ImportStats(val existing: Int, val newCount: Int)

class ScheduleRepository(
    private val db: JuwDatabase,
    private val prefs: DisplayPrefsStore,
) {

    private val json = CourseJsonFormat

    /** displayPrefs 合并用的全局偏好切片（combine 参数上限的收拢容器）。 */
    private data class GlobalPrefs(
        val theme: ThemeMode,
        val dynamicColor: Boolean,
        val haptics: Boolean,
    )

    // ------------------------------------------------------------------
    // 课表清单与当前课表
    // ------------------------------------------------------------------

    val timetables: Flow<List<Timetable>> =
        db.timetableDao().observeAll().map { list -> list.map { it.toDomain() } }

    /**
     * 当前课表的**已解析** id：存储值失效（被删/未设置）时回退到第一张。
     * 直接暴露解析后的值，调用方不用各自处理「指向已删除课表」的脏状态。
     */
    val currentTimetableId: Flow<Long> = combine(
        db.timetableDao().observeAll(),
        prefs.currentTimetableId,
    ) { list, stored ->
        val id = stored ?: 1L
        if (list.any { it.id == id }) id else list.firstOrNull()?.id ?: 1L
    }.distinctUntilChanged()

    // ------------------------------------------------------------------
    // 当前课表的数据流（换课表即换内容）
    // ------------------------------------------------------------------

    @OptIn(ExperimentalCoroutinesApi::class)
    val courses: Flow<List<Course>> = currentTimetableId.flatMapLatest { id ->
        db.courseDao().observeForTimetable(id).map { list -> list.map { it.toDomain() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val timeSlots: Flow<List<TimeSlot>> = currentTimetableId.flatMapLatest { id ->
        db.timeSlotDao().observeForTimetable(id).map { list -> list.map { it.toDomain() } }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val semester: Flow<SemesterConfig?> = currentTimetableId.flatMapLatest { id ->
        db.semesterConfigDao().observeForTimetable(id).map { it?.toDomain() }
    }

    /**
     * 显示偏好 = 全局项（主题、动态取色、触感）+ 全局视图偏好（DESIGN §4.9）。
     * 2026-09-19 起全部字段全局，不再依赖当前课表——换课表不换观感。
     * 对 UI 仍暴露合并后的 [DisplayPrefs]，调用点签名与分层时期一致。
     */
    val displayPrefs: Flow<DisplayPrefs> = combine(
        combine(
            prefs.themeMode,
            prefs.dynamicColor,
            prefs.hapticsEnabled,
            ::GlobalPrefs,
        ),
        prefs.viewPrefs,
    ) { global, p ->
        // 夹取沿用旧 DataStore 读路径的防线：旧数据/手改数据超出收紧后的滑块范围会让 Slider 抛异常
        DisplayPrefs(
            themeMode = global.theme,
            dynamicColor = global.dynamicColor,
            hapticsEnabled = global.haptics,
            // 遗留单开关也一并透出，与实际存储保持一致，免得读了它的人拿到陈旧值。
            showWeekend = p.showSaturday && p.showSunday,
            showSaturday = p.showSaturday,
            showSunday = p.showSunday,
            showNonCurrentWeek = p.showNonCurrentWeek,
            courseFilter = p.courseFilter,
            gridFontDp = p.gridFontDp,
            gridRoomDp = p.gridRoomDp,
            gridTeacherDp = p.gridTeacherDp,
            gridRailDp = p.gridRailDp,
            gridDateDp = p.gridDateDp,
            rowHeightScale = p.rowHeightScale.coerceIn(0.5f, 1.5f),
            railWidthDp = p.railWidthDp.coerceIn(
                TimetablePrefs.MinRailWidthDp,
                TimetablePrefs.MaxRailWidthDp,
            ),
            dayHeaderHeightDp = p.dayHeaderHeightDp.coerceIn(
                TimetablePrefs.MinDayHeaderHeightDp,
                TimetablePrefs.MaxDayHeaderHeightDp,
            ),
            cellRadiusDp = p.cellRadiusDp.coerceIn(0f, 12f),
            cellOpacity = p.cellOpacity.coerceIn(0.5f, 1f),
            cellCenterH = p.cellCenterH,
            cellCenterV = p.cellCenterV,
            showTeacher = p.showTeacher,
            showNowLine = p.showNowLine,
            showCellBorder = p.showCellBorder,
            showGridLines = p.showGridLines,
            showAtSign = p.showAtSign,
            tapBlankToAdd = p.tapBlankToAdd,
        )
        // 去重：combine 每次发射都 new 一个 DisplayPrefs，值实际没变（如写库后回读同值）
        // 时下游两个 VM 不必整体重算
    }.distinctUntilChanged()

    /**
     * 今日页快捷方式（DESIGN §3.8/§4.16）。全局 DataStore 项，与当前课表无关，直接透传 store。
     */
    val shortcutSettings: Flow<ShortcutSettings> = prefs.shortcutSettings

    // ------------------------------------------------------------------
    // 偏好写入：视图偏好写全局 DataStore 键，主题等全局项同层
    // ------------------------------------------------------------------

    // 快捷方式（DESIGN §4.16）：开关与条目列表统一走 store 的 updateShortcuts
    suspend fun setShortcutsEnabled(value: Boolean) = prefs.setShortcutsEnabled(value)


    suspend fun updateShortcuts(transform: (List<ShortcutItem>) -> List<ShortcutItem>) =
        prefs.updateShortcuts(transform)

    /** 视图偏好统一写全局键（2026-09-19 起不再按课表分，DESIGN §4.9）。 */
    private suspend fun updateViewPrefs(transform: (TimetablePrefs) -> TimetablePrefs) =
        prefs.updateViewPrefs(transform)

    suspend fun setShowSaturday(value: Boolean) =
        updateViewPrefs { it.copy(showSaturday = value, showWeekend = value && it.showSunday) }

    suspend fun setShowSunday(value: Boolean) =
        updateViewPrefs { it.copy(showSunday = value, showWeekend = it.showSaturday && value) }

    /** 兼容入口：老调用点一次改两天。新代码请用两个独立 setter。 */
    suspend fun setShowWeekend(value: Boolean) =
        updateViewPrefs { it.copy(showSaturday = value, showSunday = value, showWeekend = value) }

    suspend fun setShowAtSign(value: Boolean) = updateViewPrefs { it.copy(showAtSign = value) }

    suspend fun setTapBlankToAdd(value: Boolean) = updateViewPrefs { it.copy(tapBlankToAdd = value) }

    suspend fun setShowNonCurrentWeek(value: Boolean) = updateViewPrefs { it.copy(showNonCurrentWeek = value) }

    suspend fun setCourseFilter(value: CourseFilter) =
        updateViewPrefs { it.copy(courseFilter = value) }

    /** null = 清掉覆盖，回到跟随系统。 */
    suspend fun setGridFontDp(value: Float?) = updateViewPrefs { it.copy(gridFontDp = value) }

    suspend fun setGridRoomDp(value: Float?) = updateViewPrefs { it.copy(gridRoomDp = value) }

    suspend fun setGridTeacherDp(value: Float?) = updateViewPrefs { it.copy(gridTeacherDp = value) }

    /** 时间轴字号；null = 跟随系统（独立于课名）。 */
    suspend fun setGridRailDp(value: Float?) = updateViewPrefs { it.copy(gridRailDp = value) }

    /** 月份 / 日期表头字号；null = 跟随系统（独立于课名）。 */
    suspend fun setGridDateDp(value: Float?) = updateViewPrefs { it.copy(gridDateDp = value) }

    suspend fun setRowHeightScale(value: Float) = updateViewPrefs { it.copy(rowHeightScale = value) }

    /** 左侧时间轴栏宽（dp）；写入前夹进滑块范围，防脏数据撑爆布局。 */
    suspend fun setRailWidthDp(value: Float) = updateViewPrefs {
        it.copy(railWidthDp = value.coerceIn(TimetablePrefs.MinRailWidthDp, TimetablePrefs.MaxRailWidthDp))
    }

    /** 顶部表头高度（dp）；写入前夹进滑块范围。 */
    suspend fun setDayHeaderHeightDp(value: Float) = updateViewPrefs {
        it.copy(
            dayHeaderHeightDp = value.coerceIn(
                TimetablePrefs.MinDayHeaderHeightDp,
                TimetablePrefs.MaxDayHeaderHeightDp,
            ),
        )
    }

    suspend fun setCellRadiusDp(value: Float) = updateViewPrefs { it.copy(cellRadiusDp = value) }

    suspend fun setCellOpacity(value: Float) = updateViewPrefs { it.copy(cellOpacity = value) }

    suspend fun setCellCenterH(value: Boolean) = updateViewPrefs { it.copy(cellCenterH = value) }

    suspend fun setCellCenterV(value: Boolean) = updateViewPrefs { it.copy(cellCenterV = value) }

    suspend fun setShowTeacher(value: Boolean) = updateViewPrefs { it.copy(showTeacher = value) }

    suspend fun setShowNowLine(value: Boolean) = updateViewPrefs { it.copy(showNowLine = value) }

    suspend fun setShowCellBorder(value: Boolean) = updateViewPrefs { it.copy(showCellBorder = value) }

    suspend fun setShowGridLines(value: Boolean) = updateViewPrefs { it.copy(showGridLines = value) }

    suspend fun setThemeMode(value: ThemeMode) = prefs.setThemeMode(value)

    /** 动态取色开关（全局，默认开）。 */
    suspend fun setDynamicColor(value: Boolean) = prefs.setDynamicColor(value)

    /** 触感反馈开关（全局）。 */
    suspend fun setHapticsEnabled(value: Boolean) = prefs.setHapticsEnabled(value)


    /** 日历同步提前提醒分钟数（全局，0 = 不提醒），供设置页与同步动作共用（DESIGN §4.12）。 */
    val calendarReminderMinutes: Flow<Int> = prefs.calendarReminderMinutes

    suspend fun setCalendarReminderMinutes(value: Int) = prefs.setCalendarReminderMinutes(value)

    // ---- 上课提醒（全局，DESIGN §3.7；调度在 ui/reminder，数据口径在这里） ----

    val reminderEnabled: Flow<Boolean> = prefs.reminderEnabled

    val reminderLeadMinutes: Flow<Int> = prefs.reminderLeadMinutes

    suspend fun setReminderEnabled(value: Boolean) = prefs.setReminderEnabled(value)

    suspend fun setReminderLeadMinutes(value: Int) = prefs.setReminderLeadMinutes(value)

    suspend fun reminderLastKey(): String? = prefs.reminderLastKey()

    suspend fun setReminderLastKey(key: String) = prefs.setReminderLastKey(key)

    suspend fun setCurrentTimetable(id: Long) = prefs.setCurrentTimetable(id)

    /** null = 清掉默认配置源，回退内置默认。 */
    suspend fun setDefaultConfigSource(id: Long?) = prefs.setDefaultConfigSource(id)

    suspend fun defaultConfigSourceId(): Long? = prefs.defaultConfigSourceId.first()

    // ------------------------------------------------------------------
    // 课表管理
    // ------------------------------------------------------------------

    /**
     * 新建课表。配置（学期/作息/作息自定义标记）按用户拍板用**引用型**默认：
     * 实时拷贝 [copyFromId]（缺省取全局默认配置源，再缺省用内置默认）当时的值。
     * 显示偏好 2026-09-19 起全局，不再随配置拷贝。
     */
    suspend fun createTimetable(name: String, copyFromId: Long? = null): Long {
        val sourceId = copyFromId ?: prefs.defaultConfigSourceId.first()
        val source = sourceId?.let { db.timetableDao().getById(it) }
        val sortOrder = (db.timetableDao().getAll().maxOfOrNull { it.sortOrder } ?: 0) + 1
        return createTimetableInternal(
            name = name.trim().ifEmpty { "新课表" },
            source = source,
            sortOrder = sortOrder,
        )
    }

    /** 复制课表：除配置外连同课程一起拷贝。 */
    suspend fun duplicateTimetable(id: Long): Long? {
        val source = db.timetableDao().getById(id) ?: return null
        val courses = getTimetableCourses(id)
        val newId = createTimetableInternal(
            name = "${source.name} 副本",
            source = source,
            sortOrder = (db.timetableDao().getAll().maxOfOrNull { it.sortOrder } ?: 0) + 1,
        )
        db.courseDao().insertAll(
            courses.map { CourseEntity.fromDomain(it).copy(id = 0, timetableId = newId) },
        )
        return newId
    }

    private suspend fun createTimetableInternal(
        name: String,
        source: TimetableEntity?,
        sortOrder: Int,
    ): Long {
        val id = db.timetableDao().upsert(
            TimetableEntity(
                name = name,
                createdAt = System.currentTimeMillis(),
                sortOrder = sortOrder,
                slotsCustomized = source?.slotsCustomized ?: false,
                // prefs_json 列已退役（显示偏好全局化）：只写内置默认占位，读写都走 DataStore
                prefsJson = TimetablePrefs().encode(),
            ),
        )
        if (source != null) {
            // 引用型拷贝：拿源课表「当时」的学期与作息快照
            db.semesterConfigDao().getForTimetable(source.id)?.let {
                db.semesterConfigDao().upsert(it.copy(timetableId = id))
            }
            db.timeSlotDao().upsertAll(
                db.timeSlotDao().getForTimetable(source.id).map { it.copy(timetableId = id) },
            )
        } else {
            db.semesterConfigDao().upsert(
                SemesterConfigEntity.fromDomain(id, DefaultData.defaultSemester),
            )
            writeDefaultTimeSlots(id)
        }
        return id
    }

    suspend fun renameTimetable(id: Long, name: String) {
        val entity = db.timetableDao().getById(id) ?: return
        db.timetableDao().upsert(entity.copy(name = name.trim().ifEmpty { entity.name }))
    }

    /**
     * 删除课表。返回 false 表示拒绝执行：至少要保留一张课表。
     * 删除当前课表时自动切到剩下第一张；删除默认配置源时清回内置默认。
     */
    suspend fun deleteTimetable(id: Long): Boolean {
        if (db.timetableDao().count() <= 1) return false
        db.courseDao().clearForTimetable(id)
        db.timeSlotDao().clearForTimetable(id)
        db.semesterConfigDao().deleteForTimetable(id)
        db.timetableDao().delete(id)
        if (prefs.defaultConfigSourceId.first() == id) {
            prefs.setDefaultConfigSource(null)
        }
        if (prefs.currentTimetableId.first() == id) {
            db.timetableDao().getAll().firstOrNull()?.let { prefs.setCurrentTimetable(it.id) }
        }
        return true
    }

    suspend fun timetableCourseCount(id: Long): Int = db.courseDao().countForTimetable(id)

    // ------------------------------------------------------------------
    // 启动初始化与迁移
    // ------------------------------------------------------------------

    suspend fun ensureDefaults() {
        // 课表兜底：全新安装建第一张；升级安装已由 MIGRATION_2_3 插入 id=1
        if (db.timetableDao().count() == 0) {
            createTimetableInternal(name = "我的课表", source = null, sortOrder = 0)
        }
        globalizeViewPrefs()
        migrateTimeSlotSchema()
        rebalanceCourseColorsIfColliding()
    }

    /**
     * 显示偏好全局化一次性迁移（DESIGN §4.9，2026-09-19）：把显示偏好搬进 DataStore
     * `view_prefs_json`，此后所有课表共用一份。
     *
     * 数据源优先级：
     * - v2 直升用户：旧全局键还在（`prefs_migrated` 未落）→ 以 `legacyViewPrefs()` 为准
     *   （此时课表行的 prefs_json 是 MIGRATION_2_3 写的内置默认，不能代表用户设置）；
     * - v3 时期用户：旧全局键已清（`legacyViewPrefs()` 返回 null）→ 取**当前课表行**的
     *   `prefs_json`（保留用户正在使用的观感）；
     * - 全新安装：两者皆默认值，写入等价默认。
     *
     * 幂等靠 `view_prefs_globalized` 标记；旧「改过作息」标记仍映射到课表 1（作息仍课表级）。
     */
    private suspend fun globalizeViewPrefs() {
        if (prefs.viewPrefsGlobalized()) return
        val source = prefs.legacyViewPrefs()
            ?: currentTimetableId.first()
                .let { db.timetableDao().getById(it) }
                ?.let { TimetablePrefs.decode(it.prefsJson) }
            ?: TimetablePrefs()
        prefs.setInitialViewPrefs(source)
        if (prefs.legacySlotCustomized()) {
            db.timetableDao().getById(1)?.let {
                db.timetableDao().upsert(it.copy(slotsCustomized = true))
            }
        }
        prefs.setPrefsMigrated(true)
        prefs.setViewPrefsGlobalized()
    }

    /**
     * 根因：老版本的节次表是 5 条「大节」（08:00/10:00/…），而课程存的是小节号 1–11，
     * 语义不一致会让第 7 行以后的课落到网格外。这里按版本号做一次性覆盖。
     * 用 DataStore 记版本而不是改 Room 版本号：节次表结构没变，只是数据语义变了。
     *
     * v3 起作息表按课表各一份：迁移对**未自定义**的每张课表逐张覆盖；
     * 旧全局 slotCustomized 标记已在 globalizeViewPrefs 映射到课表 1。
     */
    private suspend fun migrateTimeSlotSchema() {
        if (prefs.slotSchemaVersion() >= DefaultData.SLOT_SCHEMA_VERSION) return
        db.timetableDao().getAll()
            .filter { !it.slotsCustomized }
            .forEach { writeDefaultTimeSlots(it.id) }
        prefs.setSlotSchemaVersion(DefaultData.SLOT_SCHEMA_VERSION)
    }

    private suspend fun writeDefaultTimeSlots(timetableId: Long) {
        db.timeSlotDao().upsertAll(
            DefaultData.defaultTimeSlots.map {
                TimeSlotEntity(timetableId, it.number, it.startTime, it.endTime)
            },
        )
    }

    /**
     * 既有课程撞色自愈（按课表逐张做：配色以课表为 scope，跨课表的名次互不相干）。
     *
     * 根因：调色板 12→16 之前入库的课按 12 取模分配，不同课名超过 12 个时必然回绕撞色。
     * 色值存在数据库里，扩色板救不了旧数据，所以在启动时检测一次：
     * 若按「课名排序名次」重排能减少撞色才整体重排；确定性映射保证幂等。
     */
    private suspend fun rebalanceCourseColorsIfColliding() {
        db.timetableDao().getAll().forEach { t ->
            val courses = getTimetableCourses(t.id)
            if (courses.isEmpty()) return@forEach

            fun collisionCount(mapping: Map<String, Int>): Int =
                mapping.values.groupingBy { it }.eachCount().count { it.value > 1 }

            val current = courses.groupBy { it.name }
                .mapValues { (_, group) -> group.first().colorIndex }
            val target = ScheduleCalculator.colorIndexesBySortedName(current.keys)

            if (collisionCount(target) >= collisionCount(current)) return@forEach

            courses
                .filter { it.colorIndex != target[it.name] }
                .forEach {
                    db.courseDao().upsert(
                        CourseEntity.fromDomain(it.copy(colorIndex = target[it.name] ?: it.colorIndex))
                            .copy(timetableId = t.id),
                    )
                }
        }
    }

    // ------------------------------------------------------------------
    // 课程 CRUD（scope = 当前课表）
    // ------------------------------------------------------------------

    private suspend fun getTimetableCourses(timetableId: Long): List<Course> =
        db.courseDao().getForTimetable(timetableId).map { it.toDomain() }

    private suspend fun usedColorIndexes(timetableId: Long): List<Int> =
        db.courseDao().getForTimetable(timetableId).map { it.colorIndex }

    suspend fun upsertCourse(course: Course): Long {
        val timetableId = currentTimetableId.first()
        // 只有新增的课才重新配色；已有课程保留原色，避免改一门课把整屏颜色打乱
        val target = if (course.id > 0 || course.colorIndex != 0) {
            course
        } else {
            course.copy(colorIndex = ScheduleCalculator.nextColorIndex(usedColorIndexes(timetableId)))
        }
        return db.courseDao().upsert(CourseEntity.fromDomain(target).copy(timetableId = timetableId))
    }

    suspend fun deleteCourse(course: Course) {
        db.courseDao().delete(CourseEntity.fromDomain(course))
    }

    suspend fun clearCourses() {
        db.courseDao().clearForTimetable(currentTimetableId.first())
    }

    suspend fun replaceAllCourses(courses: List<Course>, timetableId: Long? = null) {
        val ttId = timetableId ?: currentTimetableId.first()
        db.courseDao().clearForTimetable(ttId)
        db.courseDao().insertAll(
            withSortedNameColors(courses).map { CourseEntity.fromDomain(it).copy(timetableId = ttId) },
        )
    }

    /**
     * 撤销回滚专用：按**原 id 与原颜色**逐门恢复。不能用 [replaceAllCourses] 兜——
     * 那会重排序取色，撤销后整屏颜色就变了。
     */
    suspend fun restoreCourses(courses: List<Course>, timetableId: Long? = null) {
        val ttId = timetableId ?: currentTimetableId.first()
        db.courseDao().insertAll(
            courses.map { CourseEntity.fromDomain(it).copy(timetableId = ttId) },
        )
    }

    suspend fun mergeCourses(courses: List<Course>, timetableId: Long? = null): Int {
        val ttId = timetableId ?: currentTimetableId.first()
        val existing = getTimetableCourses(ttId)
        val keys = existing.map { it.mergeKey() }.toHashSet()
        val used = existing.map { it.colorIndex }.toMutableList()
        var added = 0
        for (c in courses) {
            val key = c.mergeKey()
            if (key in keys) continue
            val colored = c.copy(colorIndex = ScheduleCalculator.nextColorIndex(used))
            db.courseDao().upsert(CourseEntity.fromDomain(colored).copy(timetableId = ttId))
            used += colored.colorIndex
            keys += key
            added++
        }
        return added
    }

    /**
     * 整批课程按课程名排序后的名次取色。
     * 这样同一份课表每次导入/覆盖出来的颜色完全一致，且 16 门以内不会撞色。
     */
    private fun withSortedNameColors(courses: List<Course>): List<Course> {
        val mapping = ScheduleCalculator.colorIndexesBySortedName(courses.map { it.name })
        return courses.map { c ->
            c.copy(colorIndex = mapping[c.name] ?: ScheduleCalculator.colorIndexFor(c.name))
        }
    }

    /** 教务解析结果入库；merge=false 全量覆盖目标课表 */
    suspend fun importParsedCourses(courses: List<Course>, merge: Boolean, timetableId: Long? = null): Int {
        if (merge) return mergeCourses(courses, timetableId)
        replaceAllCourses(courses, timetableId)
        return courses.size
    }

    // ------------------------------------------------------------------
    // 调课自动检测（DESIGN §4.17）：基线 / 报告的读写与应用
    // ------------------------------------------------------------------

    /** 待处理的差异报告（unread）；课表页气泡、导入弹层的「检测课表更新」与通知都读它。 */
    val pendingDetectReport: Flow<DetectReportPayload?> =
        currentTimetableId.flatMapLatest { id ->
            db.detectReportDao().observe(id).map { e ->
                e?.takeIf { it.unread }?.let { DetectReportPayload.decode(it.payload) }
            }
        }.distinctUntilChanged()

    suspend fun detectBaseline(timetableId: Long): DetectSnapshotPayload? =
        db.detectBaselineDao().get(timetableId)?.let { DetectSnapshotPayload.decode(it.payload) }

    /** 检测的本地侧：当前课表全量（含考试与自定义条目——三方合并按 kind+课程名配对，天然不受影响）。 */
    suspend fun detectLocalCourses(timetableId: Long): List<Course> = getTimetableCourses(timetableId)

    suspend fun saveDetectBaseline(timetableId: Long, payload: DetectSnapshotPayload) {
        db.detectBaselineDao().upsert(
            DetectBaselineEntity(
                timetableId = timetableId,
                term = payload.term ?: "",
                payload = payload.encode(),
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    /**
     * 教务导入确认落库后刷新基线（§4.4/§4.8 的确认弹窗路径都要调）：
     * 「教务数据成为本地数据」就是基线的定义。考试与 JSON 导入不进来——
     * 前者不参与检测，后者不是教务数据。
     */
    suspend fun refreshBaselineFromJwImport(timetableId: Long?, courses: List<Course>, term: String?) {
        val ttId = timetableId ?: currentTimetableId.first()
        val theory = courses.filter { it.kind == CourseKind.Theory }
        val lab = courses.filter { it.kind == CourseKind.Lab }
        if (theory.isEmpty() && lab.isEmpty()) return
        saveDetectBaseline(ttId, DetectSnapshotPayload.fromCourses(term, theory, lab))
    }

    suspend fun saveDetectReport(timetableId: Long, payload: DetectReportPayload) {
        db.detectReportDao().upsert(
            DetectReportEntity.unread(timetableId, payload.encode(), System.currentTimeMillis()),
        )
    }

    /** 应用/忽略报告后清气泡：内容保留，设置页状态区仍可回看。 */
    suspend fun markDetectReportRead() {
        db.detectReportDao().markRead(currentTimetableId.first())
    }

    /** 清掉当前课表的检测数据（关闭功能/删除课表时；基线一并清，下次开启重建）。 */
    suspend fun clearDetectData(timetableId: Long? = null) {
        val ttId = timetableId ?: currentTimetableId.first()
        db.detectBaselineDao().delete(ttId)
        db.detectReportDao().delete(ttId)
    }

    /**
     * 应用「更新课表」页勾选的差异（DESIGN §4.17）。一个事务内完成：
     * 每个勾选组删掉本地该 (kind, 课程名) 的全部行 → 插入教务侧行
     * （沿用被替换第一行的配色，纯新增课走 nextColorIndex；教务停课组只删不插），
     * 并把基线**整体推进**为报告里的教务全量快照——只勾一部分时，未勾选的组
     * 视为用户默许忽略，下次检测不再报；「忽略本次」整个不动（见 UI 层）。
     */
    suspend fun applyDetectGroups(groups: List<DetectGroup>, report: DetectReportPayload) {
        if (groups.isEmpty()) return
        val ttId = currentTimetableId.first()
        db.withTransaction {
            val all = getTimetableCourses(ttId)
            val used = all.map { it.colorIndex }.toMutableList()
            val deleteIds = mutableListOf<Long>()
            val inserts = mutableListOf<CourseEntity>()
            for (group in groups) {
                val replaced = all.filter { it.kind == group.kind && it.name == group.name }
                deleteIds += replaced.map { it.id }
                val color = replaced.firstOrNull()?.colorIndex
                    ?: ScheduleCalculator.nextColorIndex(used)
                used += color
                inserts += group.remoteCourses.map {
                    CourseEntity.fromDomain(it.copy(id = 0, colorIndex = color)).copy(timetableId = ttId)
                }
            }
            if (deleteIds.isNotEmpty()) db.courseDao().deleteByIds(deleteIds)
            if (inserts.isNotEmpty()) db.courseDao().insertAll(inserts)
            saveDetectBaseline(ttId, report.snapshot)
            db.detectReportDao().markRead(ttId)
        }
    }

    /**
     * 调课（DESIGN §4.11）：把 [fromWeek] 周 [fromDay] 的课按 [mode] 调整到 [toWeek] 周 [toDay]。
     *
     * 规划与写库分离：[CourseTweaker.plan] 是纯函数（JVM 可测），这里只负责
     * 「按目标课表 scope 读全量 → 规划 → 在一个事务里删/改/插」。
     * 事务是必需的：规划基于读到的快照，中间若混进别的写入（导入、编辑），
     * 会按陈旧快照把课程改回旧值。
     *
     * 返回实际执行的动作，供 UI 汇报（不依赖再查一次库）。
     */
    suspend fun tweakCourses(
        mode: TweakMode,
        fromWeek: Int,
        fromDay: Int,
        toWeek: Int,
        toDay: Int,
        timetableId: Long? = null,
    ): TweakPlan {
        val ttId = timetableId ?: currentTimetableId.first()
        return db.withTransaction {
            val courses = getTimetableCourses(ttId)
            val plan = CourseTweaker.plan(courses, mode, fromWeek, fromDay, toWeek, toDay)
            if (plan.deleteIds.isNotEmpty()) db.courseDao().deleteByIds(plan.deleteIds)
            if (plan.updates.isNotEmpty()) {
                db.courseDao().insertAll(
                    plan.updates.map { CourseEntity.fromDomain(it).copy(timetableId = ttId) },
                )
            }
            if (plan.inserts.isNotEmpty()) {
                db.courseDao().insertAll(
                    plan.inserts.map { CourseEntity.fromDomain(it.copy(id = 0)).copy(timetableId = ttId) },
                )
            }
            plan
        }
    }

    /**
     * 写**指定课表**的学期配置（DESIGN §4.9）：课表设置页可切换编辑目标，
     * 不再隐式只写当前课表。
     */
    suspend fun updateSemesterFor(timetableId: Long, config: SemesterConfig) {
        db.semesterConfigDao().upsert(SemesterConfigEntity.fromDomain(timetableId, config))
    }

    /**
     * 指定课表的学期配置（DESIGN §4.14）：考试导入在确认弹窗选定目标课后，
     * 用**目标课表**的开学日重算周次——多课表各有自己的开学日，站在 A 课表
     * 把考试导进 B 课表时只有按 B 定位才落得准。
     */
    suspend fun semesterFor(timetableId: Long): SemesterConfig? =
        db.semesterConfigDao().getForTimetable(timetableId)?.toDomain()

    /** 指定课表学期配置的观察流：课表设置子页切换目标后实时跟随（DESIGN §4.9）。 */
    fun observeSemesterFor(timetableId: Long): Flow<SemesterConfig?> =
        db.semesterConfigDao().observeForTimetable(timetableId).map { it?.toDomain() }

    /** 指定课表的课程数观察流：课表设置页目标信息行（DESIGN §4.9）。 */
    fun observeCourseCountFor(timetableId: Long): Flow<Int> =
        db.courseDao().observeCountForTimetable(timetableId)

    fun coursesForWeek(week: Int): Flow<List<Course>> =
        courses.map { ScheduleCalculator.coursesInWeek(it, week) }

    // ------------------------------------------------------------------
    // 作息表（scope = 当前课表）
    // ------------------------------------------------------------------

    /**
     * 保存用户自定义作息（写入当前课表并标记 slotsCustomized）。
     * 课表行高、时刻线、「还剩 X 分」、下一节判定全都依赖这张表，
     * 一旦用户按学校实际调整过，后续任何迁移都不该再覆盖它。
     * 调用方需先过 [TimeSlotRules.validate]。
     */
    suspend fun saveTimeSlots(slots: List<TimeSlot>) {
        val timetableId = currentTimetableId.first()
        db.timeSlotDao().upsertAll(
            slots.sortedBy { it.number }
                .map { TimeSlotEntity(timetableId, it.number, it.startTime, it.endTime) },
        )
        db.timetableDao().getById(timetableId)?.let {
            db.timetableDao().upsert(it.copy(slotsCustomized = true))
        }
    }

    /** 恢复默认作息（当前课表），并重新交回给迁移逻辑管辖。 */
    suspend fun resetTimeSlotsToDefault() {
        val timetableId = currentTimetableId.first()
        writeDefaultTimeSlots(timetableId)
        db.timetableDao().getById(timetableId)?.let {
            db.timetableDao().upsert(it.copy(slotsCustomized = false))
        }
    }

    // ------------------------------------------------------------------
    // JSON 导入导出（导出 = 当前课表；格式不变，拾光互导兼容）
    // ------------------------------------------------------------------

    suspend fun exportJson(timetableId: Long? = null): String {
        val courses = getTimetableCourses(timetableId ?: currentTimetableId.first()).map {
            CourseJson(
                id = it.id,
                name = it.name,
                teacher = it.teacher,
                position = it.position,
                day = it.day,
                startSection = it.startSection,
                endSection = it.endSection,
                weeks = it.weeks.sorted(),
                isCustomTime = it.isCustomTime,
                customStartTime = it.customStartTime,
                customEndTime = it.customEndTime,
                colorIndex = it.colorIndex,
                kind = it.kind.name.lowercase(),
            )
        }
        // 成绩全局归属学生（DESIGN §4.15），随备份一起带走；timetableId 不影响它
        val scores = db.scoreDao().getAll().map { it.toDomain().toBackupJson() }
        // 学期配置与作息按课表（DESIGN §4.3）：导出的就是这份课表的时间口径，恢复时跟课表走
        val ttId = timetableId ?: currentTimetableId.first()
        val semester = db.semesterConfigDao().getForTimetable(ttId)?.toDomain()?.let {
            SemesterBackupJson(it.startDate, it.totalWeeks, it.firstDayOfWeek)
        }
        val slots = db.timeSlotDao().getForTimetable(ttId).map {
            TimeSlotBackupJson(it.number, it.startTime, it.endTime)
        }
        return json.encodeToString(
            CourseExport.serializer(),
            CourseExport(courses = courses, scores = scores, semester = semester, timeSlots = slots),
        )
    }

    /**
     * 当前课表展开成具体日期的日历事件（DESIGN §4.12）。
     *
     * 分享弹层的「同步到日历 / 导出 CSV」与「我的 → 日历同步」共用这一条口径。
     * 学期未配置或课表为空返回 null，由调用方决定提示文案；时刻口径与跳过规则
     * 见 [ScheduleExporter.expandEvents]。
     */
    suspend fun expandedCalendarEvents(): List<CourseEvent>? {
        val semester = semester.first() ?: return null
        val slots = timeSlots.first()
        val courses = courses.first()
        if (courses.isEmpty()) return null
        return ScheduleExporter.expandEvents(courses, slots, semester).ok
    }

    /**
     * 导入 JSON 到指定课表（null = 当前课表）。
     * 目标选择与覆盖/合并由 UI 弹窗强制确定（DESIGN §4.9），本方法只负责校验与写库。
     * 文件携带成绩段时**整体替换**现有成绩（先清后插，DESIGN §4.3）；空段/缺段不动成绩。
     */
    suspend fun importJson(text: String, merge: Boolean, timetableId: Long? = null): ImportResult {
        val export = try {
            json.decodeFromString(CourseExport.serializer(), text)
        } catch (e: Exception) {
            return ImportResult.Failure("JSON 解析失败：${e.message ?: "格式不正确"}")
        }
        val courses = export.courses
        if (courses.isEmpty()) {
            return ImportResult.Failure("文件中没有课程（courses 为空）")
        }
        courses.forEachIndexed { index, c ->
            val err = validateCourseJson(index, c)
            if (err != null) return ImportResult.Failure(err)
        }
        // 成绩在校验阶段一并挡下：课程写了一半才发现成绩段脏数据，等于留下半套备份
        val scoreRecords = export.scores.map { it.toScoreRecord() }
        scoreRecords.forEachIndexed { index, s ->
            if (s.term.isBlank() || s.name.isBlank()) {
                return ImportResult.Failure("第 ${index + 1} 条成绩缺少 term 或 name")
            }
        }
        // 学期/作息段同样先全量校验（DESIGN §4.3）：宁可整体拒绝，不留「课程对了时间错」的半套
        val backupSemester = export.semester?.let { s ->
            val date = runCatching { ScheduleCalculator.parseDate(s.startDate) }.getOrNull()
                ?: return ImportResult.Failure("备份里的开学日期格式不正确：${s.startDate}")
            SemesterConfig(date.toString(), s.totalWeeks.coerceIn(1, 30), s.firstDayOfWeek)
        }
        val backupSlots = export.timeSlots.map { TimeSlot(it.number, it.startTime, it.endTime) }
        if (backupSlots.isNotEmpty()) {
            TimeSlotRules.validate(backupSlots)?.let {
                return ImportResult.Failure("备份里的作息表有问题：$it")
            }
        }
        val domain = courses.map { it.toDomain() }
        val restoredScores = if (scoreRecords.isEmpty()) {
            0
        } else {
            db.withTransaction {
                db.scoreDao().deleteAll()
                db.scoreDao().insertAll(
                    scoreRecords.map { ScoreEntity.fromDomain(it, System.currentTimeMillis()) },
                )
            }
            scoreRecords.size
        }
        // 配置恢复到**目标课表**（与课程同落点）；恢复作息视为用户数据，置位防结构性迁移覆盖
        val ttId = timetableId ?: currentTimetableId.first()
        var restoredSemester = false
        var restoredSlots = 0
        if (backupSemester != null || backupSlots.isNotEmpty()) {
            db.withTransaction {
                if (backupSemester != null) {
                    db.semesterConfigDao().upsert(SemesterConfigEntity.fromDomain(ttId, backupSemester))
                    restoredSemester = true
                }
                if (backupSlots.isNotEmpty()) {
                    db.timeSlotDao().clearForTimetable(ttId)
                    db.timeSlotDao().upsertAll(
                        backupSlots.map { TimeSlotEntity(ttId, it.number, it.startTime, it.endTime) },
                    )
                    db.timetableDao().getById(ttId)?.let {
                        db.timetableDao().upsert(it.copy(slotsCustomized = true))
                    }
                    restoredSlots = backupSlots.size
                }
            }
        }
        return if (merge) {
            val added = mergeCourses(domain, timetableId)
            ImportResult.Success(
                added = added,
                total = domain.size,
                merge = true,
                restoredScores = restoredScores,
                restoredSemester = restoredSemester,
                restoredSlots = restoredSlots,
            )
        } else {
            replaceAllCourses(domain, timetableId)
            ImportResult.Success(
                added = domain.size,
                total = domain.size,
                merge = false,
                restoredScores = restoredScores,
                restoredSemester = restoredSemester,
                restoredSlots = restoredSlots,
            )
        }
    }

    /** 解析并校验待导入文件；成功时携带领域模型，供目标课表弹窗按所选目标计算统计。 */
    suspend fun previewImport(text: String): ImportPreview {
        val export = try {
            json.decodeFromString(CourseExport.serializer(), text)
        } catch (e: Exception) {
            return ImportPreview.Error("JSON 解析失败：${e.message ?: "格式不正确"}")
        }
        if (export.courses.isEmpty()) {
            return ImportPreview.Error("文件中没有课程（courses 为空）")
        }
        export.courses.forEachIndexed { index, c ->
            validateCourseJson(index, c)?.let { return ImportPreview.Error(it) }
        }
        val domain = export.courses.map { it.toDomain() }
        val scores = export.scores.map { it.toScoreRecord() }
        // 学期/作息段也先验一遍（与 importJson 同口径）：弹窗阶段就把坏备份挡下
        val backupSemester = export.semester?.let { s ->
            val date = runCatching { ScheduleCalculator.parseDate(s.startDate) }.getOrNull()
                ?: return ImportPreview.Error("备份里的开学日期格式不正确：${s.startDate}")
            SemesterConfig(date.toString(), s.totalWeeks.coerceIn(1, 30), s.firstDayOfWeek)
        }
        val backupSlots = export.timeSlots.map { TimeSlot(it.number, it.startTime, it.endTime) }
        if (backupSlots.isNotEmpty()) {
            TimeSlotRules.validate(backupSlots)?.let {
                return ImportPreview.Error("备份里的作息表有问题：$it")
            }
        }
        return ImportPreview.Ok(
            courses = domain,
            sample = domain.take(5).joinToString { it.name },
            term = export.term?.takeIf { it.isNotBlank() },
            scores = scores,
            semester = backupSemester,
            slotCount = backupSlots.size,
        )
    }

    /** 给定目标课表计算覆盖/合并统计（弹窗里随目标切换刷新）。 */
    suspend fun importStatsFor(courses: List<Course>, timetableId: Long): ImportStats {
        val existing = getTimetableCourses(timetableId)
        val keys = existing.map { it.mergeKey() }.toHashSet()
        return ImportStats(
            existing = existing.size,
            newCount = courses.count { it.mergeKey() !in keys },
        )
    }

    /**
     * 合并去重键。
     * 含 kind：理论课与实验课可能同名、同星期、同节次、同教师（例如「机械制造基础A」两处都有），
     * 不含类型就会互相吞并，先导入的那份把另一份挤掉。
     */
    private fun Course.mergeKey(): String =
        listOf(
            name,
            day.toString(),
            startSection.toString(),
            endSection.toString(),
            teacher,
            kind.name,
        ).joinToString("|")
}

sealed interface ImportResult {
    data class Success(
        val added: Int,
        val total: Int,
        val merge: Boolean,
        /** 随文件整体替换的成绩条数；0 = 文件没带成绩段。 */
        val restoredScores: Int = 0,
        /** 是否随文件恢复了目标课表的学期配置。 */
        val restoredSemester: Boolean = false,
        /** 随文件恢复的作息条数；0 = 文件没带作息段。 */
        val restoredSlots: Int = 0,
    ) : ImportResult
    data class Failure(val message: String) : ImportResult
}

sealed interface ImportPreview {
    /** [courses] 已通过校验，可直接作为写库入参与目标统计的输入。 */
    data class Ok(
        val courses: List<Course>,
        val sample: String,
        /** 文件声明的学年学期，仅用于导入确认弹窗展示；不影响写库。 */
        val term: String? = null,
        /** 文件携带的成绩（DESIGN §4.3）：导入时整体替换，弹窗按它出提示。 */
        val scores: List<ScoreRecord> = emptyList(),
        /** 文件带的学期配置（null = 没有）；随预览展示到确认弹窗注记。 */
        val semester: SemesterConfig? = null,
        /** 文件带的作息条数（0 = 没有）。 */
        val slotCount: Int = 0,
    ) : ImportPreview
    data class Error(val message: String) : ImportPreview
}
