package edu.gcp.schedule.data.jw

import edu.gcp.schedule.domain.ScoreRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 正方教务成绩抓取。链路与强智不同——正方是 POST 表单请求，不是 GET 查询串：
 * WebView 在任意教务页面注入 [FETCH_JS]，同源 POST
 * `/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005`，
 * 结果写 `window.__zfScoreJson`，成功即整份 JSON（无强智那种 code=0 包装层）。
 *
 * xnm/xqm 留空按抓包尝试查「全部学期」；如果学校那边不认，会退化成只返回当前
 * 默认学期（通常是最新一学期）——这种情况需要按学年循环发请求，届时再加。
 */
object ZhengfangScoreParser {

    private val json = Json { ignoreUnknownKeys = true }

    const val PAGE_SIZE = 200

    /** 注入脚本：[__PAGE__] 为页码（从 1 开始）。 */
    val FETCH_JS: String = """
(function(){
  try {
    window.__zfScoreJson = null;
    var body = 'xnm=&xqm=&sfzgcj=&kcbj=&pkey=&_search=false&nd=' + Date.now()
             + '&queryModel.showCount=$PAGE_SIZE&queryModel.currentPage=__PAGE__'
             + '&queryModel.sortName=&queryModel.sortOrder=asc&time=1';
    fetch('/jwglxt/cjcx/cjcx_cxXsgrcj.html?doType=query&gnmkdm=N305005', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8' },
      body: body
    })
      .then(function(r){ return r.text(); })
      .then(function(t){ window.__zfScoreJson = t; })
      .catch(function(e){ window.__zfScoreJson = 'ERR:' + String(e); });
  } catch (e) {
    window.__zfScoreJson = 'ERR:' + String(e);
  }
})()
""".trim()

    val READ_RESULT_JS: String = "window.__zfScoreJson === null ? '' : String(window.__zfScoreJson)"

    fun fetchJs(page: Int): String = FETCH_JS.replace("__PAGE__", page.toString())

    data class ScorePage(
        val count: Int,
        val records: List<ScoreRecord>,
    )

    fun parseFetchJson(jsonText: String): ScorePage {
        if (jsonText.startsWith("ERR:")) {
            throw IllegalStateException("请求失败：${jsonText.removePrefix("ERR:")}")
        }
        val root = try {
            json.parseToJsonElement(jsonText).jsonObject
        } catch (e: Exception) {
            throw IllegalStateException("返回的不是有效 JSON（可能未登录教务）：${e.message}")
        }
        val count = root["totalResult"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: root["totalCount"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val rows = root["items"]?.jsonArray?.mapNotNull { it as? JsonObject } ?: emptyList()
        return ScorePage(count = count, records = rows.mapNotNull { rowToRecord(it) })
    }

    private fun rowToRecord(row: JsonObject): ScoreRecord? {
        val name = row.str("kcmc")
        if (name.isNullOrBlank()) return null
        val xnmmc = row.str("xnmmc").orEmpty()
        val xqmmc = row.str("xqmmc").orEmpty()
        val term = if (xnmmc.isNotBlank() && xqmmc.isNotBlank()) "$xnmmc-$xqmmc" else xnmmc
        val cjRaw = row.str("cj")
        return ScoreRecord(
            term = term,
            courseNo = row.str("kch").orEmpty(),
            name = name,
            unit = row.str("kkbmmc").orEmpty(),
            credit = row.dbl("xf") ?: 0.0,
            hours = row.dbl("zxs") ?: 0.0,
            examForm = row.str("khfsmc").orEmpty(),
            // 假设：kcxzmc（必修课/限选课/任选课）对应「必修/选修」口径，
            // 任选课的「任选」标记很可能出现在这个字段，需要用真实任选课数据核实。
            courseAttr = row.str("kcxzmc").orEmpty(),
            category = row.str("kclbmc").orEmpty(),
            score = cjRaw?.toDoubleOrNull(),
            scoreStr = cjRaw.orEmpty(),
            gradePoint = row.dbl("jd"),
            status = row.str("ksxz").orEmpty(),
            // 正方样例数据里没见到「评教未完成锁定」的字段，暂定一律不锁定；
            // 真遇到锁定成绩（分数空白、有提示文案的情况）时需要抓一份真实数据回来定字段。
            pendingReview = false,
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.content?.trim()?.takeIf { it.isNotEmpty() }

    private fun JsonObject.dbl(key: String): Double? = try {
        this[key]?.jsonPrimitive?.content?.toDoubleOrNull()
    } catch (_: Exception) {
        null
    }
}
