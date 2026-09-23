package edu.gcp.schedule.domain

/**
 * 日历同步的固定口径（DESIGN §4.12）：提醒时长选项表 + 展示文案。
 *
 * 单一来源：CalendarSyncer（默认值）、DataStore 键默认值、设置页轮选器、
 * Snackbar 文案全部从这里取，不各自写字面量。
 */
object CalendarSyncDefaults {

    /** 默认提前提醒分钟数（需求口径）。 */
    const val DEFAULT_REMINDER_MINUTES = 20

    /** 提醒时长上限（分钟），超出部分轮选器不给。 */
    const val MAX_REMINDER_MINUTES = 120

    /** 提醒时长选项步长（分钟）；0 表示不提醒。 */
    const val REMINDER_STEP_MINUTES = 5

    /** 提醒时长候选：0, 5, 10, …, 120（步长 5）。 */
    val REMINDER_CHOICES: List<Int> = (0..MAX_REMINDER_MINUTES step REMINDER_STEP_MINUTES).toList()

    /** 存储值夹取到合法值域（手改数据/旧数据防线）。 */
    fun coerceReminderMinutes(value: Int): Int = value.coerceIn(0, MAX_REMINDER_MINUTES)

    /** 存储值 → 选项下标；不在选项表内的值归到最近的合法档位（0 与 120 夹边）。 */
    fun reminderChoiceIndex(minutes: Int): Int {
        val coerced = coerceReminderMinutes(minutes)
        val stepped = (coerced / REMINDER_STEP_MINUTES) * REMINDER_STEP_MINUTES
        return REMINDER_CHOICES.indexOf(stepped).coerceAtLeast(0)
    }

    /** 存储值 → 展示文案：0 = 不提醒，其余「提前 N 分钟」。 */
    fun reminderLabel(minutes: Int): String =
        if (coerceReminderMinutes(minutes) <= 0) "不提醒" else "提前 ${coerceReminderMinutes(minutes)} 分钟"
}
