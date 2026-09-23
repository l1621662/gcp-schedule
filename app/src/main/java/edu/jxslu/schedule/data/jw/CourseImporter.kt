package edu.jxslu.schedule.data.jw

import edu.jxslu.schedule.domain.Course

/**
 * 教务导入抽象。当前实现：ZhengfangImporter（广州城市职业学院 · 正方教务，见 DESIGN §4.4）。
 */
interface CourseImporter {
    val id: String
    val displayName: String
}

sealed interface ImportParseResult {
    /**
     * [term] 是课表页学期下拉的当前选中项（如 2026-2027-1），即本次解析数据的实际学期；
     * 用于导入确认弹窗展示。页面异常没有下拉时为 null，不阻塞导入。
     * [note] 是需要用户在确认前知情的补充说明（如历史学期考试的估算周次口径），null = 无。
     */
    data class Success(
        val courses: List<Course>,
        val term: String? = null,
        val note: String? = null,
    ) : ImportParseResult
    data class Failure(val message: String) : ImportParseResult
}

/**
 * 教务里可导入的课表页面。
 *
 * 正方把理论课与实验课排在同一张课表里，因此只剩 [Theory]（课表查询页）与 [Exam]
 * （考试安排页，数据走同源 fetch JSON，DESIGN §4.14）两种；强智的「实验课表」页
 * 与解析器已随 2026-09-23 的迁移删除。
 */
enum class JwSchedulePage { Theory, Exam, None }

object JwUrls {
    const val BASE_URL = "https://jwcjw.gcp.edu.cn"

    const val ENTRY = "$BASE_URL/jwglxt/xtgl/login_slogin.html"
    const val SSO_WARMUP = "$BASE_URL/jwglxt/xtgl/login_slogin.html"
    const val XSD_BASE = BASE_URL

    // 正方课表页面
    const val SCHEDULE_LIST = "$XSD_BASE/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html"
    const val SCHEDULE_DATA_API = "$XSD_BASE/jwglxt/kbcx/xskbcx_cxKbxx.html"

    // 修改为正方教务的考试安排查询页面
    const val EXAM_QUERY = "$XSD_BASE/jwglxt/kwgl/kscx_cxXsksxxIndex.html?gnmkdm=N358105&layout=default"
    const val SCORE_FRM = "$XSD_BASE/jwglxt/cjcx/cjcx_cxDgXscj.html?gnmkdm=N305005&layout=default"

    const val STUDENT_HOME = "$XSD_BASE/jwglxt/xtgl/index_init.html"

    fun isScheduleUrl(url: String?): Boolean =
        url != null && "xskbcx_cxXskbcxIndex.html" in url

    fun isExamQueryUrl(url: String?): Boolean =
        url != null && "kscx_cxXsksxxIndex.html" in url

    fun schedulePageKind(url: String?): JwSchedulePage = when {
        isScheduleUrl(url) -> JwSchedulePage.Theory
        isExamQueryUrl(url) -> JwSchedulePage.Exam
        else -> JwSchedulePage.None
    }
    val TRUSTED_SSL_HOSTS = setOf(
        "jwcjw.gcp.edu.cn"
    )

    fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val afterScheme = url.substringAfter(delimiter = "://", missingDelimiterValue = "")
        if (afterScheme.isEmpty()) return null
        return afterScheme
            .substringBefore(delimiter = '/')
            .substringBefore(delimiter = '?')
            .substringBefore(delimiter = '#')
            .substringBefore(delimiter = ':')
            .lowercase()
            .ifBlank { null }
    }

    fun isJwHost(url: String?): Boolean = hostOf(url) == "jwcjw.gcp.edu.cn"
    fun isCasHost(url: String?): Boolean = hostOf(url) == "jwcjw.gcp.edu.cn"
    fun isPortalHost(url: String?): Boolean = hostOf(url) == "jwcjw.gcp.edu.cn"
}

class ZhengfangImporter : CourseImporter {
    override val id: String = "gcp_zhengfang"
    override val displayName: String = "广州城市职业学院 (正方系统)"
}
