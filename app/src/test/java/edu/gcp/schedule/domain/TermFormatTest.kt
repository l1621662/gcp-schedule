package edu.gcp.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 学期口径归一化（TERM 回归钉）。
 *
 * 真机 bug（2026-09-23）：网页导入存的是页面标题原文「2026-2027学年第一学期」，
 * 调课检测给的是「2026-2027-1」，两边永不相等 → 每次检测都误报「教务已切换学期」。
 * 这里钉住：两种写法必须被认成同一个学期。
 */
class TermFormatTest {

    @Test
    fun canonicalFormKeepsAsIs() {
        assertEquals("2026-2027-1", TermFormat.normalize("2026-2027-1"))
        assertEquals("2026-2027-2", TermFormat.normalize(" 2026-2027-2 "))
        assertEquals("2026-2027-3", TermFormat.normalize("2026-2027-3"))
    }

    @Test
    fun academicYearTitleIsNormalized() {
        assertEquals("2026-2027-1", TermFormat.normalize("2026-2027学年第一学期"))
        assertEquals("2026-2027-2", TermFormat.normalize("2026-2027学年第二学期"))
        assertEquals("2026-2027-3", TermFormat.normalize("2026-2027学年第3学期"))
        assertEquals("2026-2027-1", TermFormat.normalize("2026-2027学年 1 学期"))
        assertEquals("2026-2027-1", TermFormat.normalize("2026 - 2027 学年第一学期"))
    }

    @Test
    fun seasonTitleIsNormalized() {
        assertEquals("2026-2027-1", TermFormat.normalize("2026-2027学年秋季学期"))
        assertEquals("2026-2027-2", TermFormat.normalize("2026-2027学年春季学期"))
    }

    @Test
    fun unknownInputReturnsNull() {
        assertNull(TermFormat.normalize(null))
        assertNull(TermFormat.normalize(""))
        assertNull(TermFormat.normalize("个人课表信息"))
        assertNull(TermFormat.normalize("2026-2027"))
        assertNull(TermFormat.normalize("第 1 学期"))
    }

    /** 关键回归：两条链路的口径必须判为同一个学期。 */
    @Test
    fun sameTerm_matchesBothWays() {
        assertTrue(TermFormat.sameTerm("2026-2027-1", "2026-2027学年第一学期"))
        assertTrue(TermFormat.sameTerm("2026-2027-2", "2026-2027学年第二学期"))
        assertFalse(TermFormat.sameTerm("2026-2027-1", "2026-2027学年第二学期"))
        assertFalse(TermFormat.sameTerm("2026-2027-1", "2025-2026-1"))
        assertFalse("缺学期不算相同，交给上层跳过比较", TermFormat.sameTerm(null, "2026-2027-1"))
        assertFalse(TermFormat.sameTerm("", "2026-2027-1"))
    }
}
