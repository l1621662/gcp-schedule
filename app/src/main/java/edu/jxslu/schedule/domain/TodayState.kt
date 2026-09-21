package edu.jxslu.schedule.domain

import java.time.LocalDate

/**
 * 今日课表状态。
 *
 * 核心取舍：
 * - **已结束的课不出现**；列表只放「还没开始的」。
 * - **同一节课只出现一次**：顶部焦点卡（正在上课 / 60 分钟内的下一节）拿走第一门，
 *   [listCourses] 是 [remaining] 去掉那一门之后的列表。旧版没做这层去重，
 *   列表第一项和状态卡是同一节课，看着像内容重复。
 * - **「下一节」有 60 分钟准入窗口**：更远的课留在列表里排队，不冒充「下一节」。
 * - 今天没有待上课程（上完 / 没课）时才让「明天」上桌（[tomorrowVisible]）。
 *
 * 位置说明：本文件原先在 `ui/today/TodayViewModel.kt`。桌面小组件（`ui/widget`）
 * 需要与今日页**完全同源**的状态推导，而 domain 侧不得引用 ui 包，故整体下移；
 * 今日页的 `TodayViewModel` 仍在此状态之上做 ViewModel 封装。
 */
/**
 * 「下一节」焦点卡准入窗口（分钟）。
 *
 * 2026-09-21 改（DESIGN §3.3）：下一节课距开始超过该窗口时不进焦点卡——
 * 凌晨看下午的课、上午看下午第一节的课，都不该冒充「下一节」挂一整天。
 * 正在上课不受窗口约束（focus=正在上的课，上完它下一节再按窗口接棒）。
 */
const val NEXT_COURSE_WINDOW_MINUTES = 60

data class TodayState(
    val loading: Boolean = true,
    val semester: SemesterConfig? = null,
    /** 0 表示不在学期内 */
    val week: Int = 0,
    val day: Int = 1,
    /** 今天的日期，顶栏「9月18日 周四」用 */
    val date: LocalDate = LocalDate.now(),
    val inTerm: Boolean = false,
    val slots: List<TimeSlot> = emptyList(),
    val totalCourseCount: Int = 0,

    /** 今天还没开始的课（不含正在上、不含已结束），按有效开始时刻排序 */
    val remaining: List<Course> = emptyList(),
    /** 正在上的课，多门重叠时取最早开始的；**不同时出现在 remaining 里** */
    val ongoing: Course? = null,
    /**
     * 今天下一节且**距开始 ≤ [NEXT_COURSE_WINDOW_MINUTES] 分钟**的课；**仍在 remaining 里**
     * （列表头计数只数列表，本就把它拿走了）。更远的课留在 [remaining] 里排队，
     * 不冒充「下一节」——焦点卡与小组件共用这一取舍。
     */
    val next: Course? = null,
    /** 焦点卡下方的列表 = [remaining] 去掉焦点那门，避免同一节课两处重复 */
    val listCourses: List<Course> = emptyList(),
    /** 距下一节上课还有几分钟；仅 [next] 非空时有效（远课时为 null） */
    val minutesToNext: Int? = null,
    /** 当前这节还剩几分钟下课（整门课口径，自定义时间/兜底用） */
    val minutesToOngoingEnd: Int? = null,
    /**
     * 正在上课时右侧的细粒度倒计时：
     * `还有 25 分钟下课` / `课间 · 还有 4 分钟上课`。
     * 不带小节序号——课时卡已有「第3-4节」，再标一个「第1节」是第二套编号。
     */
    val ongoingCountdown: String? = null,
    /** 正在上课时的整门课进度 0f..1f；其余情况 null（不画进度条） */
    val ongoingProgress: Float? = null,
    /** 今天排了课，但已经全部上完 */
    val todayAllDone: Boolean = false,
    /** 今天本来就没有课 */
    val todayEmpty: Boolean = false,
    /** 今天一共几节（含已结束） */
    val todayTotal: Int = 0,

    /** 明天 */
    val tomorrowDay: Int = 1,
    val tomorrowWeek: Int = 0,
    val tomorrowInTerm: Boolean = false,
    /** 只有今天没有待上课程（上完 / 没课 / 不在上课）才让明天上桌，今天的信息优先 */
    val tomorrowVisible: Boolean = false,
    val tomorrowCourses: List<Course> = emptyList(),
) {
    /**
     * 焦点课：正在上课优先，否则 60 分钟窗口内的下一节。
     * **今日页与小组件共用同一取舍。**
     */
    val focus: Course? get() = ongoing ?: next
}

/**
 * 纯函数版本的状态推导，便于单测（JVM 单测与小组件共用）：
 * 「今天还有几节 / 是否都上完 / 明天有没有课」这些分支全部在这里决定，界面只做渲染。
 */
fun buildTodayState(
    semester: SemesterConfig?,
    slots: List<TimeSlot>,
    courses: List<Course>,
    today: LocalDate,
    now: LocalTimeLike,
): TodayState {
    val day = today.dayOfWeek.value
    val week = semester?.let { ScheduleCalculator.weekNumberOf(it, today) } ?: 0
    val inTerm = semester != null && week in 1..semester.totalWeeks

    val dayCourses = if (inTerm) {
        ScheduleCalculator.coursesOnDay(courses, week, day)
    } else {
        emptyList()
    }
    // 排序键统一走「有效开始时刻」：自定义时间的课按 startSection 会排错序（错到别的课前面）
    val sorted = dayCourses.sortedBy { startKey(slots, it) }
    val phases = sorted.associate { it.id to ScheduleCalculator.coursePhase(slots, it, now) }

    // 列表只放「还没开始」：已结束不进列表，正在上的只进顶部焦点卡（避免同一节课两处重复）
    val remaining = sorted.filter { phases[it.id] == CoursePhase.Upcoming }
    val ongoing = sorted.firstOrNull { phases[it.id] == CoursePhase.Ongoing }
    // 「下一节」要过准入窗口：最近的未开始课距开始 ≤60 分钟才进焦点卡；
    // 更远时 next=null（焦点让位给轻量提示 + 完整列表），但 remaining 不变
    val next = remaining.firstOrNull()?.takeIf { first ->
        (minutesUntilStart(slots, first, now) ?: Int.MAX_VALUE) <= NEXT_COURSE_WINDOW_MINUTES
    }
    val focus = ongoing ?: next

    val tomorrow = today.plusDays(1)
    val tomorrowDay = tomorrow.dayOfWeek.value
    val tomorrowWeek = semester?.let { ScheduleCalculator.weekNumberOf(it, tomorrow) } ?: 0
    val tomorrowInTerm = semester != null && tomorrowWeek in 1..semester.totalWeeks

    return TodayState(
        loading = false,
        semester = semester,
        week = week,
        day = day,
        date = today,
        inTerm = inTerm,
        slots = slots,
        totalCourseCount = courses.size,
        remaining = remaining,
        ongoing = ongoing,
        next = next,
        listCourses = remaining.filter { it.id != focus?.id },
        minutesToNext = next?.let { minutesUntilStart(slots, it, now) },
        minutesToOngoingEnd = ongoing?.let { minutesUntilEnd(slots, it, now) },
        ongoingCountdown = ongoing?.let { ongoingCountdownLabel(slots, it, now) },
        ongoingProgress = ongoing?.let { ongoingProgress(slots, it, now) },
        todayAllDone = dayCourses.isNotEmpty() && dayCourses.all { phases[it.id] == CoursePhase.Ended },
        todayEmpty = dayCourses.isEmpty(),
        todayTotal = dayCourses.size,
        tomorrowDay = tomorrowDay,
        tomorrowWeek = tomorrowWeek,
        tomorrowInTerm = tomorrowInTerm,
        // 明天上桌的判定**显式**按「今天的待上课程清空」：不依赖焦点是否为空——
        // 远课时（下一节在 60 分钟窗口外）焦点为空但今天有课，明天不得提前上桌
        tomorrowVisible = tomorrowInTerm && ongoing == null && remaining.isEmpty(),
        tomorrowCourses = if (tomorrowInTerm) {
            ScheduleCalculator.coursesOnDay(courses, tomorrowWeek, tomorrowDay)
                .sortedBy { startKey(slots, it) }
        } else {
            emptyList()
        },
    )
}

/** 排序键：有效开始时刻；作息表缺该节又没自定义时间的课排到最后，不让它们冒到列表头上。 */
private fun startKey(slots: List<TimeSlot>, course: Course): Int =
    ScheduleCalculator.courseStartMinutes(slots, course) ?: Int.MAX_VALUE

/** 距上课还有几分钟；已过开课时间返回 null。自定义时间课以 customStartTime 为准。 */
fun minutesUntilStart(slots: List<TimeSlot>, course: Course, now: LocalTimeLike): Int? =
    ScheduleCalculator.courseStartMinutes(slots, course)
        ?.let { (it - now.toMinutes()).takeIf { delta -> delta > 0 } }

/** 距下课还有几分钟；已过下课时间返回 null。自定义时间课以 customEndTime 为准。 */
fun minutesUntilEnd(slots: List<TimeSlot>, course: Course, now: LocalTimeLike): Int? =
    ScheduleCalculator.courseEndMinutes(slots, course)
        ?.let { (it - now.toMinutes()).takeIf { delta -> delta > 0 } }

/** 正在上课时的整门课进度 0f..1f；缺起始/结束时间时不画。 */
fun ongoingProgress(
    slots: List<TimeSlot>,
    course: Course,
    now: LocalTimeLike,
): Float? {
    val start = ScheduleCalculator.courseStartMinutes(slots, course) ?: return null
    val end = ScheduleCalculator.courseEndMinutes(slots, course) ?: return null
    if (end <= start) return null
    return ((now.toMinutes() - start).toFloat() / (end - start)).coerceIn(0f, 1f)
}

/**
 * 正在上课时的细粒度倒计时文案。
 *
 * 大节内两小节之间有 5 分钟课间：上课时说「第3节 · 还有 xx 分钟下课」，
 * 课间说「课间 · 还有 xx 分钟上课」。
 *
 * 节次号用**作息小节号**（`slot.number`），与焦点卡标题的「第3-4节」同一套坐标；
 * 旧版用课程内序号（`index + 1`），于是「正在上课 · 第3-4节」旁边挂着「第1节」——
 * 同一张卡上两套编号，是今日页显乱的来源之一。
 *
 * 自定义时间课没有小节表，退回整门口径。
 */
fun ongoingCountdownLabel(
    slots: List<TimeSlot>,
    course: Course,
    now: LocalTimeLike,
): String? {
    val cur = now.toMinutes()
    if (course.isCustomTime) {
        val mins = minutesUntilEnd(slots, course, now) ?: return null
        return "还有 $mins 分钟下课"
    }
    val courseSlots = slots
        .filter { it.number in course.startSection..course.endSection }
        .sortedBy { it.number }
    if (courseSlots.isEmpty()) {
        return minutesUntilEnd(slots, course, now)?.let { "还有 $it 分钟下课" }
    }
    courseSlots.forEachIndexed { index, slot ->
        val start = ScheduleCalculator.toMinutes(slot.startTime)
        val end = ScheduleCalculator.toMinutes(slot.endTime)
        if (cur in start until end) {
            val mins = end - cur
            return if (courseSlots.size <= 1) {
                "还有 $mins 分钟下课"
            } else {
                "第${slot.number}节 · 还有 $mins 分钟下课"
            }
        }
        if (index < courseSlots.lastIndex) {
            val nextStart = ScheduleCalculator.toMinutes(courseSlots[index + 1].startTime)
            if (cur in end until nextStart) {
                return "课间 · 还有 ${nextStart - cur} 分钟上课"
            }
        }
    }
    return minutesUntilEnd(slots, course, now)?.let { "还有 $it 分钟下课" }
}
