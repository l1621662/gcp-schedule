package edu.gcp.schedule.data.repo

import edu.gcp.schedule.domain.CourseKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入 JSON 的字段约定与取值校验。
 *
 * 这里用生产同一份 [CourseJsonFormat] 解码、同一个 [validateCourseJson] 校验——
 * 之前这一组断言是 `good.contains("\"weeks\"")` 这种子串检查，挡不住任何回归：
 * 关掉 `ignoreUnknownKeys`、改掉 `weeks` 的默认值、把 day 的范围放宽，它都不会红。
 * 现在字段改名或默认值变化会直接让解码/断言失败。
 */
class ImportJsonShapeTest {

    private val good = """
        {
          "courses": [
            {
              "name": "高数",
              "teacher": "张",
              "position": "A101",
              "day": 1,
              "startSection": 1,
              "endSection": 2,
              "weeks": [1,2,3]
            }
          ]
        }
    """.trimIndent()

    private val badDay = """
        {"courses":[{"name":"x","day":9,"startSection":1,"endSection":1,"weeks":[1]}]}
    """.trimIndent()

    private val missingWeeks = """
        {"courses":[{"name":"x","day":1,"startSection":1,"endSection":1}]}
    """.trimIndent()

    private fun decode(text: String): CourseExport =
        CourseJsonFormat.decodeFromString(CourseExport.serializer(), text)

    private fun firstCourse(text: String): CourseJson = decode(text).courses.first()

    @Test
    fun courseJsonDefaults() {
        val c = CourseJson(name = "a", day = 1, startSection = 1, endSection = 1)
        assertEquals(emptyList<Int>(), c.weeks)
        assertEquals("", c.teacher)
        assertEquals("theory", c.kind)
    }

    /** 完整文件能真的读出来，且字段一一落到对应位置、通过校验 */
    @Test
    fun goodFileDecodesAndPassesValidation() {
        val export = decode(good)
        assertEquals(1, export.courses.size)
        val c = export.courses.first()
        assertEquals("高数", c.name)
        assertEquals("张", c.teacher)
        assertEquals("A101", c.position)
        assertEquals(1, c.day)
        assertEquals(1, c.startSection)
        assertEquals(2, c.endSection)
        assertEquals(listOf(1, 2, 3), c.weeks)
        assertNull(validateCourseJson(0, c))
    }

    /** 缺 weeks 会落成空集合，并且必须被校验挡下——否则课会导入却哪一周都不显示 */
    @Test
    fun missingWeeksIsRejected() {
        val c = firstCourse(missingWeeks)
        assertEquals(emptyList<Int>(), c.weeks)
        assertNotNull(validateCourseJson(0, c))
    }

    /** day 越界：Json 能读，但写库前必须被挡下 */
    @Test
    fun outOfRangeDayIsRejected() {
        val c = firstCourse(badDay)
        assertEquals(9, c.day)
        val err = validateCourseJson(0, c)
        assertNotNull(err)
        assertTrue("错误信息要指明是第几门课，便于对文件定位", err!!.startsWith("第 1 门课"))
    }

    @Test
    fun blankNameAndInvertedSectionsAreRejected() {
        val blank = CourseJson(name = "  ", day = 1, startSection = 1, endSection = 1, weeks = listOf(1))
        assertNotNull(validateCourseJson(0, blank))

        val inverted = CourseJson(name = "x", day = 1, startSection = 5, endSection = 3, weeks = listOf(1))
        assertNotNull(validateCourseJson(0, inverted))
    }

    @Test
    fun illegalWeeksAreRejected() {
        val week0 = CourseJson(name = "x", day = 1, startSection = 1, endSection = 1, weeks = listOf(0))
        assertNotNull(validateCourseJson(0, week0))
        val week41 = CourseJson(name = "x", day = 1, startSection = 1, endSection = 1, weeks = listOf(41))
        assertNotNull(validateCourseJson(0, week41))
    }

    /** 多余字段必须被忽略：换个工具导出的 JSON 常带自己的扩展键，不能因此报错 */
    @Test
    fun unknownKeysAreIgnored() {
        val withExtra = """
            {"courses":[{"name":"高数","day":1,"startSection":1,"endSection":2,
              "weeks":[1],"campus":"龙湖","note":{"a":1}}]}
        """.trimIndent()
        val c = firstCourse(withExtra)
        assertEquals("高数", c.name)
        assertNull(validateCourseJson(0, c))
    }

    /** 加 kind 字段之前导出的旧文件里没有这个键，必须仍能读进来并落成理论课。 */
    @Test
    fun oldJsonWithoutKindFallsBackToTheory() {
        val c = firstCourse(good)
        assertEquals("theory", c.kind)
        assertEquals(CourseKind.Theory, c.toDomain().kind)
    }

    /** 实验课导出再导入要保住类型，否则来回一趟就被"洗"成理论课。 */
    @Test
    fun labKindSurvivesRoundTrip() {
        val lab = CourseJson(
            name = "机械制造基础A",
            day = 6,
            startSection = 7,
            endSection = 8,
            weeks = listOf(4),
            kind = "lab",
        )
        val text = CourseJsonFormat.encodeToString(
            CourseExport.serializer(),
            CourseExport(listOf(lab)),
        )
        val back = firstCourse(text)
        assertEquals(CourseKind.Lab, back.toDomain().kind)
        assertEquals(lab, back)
    }

    /** 导出结果的键名是与外部工具互通的一部分，改名会破坏兼容，这里锁住 */
    @Test
    fun exportKeepsSchemaKeys() {
        val text = CourseJsonFormat.encodeToString(
            CourseExport.serializer(),
            CourseExport(listOf(CourseJson(name = "高数", day = 1, startSection = 1, endSection = 2, weeks = listOf(1)))),
        )
        val root = CourseJsonFormat.parseToJsonElement(text) as JsonObject
        val course = (root["courses"] as JsonArray).first() as JsonObject
        for (key in listOf("name", "teacher", "position", "day", "startSection", "endSection", "weeks", "kind")) {
            assertTrue("导出 JSON 缺少键 $key", course.containsKey(key))
        }
        // weeks 必须是数组而不是被压成字符串，否则外部工具读不了
        val weeks = (course["weeks"] as JsonArray).map { it.jsonPrimitive.content.toInt() }
        assertEquals(listOf(1), weeks)
    }

    /** 顶层 term：教务/脚本导出会带「数据属于哪个学期」，解码要保住；旧文件没有则落 null。 */
    @Test
    fun termSurvivesDecodeAndOldFilesFallBackToNull() {
        val withTerm = """
            {"term":"2026-2027-1","courses":[
              {"name":"高数","day":1,"startSection":1,"endSection":2,"weeks":[1]}
            ]}
        """.trimIndent()
        assertEquals("2026-2027-1", decode(withTerm).term)
        assertNull("旧文件没有 term 键，必须仍能读进来", decode(good).term)
    }

    // ---- 成绩备份段（DESIGN §4.3）----

    private val record = edu.gcp.schedule.domain.ScoreRecord(
        term = "2025-2026-2",
        courseNo = "050221005",
        name = "跨文化交际",
        unit = "外国语学院",
        credit = 2.0,
        hours = 32.0,
        examForm = "考试",
        courseAttr = "必修",
        category = "专业必修课",
        score = 86.5,
        scoreStr = "86.5",
        gradePoint = 3.7,
        status = "正常考试",
        pendingReview = false,
    )

    /** 成绩条目导出再导入逐字段一致，来回一趟不能丢信息。 */
    @Test
    fun scoreBackupJsonRoundTrips() {
        val json = record.toBackupJson()
        val text = CourseJsonFormat.encodeToString(
            CourseExport.serializer(),
            CourseExport(courses = emptyList(), scores = listOf(json)),
        )
        val back = decode(text).scores.single().toScoreRecord()
        assertEquals(record, back)
    }

    /** 缺字段（手工编辑/旧工具产出）降级为默认值，不能解码失败。 */
    @Test
    fun scoreBackupPartialFieldsFallBackToDefaults() {
        val sparse = """
            {"courses":[{"name":"高数","day":1,"startSection":1,"endSection":2,"weeks":[1]}],
             "scores":[{"term":"2025-2026-2","name":"高数"}]}
        """.trimIndent()
        val s = decode(sparse).scores.single().toScoreRecord()
        assertEquals("高数", s.name)
        assertEquals("2025-2026-2", s.term)
        assertEquals(0.0, s.credit, 1e-9)
        assertNull(s.score)
        assertNull(s.gradePoint)
    }

    /** 没有 scores 键的旧文件照常读入，成绩段为空 = 导入时不动成绩。 */
    @Test
    fun oldFileWithoutScoresHasEmptyScoreList() {
        assertTrue(decode(good).scores.isEmpty())
    }

    // ---- 学期配置与作息备份段（DESIGN §4.3）----

    /** 学期/作息段导出再导入逐字段一致；键名是与旧备份互通的一部分，改名会破坏兼容。 */
    @Test
    fun semesterAndSlotsSurviveRoundTrip() {
        val export = CourseExport(
            courses = listOf(CourseJson(name = "高数", day = 1, startSection = 1, endSection = 2, weeks = listOf(1))),
            semester = SemesterBackupJson(startDate = "2026-09-07", totalWeeks = 20, firstDayOfWeek = 1),
            timeSlots = listOf(
                TimeSlotBackupJson(1, "08:30", "09:10"),
                TimeSlotBackupJson(11, "20:30", "21:10"),
            ),
        )
        val back = decode(CourseJsonFormat.encodeToString(CourseExport.serializer(), export))
        assertEquals("2026-09-07", back.semester!!.startDate)
        assertEquals(20, back.semester!!.totalWeeks)
        assertEquals(1, back.semester!!.firstDayOfWeek)
        assertEquals(2, back.timeSlots.size)
        assertEquals(11, back.timeSlots[1].number)
        assertEquals("20:30", back.timeSlots[1].startTime)
    }

    /** 旧备份（或外部工具导出）没有 semester/timeSlots 键：照常解码，配置段为空 = 导入不动配置。 */
    @Test
    fun oldFileWithoutConfigSegmentsFallsBackToEmpty() {
        val export = decode(good)
        assertNull(export.semester)
        assertTrue(export.timeSlots.isEmpty())
    }
}
