package edu.gcp.schedule.domain

/**
 * 调课模式（DESIGN §4.11，参考拾光「快捷操作-调课」）。
 *
 * 三者的差别只在「目标日期原有课程怎么处理」；源日期的课一律摘走：
 * - [Merge]：目标日期保留，源日期的课叠加进来（A → B）
 * - [Overwrite]：先清空目标日期当周，再放入源日期（A ≫ B）
 * - [Exchange]：两个日期当周互换（A ↔ B）
 */
enum class TweakMode {
    Merge,
    Overwrite,
    Exchange;

    val label: String
        get() = when (this) {
            Merge -> "合并"
            Overwrite -> "覆盖"
            Exchange -> "交换"
        }

    /** 随模式选择显示的一句话说明。 */
    val description: String
        get() = when (this) {
            Merge -> "源日期的课加入目标日期，目标原有课程保留"
            Overwrite -> "先清空目标日期当周，再放入源日期的课"
            Exchange -> "两个日期当周的课程互换位置"
        }

    /** 是否有破坏性（UI 需二次确认）：合并只是叠加，不动目标日期已有安排。 */
    val destructive: Boolean
        get() = this != Merge
}

/**
 * 一次调课要落库的全部动作。
 *
 * 根因：调课不是「改一条记录」——多周课要拆成「原行摘掉源周」+「新行只带目标周」两行，
 * 覆盖/交换还要先动目标日期的课。把动作规划成纯数据、由调用方一次性落库，
 * 规划逻辑就能脱离 Android 与 Room 做 JVM 单测（与 [ScheduleCalculator] 同样的理由）。
 *
 * 三种动作互不重叠：同一 id 不会既在 [deleteIds] 又在 [updates]。
 */
data class TweakPlan(
    /** 摘周后不再有任何出现的课程 id（整行删除）。 */
    val deleteIds: List<Long>,
    /** 就地修改的课程（周次变化，或整行搬到了别的星期）。 */
    val updates: List<Course>,
    /** 新增的行（多周课拆出来的那一份，id = 0 交给 Room 自增）。 */
    val inserts: List<Course>,
    /** 源日期被移走的课程门数。 */
    val movedCount: Int,
    /** 目标日期当周原有课程门数（覆盖会被清、交换会被换走）。 */
    val targetCount: Int,
    /** 上述中因「摘完一周就不剩别的周」而整行删除的门数，用于二次确认文案。 */
    val targetPurgedCount: Int,
)

/**
 * 调课规划（DESIGN §4.11）。纯函数：输入全量课程与两个日期，输出要写库的动作。
 *
 * **模型**：先把课程展开成「出现」（某一行课在某一周某一天实际存在），
 * 对出现集合做搬移/清除，再把终态折回「行」。
 * 不走「边算边攒动作」的增量路线，是因为源与目标会重合——
 * 同一星期不同周（「把这周四的课挪到下周四」）、甚至同一天同一周的另一门课，
 * 增量改动会在同一行上互相覆盖（例如交换模式下同一行横跨两周时，第二次摘周会摘错）。
 * 终态折算天然幂等：出现集合算完，行怎么拆怎么合是确定的。
 *
 * 与拾光的差异：拾光把周次放在 `course_weeks` 关联表里逐周摘除，本项目 `weeks` 是内嵌列，
 * 「摘掉一周」直接体现为新的 `weeks` 集合；拆行仍是「克隆一条只带目标周的新课」。
 *
 * 边界：
 * - 源日期当周没有课 → 返回空 plan，**不做任何破坏性动作**（含覆盖/交换）。
 *   UI 也会挡住这种输入，这里再挡一次是为了让「界面数据陈旧」不至于清掉目标日期；
 * - 两日期相同、周次不在学期内，由调用方挡在最外层（见 `TweakSchedule`）。
 */
object CourseTweaker {

    /**
     * 一次「出现」。
     * [originWeek]/[originDay] 是它出生在哪（用来判断本次操作是否该搬它、以及它是不是被搬过），
     * [week]/[day] 是它现在在哪。
     */
    private data class Occ(
        val rowId: Long,
        val originWeek: Int,
        val originDay: Int,
        val week: Int,
        val day: Int,
    ) {
        /** 被本次操作搬动过。同格去重时未搬动的优先保留（少动一行、少删一行）。 */
        val moved: Boolean get() = week != originWeek || day != originDay
    }

    /** 课程身份（忽略 day / weeks / 配色）：判断同一格是否已有同一门课，避免叠出两个一模一样的块。 */
    private fun Course.identity(): String =
        listOf(name, startSection, endSection, teacher, kind.name).joinToString("|")

    fun plan(
        courses: List<Course>,
        mode: TweakMode,
        fromWeek: Int,
        fromDay: Int,
        toWeek: Int,
        toDay: Int,
    ): TweakPlan {
        val sourceRows = ScheduleCalculator.coursesOnDay(courses, fromWeek, fromDay)
        val targetRows = ScheduleCalculator.coursesOnDay(courses, toWeek, toDay)
        // 同一天：覆盖/交换会先把该格清空而源又「搬到自己身上」，净效果是把课删掉。
        // UI 会拦住这个输入，这里再挡一次——纯函数不该有「按错参数就毁数据」的行为。
        val sameDate = fromWeek == toWeek && fromDay == toDay
        if (sameDate || sourceRows.isEmpty()) {
            return TweakPlan(emptyList(), emptyList(), emptyList(), 0, targetRows.size, 0)
        }

        val byId = courses.associateBy { it.id }
        val expanded = courses.flatMap { c -> c.weeks.map { Occ(c.id, it, c.day, it, c.day) } }

        val relocated = when (mode) {
            TweakMode.Merge -> expanded.map {
                if (it.originWeek == fromWeek && it.originDay == fromDay) {
                    it.copy(week = toWeek, day = toDay)
                } else {
                    it
                }
            }

            TweakMode.Overwrite -> {
                // 先清空目标当周：此刻停在该格的都清掉（含本来就在那儿的），再把源日期的搬过来
                expanded
                    .filterNot { it.week == toWeek && it.day == toDay }
                    .map {
                        if (it.originWeek == fromWeek && it.originDay == fromDay) {
                            it.copy(week = toWeek, day = toDay)
                        } else {
                            it
                        }
                    }
            }

            TweakMode.Exchange -> expanded.map {
                when {
                    it.originWeek == fromWeek && it.originDay == fromDay ->
                        it.copy(week = toWeek, day = toDay)

                    it.originWeek == toWeek && it.originDay == toDay ->
                        it.copy(week = fromWeek, day = fromDay)

                    else -> it
                }
            }
        }

        // 同一格（周次 × 星期）里同一门课只留一份：被搬来的那份让位给本来就占着那格的。
        // 没有这一步，「调过去再调回来」会在同一格叠出两个一模一样的块。
        val survivors = relocated
            .groupBy { Triple(it.week, it.day, byId.getValue(it.rowId).identity()) }
            .values
            .map { group -> group.minWith(compareBy({ it.moved }, { it.rowId })) }

        val rows = mutableListOf<Course>()
        survivors.groupBy { it.rowId }.forEach { (rowId, occurrences) ->
            val original = byId.getValue(rowId)
            // 按天分组：原星期那一组排最前、保留行 id，其余组各克隆一行（多周课被拆到别天的情形）
            val byDay = occurrences.groupBy { it.day }
                .map { (day, list) ->
                    val weeks = list.fold(mutableSetOf<Int>()) { acc, occ -> acc.apply { add(occ.week) } }
                    day to weeks
                }
                .sortedBy { (day, _) -> if (day == original.day) 0 else 1 }
            byDay.forEachIndexed { index, (day, weeks) ->
                rows += if (index == 0) {
                    original.copy(day = day, weeks = weeks)
                } else {
                    // 克隆必须复制全部字段（配色 / 自定义时间 / kind），否则调完课会掉色、掉自定义时间
                    original.copy(id = 0, day = day, weeks = weeks)
                }
            }
        }

        val survivingIds = rows.mapNotNullTo(HashSet()) { it.id.takeIf { id -> id > 0 } }
        // 只删除「本来有出现、搬完却没有了」的行。`weeks` 为空的历史脏行不参与本次操作，
        // 也不该被顺手删掉——调课只动它被要求动的那部分。
        val touchedIds = expanded.mapTo(HashSet()) { it.rowId }
        val deleteIds = courses.filter { it.id in touchedIds && it.id !in survivingIds }.map { it.id }
        return TweakPlan(
            deleteIds = deleteIds,
            // 极端组合下同一 id 既被改写又被删，删除优先
            updates = rows.filter { it.id > 0 && it != byId.getValue(it.id) }
                .filterNot { it.id in deleteIds },
            inserts = rows.filter { it.id == 0L },
            movedCount = sourceRows.size,
            targetCount = targetRows.size,
            targetPurgedCount = targetRows.count { it.id in deleteIds },
        )
    }
}
