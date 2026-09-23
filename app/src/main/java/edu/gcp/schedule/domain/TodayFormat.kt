package edu.gcp.schedule.domain

import java.time.format.DateTimeFormatter

/**
 * 课程文案格式化：**今日页与桌面小组件共用同一份**。
 *
 * 根因：这些函数原先私有在 `ui/today/TodayScreen.kt` 里，桌面小组件（`ui/widget`）
 * 需要完全相同的 `第3-4节` / `10:15` / `@南B208 · 陈磊` 口径。若各写一遍，
 * 两边迟早会在「自定义时间课怎么显示」「地点要不要 @」这类细节上分叉。
 * 同时 domain 侧不得引用 ui 包，故整体下移到 domain（纯字符串运算，零 Android 依赖）。
 */

/** `9月18日`：今日页标题与调课页日期标签共用（此前各页私写 formatter，口径会漂）。 */
val MONTH_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")

/** `9/18`：课表网格日期表头（单列窄，放不下「9月18日」）。 */
val SHORT_DAY_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("M/d")

/** `第3-4节` / `第5节`。传整门课，避免调用点各自拼 start/end。 */
fun sectionRange(course: Course): String {
    val start = course.startSection
    val end = course.endSection
    return if (end > start) "第${start}-${end}节" else "第${start}节"
}

/** 起始时刻 `10:15`；取不到给 `--:--`。自定义时间课以 customStartTime 为准（唯一口径）。 */
fun clockOf(slots: List<TimeSlot>, course: Course): String =
    ScheduleCalculator.courseStartMinutes(slots, course)?.let(::minutesToClock) ?: "--:--"

/** `615` → `10:15`。 */
fun minutesToClock(minutes: Int): String =
    "%02d:%02d".format(minutes / 60, minutes % 60)

/** `10:15–11:40 · @南B208 · 陈磊`；缺项自动省略，全缺给空串。 */
fun metaLine(slots: List<TimeSlot>, course: Course): String {
    val start = ScheduleCalculator.courseStartMinutes(slots, course)
    val end = ScheduleCalculator.courseEndMinutes(slots, course)
    val range = if (start != null && end != null) {
        "${minutesToClock(start)}–${minutesToClock(end)}"
    } else {
        null
    }
    val location = compactPosition(course.position)
    return listOfNotNull(
        range,
        location.takeIf { it.isNotBlank() }?.let { "@$it" },
        course.teacher.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
}

/** `1` → `一`（星期几的单字写法）。 */
fun dayLabel(day: Int): String = when (day) {
    1 -> "一"
    2 -> "二"
    3 -> "三"
    4 -> "四"
    5 -> "五"
    6 -> "六"
    7 -> "日"
    else -> day.toString()
}

private val parenRegex = Regex("[（(]([^）)]*)[）)]")
private val openParenTailRegex = Regex("[（(]([^）)]*)$")

/**
 * 地点压缩。
 *
 * 根因：教务返回的是 `教学南大楼(南B302)` 这种带楼栋全名的写法，
 * 7 列布局下单列内容宽约 37dp，原样显示会被截成 `教学南…`，反而看不到教室号。
 *
 * 规则（按顺序）：
 * 1. 取最后一对括号里的非空内容 → `教学南大楼(南B302)` → `南B302`
 * 2. 括号没闭合也认 → `教学北大楼(北B102` → `北B102`
 *    （老版本解析把结尾右括号 trim 掉了，库里已经存了一批这样的脏数据，只能在这层兜住）
 * 3. 只剩括号残留（教务对无地点课程返回 `()`）→ 视为无地点
 * 4. 没有括号就原样返回
 */
fun compactPosition(position: String): String {
    val raw = position.trim()
    if (raw.isEmpty()) return ""
    val inners = parenRegex.findAll(raw)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (inners.isNotEmpty()) return inners.last()

    val tail = openParenTailRegex.find(raw)?.groupValues?.get(1)?.trim()
    if (!tail.isNullOrEmpty()) return tail

    return if (raw.any { it == '(' || it == '（' || it == ')' || it == '）' }) "" else raw
}
