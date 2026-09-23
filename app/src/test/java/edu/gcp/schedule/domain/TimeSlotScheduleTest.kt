package edu.gcp.schedule.domain

import edu.gcp.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 作息表（DESIGN 3.5）与网格几何相关的不变量。
 *
 * 这些断言的作用是：作息一旦被误改（比如手滑把某一节改成 45 分钟），单测会立刻失败，
 * 而不是等到界面上课块错位才发现。
 */
class TimeSlotScheduleTest {

    private val slots = DefaultData.defaultTimeSlots

    /** 作息共 11 小节且编号连续 */
    @Test
    fun slotsAreElevenAndContiguous() {
        assertEquals(11, slots.size)
        assertEquals((1..11).toList(), slots.map { it.number })
    }

    /** 每小节 40 分钟 */
    @Test
    fun everySlotLastsFortyMinutes() {
        slots.forEach { slot ->
            val duration = ScheduleCalculator.toMinutes(slot.endTime) -
                ScheduleCalculator.toMinutes(slot.startTime)
            assertEquals("第 ${slot.number} 节时长不是 40 分钟", 40, duration)
        }
    }

    /**
     * 大节内间隔 5 分钟；跨大节的间隔逐条钉死（工位换教室 / 午休 / 晚饭 各不同）。
     *
     * 2026-09-23 起本校实际作息：**6→7 只有 15 分钟**（15:25→15:40），
     * 所以这里不再写成「跨大节一律 ≥20」，而是每个边界显式断言——
     * 作息一旦被手滑改动，失败信息能直接指出是哪一段。
     */
    @Test
    fun gapsMatchBigSectionBoundaries() {
        fun gap(from: Int, to: Int): Int =
            ScheduleCalculator.toMinutes(slots[to].startTime) -
                ScheduleCalculator.toMinutes(slots[from].endTime)

        assertEquals("1-2 与 3-4 之间（换教室）", 20, gap(1, 2))
        assertEquals("3-4 与 5-6 之间（午休）", 140, gap(3, 4))
        assertEquals("5-6 与 7-8 之间（本校 15 分钟）", 15, gap(5, 6))
        assertEquals("7-8 与 9-11 之间（晚饭）", 85, gap(7, 8))

        for (i in 0 until slots.size - 1) {
            val g = gap(i, i + 1)
            if (!ScheduleCalculator.isBigSectionEnd(slots[i].number)) {
                assertEquals("第 ${slots[i].number} 节与大节内下一节之间", 5, g)
            }
        }
    }

    /** 关键锚点（2026-09-23 用户确认的本校作息） */
    @Test
    fun anchorsMatchSchoolTimetable() {
        assertEquals("08:30", slots.first().startTime)
        assertEquals("20:40", slots.last().endTime)
        assertEquals("10:15", slots[2].startTime)
        assertEquals("14:00", slots[4].startTime)
        assertEquals("15:40", slots[6].startTime)
        assertEquals("16:25", slots[7].startTime)
        assertEquals("18:30", slots[8].startTime)
        assertEquals("20:00", slots[10].startTime)
    }

    /** 大节分组覆盖 1..11 且不重叠 */
    @Test
    fun bigSectionsCoverAllSectionsOnce() {
        val groups = ScheduleCalculator.BIG_SECTIONS
        assertEquals(listOf(1..2, 3..4, 5..6, 7..8, 9..11), groups)
        val flat = groups.flatMap { it.toList() }
        assertEquals((1..11).toList(), flat)
        assertEquals(flat.size, flat.distinct().size)
    }

    /** 大节首末判定 */
    @Test
    fun bigSectionBoundaries() {
        assertTrue(ScheduleCalculator.isBigSectionStart(1))
        assertFalse(ScheduleCalculator.isBigSectionStart(2))
        assertTrue(ScheduleCalculator.isBigSectionEnd(2))
        assertFalse(ScheduleCalculator.isBigSectionEnd(3))
        assertTrue(ScheduleCalculator.isBigSectionStart(9))
        assertTrue(ScheduleCalculator.isBigSectionEnd(11))
    }

    /** 周次压缩显示 */
    @Test
    fun weeksAreCompacted() {
        assertEquals("6-11,14-15", ScheduleCalculator.formatWeeks(setOf(6, 7, 8, 9, 10, 11, 14, 15)))
        assertEquals("1", ScheduleCalculator.formatWeeks(setOf(1)))
        assertEquals("1,3,5", ScheduleCalculator.formatWeeks(setOf(5, 1, 3)))
        assertEquals("", ScheduleCalculator.formatWeeks(emptySet()))
    }

    /** 顺序占位不与已有颜色重复 */
    @Test
    fun nextColorIndexAvoidsUsedOnes() {
        val used = mutableListOf<Int>()
        repeat(ScheduleCalculator.PALETTE_SIZE) {
            val next = ScheduleCalculator.nextColorIndex(used)
            assertFalse("第 ${used.size + 1} 次分配撞色", next in used)
            used += next
        }
        // 全部槽位用满后只能复用
        assertTrue(ScheduleCalculator.nextColorIndex(used) in 0 until ScheduleCalculator.PALETTE_SIZE)
    }

    /** 整批配色与传入顺序无关，且不超过调色板容量时不撞色 */
    @Test
    fun sortedNameColorsAreStableAndDistinct() {
        val names = listOf(
            "液压与气压传动A", "PLC原理及应用B", "智能装备与物联网技术（通讯）", "机械制造基础A",
            "传感器与测试技术", "现代机械设计方法", "机电传动控制B", "机械装备结构与设计",
            "5形势与政策", "人机交互技术",
        )
        val first = ScheduleCalculator.colorIndexesBySortedName(names)
        val second = ScheduleCalculator.colorIndexesBySortedName(names.reversed())
        assertEquals(first, second)
        assertEquals(names.size, first.values.toSet().size)
    }

    /** 回归：不同课名超过旧 12 桶时（16 色扩容的场景），容量内仍不允许撞色 */
    @Test
    fun sortedNameColorsDistinctBeyondLegacyPalette() {
        val names = (1..16).map { "课程${('A' + it - 1)}" }
        val mapping = ScheduleCalculator.colorIndexesBySortedName(names)
        assertEquals(16, mapping.values.toSet().size)
    }
}
