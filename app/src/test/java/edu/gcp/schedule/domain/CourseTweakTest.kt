package edu.gcp.schedule.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调课规划（DESIGN §4.11）。
 *
 * 断言按「行」而不是按「(周, 天) 查到什么」：调课的本质就是行会被拆开
 * （多周课拆成「原行摘源周」+「克隆行只带目标周」），若还按源周去查原行，
 * 查到的必然是空——那正是「这一周已经不在这儿了」。
 */
class CourseTweakTest {

    private fun course(
        id: Long,
        name: String = "高数",
        day: Int,
        weeks: Set<Int>,
        startSection: Int = 1,
        endSection: Int = 2,
        teacher: String = "张",
        colorIndex: Int = 3,
        kind: CourseKind = CourseKind.Theory,
        custom: Boolean = false,
    ) = Course(
        id = id,
        name = name,
        teacher = teacher,
        position = "A101",
        day = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
        colorIndex = colorIndex,
        kind = kind,
        isCustomTime = custom,
        customStartTime = if (custom) "08:00" else null,
        customEndTime = if (custom) "09:35" else null,
    )

    /** 把 plan 折回「全量课程」，便于按终态断言（模拟写库后的结果）。 */
    private fun TweakPlan.applyTo(courses: List<Course>): List<Course> {
        val removed = deleteIds.toHashSet()
        val updatedById = updates.associateBy { it.id }
        var nextId = (courses.maxOfOrNull { it.id } ?: 0L) + 1
        val survivors = courses.filter { it.id !in removed }
            .map { updatedById[it.id] ?: it }
        return survivors + inserts.map { it.copy(id = nextId++) }
    }

    private fun List<Course>.row(name: String, day: Int): Course =
        single { it.name == name && it.day == day }

    private fun List<Course>.on(week: Int, day: Int): List<Course> =
        ScheduleCalculator.coursesOnDay(this, week, day)

    /** 不变量：同一格（星期 × 周次）里同一门课不得出现两次，否则网格上会叠出两个相同的块。 */
    private fun assertNoDuplicateSlots(courses: List<Course>) {
        val slots = courses.flatMap { c ->
            c.weeks.map { w -> Triple(c.day, w, listOf(c.name, c.startSection, c.endSection, c.teacher).joinToString("|")) }
        }
        val duplicated = slots.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue("同一格叠了重复课程：$duplicated", duplicated.isEmpty())
    }

    // ---- 合并 ----

    @Test
    fun merge_singleWeekCourseMovesRow() {
        // 只在第 3 周存在的课：整行搬到目标日，weeks 跟着换成目标周
        val courses = listOf(course(1, day = 3, weeks = setOf(3)))
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 3, 3, 5, 5)
        val result = plan.applyTo(courses)

        assertEquals(1, plan.movedCount)
        assertTrue(plan.deleteIds.isEmpty())
        assertEquals(1, plan.updates.size)
        assertTrue(plan.inserts.isEmpty())
        // 单周课不拆行：还是那一行，id 未变
        assertEquals(1L, result.single().id)
        assertEquals(5, result.single().day)
        assertEquals(setOf(5), result.single().weeks)
        assertNoDuplicateSlots(result)
    }

    @Test
    fun merge_multiWeekCourseSplits() {
        // 1-16 周的课从第 5 周周三挪到第 5 周周五：
        // 原行摘掉第 5 周（仍在周三），另克隆一行只带第 5 周（在周五）
        val courses = listOf(course(1, day = 3, weeks = (1..16).toSet()))
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 5, 3, 5, 5)
        val result = plan.applyTo(courses)

        assertEquals(1, plan.movedCount)
        assertEquals(1, plan.inserts.size)
        assertEquals(1, plan.updates.size)
        assertEquals((1..16).toSet() - 5, result.row("高数", 3).weeks)
        assertEquals(setOf(5), result.row("高数", 5).weeks)
        // 拆出去的那份必须是新行（id = 0 交给 Room 自增），不能改写原行 id
        assertEquals(0L, plan.inserts.single().id)
        assertNoDuplicateSlots(result)
    }

    @Test
    fun merge_cloneKeepsColorKindAndCustomTime() {
        // 克隆行带的是**目标**周，且除 day/weeks 外的字段一个不能丢
        val courses = listOf(
            course(1, day = 1, weeks = setOf(1, 2, 3), colorIndex = 7, kind = CourseKind.Lab, custom = true),
        )
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 1, 1, 2, 2)
        val clone = plan.inserts.single()

        assertEquals(7, clone.colorIndex)
        assertEquals(CourseKind.Lab, clone.kind)
        assertTrue(clone.isCustomTime)
        assertEquals("08:00", clone.customStartTime)
        assertEquals("09:35", clone.customEndTime)
        assertEquals(setOf(2), clone.weeks)
        assertEquals(2, clone.day)
    }

    @Test
    fun merge_sameIdentityAlreadyOnTargetDoesNotDuplicate() {
        // 目标格已有同一门课（同身份，哪怕它是另一行）：只把源周摘掉，不插新行
        val courses = listOf(
            course(1, name = "高数", day = 3, weeks = setOf(2, 3)),
            course(2, name = "高数", day = 5, weeks = setOf(2)),
        )
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 2, 3, 2, 5)
        val result = plan.applyTo(courses)

        assertTrue(plan.inserts.isEmpty())
        // 目标行没被改动，源行只剩第 3 周
        assertEquals(setOf(2), result.row("高数", 5).weeks)
        assertEquals(setOf(3), result.row("高数", 3).weeks)
        assertNoDuplicateSlots(result)
    }

    @Test
    fun merge_sameWeekdayDifferentWeekKeepsOneRow() {
        // 「把这周四的课挪到下周四」：目标格上本来就有同一门课，合并后整门课仍是一行。
        // 注意这不是「把第 2 周挪过去就多出一周」——目标格已有课，两出现合一，第 2 周随之消失。
        val courses = listOf(course(1, name = "高数", day = 4, weeks = (1..16).toSet()))
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 2, 4, 3, 4)
        val result = plan.applyTo(courses)

        assertEquals(1, result.size)
        val row = result.single()
        assertEquals((1..16).toSet() - 2, row.weeks)
        assertTrue(3 in row.weeks)
        assertNoDuplicateSlots(result)
    }

    @Test
    fun merge_sameWeekdayTargetFreeMergesWeeksIntoOneRow() {
        // 同一星期、目标周空着：第 2 周的那次课搬到第 3 周。
        // 两周边都还在这一行上（3 是搬来的、5 是原本就有的），仍是一行——同一天不拆行。
        val courses = listOf(course(1, name = "高数", day = 4, weeks = setOf(2, 5)))
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 2, 4, 3, 4)
        val result = plan.applyTo(courses)

        assertEquals(1, result.size)
        assertEquals(setOf(3, 5), result.single().weeks)
        assertEquals(4, result.single().day)
        assertNoDuplicateSlots(result)
    }

    @Test
    fun merge_sourceEmptyIsNoOp() {
        val courses = listOf(course(1, day = 3, weeks = setOf(1)))
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 2, 3, 2, 5)

        assertEquals(0, plan.movedCount)
        assertTrue(plan.deleteIds.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertTrue(plan.inserts.isEmpty())
    }

    // ---- 覆盖 ----

    @Test
    fun overwrite_clearsTargetWeekAndKeepsOtherWeeks() {
        // 目标日的课还有别的周：只摘掉被覆盖的那一周，行保留
        val courses = listOf(
            course(1, name = "高数", day = 3, weeks = setOf(5)),
            course(2, name = "英语", day = 5, weeks = (1..16).toSet()),
        )
        val plan = CourseTweaker.plan(courses, TweakMode.Overwrite, 5, 3, 5, 5)
        val result = plan.applyTo(courses)

        assertEquals(1, plan.movedCount)
        assertEquals(1, plan.targetCount)
        assertEquals(0, plan.targetPurgedCount)
        assertEquals((1..16).toSet() - 5, result.row("英语", 5).weeks)
        assertEquals(setOf(5), result.row("高数", 5).weeks)
        assertTrue(result.on(5, 3).isEmpty())
        assertNoDuplicateSlots(result)
    }

    @Test
    fun overwrite_targetOnlyWeekIsDeleted() {
        // 目标日的课当周之外没有别的周 → 整行删除
        val courses = listOf(
            course(1, name = "高数", day = 3, weeks = setOf(5)),
            course(2, name = "英语", day = 5, weeks = setOf(5)),
        )
        val plan = CourseTweaker.plan(courses, TweakMode.Overwrite, 5, 3, 5, 5)
        val result = plan.applyTo(courses)

        assertEquals(1, plan.targetPurgedCount)
        assertEquals(listOf(2L), plan.deleteIds)
        assertEquals(listOf("高数"), result.map { it.name })
        assertNoDuplicateSlots(result)
    }

    @Test
    fun overwrite_sourceEmptyDoesNotClearTarget() {
        // 源日期没课却执行覆盖：绝不能把目标日期清空
        val courses = listOf(course(2, name = "英语", day = 5, weeks = (1..16).toSet()))
        val plan = CourseTweaker.plan(courses, TweakMode.Overwrite, 5, 3, 5, 5)

        assertTrue(plan.deleteIds.isEmpty())
        assertTrue(plan.updates.isEmpty())
        assertEquals(0, plan.movedCount)
    }

    // ---- 交换 ----

    @Test
    fun exchange_swapsBothSides() {
        val courses = listOf(
            course(1, name = "高数", day = 3, weeks = setOf(5)),
            course(2, name = "英语", day = 5, weeks = setOf(5)),
        )
        val plan = CourseTweaker.plan(courses, TweakMode.Exchange, 5, 3, 5, 5)
        val result = plan.applyTo(courses)

        assertEquals(1, plan.movedCount)
        assertEquals(1, plan.targetCount)
        assertEquals(5, result.row("高数", 5).day)
        assertEquals(3, result.row("英语", 3).day)
        assertEquals(setOf(5), result.on(5, 5).single { it.name == "高数" }.weeks)
        assertEquals(setOf(5), result.on(5, 3).single { it.name == "英语" }.weeks)
        assertNoDuplicateSlots(result)
    }

    @Test
    fun exchange_multiWeekRowsSplitOnBothSides() {
        val courses = listOf(
            course(1, name = "高数", day = 3, weeks = (1..16).toSet()),
            course(2, name = "英语", day = 5, weeks = (1..16).toSet()),
        )
        val plan = CourseTweaker.plan(courses, TweakMode.Exchange, 5, 3, 5, 5)
        val result = plan.applyTo(courses)

        // 两侧各拆出一行：高数第 5 周在周五，英语第 5 周在周三；两个原行各少掉第 5 周
        assertEquals(2, plan.inserts.size)
        assertEquals(setOf(5), result.row("高数", 5).weeks)
        assertEquals(setOf(5), result.row("英语", 3).weeks)
        assertEquals((1..16).toSet() - 5, result.row("高数", 3).weeks)
        assertEquals((1..16).toSet() - 5, result.row("英语", 5).weeks)
        assertNoDuplicateSlots(result)
    }

    @Test
    fun exchange_targetEmptyJustMovesSource() {
        val courses = listOf(course(1, name = "高数", day = 3, weeks = setOf(5)))
        val plan = CourseTweaker.plan(courses, TweakMode.Exchange, 5, 3, 5, 5)
        val result = plan.applyTo(courses)

        assertEquals(1, plan.movedCount)
        assertEquals(0, plan.targetCount)
        assertEquals(5, result.on(5, 5).single().day)
        assertTrue(result.on(5, 3).isEmpty())
    }

    // ---- 跨周 / 边界 ----

    @Test
    fun crossWeekMoveUpdatesWeekSet() {
        // 第 2 周周三 → 第 4 周周五：单周课的周次也要跟着换，否则会留在原周
        val courses = listOf(course(1, day = 3, weeks = setOf(2)))
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 2, 3, 4, 5)
        val result = plan.applyTo(courses)

        val moved = result.single()
        assertEquals(setOf(4), moved.weeks)
        assertEquals(5, moved.day)
    }

    @Test
    fun sameDateIsNoOpForAllModes() {
        // 同一天：任何一种模式都不该动数据（否则覆盖会把当天课删光）
        val courses = listOf(course(1, day = 3, weeks = (1..16).toSet()))
        TweakMode.entries.forEach { mode ->
            val plan = CourseTweaker.plan(courses, mode, 5, 3, 5, 3)
            assertTrue("$mode 应拒绝同日期", plan.deleteIds.isEmpty())
            assertTrue("$mode 应拒绝同日期", plan.updates.isEmpty())
            assertTrue("$mode 应拒绝同日期", plan.inserts.isEmpty())
            assertEquals(0, plan.movedCount)
        }
    }

    @Test
    fun planIsDeterministic() {
        val courses = listOf(
            course(1, name = "高数", day = 3, weeks = (1..16).toSet()),
            course(2, name = "英语", day = 4, weeks = setOf(5, 6)),
            course(3, name = "物理", day = 3, weeks = setOf(5)),
        )
        val first = CourseTweaker.plan(courses, TweakMode.Exchange, 5, 3, 5, 5)
        val second = CourseTweaker.plan(courses, TweakMode.Exchange, 5, 3, 5, 5)

        assertEquals(first.deleteIds, second.deleteIds)
        assertEquals(first.updates, second.updates)
        assertEquals(first.inserts, second.inserts)
    }

    @Test
    fun emptyWeeksRowIsNotDeletedByUnrelatedTweak() {
        // 历史脏数据（weeks 为空）不参与任何出现，调课不该顺手把它删掉
        val courses = listOf(
            course(1, name = "高数", day = 3, weeks = setOf(2)),
            course(2, name = "脏数据", day = 5, weeks = emptySet()),
        )
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 2, 3, 2, 5)

        assertFalse("脏行不该被删", 2L in plan.deleteIds)
        assertTrue(plan.updates.none { it.id == 2L })
    }

    @Test
    fun mergeUnrelatedOccurrencesUntouched() {
        // 只动「第 2 周周三」这一次出现，同一行在别的周/别的天不受影响
        val courses = listOf(
            course(1, name = "高数", day = 3, weeks = setOf(1, 2, 5, 9)),
            course(2, name = "英语", day = 3, weeks = setOf(1, 2)),
            course(3, name = "物理", day = 5, weeks = setOf(1, 2)),
        )
        val plan = CourseTweaker.plan(courses, TweakMode.Merge, 2, 3, 2, 5)
        val result = plan.applyTo(courses)

        assertEquals(2, plan.movedCount)
        // 高数：周三剩下的周次不动，第 2 周拆到周五
        assertEquals(setOf(1, 5, 9), result.row("高数", 3).weeks)
        assertEquals(setOf(2), result.row("高数", 5).weeks)
        // 英语：只剩第 1 周，拆到周五的是第 2 周
        assertEquals(setOf(1), result.row("英语", 3).weeks)
        assertEquals(setOf(2), result.row("英语", 5).weeks)
        // 物理本来就在周五，与调入的课互不影响
        assertEquals(setOf(1, 2), result.row("物理", 5).weeks)
        // 第 2 周周三已清空
        assertTrue(result.on(2, 3).isEmpty())
        assertFalse(result.on(2, 5).isEmpty())
        assertNull(result.firstOrNull { it.name == "英语" && 2 in it.weeks && it.day == 3 })
        assertNoDuplicateSlots(result)
    }
}
