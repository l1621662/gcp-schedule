package edu.gcp.schedule.domain

/**
 * 学期字符串的唯一归一化口径（DESIGN §4.4 / §4.17）。
 *
 * 根因（2026-09-23 真机）：两条链路存的学期格式不一样——
 * - 网页导入：直接取正方课表页标题的**原始文本**（如「2026-2027学年第一学期」）；
 * - 调课检测：用教务的学年/学期代码拼出「2026-2027-1」。
 * 两者永远不相等，于是每次检测都误判「教务已切换学期」，把差异检测整条堵死。
 *
 * 现在统一在这里归一：`2026-2027学年第一学期` → `2026-2027-1`；
 * 认不出来的原样返回 null（调用方保留原文用于展示，比较时跳过）。
 */
object TermFormat {

    /** 已经是规范形式：2026-2027-1 / -2 / -3 */
    private val canonical = Regex("""^(\d{4})-(\d{4})-([123])$""")

    /** 正方课表页标题：2026-2027学年第一学期、2026-2027学年第2学期、2026-2027学年 1 学期 */
    private val withAcademicYear = Regex("""^(\d{4})\s*-\s*(\d{4})\s*学年\s*第?\s*([一二三123])\s*学期$""")

    /** 秋季/春季学期这类说法（部分部署把春夏秋冬写进标题） */
    private val withSeason = Regex("""^(\d{4})\s*-\s*(\d{4})\s*学年\s*(秋季|春季|秋季学期|春季学期)$""")

    /** 归一化为 `2026-2027-1`；无法识别返回 null。 */
    fun normalize(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        canonical.matchEntire(text)?.let { m ->
            return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
        }
        withAcademicYear.matchEntire(text)?.let { m ->
            val no = when (m.groupValues[3]) {
                "一", "1" -> "1"
                "二", "2" -> "2"
                "三", "3" -> "3"
                else -> return null
            }
            return "${m.groupValues[1]}-${m.groupValues[2]}-$no"
        }
        withSeason.matchEntire(text)?.let { m ->
            val no = if ("秋" in m.groupValues[3]) "1" else "2"
            return "${m.groupValues[1]}-${m.groupValues[2]}-$no"
        }
        return null
    }

    /** 比较用：两侧都尽量归一，认不出来就退回原文（都认不出时仍按字符串比较）。 */
    fun sameTerm(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return (normalize(a) ?: a.trim()) == (normalize(b) ?: b.trim())
    }
}
