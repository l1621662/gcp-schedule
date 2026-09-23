package edu.gcp.schedule.domain

/**
 * 一条课程成绩（DESIGN §4.15）。成绩不是课程（没有排课语义），独立存储、按学期替换。
 *
 * 成绩双口径：[score] 是教务给的数值分（等级制成绩为 null），[scoreStr] 是展示口径
 * （"84" / "优" / "及格"）。统计一律以 [score] 非空为准，展示一律用 [scoreStr]。
 */
data class ScoreRecord(
    val id: Long = 0,
    /** 学年学期，如 2025-2026-2。 */
    val term: String,
    val courseNo: String = "",
    val name: String,
    val unit: String = "",
    val credit: Double = 0.0,
    val hours: Double = 0.0,
    /** 考试方式：考试 / 考查。 */
    val examForm: String = "",
    /** 必修 / 选修。 */
    val courseAttr: String = "",
    /** 课程性质（通识必修课、学科基础课…）。 */
    val category: String = "",
    val score: Double? = null,
    val scoreStr: String = "",
    val gradePoint: Double? = null,
    /** 考试性质：正常考试 / 补考…。 */
    val status: String = "",
    /** 评教未完成被教务锁定：分数不展示，也不进统计。 */
    val pendingReview: Boolean = false,
)

/** 一个学期（或按学年分组时的一个学年）的成绩汇总。 */
data class TermSummary(
    val term: String,
    /** 参与统计的课程数（已认定、有数值分的）。 */
    val courseCount: Int,
    /** 修得学分：及格（数值分 ≥60 或等级制绩点 >0）课程的学分之和。 */
    val credits: Double = 0.0,
    /** 加权平均分：Σ(分数×学分) / Σ学分；无比计分时 null。 */
    val weightedAverage: Double? = null,
    /** 平均绩点：Σ(绩点×学分) / Σ学分；无比计分时 null。 */
    val gpa: Double? = null,
    /** 评教未完成被锁定的课程数。 */
    val lockedCount: Int = 0,
    /** 加权平均分与平均绩点中被排除的任选课门数（通识任选、专业任选等；修得学分不受影响）。 */
    val excludedCount: Int = 0,
)

/** 成绩列表排序口径（DESIGN §4.15）。 */
enum class ScoreSortMode {
    /** 入库顺序（按学期替换写库的自然顺序，即教务成绩单顺序）。 */
    Default,
    /** 数值分从高到低；无数值分（等级制、待评教）排末尾，组内保持原有相对顺序。 */
    ByScore,
    /** 绩点从高到低；无绩点排末尾。 */
    ByGradePoint,
}

object ScoreCalculator {

    /**
     * 汇总一个学期（或学年）。记录为空时返回 null（UI 直接显示空态）。
     *
     * [includeFreeElectives]：任选课是否计入两项平均（DESIGN §4.15）。默认排除——
     * 作者学校综测同样不计任选课；开关打开后恢复计入，[TermSummary.excludedCount] 仍照常报告门数。
     */
    fun summarize(
        term: String,
        records: List<ScoreRecord>,
        includeFreeElectives: Boolean = false,
    ): TermSummary? {
        if (records.isEmpty()) return null
        val locked = records.count { it.pendingReview }
        // 加权平均分/平均绩点默认不计任选课（通识任选、专业任选等，2026-09-19 用户拍板）；修得学分照常计入
        val counted = records.filter {
            !it.pendingReview && it.score != null && it.credit > 0.0 &&
                (includeFreeElectives || !isFreeElective(it))
        }
        val creditSum = counted.sumOf { it.credit }
        val weightedAvg = if (creditSum > 0.0) {
            counted.sumOf { it.score!! * it.credit } / creditSum
        } else {
            null
        }
        val gpaRecords = records.filter {
            !it.pendingReview && it.gradePoint != null && it.credit > 0.0 &&
                (includeFreeElectives || !isFreeElective(it))
        }
        val gpaCredit = gpaRecords.sumOf { it.credit }
        val gpa = if (gpaCredit > 0.0) {
            gpaRecords.sumOf { it.gradePoint!! * it.credit } / gpaCredit
        } else {
            null
        }
        return TermSummary(
            term = term,
            courseCount = records.size - locked,
            credits = round2(passedCredits(records)),
            weightedAverage = weightedAvg?.let { round2(it) },
            gpa = gpa?.let { round2(it) },
            lockedCount = locked,
            excludedCount = records.count { isFreeElective(it) },
        )
    }

    /**
     * 列表排序（DESIGN §4.15）。sortedByDescending 是稳定排序：同分与空值保持入库相对
     * 顺序，补考批次等重复课程不会因排序彼此打乱。
     */
    fun sort(records: List<ScoreRecord>, mode: ScoreSortMode): List<ScoreRecord> = when (mode) {
        ScoreSortMode.Default -> records
        ScoreSortMode.ByScore -> records.sortedByDescending { it.score ?: Double.NEGATIVE_INFINITY }
        ScoreSortMode.ByGradePoint -> records.sortedByDescending { it.gradePoint ?: Double.NEGATIVE_INFINITY }
    }

    /**
     * 任选课判定：课程性质含「任选」（强智 kcxzmc 如「通识任选课」「专业任选课」）。
     * 只排除任选；专业选修/限选等其它选修性质照常计入两项平均。
     */
    private fun isFreeElective(r: ScoreRecord): Boolean = r.category.contains("任选")

    /**
     * 修得学分：非锁定且及格的课程学分之和。及格判定双口径——数值分 ≥60；
     * 等级制没有数值分，用绩点 >0（「及格」档绩点 1.0，不及格为 0/null）。
     * 补考批次按记录逐条计入，与教务成绩单口径一致地简单。
     */
    fun passedCredits(records: List<ScoreRecord>): Double =
        records.filter { it.credit > 0.0 && !it.pendingReview && isPassed(it) }.sumOf { it.credit }

    private fun isPassed(r: ScoreRecord): Boolean = when {
        r.score != null -> r.score >= 60.0
        r.gradePoint != null -> r.gradePoint > 0.0
        else -> false
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}

/** 一个学年的成绩分组：`2024-2025-1/2` 归并为学年 `2024-2025`（DESIGN §4.15）。 */
data class YearGroup(
    val academicYear: String,
    /** 年级显示名（大一/大二/…）；无法解析学期号的组为 null。 */
    val gradeLabel: String?,
    /** 该学年包含的学期（倒序，最新在前）。 */
    val terms: List<String>,
)

object ScoreGroups {

    /** `2024-2025-1` → 学年 `2024-2025`；其余格式返回 null。 */
    fun academicYearOf(term: String): String? =
        termRe.matchEntire(term.trim())?.groupValues?.getOrNull(1)

    /**
     * 学年分组。年级标签按学年升序编号：**最早学年 = 大一**（成绩从入学学期开始积累）。
     * 返回按学年倒序（最新在前）；无法解析学期号的学期各自成组、追加在末尾、不带年级标签。
     */
    fun group(terms: List<String>): List<YearGroup> {
        val yearOf = HashMap<String, String?>()
        terms.forEach { term -> yearOf[term] = academicYearOf(term) }

        val byYear = terms.filter { yearOf[it] != null }
            .distinct()
            .groupBy { yearOf[it]!! }
        // 年级编号只看可解析的学年，按升序排
        val grades = byYear.keys.sorted()
            .mapIndexed { i, y -> y to gradeLabel(i) }
            .toMap()

        val yearGroups = byYear.entries
            .sortedByDescending { it.key }
            .map { (year, termList) ->
                YearGroup(
                    academicYear = year,
                    gradeLabel = grades[year],
                    terms = termList.sortedDescending(),
                )
            }
        // 脏学期号（解析失败）：各自成组放末尾，不参与年级编号
        val loose = terms.filter { yearOf[it] == null }
            .distinct()
            .map { YearGroup(academicYear = it, gradeLabel = null, terms = listOf(it)) }
        return yearGroups + loose
    }

    private val GRADES = arrayOf("大一", "大二", "大三", "大四", "大五", "大六", "大七", "大八")

    private fun gradeLabel(index: Int): String = GRADES.getOrElse(index) { "大${index + 1}" }

    private val termRe = Regex("""^(\d{4}-\d{4})-\d$""")
}
