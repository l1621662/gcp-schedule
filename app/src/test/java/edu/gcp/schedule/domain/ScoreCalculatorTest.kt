package edu.gcp.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 学期成绩汇总口径（DESIGN §4.15）：锁定课程不进统计，加权用学分；任选课（通识任选/专业任选）不进加权/绩点但计学分。 */
class ScoreCalculatorTest {

    private val records = listOf(
        ScoreRecord(term = "2025-2026-2", name = "高数", credit = 4.0, score = 90.0, scoreStr = "90", gradePoint = 4.0),
        ScoreRecord(term = "2025-2026-2", name = "英语", credit = 2.0, score = 80.0, scoreStr = "80", gradePoint = 3.0),
        // 等级制课程：无数值分，不进平均分；有绩点仍进 GPA
        ScoreRecord(term = "2025-2026-2", name = "劳动", credit = 1.0, score = null, scoreStr = "优", gradePoint = 4.0),
        // 评教锁定：两者都不进
        ScoreRecord(term = "2025-2026-2", name = "体育", credit = 1.0, score = 70.0, scoreStr = "70", gradePoint = 2.0, pendingReview = true),
    )

    @Test
    fun weightedAverageExcludesLockedAndGradeOnly() {
        val s = ScoreCalculator.summarize("2025-2026-2", records)!!
        // 加权平均分 = (90×4 + 80×2) / 6 = 86.67（劳动无数值分、体育锁定，都不进）
        assertEquals(86.67, s.weightedAverage!!, 1e-9)
        // GPA = (4×4 + 3×2 + 4×1) / 7 = 26/7 = 3.71
        assertEquals(3.71, s.gpa!!, 1e-9)
        assertEquals(3, s.courseCount)      // 4 门 − 1 门锁定
        assertEquals(1, s.lockedCount)
        // 修得学分 = 4 + 2 + 1（劳动等级制绩点 >0 视为及格；体育锁定不计）= 7
        assertEquals(7.0, s.credits, 1e-9)
    }

    @Test
    fun passedCreditsExcludesFailedAndLocked() {
        val list = listOf(
            // 数值分不及格：不计
            ScoreRecord(term = "t", name = "a", credit = 3.0, score = 50.0, scoreStr = "50", gradePoint = 0.0),
            // 等级制不及格（绩点 0）：不计
            ScoreRecord(term = "t", name = "b", credit = 2.0, score = null, scoreStr = "不及格", gradePoint = 0.0),
            // 数值分及格：计
            ScoreRecord(term = "t", name = "c", credit = 2.0, score = 60.0, scoreStr = "60", gradePoint = 1.0),
            // 等级制及格（绩点 >0）：计
            ScoreRecord(term = "t", name = "d", credit = 1.0, score = null, scoreStr = "中", gradePoint = 2.0),
            // 锁定：不计
            ScoreRecord(term = "t", name = "e", credit = 1.0, score = 80.0, pendingReview = true),
            // 0 学分：不计
            ScoreRecord(term = "t", name = "f", credit = 0.0, score = 90.0, gradePoint = 4.0),
        )
        assertEquals(3.0, ScoreCalculator.passedCredits(list), 1e-9)
    }

    @Test
    fun emptyTermReturnsNull() {
        assertNull(ScoreCalculator.summarize("2025-2026-2", emptyList()))
    }

    @Test
    fun allLockedReturnsNullAverages() {
        val locked = listOf(
            ScoreRecord(term = "t", name = "a", credit = 1.0, score = 60.0, pendingReview = true),
        )
        val s = ScoreCalculator.summarize("t", locked)!!
        assertNull(s.weightedAverage)
        assertNull(s.gpa)
        assertEquals(0, s.courseCount)
        assertEquals(1, s.lockedCount)
    }

    @Test
    fun zeroCreditRecordsDoNotDivideByZero() {
        val s = ScoreCalculator.summarize(
            "t",
            listOf(ScoreRecord(term = "t", name = "a", credit = 0.0, score = 60.0, gradePoint = 1.0)),
        )!!
        assertNull(s.weightedAverage)
        assertNull(s.gpa)
    }

    /** 任选课（通识任选/专业任选，2026-09-19 用户拍板）：不进加权平均分与绩点，修得学分照常计入。 */
    @Test
    fun freeElectivesExcludedFromAveragesButCountedInCredits() {
        val list = listOf(
            ScoreRecord(term = "t", name = "高数", credit = 4.0, score = 90.0, scoreStr = "90", gradePoint = 4.0),
            ScoreRecord(
                term = "t", name = "影视鉴赏", credit = 2.0, score = 60.0, scoreStr = "60",
                gradePoint = 1.0, category = "通识任选课",
            ),
            ScoreRecord(
                term = "t", name = "职场沟通", credit = 2.0, score = 80.0, scoreStr = "80",
                gradePoint = 3.0, category = "专业任选课",
            ),
        )
        val s = ScoreCalculator.summarize("t", list)!!
        // 两门任选课都被排除：加权平均分只算高数 90.0（不排除会被摊薄成 (90×4+80×2+60×2)/8 = 80）
        assertEquals(90.0, s.weightedAverage!!, 1e-9)
        assertEquals(4.0, s.gpa!!, 1e-9)
        // 修得学分不受影响：及格的任选课照常计入
        assertEquals(8.0, s.credits, 1e-9)
        assertEquals(2, s.excludedCount)
        // 开关打开：任选课回到两项平均（加权 (90×4+80×2+60×2)/8 = 80；绩点 (4×4+3×2+1×2)/8 = 3）
        val included = ScoreCalculator.summarize("t", list, includeFreeElectives = true)!!
        assertEquals(80.0, included.weightedAverage!!, 1e-9)
        assertEquals(3.0, included.gpa!!, 1e-9)
        // 修得学分与排除门数口径不受开关影响
        assertEquals(8.0, included.credits, 1e-9)
        assertEquals(2, included.excludedCount)
    }

    /** 排序三档（DESIGN §4.15）：空值垫底且保持原相对顺序，默认档不重排。 */
    @Test
    fun sortByScoreAndGradePointPutNullsLast() {
        val a = ScoreRecord(term = "t", name = "a", credit = 1.0, score = 85.0, scoreStr = "85", gradePoint = 3.0)
        val b = ScoreRecord(term = "t", name = "b", credit = 1.0, score = 95.0, scoreStr = "95", gradePoint = 4.0)
        // 等级制「中」：无数值分但有绩点——两档排序的落点因此不同
        val c = ScoreRecord(term = "t", name = "c", credit = 1.0, scoreStr = "中", gradePoint = 2.0)
        // 补考通过：有数值分但无绩点
        val d = ScoreRecord(term = "t", name = "d", credit = 1.0, score = 60.0, scoreStr = "60")
        val original = listOf(a, b, c, d)
        // 成绩档：c 无数值分垫底（原相对顺序），d(60) 在其前
        assertEquals(listOf(b, a, d, c), ScoreCalculator.sort(original, ScoreSortMode.ByScore))
        // 绩点档：d 无绩点垫底，c(2.0) 在其前
        assertEquals(listOf(b, a, c, d), ScoreCalculator.sort(original, ScoreSortMode.ByGradePoint))
        assertEquals(original, ScoreCalculator.sort(original, ScoreSortMode.Default))
    }
}
