package edu.gcp.schedule.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 调课自动检测（DESIGN §4.17）：三方合并差异模型。
 *
 * 参照物是**基线快照**（上次「教务数据成为本地数据」时刻的教务原始数据），
 * 不是本地课表——本地课表可能被用户调过课（§4.11）或手动编辑，
 * 直接拿它和教务比无法归因「教务改了」还是「用户自己调的」。
 * 报告只报 diff(base→theirs)（纯教务改动），用户的调课根本不参与；
 * diff(base→ours) 只用于冲突判定，不进报告。
 */

/** 字符串 → [CourseKind]，脏值退 Theory（与 data.local 同口径；domain 不反向依赖 data）。 */
private fun courseKindFromName(value: String?): CourseKind =
    CourseKind.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: CourseKind.Theory
object ScheduleDetector {

    /**
     * 「出现」：一行课在某一周的实际存在，比较的最小单位。
     * 行级比较立不住——调课拆行后行结构与基线对不上；出现级展开天然无视行的拆法。
     */
    private data class Occurrence(
        val day: Int,
        val startSection: Int,
        val endSection: Int,
        val week: Int,
        val teacher: String,
        val position: String,
    )

    private fun Course.occurrences(): Set<Occurrence> =
        weeks.mapTo(mutableSetOf()) {
            Occurrence(day, startSection, endSection, it, teacher, position)
        }

    /**
     * 三方合并。输入三份课程列表（理论 + 实验混合即可，kind 是分组键的一部分），
     * 输出按「kind + 课程名」分组的差异；配对一级键 = 课程名（教务极少改名，
     * 真改名自然表现为「删一门 + 加一门」，可接受）。
     *
     * - 教务侧与本地侧终态一致（含双方都停课、双方改到同一形态）→ 不报；
     * - 教务没动（ours 与 base 的差异 = 用户自己的调课/编辑）→ 不报；
     * - 教务动了、本地没动 → 普通差异组；
     * - 两边都动且终态不同 → 冲突组（更新课表页单选「保留本地 / 以教务为准」）。
     */
    fun detect(base: List<Course>, ours: List<Course>, theirs: List<Course>): List<DetectGroup> {
        fun keyOf(c: Course): Pair<CourseKind, String> = c.kind to c.name
        val baseBy = base.groupBy { keyOf(it) }
        val oursBy = ours.groupBy { keyOf(it) }
        val theirsBy = theirs.groupBy { keyOf(it) }

        return (baseBy.keys + oursBy.keys + theirsBy.keys).distinct().mapNotNull { key ->
            val baseOcc = baseBy[key].orEmpty().flatMap { it.occurrences() }.toSet()
            val oursOcc = oursBy[key].orEmpty().flatMap { it.occurrences() }.toSet()
            val theirsOcc = theirsBy[key].orEmpty().flatMap { it.occurrences() }.toSet()
            when {
                // 教务终态与本地一致：无可更新（覆盖「教务停了你也删了」「双方改成一样」）
                theirsOcc == oursOcc -> null
                // 教务没动：本地与基线的差异是用户自己的操作，不进报告
                theirsOcc == baseOcc -> null
                // 本地与基线一致：纯教务改动
                oursOcc == baseOcc ->
                    DetectGroup(key.first, key.second, conflict = false, oursBy[key].orEmpty(), theirsBy[key].orEmpty())
                // 两边都动且终态不同
                else ->
                    DetectGroup(key.first, key.second, conflict = true, oursBy[key].orEmpty(), theirsBy[key].orEmpty())
            }
        }.sortedWith(compareBy({ it.kind }, { it.name }))
    }
}

/** 一门课的差异组：kind + 课程名相同的教学活动归一组，更新课表页的勾选粒度。 */
data class DetectGroup(
    val kind: CourseKind,
    val name: String,
    /** 本地也动过（与教务改动撞车），更新课表页改为单选而非默认勾选。 */
    val conflict: Boolean,
    /** 本地当前行（空 = 教务新增的课）。 */
    val localCourses: List<Course>,
    /** 教务当前行（空 = 教务停课）。应用 = 用这份替换本地同名组。 */
    val remoteCourses: List<Course>,
) {
    /** 组类型标签（更新课表页分组展示）。 */
    val typeLabel: String
        get() = when {
            conflict -> "冲突"
            localCourses.isEmpty() -> "教务新增"
            remoteCourses.isEmpty() -> "教务停课"
            else -> "有变更"
        }
}

/**
 * 差异报告的持久化 DTO。[Course] 不直接可序列化，这里用扁平结构承接；
 * 字段与 courses 表一一对应，kind 存小写名（与 DB 同口径）。
 */
@Serializable
data class DetectCourseDto(
    val name: String,
    val teacher: String,
    val position: String,
    val day: Int,
    val startSection: Int,
    val endSection: Int,
    val weeks: List<Int>,
    val isCustomTime: Boolean = false,
    val customStartTime: String? = null,
    val customEndTime: String? = null,
    val colorIndex: Int = 0,
    val kind: String = "theory",
) {
    fun toDomain(): Course = Course(
        id = 0,
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

    companion object {
        fun fromDomain(course: Course): DetectCourseDto = DetectCourseDto(
            name = course.name,
            teacher = course.teacher,
            position = course.position,
            day = course.day,
            startSection = course.startSection,
            endSection = course.endSection,
            weeks = course.weeks.sorted(),
            isCustomTime = course.isCustomTime,
            customStartTime = course.customStartTime,
            customEndTime = course.customEndTime,
            colorIndex = course.colorIndex,
            kind = course.kind.name.lowercase(),
        )
    }
}

@Serializable
data class DetectGroupDto(
    val kind: String,
    val name: String,
    val conflict: Boolean,
    val local: List<DetectCourseDto>,
    val remote: List<DetectCourseDto>,
) {
    fun toDomain(): DetectGroup = DetectGroup(
        kind = courseKindFromName(kind),
        name = name,
        conflict = conflict,
        localCourses = local.map { it.toDomain() },
        remoteCourses = remote.map { it.toDomain() },
    )

    companion object {
        fun fromDomain(group: DetectGroup): DetectGroupDto = DetectGroupDto(
            kind = group.kind.name.lowercase(),
            name = group.name,
            conflict = group.conflict,
            local = group.localCourses.map { DetectCourseDto.fromDomain(it) },
            remote = group.remoteCourses.map { DetectCourseDto.fromDomain(it) },
        )
    }
}

/**
 * 一次抓取的教务全量快照（理论 + 实验）。既是基线的载体，也内嵌进报告——
 * 应用更新（哪怕只勾一部分）后基线整体推进为这份快照，未勾选的组视为用户默许忽略。
 */
@Serializable
data class DetectSnapshotPayload(
    val term: String? = null,
    val theory: List<DetectCourseDto> = emptyList(),
    val lab: List<DetectCourseDto> = emptyList(),
) {
    fun allCourses(): List<Course> = theory.map { it.toDomain() } + lab.map { it.toDomain() }

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }

        fun fromCourses(term: String?, theory: List<Course>, lab: List<Course>): DetectSnapshotPayload =
            DetectSnapshotPayload(
                term = term,
                theory = theory.map { DetectCourseDto.fromDomain(it) },
                lab = lab.map { DetectCourseDto.fromDomain(it) },
            )

        /** 自己写自己的数据，脏 JSON 一律退空——读路径不抛异常。 */
        fun decode(text: String): DetectSnapshotPayload? = try {
            lenient.decodeFromString(serializer(), text)
        } catch (_: Exception) {
            null
        }
    }
}

@Serializable
data class DetectReportPayload(
    val term: String? = null,
    val checkedAt: Long,
    val groups: List<DetectGroupDto>,
    /** 本次抓取的教务全量；应用更新后用它整体刷新基线。 */
    val snapshot: DetectSnapshotPayload,
) {
    fun toGroups(): List<DetectGroup> = groups.map { it.toDomain() }

    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        private val lenient = Json { ignoreUnknownKeys = true }

        /** 自己写自己的数据，脏 JSON 一律退空——读路径不抛异常。 */
        fun decode(text: String): DetectReportPayload? = try {
            lenient.decodeFromString(serializer(), text)
        } catch (_: Exception) {
            null
        }
    }
}

/** 连续凭证失败的保护（DESIGN §4.17）：防反复自动登录触发验证码把账号锁住。 */
object DetectFailurePolicy {
    const val MAX_CREDENTIAL_FAILURES = 3

    /** 达到上限 → 自动停用检测（等用户到设置页重新开启，开启时计数清零）。 */
    fun shouldDisableAfterCredentialFailure(consecutiveFailures: Int): Boolean =
        consecutiveFailures >= MAX_CREDENTIAL_FAILURES
}
