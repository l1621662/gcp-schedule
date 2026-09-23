package edu.gcp.schedule.domain

import kotlinx.serialization.Serializable

/**
 * 单个课表（多课表支持，DESIGN §4.9）。
 *
 * 根因：此前所有数据都隐含「全局只有一张课表」，要支持多学期/多版本课表并存，
 * 必须有一层课表身份把课程、学期配置、作息表归属起来。
 * 显示偏好原随课表存储，2026-09-19 起全局化（DataStore `view_prefs_json`），不再挂在课表上。
 */
data class Timetable(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int,
    /** 用户是否手工改过这张课表的作息表；为 true 时结构性作息迁移不再覆盖它。 */
    val slotsCustomized: Boolean,
)

/**
 * 显示偏好（2026-09-19 起全局：存 DataStore `view_prefs_json`，所有课表共用一份；
 * 历史上曾按课表存于 `timetables.prefs_json`，类名保留旧称）。
 *
 * 整体以 kotlinx 序列化成 JSON 存储：这些值没有查询需求，加字段不用动任何表结构，
 * 且 decode 的字段默认值机制天然兼容老数据升级。
 * 主题等其余全局项见 [edu.gcp.schedule.data.prefs.DisplayPrefsStore]。
 */
@Serializable
data class TimetablePrefs(
    /**
     * 【遗留】周末显示的单开关（关 = 只显示周一到周五）。
     *
     * 根因：需求要求周六、周日可分别开关，一个 Boolean 表达不了「只显示周日不显示周六」。
     * 方案：读路径上把它当作 [showSaturday]/[showSunday] 的**迁移来源**——
     * 老数据 `false` 映射成「两天都关」，与新字段的默认值等效时自然退化；
     * 写路径自 v4 起只写新字段，本字段仅保证老 JSON 反序列化不丢信息（见 [afterDecode]）。
     */
    val showWeekend: Boolean = true,
    val showNonCurrentWeek: Boolean = false,
    val courseFilter: CourseFilter = CourseFilter.All,
    /** 课名目标字号（dp）；null = 跟随系统。范围与换算见 [GridFont]。 */
    val gridFontDp: Float? = null,
    /** 地点目标字号（dp）；null = 按课名倍率等比缩放。 */
    val gridRoomDp: Float? = null,
    /** 教师目标字号（dp）；null = 按课名倍率等比缩放。 */
    val gridTeacherDp: Float? = null,
    /**
     * 时间轴（左侧节次号 + 起止时间）目标字号（dp）；null = 跟随系统。
     *
     * 根因：时间轴此前和课名共用同一个 `gridScale`（由 [gridFontDp] 换算），
     * 于是「课程名字号」的滑块会连带把左上角月份、左侧节次与时间一起放大/缩小——
     * 两处关注点不同（课名要看得清，时间轴只是定位参照），必须拆开。
     */
    val gridRailDp: Float? = null,
    /** 月份/日期表头目标字号（dp）；null = 跟随系统。与时间轴同理，独立于课名字号。 */
    val gridDateDp: Float? = null,
    /**
     * 左侧时间轴栏宽（dp）。默认 48dp；决定列宽 =（可用宽 − 栏宽）/ 列数，
     * 也决定节次号 + 起止时间三行文字的可用宽度。
     */
    val railWidthDp: Float = DefaultRailWidthDp,
    /**
     * 顶部表头（星期 + 日期）高度（dp）。默认 44dp；参与自适应行高计算——
     * 表头调高后网格可用高度变矮，行高自动收，不需要额外迁移。
     */
    val dayHeaderHeightDp: Float = DefaultDayHeaderHeightDp,
    val rowHeightScale: Float = 1.1f,
    val cellRadiusDp: Float = 6f,
    val cellOpacity: Float = 1f,
    /**
     * 格子文字水平居中。默认开（2026-09 调整：用户偏好居中排版，WakeUp 左对齐降为可选项）。
     */
    val cellCenterH: Boolean = true,
    /**
     * 格子文字竖直居中。默认开；关 = 顶部起排、教师沉底。
     */
    val cellCenterV: Boolean = true,
    val showTeacher: Boolean = true,
    val showNowLine: Boolean = true,
    /**
     * 色块白色虚线描边。默认关（2026-09 调整：描边在部分壁纸/深色下观感发糊，
     * WakeUp 式描边降为可选项）。
     */
    val showCellBorder: Boolean = false,
    val showGridLines: Boolean = true,
    /** 显示星期六。与 [showSunday] 独立，分别控制两列的可见性。 */
    val showSaturday: Boolean = true,
    /** 显示星期日。 */
    val showSunday: Boolean = true,
    /** 地点前是否显示「@」前缀。关 = 只显示地点文本本身，省一格宽度。 */
    val showAtSign: Boolean = true,
    /**
     * 点击课表空白格新建课程。默认关（2026-09 调整：横滑切周时指腹压格误触弹编辑的
     * 反馈多，开课编辑的主入口走导入弹层 →「手动添加」与课程详情 → 编辑）。
     */
    val tapBlankToAdd: Boolean = false,
    /**
     * 周末拆分的迁移标记。
     *
     * 根因：[showWeekend] 与 [showSaturday]/[showSunday] 无法互推——
     * 「只关周六」会让 showWeekend 为 false 而 showSunday 为 true，
     * 若每次解码都无条件 `showSunday = showWeekend`，下一次读库就会把这个设置抹掉。
     * 方案：老 JSON（无此字段）读出来是 false → 才做一次迁移；写过库后该位为 true，永不再迁移。
     */
    val weekendSplitMigrated: Boolean = false,
) {
    companion object {
        /**
         * 布局尺寸的滑块范围与默认值：单一来源。
         * WeekScreen 的布局常量与显示设置滑块两端都引用这里，改一处即全局一致。
         */
        const val MinRailWidthDp = 28f
        const val MaxRailWidthDp = 72f
        const val DefaultRailWidthDp = 48f

        const val MinDayHeaderHeightDp = 32f
        const val MaxDayHeaderHeightDp = 64f
        const val DefaultDayHeaderHeightDp = 44f

        private val format = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        /**
         * 解码失败一律退回默认值：prefs_json 是自己写自己的数据，正常不该坏；
         * 真坏了（换版本/手改）也不该让读库路径抛异常崩掉整张课表。
         */
        fun decode(text: String): TimetablePrefs = try {
            format.decodeFromString(serializer(), text).let { raw ->
                // 仅老数据（无标记）迁移一次：showWeekend 展开成两个独立开关。
                // 标记落库后不再进入此分支，避免覆盖用户「只关周六/只关周日」的选择。
                if (raw.weekendSplitMigrated) raw
                else raw.copy(
                    showSaturday = raw.showWeekend,
                    showSunday = raw.showWeekend,
                    weekendSplitMigrated = true,
                )
            }
        } catch (_: Exception) {
            TimetablePrefs()
        }
    }

    fun encode(): String = Companion.format.encodeToString(serializer(), this)
}

/**
 * 课程类型。
 *
 * 根因：教务有两个独立的课表入口——「学期理论课表」`xskb_list.do` 与「实验课表」`syjx/toXskb.do`，
 * 两张页面的 DOM 结构与字段完全不同（实验课表按「周次 × 节次」两级分组，且不提供教师）。
 * 导入后必须能区分，否则实验课无法追溯来源，也做不了「只看理论课」这类过滤。
 *
 * [Exam]（DESIGN §4.14）：考试安排也用 [Course] 承载——日期落 `weeks`(单元素)+`day`、
 * 起止时刻落 `customStartTime/EndTime`、节次按作息表相交映射、考场落 `position`；
 * 没有独立实体，无需 schema 迁移。
 */
enum class CourseKind {
    Theory,
    Lab,
    Exam;

    val label: String
        get() = when (this) {
            Theory -> "理论课"
            Lab -> "实验课"
            Exam -> "考试"
        }
}

/**
 * 课表页的课程类型筛选。
 * 实验课常常集中在某几周、整周排满或整周没有，和理论课混在一起看时不好判断，
 * 允许只看其中一类。默认「全部」——分成两张课表反而容易漏事。
 */
enum class CourseFilter {
    All,
    Theory,
    Lab,
    Exam;

    fun matches(kind: CourseKind): Boolean = when (this) {
        All -> true
        Theory -> kind == CourseKind.Theory
        Lab -> kind == CourseKind.Lab
        Exam -> kind == CourseKind.Exam
    }

    val label: String
        get() = when (this) {
            All -> "全部"
            Theory -> "理论课"
            Lab -> "实验课"
            Exam -> "考试"
        }
}

/**
 * 课表领域模型，字段对齐拾光互通 JSON（DESIGN 4.3）。
 * P1 仅建模，P2 再接 Room。
 */
data class Course(
    val id: Long,
    val name: String,
    val teacher: String,
    val position: String,
    /** 1=周一 … 7=周日 */
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: Set<Int>,
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
    val colorIndex: Int = 0,
    val kind: CourseKind = CourseKind.Theory,
)

data class TimeSlot(
    val number: Int,
    val startTime: String,
    val endTime: String,
)

data class SemesterConfig(
    /** yyyy-MM-dd */
    val startDate: String,
    val totalWeeks: Int = 20,
    /** 1=周一 */
    val firstDayOfWeek: Int = 1,
)
