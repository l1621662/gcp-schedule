package edu.gcp.schedule.domain

/**
 * 作息表编辑规则。
 *
 * 作息表不是「随便填的展示数据」：网格行高、当前时刻线、「还剩 X 分」、下一节判定
 * 全都从 [TimeSlot] 推出来。填错了页面不会崩，只会安静地显示错误信息，
 * 所以脏输入必须在写入之前挡掉。这里放纯函数，便于单测覆盖。
 */
object TimeSlotRules {

    /** 24 小时制的 HH:mm。 */
    private val hhmm = Regex("^([01]\\d|2[0-3]):[0-5]\\d$")

    /**
     * 校验整张作息表。返回 null 表示通过，否则返回给用户看的错误文案。
     *
     * 三条规则：时间格式合法、每节结束晚于开始、相邻小节不重叠。
     * 相邻小节首尾相接（上一节 09:10 结束、下一节 09:15 开始）是正常的课间，不算重叠。
     */
    fun validate(slots: List<TimeSlot>): String? {
        if (slots.isEmpty()) return "作息表不能为空"
        for (slot in slots.sortedBy { it.number }) {
            if (!hhmm.matches(slot.startTime)) {
                return "第 ${slot.number} 节的开始时间应为 HH:mm，如 08:30"
            }
            if (!hhmm.matches(slot.endTime)) {
                return "第 ${slot.number} 节的结束时间应为 HH:mm，如 09:10"
            }
            if (ScheduleCalculator.toMinutes(slot.endTime) <=
                ScheduleCalculator.toMinutes(slot.startTime)
            ) {
                return "第 ${slot.number} 节的结束时间必须晚于开始时间"
            }
        }
        val sorted = slots.sortedBy { it.number }
        for (i in 0 until sorted.lastIndex) {
            val cur = sorted[i]
            val next = sorted[i + 1]
            if (ScheduleCalculator.toMinutes(next.startTime) <
                ScheduleCalculator.toMinutes(cur.endTime)
            ) {
                return "第 ${next.number} 节的开始时间早于第 ${cur.number} 节的结束时间，两节重叠了"
            }
        }
        return null
    }

    /**
     * 时间输入框的即时格式化：边打字边补冒号。
     *
     * - 已经带冒号（例如粘贴进来的 `8:30`）→ 只做字符与长度过滤，不重新切分，
     *   否则 `8:30` 会被补成 `83:0`。
     * - 纯数字 → 满 3 位起在第 2 位后补冒号，`083` → `08:3`、`0830` → `08:30`。
     *
     * 边界：想输入个位数小时要写成 `08`。写成 `830` 会被补成 `83:0`，
     * 这属于非法值，会被 [validate] 挡下并给出提示，不会静默写库。
     */
    fun normalizeInput(raw: String): String {
        if (raw.contains(':')) return raw.filter { it.isDigit() || it == ':' }.take(5)
        val digits = raw.filter(Char::isDigit).take(4)
        return if (digits.length >= 3) {
            digits.substring(0, 2) + ":" + digits.substring(2)
        } else {
            digits
        }
    }

    /** 把任意时刻补零成 HH:mm；解析不出来时原样返回，交给 [validate] 报错。 */
    fun padTime(raw: String): String {
        val t = raw.trim()
        if (hhmm.matches(t)) return t
        val parts = t.split(':')
        if (parts.size != 2) return t
        val h = parts[0].toIntOrNull() ?: return t
        val m = parts[1].toIntOrNull() ?: return t
        if (h !in 0..23 || m !in 0..59) return t
        return "%02d:%02d".format(h, m)
    }
}
