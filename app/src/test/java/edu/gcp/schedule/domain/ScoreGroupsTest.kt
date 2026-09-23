package edu.gcp.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 成绩学年分组口径（DESIGN §4.15）：`2024-2025-1/2` → 学年 `2024-2025`，
 * 最早学年 = 大一；脏学期号各自成组、不参与年级编号。
 */
class ScoreGroupsTest {

    @Test
    fun academicYearParsesStandardTermFormat() {
        assertEquals("2024-2025", ScoreGroups.academicYearOf("2024-2025-1"))
        assertEquals("2024-2025", ScoreGroups.academicYearOf("2024-2025-2"))
        // 分组口径宽松：任何学期序号都按学年归并（不限定两学期制）
        assertEquals("2024-2025", ScoreGroups.academicYearOf("2024-2025-3"))
        assertNull(ScoreGroups.academicYearOf("2024-2025"))
        assertNull(ScoreGroups.academicYearOf(""))
    }

    @Test
    fun groupsByYearNewestFirstWithGradeLabels() {
        val groups = ScoreGroups.group(
            listOf("2025-2026-2", "2025-2026-1", "2024-2025-2", "2024-2025-1"),
        )
        assertEquals(2, groups.size)
        // 最新学年在前；年级标签按升序编号，最早学年 = 大一
        assertEquals("2025-2026", groups[0].academicYear)
        assertEquals("大二", groups[0].gradeLabel)
        assertEquals(listOf("2025-2026-2", "2025-2026-1"), groups[0].terms)
        assertEquals("2024-2025", groups[1].academicYear)
        assertEquals("大一", groups[1].gradeLabel)
        assertEquals(listOf("2024-2025-2", "2024-2025-1"), groups[1].terms)
    }

    @Test
    fun onlyOneYearStillLabelledFreshman() {
        val groups = ScoreGroups.group(listOf("2025-2026-1"))
        assertEquals("大一", groups.single().gradeLabel)
    }

    @Test
    fun unparsableTermsFormOwnGroupsAtEnd() {
        val groups = ScoreGroups.group(listOf("2025-2026-1", "交换学年", "2024-2025-1"))
        assertEquals(3, groups.size)
        assertEquals("2025-2026", groups[0].academicYear)
        assertEquals("2024-2025", groups[1].academicYear)
        // 脏学期号：单独成组、无年级标签、追加在末尾
        assertEquals("交换学年", groups[2].academicYear)
        assertNull(groups[2].gradeLabel)
        assertEquals(listOf("交换学年"), groups[2].terms)
    }

    @Test
    fun emptyInputYieldsEmptyGroups() {
        assertEquals(emptyList<YearGroup>(), ScoreGroups.group(emptyList()))
    }
}
