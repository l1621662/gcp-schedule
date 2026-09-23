package edu.gcp.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示偏好默认值契约（2026-09 需求调整后的钉子）：
 * - 水平居中 / 竖直居中默认**开**；
 * - 虚线描边默认**关**；
 * - 点空白格新建课程默认**关**；
 * - 时间轴栏宽 48dp / 表头高度 44dp 与旧布局常量一致（升级后视觉零变化）。
 *
 * 这些默认值同时决定升级用户（prefs_json 里没有对应字段）和新装用户看到的第一眼，
 * 改动任何一项都应该是有意的需求行为，这里钉住防回归。
 */
class TimetablePrefsDefaultsTest {

    @Test
    fun defaultsMatchSpec() {
        val prefs = TimetablePrefs()
        assertTrue("文字水平居中默认开", prefs.cellCenterH)
        assertTrue("文字竖直居中默认开", prefs.cellCenterV)
        assertFalse("虚线描边默认关", prefs.showCellBorder)
        assertFalse("点空白格新建课程默认关", prefs.tapBlankToAdd)
    }

    @Test
    fun layoutSizeDefaultsMatchLegacyConstants() {
        val prefs = TimetablePrefs()
        assertEquals(48f, prefs.railWidthDp, 0f)
        assertEquals(44f, prefs.dayHeaderHeightDp, 0f)
        assertEquals(28f, TimetablePrefs.MinRailWidthDp, 0f)
        assertEquals(72f, TimetablePrefs.MaxRailWidthDp, 0f)
        assertEquals(32f, TimetablePrefs.MinDayHeaderHeightDp, 0f)
        assertEquals(64f, TimetablePrefs.MaxDayHeaderHeightDp, 0f)
    }

    /** 老 JSON（无新字段）解码后落到新默认值：升级用户第一眼与新装一致。 */
    @Test
    fun legacyJsonWithoutNewFieldsFallsBackToNewDefaults() {
        val legacy = """{"showWeekend":true,"rowHeightScale":1.1}"""
        val decoded = TimetablePrefs.decode(legacy)
        assertTrue(decoded.cellCenterH)
        assertTrue(decoded.cellCenterV)
        assertFalse(decoded.showCellBorder)
        assertFalse(decoded.tapBlankToAdd)
        assertEquals(48f, decoded.railWidthDp, 0f)
        assertEquals(44f, decoded.dayHeaderHeightDp, 0f)
    }

    /** 用户已写入的设置不能被新默认值覆盖。 */
    @Test
    fun persistedValuesSurviveDecode() {
        val stored = TimetablePrefs(
            cellCenterH = false,
            showCellBorder = true,
            tapBlankToAdd = true,
            railWidthDp = 60f,
            dayHeaderHeightDp = 56f,
        ).encode()
        val decoded = TimetablePrefs.decode(stored)
        assertFalse(decoded.cellCenterH)
        assertTrue(decoded.showCellBorder)
        assertTrue(decoded.tapBlankToAdd)
        assertEquals(60f, decoded.railWidthDp, 0f)
        assertEquals(56f, decoded.dayHeaderHeightDp, 0f)
    }
}
