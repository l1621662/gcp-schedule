package edu.gcp.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 正方课表解析契约：周次/节次文本、注入 JSON、学期透传。 */
class ZhengfangScheduleParserTest {

    @Test
    fun parseWeeks_rangesListsAndParity() {
        assertEquals(setOf(1, 2, 3, 4, 5), ZhengfangScheduleParser.parseWeeks("1-5周"))
        assertEquals(setOf(2, 4, 6, 8), ZhengfangScheduleParser.parseWeeks("2,4,6,8"))
        assertEquals(setOf(3, 4), ZhengfangScheduleParser.parseWeeks("第3-4周"))
        // 单/双周标记只作用于自己那一段：逗号后面的段不受影响
        assertEquals(
            setOf(9, 11, 13, 15, 16, 17),
            ZhengfangScheduleParser.parseWeeks("(1-2节)9-15周(单),16-17周"),
        )
        // 全角括号同样要认
        assertEquals(setOf(2, 4, 6, 8), ZhengfangScheduleParser.parseWeeks("(1-2节)2-8周（双）"))
        assertTrue(ZhengfangScheduleParser.parseWeeks("").isEmpty())
    }

    @Test
    fun parseSections_readsRangeFromParenText() {
        assertEquals(1 to 2, ZhengfangScheduleParser.parseSections("(1-2节)1-16周"))
        assertEquals(3 to 4, ZhengfangScheduleParser.parseSections("（3-4节）1-8周"))
        assertEquals(11 to 11, ZhengfangScheduleParser.parseSections("(11节)第1周"))
        assertNull(ZhengfangScheduleParser.parseSections("1-16周"))
    }

    @Test
    fun toCourse_usesSectionRangeFromTextNotCellId() {
        // rowspan 合并格：单元格 id 上的节次只是格子的第一行，真实范围写在「(3-4节)」里
        val course = ZhengfangScheduleParser.toCourse(
            ZhengfangScheduleParser.RawItem(
                name = "高等数学",
                weekTime = "(3-4节)1-16周",
                place = "A101",
                teacher = "张三",
                day = 2,
                section = 3,
            ),
        )
        assertNotNull(course)
        assertEquals(2, course!!.day)
        assertEquals(3, course.startSection)
        assertEquals(4, course.endSection)
        assertEquals(16, course.weeks.size)
        assertEquals("张三", course.teacher)
        assertEquals("A101", course.position)
    }

    @Test
    fun toCourse_skipsItemsWithoutWeeksOrDay() {
        assertNull(
            ZhengfangScheduleParser.toCourse(
                ZhengfangScheduleParser.RawItem("高数", "", "A101", "张三", day = 1, section = 1),
            ),
        )
        assertNull(
            ZhengfangScheduleParser.toCourse(
                ZhengfangScheduleParser.RawItem("高数", "(1-2节)1-8周", "A101", "张三", day = 0, section = 1),
            ),
        )
    }

    @Test
    fun parseExtractJson_carriesTermAndCourses() {
        val json = """
            {"ok":true,"term":"2026-2027-1","title":"个人课表","items":[
              {"name":"高等数学","weekTime":"(1-2节)1-16周","place":"A101","teacher":"张三","day":1,"section":1},
              {"name":"体育","weekTime":"(5-6节)1-16周","place":"操场","teacher":"李四","day":3,"section":5}
            ]}
        """.trimIndent()
        val result = ZhengfangScheduleParser.parseExtractJson(json)
        assertTrue("应解析成功：$result", result is ImportParseResult.Success)
        val success = result as ImportParseResult.Success
        assertEquals("2026-2027-1", success.term)
        assertEquals(2, success.courses.size)
    }

    @Test
    fun parseExtractJson_failsWhenNoCourseSurvives() {
        val json = """{"ok":true,"items":[{"name":"高数","weekTime":"","place":"","teacher":"","day":1,"section":1}]}"""
        val result = ZhengfangScheduleParser.parseExtractJson(json)
        assertTrue("周次缺失时必须报失败：$result", result is ImportParseResult.Failure)
    }

    @Test
    fun parseExtractJson_failsOnEmptyPage() {
        val result = ZhengfangScheduleParser.parseExtractJson("""{"ok":true,"items":[]}""")
        assertTrue("空页必须报失败：$result", result is ImportParseResult.Failure)
    }
}
