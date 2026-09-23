package edu.gcp.schedule.data.jw

import edu.gcp.schedule.domain.CourseKind
import edu.gcp.schedule.domain.ExamMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** 考试抓取解析（注入 fetch 的 JSON → ExamEntry）。样例取自 2026-09-19 真实接口返回。 */
class ExamScheduleParserTest {

    @Test
    fun parsesRealPayloadShape() {
        val payload = """
            {"msg":"","code":0,"count":2,"data":[
              {"kw0410id":"A","kw0413id":"B","ksccmc":"3112522010113","kch":"050221005",
               "kskcmc":"跨文化交际英语（中国水文化英译）","kssj":"2026-05-18 08:30~09:55",
               "js_mc":"南C303","ksxq":"瑶湖校区","xqmc":"瑶湖校区","jsxm":"李红梅",
               "jsid":"00049","rownum_":1},
              {"kw0410id":"C","kw0413id":"D","ksccmc":"3112522010114","kch":"040301001",
               "kskcmc":"热工基础","kssj":"2026-05-26 19:00~20:25",
               "js_mc":"北C104","ksxq":"瑶湖校区","xqmc":"瑶湖校区","jsxm":"王强",
               "zwh":"65","rownum_":2}
            ]}
        """.trimIndent()
        val result = ExamScheduleParser.parseFetchJson(payload)
        assertEquals(2, result.count)
        assertEquals(2, result.exams.size)

        val first = result.exams[0]
        assertEquals("2026-05-18", first.date)
        assertEquals("08:30", first.startTime)
        assertEquals("09:55", first.endTime)
        assertEquals("南C303", first.room)
        assertEquals("李红梅", first.teacher)

        // 座位号是可选字段：缺省为空串，有值要接住
        assertEquals("", result.exams[0].seatNo)
        assertEquals("65", result.exams[1].seatNo)

        // 与 ExamMapper 串起来应能转出考试课程
        val course = ExamMapper.toExamCourse(first, DefaultSlots.value, TermFixture.value)!!
        assertEquals(CourseKind.Exam, course.kind)
    }

    @Test
    fun noOpenPageIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            ExamScheduleParser.parseFetchJson("""<html>系统功能暂未开放，敬请等待</html>""")
        }
        assert(e.message!!.contains("暂未开放"))
    }

    @Test
    fun networkErrorPrefixIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            ExamScheduleParser.parseFetchJson("ERR:net::ERR_CONNECTION_TIMED_OUT")
        }
        assert(e.message!!.contains("请求失败"))
    }

    @Test
    fun nonZeroCodeIsReadableError() {
        val e = assertThrows(IllegalStateException::class.java) {
            ExamScheduleParser.parseFetchJson("""{"code":-1,"msg":"用户没有登录"}""")
        }
        assert(e.message!!.contains("用户没有登录"))
    }

    @Test
    fun fetchJsSubstitutesTermAndPage() {
        val js = ExamScheduleParser.fetchJs(term = "2025-2026-2", page = 3)
        assert(js.contains("xnxqid=' + encodeURIComponent('2025-2026-2')"))
        assert(js.contains("pageNum=3"))
        assert(js.contains("pageSize=${ExamScheduleParser.PAGE_SIZE}"))
        // 占位符不许残留
        assert(!js.contains("__TERM__"))
        assert(!js.contains("__PAGE__"))
    }
}

/** 测试用作息表：§3.5 默认口径的抽样（首尾节 + 交界节）。 */
private object DefaultSlots {
    val value = listOf(
        edu.gcp.schedule.domain.TimeSlot(1, "08:30", "09:10"),
        edu.gcp.schedule.domain.TimeSlot(2, "09:15", "09:55"),
        edu.gcp.schedule.domain.TimeSlot(3, "10:15", "10:55"),
        edu.gcp.schedule.domain.TimeSlot(4, "11:00", "11:40"),
        edu.gcp.schedule.domain.TimeSlot(5, "14:00", "14:40"),
        edu.gcp.schedule.domain.TimeSlot(6, "14:45", "15:25"),
        edu.gcp.schedule.domain.TimeSlot(7, "15:45", "16:25"),
        edu.gcp.schedule.domain.TimeSlot(8, "16:30", "17:10"),
        edu.gcp.schedule.domain.TimeSlot(9, "19:00", "19:40"),
        edu.gcp.schedule.domain.TimeSlot(10, "19:45", "20:25"),
        edu.gcp.schedule.domain.TimeSlot(11, "20:30", "21:10"),
    )
}

/** 测试用学期配置：2026-03-02（周一）开学的 20 周学期。 */
private object TermFixture {
    val value = edu.gcp.schedule.domain.SemesterConfig(
        startDate = "2026-03-02",
        totalWeeks = 20,
        firstDayOfWeek = 1,
    )
}
