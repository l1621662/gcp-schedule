package edu.gcp.schedule.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.CourseKind
import edu.gcp.schedule.domain.ScoreRecord
import edu.gcp.schedule.domain.SemesterConfig
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.domain.Timetable

/**
 * 单个课表的元信息与课表级设置（DESIGN §4.9）。
 *
 * 学期配置、作息表、课程都以 timetableId 归属。显示偏好 v3 时期以 JSON 存在本表，
 * 2026-09-19 起全局化（DataStore `view_prefs_json`）——[prefsJson] 列保留只为 schema
 * 稳定（迁移只增不删），新代码不读不写。
 */
@Entity(tableName = "timetables")
data class TimetableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val sortOrder: Int,
    /** 用户是否手工改过作息；结构性作息迁移只对未自定义的课表生效。 */
    val slotsCustomized: Boolean = false,
    /** 【退役列】v3 的课表级显示偏好 JSON。仅历史迁移（globalizeViewPrefs）还会读。 */
    val prefsJson: String,
) {
    fun toDomain(): Timetable = Timetable(
        id = id,
        name = name,
        createdAt = createdAt,
        sortOrder = sortOrder,
        slotsCustomized = slotsCustomized,
    )
}

@Entity(
    tableName = "courses",
    indices = [Index("timetableId")],
)
data class CourseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 归属课表；DEFAULT 1 使 v2 老数据迁移后自动落进默认课表 */
    val timetableId: Long = 1,
    val name: String,
    val teacher: String,
    val position: String,
    /** 1=周一 … 7=周日 */
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    /** 逗号分隔周次，如 1,3,5-8 展开为 1,3,5,6,7,8 */
    val weeksCsv: String,
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
    val colorIndex: Int = 0,
    /** [CourseKind] 的小写名；DB 默认 'theory'，老数据迁移后自动落为理论课 */
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
        weeks = weeksCsv.split(',')
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet(),
        isCustomTime = isCustomTime,
        customStartTime = customStartTime,
        customEndTime = customEndTime,
        colorIndex = colorIndex,
        kind = courseKindFromName(kind),
    )

    companion object {
        fun fromDomain(course: Course): CourseEntity = CourseEntity(
            id = course.id.takeIf { it > 0 } ?: 0,
            name = course.name,
            teacher = course.teacher,
            position = course.position,
            day = course.day,
            startSection = course.startSection,
            endSection = course.endSection,
            weeksCsv = course.worstCaseWeeksCsv(),
            isCustomTime = course.isCustomTime,
            customStartTime = course.customStartTime,
            customEndTime = course.customEndTime,
            colorIndex = course.colorIndex,
            kind = course.kind.name.lowercase(),
        )
    }
}

/**
 * 字符串 → [CourseKind]。
 * 不用 `CourseKind.valueOf`：库里可能存着历史脏值或大小写差异，取不到时应退回 Theory 而不是抛异常崩在读库路径上。
 */
fun courseKindFromName(value: String?): CourseKind =
    CourseKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CourseKind.Theory

private fun Course.worstCaseWeeksCsv(): String =
    weeks.sorted().joinToString(",")

/**
 * 作息表。v3 起每张课表一份：主键从 number 变为 (timetableId, number)。
 * SQLite 不能改主键，v2→v3 迁移用「建新表→搬数据→改名」重建（见 JuwDatabase.MIGRATION_2_3）。
 */
@Entity(tableName = "time_slots", primaryKeys = ["timetableId", "number"])
data class TimeSlotEntity(
    val timetableId: Long,
    val number: Int,
    val startTime: String,
    val endTime: String,
) {
    fun toDomain(): TimeSlot = TimeSlot(number, startTime, endTime)
}

/**
 * 学期配置。v3 起每张课表一份：主键从固定 id=1 变为 timetableId。
 */
@Entity(tableName = "semester_config", primaryKeys = ["timetableId"])
data class SemesterConfigEntity(
    val timetableId: Long,
    val startDate: String,
    val totalWeeks: Int,
    val firstDayOfWeek: Int,
) {
    fun toDomain(): SemesterConfig = SemesterConfig(startDate, totalWeeks, firstDayOfWeek)

    companion object {
        fun fromDomain(timetableId: Long, config: SemesterConfig): SemesterConfigEntity =
            SemesterConfigEntity(
                timetableId = timetableId,
                startDate = config.startDate,
                totalWeeks = config.totalWeeks,
                firstDayOfWeek = config.firstDayOfWeek,
            )
    }
}

/**
 * 调课自动检测的教务基线快照（DESIGN §4.17）：每课表一份。
 *
 * 基线 = 上次「教务数据成为本地数据」时刻的教务原始数据（理论 + 实验全量 JSON）。
 * 刷新时机只有两个：教务导入确认落库后、应用检测报告后——用户调课/编辑**不**刷新，
 * 这是三方合并能区分「教务改课」与「用户自己调课」的全部前提。
 */
@Entity(tableName = "detect_baselines", primaryKeys = ["timetableId"])
data class DetectBaselineEntity(
    val timetableId: Long,
    /** 建基线时的教务学期（xnxq01id 口径）；检测时与教务当前学期比对，换学期提示重建。 */
    val term: String,
    /** [edu.gcp.schedule.domain.DetectSnapshotPayload] 的 JSON。 */
    val payload: String,
    val updatedAt: Long,
)

/**
 * 调课自动检测的最新差异报告（DESIGN §4.17）：每课表只留一份。
 * `unread` 驱动课表页导入图标的气泡与导入弹层里的「检测课表更新」；应用/忽略后置 0。
 */
@Entity(tableName = "detect_reports", primaryKeys = ["timetableId"])
data class DetectReportEntity(
    val timetableId: Long,
    /** [edu.gcp.schedule.domain.DetectReportPayload] 的 JSON（含差异组与教务全量快照）。 */
    val payload: String,
    val unread: Boolean,
    val updatedAt: Long,
) {
    companion object {
        fun unread(timetableId: Long, payload: String, now: Long) =
            DetectReportEntity(timetableId, payload, unread = true, updatedAt = now)
    }
}

class Converters {
    @TypeConverter
    fun fromWeeksSet(weeks: Set<Int>): String = weeks.sorted().joinToString(",")

    @TypeConverter
    fun toWeeksSet(value: String): Set<Int> =
        value.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
}

/**
 * 课程成绩（DESIGN §4.15）。全局归属学生、不挂 timetableId；
 * 写入口径是「按学期替换」——同一学期先删后插，重复导入不产生重复行。
 */
@Entity(
    tableName = "scores",
    indices = [Index("term")],
)
data class ScoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 学年学期，如 2025-2026-2 */
    val term: String,
    val courseNo: String,
    val name: String,
    val unit: String,
    val credit: Double,
    val hours: Double,
    val examForm: String,
    val courseAttr: String,
    val category: String,
    /** 数值分；等级制成绩为 null */
    val score: Double?,
    val scoreStr: String,
    val gradePoint: Double?,
    val status: String,
    val pendingReview: Boolean,
    val importedAt: Long,
) {
    fun toDomain(): ScoreRecord = ScoreRecord(
        id = id,
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

    companion object {
        fun fromDomain(record: ScoreRecord, importedAt: Long): ScoreEntity = ScoreEntity(
            id = record.id.takeIf { it > 0 } ?: 0,
            term = record.term,
            courseNo = record.courseNo,
            name = record.name,
            unit = record.unit,
            credit = record.credit,
            hours = record.hours,
            examForm = record.examForm,
            courseAttr = record.courseAttr,
            category = record.category,
            score = record.score,
            scoreStr = record.scoreStr,
            gradePoint = record.gradePoint,
            status = record.status,
            pendingReview = record.pendingReview,
            importedAt = importedAt,
        )
    }
}
