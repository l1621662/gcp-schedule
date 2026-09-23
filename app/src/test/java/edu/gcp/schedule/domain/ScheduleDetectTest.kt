package edu.gcp.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调课自动检测（DESIGN §4.17）：三方合并。
 *
 * 核心断言不是「差异算得对」，而是**归因对**：用户的调课/编辑/自定义课
 * 永远不进报告（报告 = diff(base→theirs)），冲突只在两侧都动且终态不同时出现。
 */
class ScheduleDetectTest {

    private fun course(
        name: String = "高数",
        day: Int = 1,
        weeks: Set<Int>,
        startSection: Int = 1,
        endSection: Int = 2,
        teacher: String = "张",
        position: String = "北B102",
        kind: CourseKind = CourseKind.Theory,
        id: Long = 0,
    ) = Course(
        id = id, name = name, teacher = teacher, position = position,
        day = day, startSection = startSection, endSection = endSection,
        weeks = weeks, colorIndex = 0, kind = kind,
    )

    @Test
    fun `教务无变化时不产出任何组`() {
        val courses = listOf(course(weeks = (1..16).toSet()), course(name = "英语", day = 3, weeks = (1..16).toSet()))
        val groups = ScheduleDetector.detect(base = courses, ours = courses, theirs = courses)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `用户自己的调课不进报告`() {
        val base = listOf(course(weeks = (1..16).toSet()))
        // 用户把第 8 周调走：原行摘掉第 8 周 + 克隆行只带第 8 周（调到周三）
        val ours = listOf(
            course(weeks = (1..16).toSet() - 8),
            course(day = 3, weeks = setOf(8), id = 2),
        )
        val groups = ScheduleDetector.detect(base = base, ours = ours, theirs = base)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `用户手动添加的自定义课不进报告`() {
        val base = emptyList<Course>()
        val ours = listOf(course(name = "自选课", weeks = setOf(5)))
        val groups = ScheduleDetector.detect(base = base, ours = ours, theirs = base)
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `教务改地点报变更好勾选`() {
        val base = listOf(course(weeks = (1..16).toSet()))
        val theirs = listOf(course(weeks = (1..16).toSet(), position = "南A201"))
        val groups = ScheduleDetector.detect(base = base, ours = base, theirs = theirs)
        assertEquals(1, groups.size)
        val g = groups[0]
        assertFalse(g.conflict)
        assertEquals("有变更", g.typeLabel)
        assertEquals("南A201", g.remoteCourses.single().position)
        assertEquals("北B102", g.localCourses.single().position)
    }

    @Test
    fun `教务改周次报变更`() {
        val base = listOf(course(weeks = (1..16).toSet()))
        val theirs = listOf(course(weeks = (1..15).toSet()))
        val groups = ScheduleDetector.detect(base = base, ours = base, theirs = theirs)
        assertEquals(1, groups.size)
        assertEquals(setOf(1..15).flatten().toSet(), groups[0].remoteCourses.single().weeks)
    }

    @Test
    fun `教务新增课程在报告里且本地侧为空`() {
        val base = emptyList<Course>()
        val theirs = listOf(course(name = "新开的课", day = 5, weeks = setOf(1, 2)))
        val groups = ScheduleDetector.detect(base = base, ours = base, theirs = theirs)
        assertEquals(1, groups.size)
        assertFalse(groups[0].conflict)
        assertEquals("教务新增", groups[0].typeLabel)
        assertTrue(groups[0].localCourses.isEmpty())
    }

    @Test
    fun `教务停课在报告里且教务侧为空`() {
        val base = listOf(course(name = "被砍的课", weeks = setOf(1)))
        val ours = base
        val groups = ScheduleDetector.detect(base = base, ours = ours, theirs = emptyList())
        assertEquals(1, groups.size)
        assertEquals("教务停课", groups[0].typeLabel)
        assertTrue(groups[0].remoteCourses.isEmpty())
    }

    @Test
    fun `两侧都停课时终态一致不报`() {
        val base = listOf(course(name = "都删的课", weeks = setOf(1)))
        val groups = ScheduleDetector.detect(base = base, ours = emptyList(), theirs = emptyList())
        assertTrue(groups.isEmpty())
    }

    @Test
    fun `两侧都动且终态不同判冲突`() {
        val base = listOf(course(weeks = (1..16).toSet()))
        // 用户把课调到周三；教务把地点改了
        val ours = listOf(course(day = 3, weeks = (1..16).toSet()))
        val theirs = listOf(course(weeks = (1..16).toSet(), position = "南A201"))
        val groups = ScheduleDetector.detect(base = base, ours = ours, theirs = theirs)
        assertEquals(1, groups.size)
        assertTrue(groups[0].conflict)
        assertEquals("冲突", groups[0].typeLabel)
    }

    @Test
    fun `教务改名表现为删加两组`() {
        val base = listOf(course(name = "旧课名", weeks = setOf(1)))
        val theirs = listOf(course(name = "新课名", weeks = setOf(1)))
        val groups = ScheduleDetector.detect(base = base, ours = base, theirs = theirs)
        assertEquals(listOf("新课名", "旧课名").sorted(), groups.map { it.name }.sorted())
    }

    @Test
    fun `实验课教师为空不误报教师变更`() {
        val base = listOf(course(name = "实训", kind = CourseKind.Lab, teacher = "", weeks = setOf(1)))
        val theirs = listOf(course(name = "实训", kind = CourseKind.Lab, teacher = "", weeks = setOf(1), position = "车间2"))
        val groups = ScheduleDetector.detect(base = base, ours = base, theirs = theirs)
        assertEquals(1, groups.size)
        assertEquals("有变更", groups[0].typeLabel)
        assertEquals("车间2", groups[0].remoteCourses.single().position)
    }

    @Test
    fun `分组按类型与课程名排序`() {
        val base = emptyList<Course>()
        val theirs = listOf(
            course(name = "B课", day = 2, weeks = setOf(1)),
            course(name = "A课", day = 1, weeks = setOf(1), kind = CourseKind.Lab),
        )
        val groups = ScheduleDetector.detect(base = base, ours = base, theirs = theirs)
        assertEquals(
            listOf(CourseKind.Theory to "B课", CourseKind.Lab to "A课"),
            groups.map { it.kind to it.name },
        )
    }

    @Test
    fun `报告payload序列化roundtrip保真`() {
        val group = DetectGroup(
            kind = CourseKind.Lab,
            name = "机械制造基础A",
            conflict = true,
            localCourses = listOf(course(name = "机械制造基础A", kind = CourseKind.Lab, teacher = "", weeks = setOf(1, 3))),
            remoteCourses = listOf(course(name = "机械制造基础A", kind = CourseKind.Lab, teacher = "", weeks = setOf(3), position = "403")),
        )
        val payload = DetectReportPayload(
            term = "2026-2027-1",
            checkedAt = 1234567890L,
            groups = listOf(DetectGroupDto.fromDomain(group)),
            snapshot = DetectSnapshotPayload.fromCourses("2026-2027-1", emptyList(), emptyList()),
        )
        val decoded = DetectReportPayload.decode(payload.encode())
        assertTrue(decoded != null)
        assertEquals(payload, decoded)
        val restored = decoded!!.toGroups().single()
        assertTrue(restored.conflict)
        assertEquals(CourseKind.Lab, restored.kind)
        assertEquals(setOf(1, 3), restored.localCourses.single().weeks)
        assertEquals("403", restored.remoteCourses.single().position)
    }

    @Test
    fun `基线快照脏JSON解码退空不抛异常`() {
        assertNull(DetectSnapshotPayload.decode("not json"))
        assertNull(DetectReportPayload.decode("{"))
        assertEquals(0, DetectSnapshotPayload.decode(DetectSnapshotPayload().encode())!!.theory.size)
    }

    @Test
    fun `连续凭证失败达到上限即停用`() {
        assertFalse(DetectFailurePolicy.shouldDisableAfterCredentialFailure(1))
        assertFalse(DetectFailurePolicy.shouldDisableAfterCredentialFailure(2))
        assertTrue(DetectFailurePolicy.shouldDisableAfterCredentialFailure(3))
        assertTrue(DetectFailurePolicy.shouldDisableAfterCredentialFailure(4))
        assertEquals(3, DetectFailurePolicy.MAX_CREDENTIAL_FAILURES)
    }
}
