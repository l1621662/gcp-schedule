package edu.gcp.schedule.data.jw

import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.TermFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 解析正方教务系统课表页 DOM（xskbcx.aspx / xskb_list.do 系）。
 *
 * 与强智不同：正方直接把「星期-节次」写死在单元格 id 上（如 id="1-1" = 周一第1节），
 * 不需要靠 colspan/rowspan 数列号，可靠得多。
 *
 * 一个单元格内可能有多门课（同一时段不同周次轮换上课），每门课是一个
 * <div class="timetable_con">，内部用 title 属性为 tooltip 的 <p> 标签分别装
 * 节/周、上课地点、教师、教学班组成、课程总学时。「调」课用 <u class="title
 * showJxbtkjl"> 代替 <span class="title">，文本前会带「【调】」前缀。
 */
object ZhengfangScheduleParser {

    data class RawItem(
        val name: String,
        val weekTime: String,
        val place: String,
        val teacher: String,
        val day: Int,
        val section: Int,
    )

    /**
     * WebView 注入脚本：按单元格 id="day-section" 直接取星期与节次，
     * 不再需要列序累加/rowspan 占用表这套强智专用的算法。
     */
    const val EXTRACT_JS: String = """
(function(){
  try {
    var out = [];
    // 学期口径：正方把学年学期写在表头标题里，比找 <select> 更稳
    var term = '';
    var termEl = document.querySelector('.timetable_title h6');
    if (termEl) { term = (termEl.textContent || '').trim(); }

    var cells = document.querySelectorAll('td.td_wrap[id]');
    for (var i = 0; i < cells.length; i++) {
      var td = cells[i];
      var idParts = (td.id || '').split('-');
      if (idParts.length !== 2) continue;
      var day = parseInt(idParts[0], 10);
      var section = parseInt(idParts[1], 10);
      if (!(day >= 1 && day <= 7)) continue;

      var conList = td.querySelectorAll('.timetable_con');
      for (var j = 0; j < conList.length; j++) {
        var con = conList[j];
        var nameEl = con.querySelector('.title');
        var name = nameEl ? (nameEl.textContent || '').replace(/^【调】/, '').replace(/\s+/g, ' ').trim() : '';
        if (!name) continue;

        var weekTime = '', place = '', teacher = '';
        var ps = con.querySelectorAll('p');
        for (var k = 0; k < ps.length; k++) {
          var p = ps[k];
          var label = p.querySelector('span[data-toggle=\"tooltip\"]');
          var titleAttr = label ? (label.getAttribute('title') || '') : '';
          var fonts = p.querySelectorAll('font');
          var val = fonts.length ? (fonts[fonts.length - 1].textContent || '').replace(/\s+/g, ' ').trim() : '';
          if (titleAttr.indexOf('节/周') >= 0) { weekTime = val; }
          else if (titleAttr.indexOf('上课地点') >= 0) { place = val; }
          else if (titleAttr.indexOf('教师') >= 0) { teacher = val; }
        }
        out.push({ name: name, weekTime: weekTime, place: place, teacher: teacher, day: day, section: section });
      }
    }
    return JSON.stringify({ ok: true, items: out, term: term, title: document.title || '', url: location.href });
  } catch (e) {
    return JSON.stringify({ ok: false, error: String(e) });
  }
})()
"""

    fun parseExtractJson(json: String): ImportParseResult {
        return try {
            val items = decodeExtractJson(json)
            if (items.isEmpty()) {
                ImportParseResult.Failure(
                    "当前页面未解析到课程。请先在教务里打开课表查询页，再点导入。",
                )
            } else {
                var failed = 0
                val courses = items.mapNotNull { toCourse(it).also { c -> if (c == null) failed++ } }
                if (courses.isEmpty()) {
                    ImportParseResult.Failure(
                        "解析到 ${items.size} 条，但周次/星期字段不完整（失败 $failed 条）",
                    )
                } else {
                    // 学期统一走 TermFormat：页面标题是「2026-2027学年第一学期」这种原文，
                // 而调课检测那边给的是「2026-2027-1」——不归一化就会每次都判「教务已切换学期」
                val rawTerm = extractTermField(json)
                ImportParseResult.Success(courses, term = TermFormat.normalize(rawTerm) ?: rawTerm)
                }
            }
        } catch (e: Exception) {
            ImportParseResult.Failure("解析失败：${e.message}")
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    internal fun decodeExtractJson(jsonText: String): List<RawItem> {
        val root = json.parseToJsonElement(jsonText).jsonObject
        val ok = root["ok"]?.let { runCatching { it.jsonPrimitive.content.toBoolean() }.getOrNull() }
        if (ok == false) return emptyList()
        val arr = root["items"]?.jsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            RawItem(
                name = o["name"]?.jsonPrimitive?.content.orEmpty(),
                weekTime = o["weekTime"]?.jsonPrimitive?.content.orEmpty(),
                place = o["place"]?.jsonPrimitive?.content.orEmpty(),
                teacher = o["teacher"]?.jsonPrimitive?.content.orEmpty(),
                day = o["day"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
                section = o["section"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            )
        }
    }

    private fun extractTermField(jsonText: String): String? {
        return try {
            json.parseToJsonElement(jsonText).jsonObject["term"]?.jsonPrimitive?.content
                ?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    fun toCourse(raw: RawItem): Course? {
        if (raw.name.isBlank()) return null
        val day = raw.day.takeIf { it in 1..7 } ?: return null
        val weeks = parseWeeks(raw.weekTime)
        if (weeks.isEmpty()) return null
        // 优先从「(x-y节)」文本里取真实节次范围；取不到才退回用 id 里的起始节次
        // （id 上的节次只是这个格子在表格里的第一行，rowspan 合并格会让它偏小）。
        val sections = parseSections(raw.weekTime) ?: (raw.section to raw.section)
        return Course(
            id = 0,
            name = raw.name,
            teacher = raw.teacher,
            position = raw.place,
            day = day,
            startSection = sections.first,
            endSection = sections.second,
            weeks = weeks,
            colorIndex = ScheduleCalculator.colorIndexFor(raw.name),
        )
    }

    /**
     * 节次范围来自形如 "(1-2节)9-15周(单),16-17周" 或 "(5-6节)第18周" 的文本，
     * 正方用圆括号包节次，强智那边用的是方括号，两边不能共用一套正则。
     */
    fun parseSections(text: String): Pair<Int, Int>? {
        Regex("[（(](\\d+)\\s*-\\s*(\\d+)\\s*节[)）]").find(text)?.let { m ->
            return m.groupValues[1].toInt() to m.groupValues[2].toInt()
        }
        Regex("[（(](\\d+)\\s*节[)）]").find(text)?.let { m ->
            val a = m.groupValues[1].toInt()
            return a to a
        }
        return null
    }

    /**
     * 周次解析，重点处理正方常见的「单周/双周」标注：
     * "9-15周(单),16-17周" = {9,11,13,15,16,17}，不是 9..17 整段。
     * 每个逗号分隔的段落各自携带自己的单/双标记，互不影响。
     */
    fun parseWeeks(text: String): Set<Int> {
        val weeks = mutableSetOf<Int>()
        // 去掉前面的 "(x-y节)" 节次段，只留周次部分；取不到就整串当周次处理
        val body = text.substringAfter("节)", text).ifBlank { text }
        for (rawSeg in body.split(',', '，')) {
            var seg = rawSeg.trim()
            if (seg.isEmpty()) continue

            var parity = 0 // 0 = 每周, 1 = 单周, 2 = 双周
            if (Regex("[（(]单[)）]").containsMatchIn(seg)) {
                parity = 1
                seg = seg.replace(Regex("[（(]单[)）]"), "")
            } else if (Regex("[（(]双[)）]").containsMatchIn(seg)) {
                parity = 2
                seg = seg.replace(Regex("[（(]双[)）]"), "")
            }
            seg = seg.replace("第", "").replace("周", "").trim()
            if (seg.isEmpty()) continue

            val range = seg.split('-', '—', '~', '至')
            if (range.size == 2) {
                val a = range[0].trim().toIntOrNull()
                val b = range[1].trim().toIntOrNull()
                if (a != null && b != null && a in 1..40 && b >= a) {
                    for (w in a..b.coerceAtMost(40)) {
                        when (parity) {
                            1 -> if (w % 2 == 1) weeks += w
                            2 -> if (w % 2 == 0) weeks += w
                            else -> weeks += w
                        }
                    }
                }
            } else {
                seg.toIntOrNull()?.let { if (it in 1..40) weeks += it }
            }
        }
        return weeks
    }
}
