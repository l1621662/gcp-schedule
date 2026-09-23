package edu.gcp.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** 正方成绩解析契约：分页请求脚本 + items/totalResult 解析 + 失败口径。 */
class ZhengfangScoreParserTest {

    @Test
    fun fetchJs_injectsPageNumberAndSize() {
        val js = ZhengfangScoreParser.fetchJs(page = 3)
        assertTrue("页码要注入", "queryModel.currentPage=3" in js)
        assertTrue("分页大小要注入", "queryModel.showCount=${ZhengfangScoreParser.PAGE_SIZE}" in js)
        assertFalse("占位符不能留在脚本里", "__PAGE__" in js)
    }

    @Test
    fun parseFetchJson_readsRowsAndTotal() {
        val page = ZhengfangScoreParser.parseFetchJson(
            """
            {"totalResult":2,"items":[
              {"kcmc":"高等数学","kch":"0001","xnmmc":"2026-2027","xqmmc":"1","xf":"4.0",
               "zxs":"64","cj":"92","jd":"4.0","kkbmmc":"数学学院","khfsmc":"考试",
               "kcxzmc":"必修课","kclbmc":"公共基础课","ksxz":"正常考试"},
              {"kcmc":"体育","xnmmc":"2026-2027","xqmmc":"1","cj":"良好","xf":"1"}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, page.count)
        assertEquals(2, page.records.size)
        val first = page.records[0]
        assertEquals("2026-2027-1", first.term)
        assertEquals("高等数学", first.name)
        assertEquals("0001", first.courseNo)
        assertEquals(4.0, first.credit, 0.001)
        assertEquals(92.0, first.score ?: -1.0, 0.001)
        assertEquals("92", first.scoreStr)
        // 等级制成绩：无数值分但保留展示口径
        val second = page.records[1]
        assertNull(second.score)
        assertEquals("良好", second.scoreStr)
    }

    @Test
    fun parseFetchJson_skipsRowsWithoutCourseName() {
        val page = ZhengfangScoreParser.parseFetchJson("""{"totalResult":1,"items":[{"cj":"92"}]}""")
        assertEquals(0, page.records.size)
    }

    @Test
    fun parseFetchJson_reportsFetchError() {
        try {
            ZhengfangScoreParser.parseFetchJson("ERR:TypeError: Failed to fetch")
            fail("请求失败必须抛错，不能当成空成绩")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("请求失败"))
        }
    }

    @Test
    fun parseFetchJson_reportsNonJsonBody() {
        // 未登录时正方会回登录页 HTML：要给出可读原因，而不是静默空结果
        try {
            ZhengfangScoreParser.parseFetchJson("<html><body>login</body></html>")
            fail("非 JSON 必须抛错")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty().contains("JSON"))
        }
    }
}
