package edu.gcp.schedule.domain

import edu.gcp.schedule.data.DefaultData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 作息表编辑规则。
 *
 * 这里挡的是「填错了页面不崩、只会安静显示错信息」的输入：作息表不只是展示数据，
 * 网格行高、时刻线、「还剩 X 分」、下一节判定全都从它推出来。
 */
class TimeSlotRulesTest {

    private val defaults = DefaultData.defaultTimeSlots

    @Test
    fun defaultSchedulePasses() {
        assertNull(TimeSlotRules.validate(defaults))
    }

    /** 学校改作息（比如每节 45 分钟）也要能存进去，规则只管格式与先后顺序 */
    @Test
    fun schoolAdjustedSchedulePasses() {
        val custom = listOf(
            TimeSlot(1, "08:00", "08:45"),
            TimeSlot(2, "08:50", "09:35"),
            TimeSlot(3, "09:50", "10:35"),
        )
        assertNull(TimeSlotRules.validate(custom))
    }

    /** 首尾相接（上一节 09:10 结束、下一节 09:15 开始）是正常课间，不算重叠 */
    @Test
    fun touchingSectionsAreAllowed() {
        val custom = listOf(
            TimeSlot(1, "08:00", "08:40"),
            TimeSlot(2, "08:40", "09:20"),
        )
        assertNull(TimeSlotRules.validate(custom))
    }

    @Test
    fun timeFormatIsChecked() {
        assertNotNull(TimeSlotRules.validate(listOf(TimeSlot(1, "8:30", "09:10"))))
        assertNotNull(TimeSlotRules.validate(listOf(TimeSlot(1, "08:30", "9:10"))))
        assertNotNull(TimeSlotRules.validate(listOf(TimeSlot(1, "24:00", "25:00"))))
        assertNotNull(TimeSlotRules.validate(listOf(TimeSlot(1, "08:65", "09:10"))))
        assertNotNull(TimeSlotRules.validate(listOf(TimeSlot(1, "", "09:10"))))
    }

    @Test
    fun endMustBeAfterStart() {
        val err = TimeSlotRules.validate(listOf(TimeSlot(1, "09:10", "09:10")))
        assertNotNull(err)
        assertNotNull(TimeSlotRules.validate(listOf(TimeSlot(1, "10:00", "09:10"))))
    }

    @Test
    fun overlappingSectionsAreRejected() {
        val err = TimeSlotRules.validate(
            listOf(
                TimeSlot(1, "08:00", "08:50"),
                TimeSlot(2, "08:40", "09:30"),
            ),
        )
        assertNotNull(err)
        // 报错要指明是哪两节，否则 11 行里很难找
        assertEquals(true, err!!.contains("第 2 节") && err.contains("第 1 节"))
    }

    /** 校验按节次排序做，不依赖传入顺序 */
    @Test
    fun orderOfInputDoesNotMatter() {
        val reversed = defaults.reversed()
        assertNull(TimeSlotRules.validate(reversed))
    }

    @Test
    fun emptyScheduleIsRejected() {
        assertNotNull(TimeSlotRules.validate(emptyList()))
    }

    @Test
    fun inputNormalizationAddsColonForDigits() {
        assertEquals("08:3", TimeSlotRules.normalizeInput("083"))
        assertEquals("08:30", TimeSlotRules.normalizeInput("0830"))
        assertEquals("12:34", TimeSlotRules.normalizeInput("123456"))
        assertEquals("08", TimeSlotRules.normalizeInput("08"))
    }

    /** 已经带冒号的输入（多为粘贴）不重新切分，否则 8:30 会变成 83:0 */
    @Test
    fun inputNormalizationKeepsExistingColon() {
        assertEquals("8:30", TimeSlotRules.normalizeInput("8:30"))
        assertEquals("08:30", TimeSlotRules.normalizeInput("08:30"))
        assertEquals("08:30", TimeSlotRules.normalizeInput("08:30abc"))
    }

    @Test
    fun padTimeFillsLeadingZero() {
        assertEquals("08:03", TimeSlotRules.padTime("8:3"))
        assertEquals("08:30", TimeSlotRules.padTime("08:30"))
        assertEquals("08:30", TimeSlotRules.padTime(" 08:30 "))
        // 解析不出来的原样返回，交给 validate 报错，不要在这里悄悄改成别的值
        assertEquals("25:00", TimeSlotRules.padTime("25:00"))
        assertEquals("abc", TimeSlotRules.padTime("abc"))
    }
}
