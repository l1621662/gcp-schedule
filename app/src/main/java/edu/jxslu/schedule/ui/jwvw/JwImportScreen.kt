package edu.jxslu.schedule.ui.jwvw

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.http.SslError
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.ExamScheduleParser
import edu.jxslu.schedule.data.jw.ImportParseResult
import edu.jxslu.schedule.data.jw.JwImportDiagnosis
import edu.jxslu.schedule.data.jw.JwSchedulePage
import edu.jxslu.schedule.data.jw.JwUrls
import edu.jxslu.schedule.data.jw.JwVpnDetector
import edu.jxslu.schedule.data.jw.SyjxScheduleParser
import edu.jxslu.schedule.data.jw.ZhengfangScheduleParser
import edu.jxslu.schedule.data.jw.ZhengfangScoreParser
import edu.jxslu.schedule.data.jw.unwrapJsString
import edu.jxslu.schedule.domain.CourseKind
import edu.jxslu.schedule.domain.ExamMapper
import edu.jxslu.schedule.domain.ExamMapper.ExamEntry
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.ImportTargetDialogHost
import edu.jxslu.schedule.ui.common.resolveImportTarget
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 教务导入窗口的模式（DESIGN §4.14 / §4.15）。 */
enum class JwImportMode {
    /** 课表导入：理论 / 实验 / 考试安排，结果写进课表 Course。 */
    Schedule,
    /** 成绩导入：抓全部学期成绩，按学期替换入库（DESIGN §4.15）。 */
    Scores,
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun JwImportScreen(
    onBack: () -> Unit,
    mode: JwImportMode = JwImportMode.Schedule,
) {
    val context = LocalContext.current
    val repo = remember { Graph.repository(context) }
    val scoreRepo = remember { Graph.scoreRepository(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var webView by remember { mutableStateOf<WebView?>(null) }
    var progress by remember { mutableIntStateOf(0) }
    var pageTitle by remember { mutableStateOf("学校统一身份认证") }
    var currentUrl by remember { mutableStateOf(JwUrls.SSO_WARMUP) }
    var canGoBack by remember { mutableStateOf(false) }
    // 弹窗直接持有解析结果：courses 之外还要带上页面学期（term）供确认弹窗展示
    var showMergeDialog by remember { mutableStateOf<ImportParseResult.Success?>(null) }
    /** 考试导入的原始行 + 学期：确认弹窗选定目标课后按**目标课表**的开学日重映射（DESIGN §4.14）。 */
    var pendingExamImport by remember { mutableStateOf<Pair<List<ExamEntry>, String>?>(null) }
    /** 导入终态反馈（门数 to 是否合并）；非 null 时弹「导入完成」，确认后返回主界面。 */
    var importSuccess by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    /** 成绩模式：待确认的导入（学期 → 条数），确认后按学期替换入库。 */
    var pendingScores by remember { mutableStateOf<List<Pair<String, List<edu.jxslu.schedule.domain.ScoreRecord>>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var pageState by remember { mutableStateOf<PageState>(PageState.Loading) }
    var statusNote by remember { mutableStateOf("正在打开教务登录页…") }
    // 主 frame 最近一次失败的 URL，由失败回调写入、由 URL 相同的 onPageFinished 消费。
    //
    // 为什么必须按 URL 匹配 + 一次性消费（2026-09-18 手机日志实证）：500 响应体与
    // Chromium 失败页都算「加载完成」，失败后 onPageFinished 照常到达，会把错误态
    // 洗成 Ready；而回调顺序是 onReceivedHttpError → onPageStarted → onPageFinished
    // （**错误先于 onPageStarted**，与直觉相反）。因此：
    // - 不能改在 onPageStarted 里清标记（实测那样等于守卫被下一次回调拆掉，
    //   错误浮层被洗掉、状态条写成「已登录教务…」）；
    // - 也不能简单用「非空即拦截」，否则同名 URL 重试成功会被上一轮的失败拦住；
    // - 按 URL 相等消费，既能顶住重复失败轮次，也不误伤新导航/重试。
    var lastFailedUrl by remember { mutableStateOf<String?>(null) }
    // 自动重试只做一次：sso.jsp 的 500 是自愈型（重走预热入口即通过），
    // 但真故障时不能无限重试打服务端。
    var autoRetryUsed by remember { mutableStateOf(false) }

    // 与顶栏返回箭头同语义：WebView 能后退时先回退网页，否则才退出导入页。
    // 根因：系统返回手势默认直接 popBackStack，会把 WebView 的历史连同登录进度一起丢掉，
    // 和左上角箭头的行为不一致。
    BackHandler(enabled = canGoBack) { webView?.goBack() }

    /**
     * 统一失败出口：置错误浮层 + 状态条，并记下失败 URL。
     *
     * 记 URL 的用途见 [lastFailedUrl]：失败页的 `onPageFinished` 仍会到达，
     * 需要在那里认出「这次是失败」而不是把错误态洗成 Ready。
     * VPN 探测放在失败路径上做——正常路径不需要这个信息，也不该有额外开销。
     *
     * 认证链上的 500 会先**自动重试一次**：实测该 500 是自愈型（响应同时写下
     * `bzb_njw`，重新走一遍预热入口即可通过），用户此前要靠「过一会手动刷新」，
     * 现在由 App 完成。只自动重试一次，避免在真故障时反复打服务端。
     */
    fun reportFailure(failedUrl: String?, diag: JwImportDiagnosis.Diagnosis, statusCode: Int? = null) {
        lastFailedUrl = failedUrl
        if (statusCode != null && JwImportDiagnosis.shouldAutoRetry(statusCode, failedUrl) && !autoRetryUsed) {
            autoRetryUsed = true
            Log.d(TAG, "auto-retry after HTTP $statusCode at $failedUrl")
            statusNote = "教务返回 $statusCode，正在自动重试…"
            pageState = PageState.Loading
            webView?.loadUrl(JwUrls.SSO_WARMUP)
            return
        }
        pageState = PageState.Error(title = diag.title, body = diag.body, retryUrl = diag.retryUrl)
        statusNote = diag.title
        Log.d(TAG, "failure: ${diag.title} retry=${diag.retryUrl ?: "reload"} $failedUrl")
    }

    /**
     * 教务把「未登录」就地渲染成登录页（URL 不变），必须在任何后续自动导航之前拦下。
     *
     * 根因（2026-09-18 实测）：未登录访问 `xsMainV` / `xskb_list` 时教务不跳转，
     * 而是以 HTTP 200 返回登录页（含 `#loginDiv` 与密码框）。旧逻辑按 URL 把
     * `xsMainV` 当「已登录主页」并自动跳课表，于是主页登录页 → 课表登录页 →
     * 又认出 xsMainV… 互跳成环（日志里 18 次导航、约 1 秒一轮）。
     *
     * ⚠️ 只在教务域（[JwUrls.isJwHost]）判定。判据是**强智式就地登录页**的两个标志
     * （`loginDiv` + 密码框，见 [JwImportDiagnosis.looksLikeLoginPage]）；正方登录页是独立的
     * `login_slogin` 页、不含 `loginDiv`，未登录时用户看到的就是登录页本身（状态条提示登录，
     * 不盖错误浮层），因此不会被这条判据误报成「会话失效」。
     */
    fun checkSessionLost(view: WebView?, url: String?, onDone: () -> Unit = {}) {
        if (!JwUrls.isJwHost(url)) {
            Log.d(TAG, "session probe skipped (non-jw host) $url")
            onDone()
            return
        }
        view?.evaluateJavascript(SESSION_PROBE_JS) { raw ->
            val probe = unwrapJsString(raw)
            // 无条件记录探针原始值：上一轮排查时无法回答「探测到底跑没跑、看到了什么」，
            // 这个日志让下次复现能直接读出判定依据，而不是靠猜。
            Log.d(TAG, "session probe: '$probe' @ $url")
            if (JwImportDiagnosis.looksLikeLoginPage(probe)) {
                Log.d(TAG, "session lost detected at $url (probe=$probe)")
                reportFailure(url, JwImportDiagnosis.sessionLost(JwVpnDetector.isVpnActive(context)))
                return@evaluateJavascript
            }
            onDone()
        }
    }

    fun runImport(wv: WebView?) {
        val target = wv ?: return
        // 解析器由当前页面决定：两张课表结构完全不同，选错了只会得到空结果
        // （考试安排走同源 fetch JSON 接口，由 onClick 分派给 runExamImport，不进本函数）
        val pageKind = JwUrls.schedulePageKind(currentUrl)
        val extractJs = when (pageKind) {
            JwSchedulePage.Theory -> ZhengfangScheduleParser.EXTRACT_JS
            JwSchedulePage.Lab -> SyjxScheduleParser.EXTRACT_JS
            JwSchedulePage.None -> {
                val msg = "当前不是课表页。请先点上方「教务主页」，在教务里打开课表查询页后再导入。"
                statusNote = msg
                scope.launch { snackbar.showSnackbar(msg) }
                return
            }
            else -> return
        }
        busy = true
        statusNote = if (pageKind == JwSchedulePage.Lab) "正在解析实验课…" else "正在解析课表…"
        target.evaluateJavascript(extractJs) { raw ->
            busy = false
            val payload = unwrapJsString(raw)
            val result = when (pageKind) {
                JwSchedulePage.Lab -> SyjxScheduleParser.parseExtractJson(payload)
                else -> ZhengfangScheduleParser.parseExtractJson(payload)
            }
            when (result) {
                is ImportParseResult.Failure -> {
                    statusNote = result.message
                    scope.launch { snackbar.showSnackbar(result.message) }
                }
                is ImportParseResult.Success -> {
                    val labCount = result.courses.count { it.kind == CourseKind.Lab }
                    statusNote = if (labCount > 0) {
                        "解析到 ${result.courses.size} 条，其中实验课 $labCount 条"
                    } else {
                        "解析到 ${result.courses.size} 条课次，确认后写入"
                    }
                    showMergeDialog = result
                }
            }
        }
    }

    /**
     * 同源 fetch JSON 的通用执行：注入脚本（写 `window.__qzJson`），轮询回收。
     * evaluateJavascript 不会 await Promise，轮询是最可靠的回收方式；
     * 页面不跳转所以 window 变量不会丢。超时返回 null。
     */
    suspend fun fetchJsonInWebView(
        wv: WebView?,
        fetchJs: String,
        readJs: String,
        timeoutLoops: Int = 60,
    ): String? {
        if (wv == null) return null
        wv.evaluateJavascript(fetchJs, null)
        repeat(timeoutLoops) {
            delay(300)
            val raw = suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(readJs) { r -> if (cont.isActive) cont.resume(r ?: "") }
            }
            val value = unwrapJsString(raw)
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /**
     * 考试导入（DESIGN §4.14）：壳页读学期 → 分页 fetch `xsksap_list` → 转
     * `Course(kind = Exam)`。定位两级：先按学期配置，失败且学期号可识别时按估算开学日
     * 兜底（历史/未来学期）；仍无法定位的跳过并计数。
     */
    suspend fun runExamImport(wv: WebView?) {
        if (wv == null) return
        if (!JwUrls.isJwHost(currentUrl)) {
            val msg = "请先打开考试安排查询页（教务域）再导入。"
            snackbar.showSnackbar(msg); return
        }
        busy = true
        try {
            // 学期取壳页下拉的当前选中项：与用户在页面上看到的一致
            val termRaw = suspendCancellableCoroutine { cont ->
                wv.evaluateJavascript(ExamScheduleParser.READ_TERM_JS) { r ->
                    if (cont.isActive) cont.resume(r ?: "")
                }
            }
            val term = unwrapJsString(termRaw).orEmpty().trim()
            // 白名单校验：term 会被拼进注入 JS 的单引号字符串里，非学期格式一律拒绝，
            // 既防脏值落库，也从根上杜绝引号注入
            if (!Regex("""\d{4}-\d{4}-\d""").matches(term)) {
                statusNote = "取不到学期：请在考试安排查询页选择学期后重试"
                snackbar.showSnackbar(statusNote)
                return
            }
            statusNote = "正在请求 $term 考试安排…"
            val rows = mutableListOf<ExamEntry>()
            var count = 0
            var page = 1
            do {
                val body = fetchJsonInWebView(
                    wv,
                    ExamScheduleParser.fetchJs(term = term, page = page),
                    ExamScheduleParser.READ_RESULT_JS,
                ) ?: run {
                    statusNote = "请求超时：请检查网络后重试"
                    snackbar.showSnackbar(statusNote)
                    return
                }
                val parsed = try {
                    ExamScheduleParser.parseFetchJson(body)
                } catch (e: Exception) {
                    statusNote = e.message ?: "解析失败"
                    snackbar.showSnackbar(statusNote)
                    return
                }
                count = parsed.count
                rows += parsed.exams
                page++
            } while (rows.size < count && page <= 10)

            val slots = repo.timeSlots.first()
            val semester = repo.semester.first()
            val mapping = ExamMapper.toExamCourses(rows, slots, semester, term)
            if (rows.isEmpty()) {
                statusNote = "$term 暂无考试安排（教务通常考前数周才录入）"
                snackbar.showSnackbar(statusNote)
                return
            }
            if (mapping.courses.isEmpty()) {
                val msg = buildString {
                    append("$term 的 ${rows.size} 场考试都无法定位")
                    // 学期号无法识别时估算口径不可用，只剩「配置开学日」一条出路；
                    // 估算可用还定位失败，只能是日期本身异常
                    if (ExamMapper.estimatedTermStart(term) == null && semester == null) {
                        append("：请先在「我的 → 课表设置」配置开学日")
                    } else {
                        append("：日期无法映射到学期周次")
                    }
                }
                statusNote = msg
                snackbar.showSnackbar(msg)
                return
            }
            // 历史/未来学期靠估算开学日定位（DESIGN §4.14）：估算可能与实际相差一两周，
            // 必须让用户在确认弹窗里知情，而不是静默落表
            val estimateNote = if (mapping.estimated > 0) {
                val start = ExamMapper.estimatedTermStart(term)
                "${mapping.estimated} 场考试不属于当前学期配置，已按「$term」的估算开学日" +
                    "（$start）定位周次，可能与实际相差一两周。"
            } else {
                null
            }
            statusNote = buildString {
                append("解析到 ${mapping.courses.size} 场考试")
                if (mapping.skipped > 0) append("（跳过无法定位的 ${mapping.skipped} 场）")
                if (mapping.estimated > 0) append("，其中 ${mapping.estimated} 场按估算周次定位")
                append("，确认后写入")
            }
            pendingExamImport = rows to term
            showMergeDialog = ImportParseResult.Success(mapping.courses, term = term, note = estimateNote)
        } finally {
            busy = false
        }
    }

    /** 成绩导入（DESIGN §4.15）：全部学期分页 fetch → 按学期分组 → 确认后逐学期替换入库。 */
    suspend fun runScoreImport(wv: WebView?) {
        if (wv == null) return
        if (!JwUrls.isJwHost(currentUrl)) {
            val msg = "请先登录教务（点击上方入口打开成绩查询页）再导入。"
            snackbar.showSnackbar(msg); return
        }
        busy = true
        try {
            statusNote = "正在请求全部学期成绩…"
            val records = mutableListOf<ScoreRecord>()
            var count = 0
            var page = 1
            do {
                val body = fetchJsonInWebView(
                    wv,
                    ZhengfangScoreParser.fetchJs(page = page),
                    ZhengfangScoreParser.READ_RESULT_JS,
                ) ?: run {
                    statusNote = "请求超时：请检查网络后重试"
                    snackbar.showSnackbar(statusNote)
                    return
                }
                val parsed = try {
                    ZhengfangScoreParser.parseFetchJson(body)
                } catch (e: Exception) {
                    statusNote = e.message ?: "解析失败"
                    snackbar.showSnackbar(statusNote)
                    return
                }
                count = parsed.count
                records += parsed.records
                page++
            } while (records.size < count && page <= 20)

            if (records.isEmpty()) {
                statusNote = "教务没有返回任何成绩"
                snackbar.showSnackbar(statusNote)
                return
            }
            val grouped = records.groupBy { it.term }.toSortedMap(compareByDescending { it })
            statusNote = "解析到 ${grouped.size} 个学期共 ${records.size} 条成绩，确认后写入"
            pendingScores = grouped.entries.map { (term, list) -> term to list }
        } finally {
            busy = false
        }
    }

    // 独立 Activity 窗口：inset 全部走 M3 默认——TopAppBar 消费状态栏、
    // Scaffold contentWindowInsets 提供底部导航栏 inset（导入按钮区不压手势条）。
    // 此前为「嵌在外层 Scaffold 里」做的双 inset 规避已随窗口拆分一起移除。
    var desktopMode by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            if (mode == JwImportMode.Scores) "成绩导入 · 正方教务" else "课表导入 · 正方教务",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            pageTitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        val wv = webView
                        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // 电脑模式切换
                    TextButton(
                        onClick = {
                            desktopMode = !desktopMode
                            webView?.let { wv ->
                                // 切回手机版要恢复 WebView 默认 UA：赋 null 在部分 ROM 上会留下空/
                                // 字面量 UA，而正方按 UA 分设备分支，页面就废了。
                                wv.settings.userAgentString =
                                    if (desktopMode) DESKTOP_USER_AGENT
                                    else WebSettings.getDefaultUserAgent(context)
                                statusNote = if (desktopMode) "正在切换到电脑版…" else "正在切换到手机版…"
                                wv.reload()
                            }
                        }
                    ) {
                        Text(if (desktopMode) "手机版" else "电脑版")
                    }
                    // 刷新按钮
                    IconButton(
                        onClick = {
                            // 先取出错误态里的重试目标，再改状态（顺序反了就永远读到 Loading）
                            val retry = (pageState as? PageState.Error)?.retryUrl
                            pageState = PageState.Loading
                            // 用户显式重试 = 新的一轮，自动重试闸门重新打开
                            autoRetryUsed = false
                            // 错误态下刷新不是无脑 reload：retryUrl 非空说明这一页不能重放
                            // （认证页/空页），必须改走入口，否则只会再撞一次同样的失败
                            if (retry.isNullOrBlank()) {
                                statusNote = "重新加载中…"
                                webView?.reload()
                            } else {
                                statusNote = "重新打开登录页…"
                                webView?.loadUrl(retry)
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            StatusStrip(note = statusNote)

            Box(Modifier.weight(1f)) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        // 套一层 FrameLayout 作壳（StackOverflow 上「Compose + WebView 白屏/不渲染」
                        // 的验证修法）：Compose 重排时摘挂的是壳，WebView 本体不再被直接摘挂/重挂。
                        // 直接托管 WebView 在部分机型上会在 AndroidView 生命周期里丢渲染面。
                        FrameLayout(ctx).apply {
                            val wv = WebView(ctx).apply {
                                configureForJw(this)
                                if (BuildConfig.DEBUG) {
                                    // 远程调试只留给 debug 包（minSdk 26 > KITKAT，无需再判版本）：
                                    // release 放开等于把用户登录会话暴露给 chrome://inspect
                                    WebView.setWebContentsDebuggingEnabled(true)
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun shouldOverrideUrlLoading(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                    ): Boolean = false

                                    override fun onPageStarted(
                                        view: WebView?,
                                        url: String?,
                                        favicon: Bitmap?,
                                    ) {
                                        progress = 0
                                        canGoBack = view?.canGoBack() ?: false
                                        currentUrl = url ?: currentUrl
                                        statusNote = "加载中…"
                                        // 这里**不要**清 lastFailedUrl。实测回调顺序为
                                        // onReceivedHttpError → onPageStarted → onPageFinished：
                                        // 失败先到、start 后到，在此清除等于守卫被下一次回调拆掉
                                        // （曾因此让 500 错误浮层被洗成「已登录教务…」）。
                                        // 标记由 onPageFinished 在「URL 相同」时一次性消费。
                                        Log.d(TAG, "onPageStarted ${view?.width}x${view?.height} $url")
                                    }

                                    /** 首屏内容可见就先注入适配样式；等 onPageFinished 在图片多的页面上太晚。 */
                                    override fun onPageCommitVisible(view: WebView?, url: String?) {
                                        Log.d(TAG, "onPageCommitVisible ${view?.width}x${view?.height} $url")
                                        applyPageFit(view, url)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        canGoBack = view?.canGoBack() ?: false
                                        currentUrl = url ?: currentUrl
                                        pageTitle = view?.title?.takeIf { it.isNotBlank() } ?: pageTitle
                                        Log.d(TAG, "onPageFinished ${view?.width}x${view?.height} $url")

                                        // 本次导航已经失败过（onReceivedError/onReceivedHttpError 先到）：
                                        // 保持错误浮层，不能再往下走把它洗成 Ready —— 旧实现无条件
                                        // 置 Ready，实测会把 ERR_CONNECTION_TIMED_OUT 的浮层盖掉，
                                        // 只剩 Chromium 原生错误页 + 下面探针写的「内容为空」提示。
                                        //
                                        // 按 URL 相等消费（而非「非空即拦」）：回调顺序实测为
                                        // onReceivedHttpError → onPageStarted → onPageFinished，
                                        // 同一个失败 URL 会重复走这几步；用相等匹配既能顶住重复轮次，
                                        // 又不会把用户的成功重试拦成错误。
                                        if (lastFailedUrl != null && lastFailedUrl == url) {
                                            Log.d(TAG, "onPageFinished after failure, keep error state ($url)")
                                            lastFailedUrl = null
                                            return
                                        }

                                        val u = url.orEmpty()
                                        if (u.isBlank() || u == "about:blank") {
                                            reportFailure(u, JwImportDiagnosis.blankPage(JwVpnDetector.isVpnActive(context)))
                                            return
                                        }

                                        // 布局后 reflow 兜底（已知案例：Compose 首测 0×0 再拉满，
                                        // 页面在视口不稳定时完成布局就会按错误视口定格 → 白屏但 JS 正常）。
                                        // 等布局稳定后派发 resize 强制页面按真实视口重排，并回读页面
                                        // 自身的布局数据进日志，白屏复现时直接看 logcat 定位。
                                        view?.post {
                                            view.requestLayout()
                                            view.invalidate()
                                            view.evaluateJavascript(VIEWPORT_PROBE_JS) { raw ->
                                                Log.d(TAG, "viewport probe: $raw")
                                            }
                                        }
                                        // 电脑模式:新页面是全新 DOM,上次注入的 viewport 改动
                                        // 不会带过来,必须在每次加载完成后按当前模式重新打一次
                                        if (desktopMode) {
                                            view?.evaluateJavascript(
                                                """
                                                (function() {
                                                    var meta = document.querySelector('meta[name="viewport"]');
                                                    if (!meta) {
                                                        meta = document.createElement('meta');
                                                        meta.name = 'viewport';
                                                        document.head.appendChild(meta);
                                                    }
                                                    meta.setAttribute('content', 'width=1280, initial-scale=1');
                                                })();
                                                """.trimIndent(),
                                                null
                                            )
                                        }

                                        // 会话检查必须先于「就绪」判定：未登录时强智会把登录页就地渲染在
                                        // xsMainV / xskb_list 这些 URL 上（HTTP 200、URL 不变），只看 URL
                                        // 会把它当成已打开的页面。探针是异步的，判定放在它的回调里。
                                        checkSessionLost(view, u) {
                                            // 注意：两张课表的 URL 都含 "xskb"（理论 xskb_list、实验 toXskb），
                                            // 不能用子串判断，必须按页面类型区分，否则会互相误判
                                            val page = JwUrls.schedulePageKind(u)
                                            statusNote = when {
                                                isLoginLikeUrl(u) ->
                                                    "请在上方登录教务系统"
                                                isStudentHomeUrl(u) ->
                                                    if (mode == JwImportMode.Scores) {
                                                        "已登录教务主页，点上方「成绩查询页」后导入"
                                                    } else {
                                                        "已登录教务主页，从教务菜单进课表查询页后回来导入"
                                                    }
                                                page == JwSchedulePage.Exam ->
                                                    "考试安排查询已打开。点下方「导入考试安排」。"
                                                page == JwSchedulePage.Lab ->
                                                    "实验课表已打开。点下方「导入实验课表」。"
                                                page == JwSchedulePage.Theory ->
                                                    "理论课表已打开。点下方「导入理论课表」。"
                                                JwUrls.SCORE_FRM.substringBefore('?') in u ->
                                                    "成绩查询页已打开。点下方「导入成绩」。"
                                                else -> "已登录教务，可从下方入口打开课表页"
                                            }

                                            // 🚫 这里**不要**再自动跳课表/成绩页（2026-09-23 用户真机反馈）：
                                            // 正方登录后立刻被顶到课表查询页时页面打不开，必须停在教务首页，
                                            // 由用户自己在教务里点进课表查询页，再用底部按钮导入。
                                            if (page != JwSchedulePage.None) {
                                                pageState = PageState.Ready
                                                applyPageFit(view, u)
                                                return@checkSessionLost
                                            }
                                            applyPageFit(view, u)
                                            pageState = PageState.Ready
                                            // 加载完成 ≠ 渲染出东西：探测一下，白屏时给可操作提示，
                                            // 而不是让用户对着空页猜发生了什么
                                            view?.evaluateJavascript(PAGE_CONTENT_PROBE_JS) { scoreRaw ->
                                                val score = scoreRaw?.trim()?.removeSurrounding("\"")?.toFloatOrNull()
                                                if (score != null && score < 20f) {
                                                    statusNote = "页面已加载但内容为空，可点「重试」重新认证"
                                                }
                                            }
                                        }
                                    }

                                    override fun onReceivedError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        error: WebResourceError?,
                                    ) {
                                        if (request?.isForMainFrame != true) return
                                        val code = error?.errorCode ?: -1
                                        val desc = error?.description?.toString().orEmpty()
                                        val failedUrl = request?.url?.toString()
                                        Log.d(TAG, "onReceivedError $code $desc $failedUrl")
                                        reportFailure(
                                            failedUrl,
                                            JwImportDiagnosis.networkFailure(
                                                errorCode = code,
                                                description = desc,
                                                url = failedUrl,
                                                vpnActive = JwVpnDetector.isVpnActive(context),
                                            ),
                                        )
                                    }

                                    /**
                                     * HTTP 4xx/5xx（**只有它能看到服务端错误页**）：
                                     * Chromium 把 5xx 的响应体当普通页面渲染，`onPageFinished`
                                     * 照常回调，而 `onReceivedError` 只管传输层失败、永远不触发。
                                     * 旧实现缺这个回调，所以「500 error System Error」页会被当成
                                     * 正常页面，状态条还显示「已登录教务…」。
                                     */
                                    override fun onReceivedHttpError(
                                        view: WebView?,
                                        request: WebResourceRequest?,
                                        errorResponse: WebResourceResponse?,
                                    ) {
                                        if (request?.isForMainFrame != true) return
                                        val code = errorResponse?.statusCode ?: -1
                                        if (code < 400) return
                                        val failedUrl = request?.url?.toString()
                                        Log.d(TAG, "onReceivedHttpError $code $failedUrl")
                                        reportFailure(
                                            failedUrl,
                                            JwImportDiagnosis.httpFailure(
                                                statusCode = code,
                                                url = failedUrl,
                                                vpnActive = JwVpnDetector.isVpnActive(context),
                                            ),
                                            statusCode = code,
                                        )
                                    }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?,
                                ) {
                                    // 只放行学校域的证书错误（白名单见 JwUrls.TRUSTED_SSL_HOSTS），
                                    // 其余一律取消。无条件 proceed 会关掉整个 WebView 的传输层校验，
                                    // 而统一认证登录表单就在这个 WebView 里，等于把凭证暴露给中间人。
                                    val host = error?.url?.let { JwUrls.hostOf(it) }
                                    if (host != null && host in JwUrls.TRUSTED_SSL_HOSTS) {
                                        handler?.proceed()
                                        statusNote = "已放行学校 HTTPS 证书"
                                    } else {
                                        handler?.cancel()
                                        Log.w(TAG, "ssl error cancelled: host=$host url=${error?.url}")
                                    }
                                }
                                }
                                webChromeClient = object : WebChromeClient() {
                                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                        progress = newProgress
                                        if (newProgress in 1..99 && pageState !is PageState.Error) {
                                            pageState = PageState.Loading
                                        }
                                    }

                                    override fun onReceivedTitle(view: WebView?, title: String?) {
                                        if (!title.isNullOrBlank()) pageTitle = title
                                    }
                                }
                                // 从预热入口进（而不是 CAS 直链）：先落教务域写 `bzb_njw`，
                                // 再由页面自带 JS 跳统一认证——目标与 ENTRY 完全相同，
                                // 但消除了「第一次 sso.jsp?ticket 必 500」的问题。
                                // 根因见 JwUrls.SSO_WARMUP 注释与 DESIGN §4.4.1。
                                loadUrl(JwUrls.SSO_WARMUP)
                            }
                            // 必须在壳里把实例交回 Compose 状态：刷新/切换课表/导入/返回
                            // 全部经由 `webView` 引用调用，丢了就是「按钮全无反应」。
                            webView = wv
                            addView(
                                wv,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                ),
                            )
                        }
                    },
                    update = { /* state held in compose */ },
                    onRelease = { shell ->
                        (0 until shell.childCount).mapNotNull { shell.getChildAt(it) as? WebView }
                            .forEach { wv ->
                                wv.stopLoading()
                                wv.destroy()
                            }
                    },
                )

                // 进度条必须是 overlay：放进 Column 会把 WebView 挤上挤下——
                // 页面刚画完（progress 100）时指示条消失、容器高度突变，
                // 硬件加速 surface 重建在部分机型（MIUI 实测）上表现为
                // 「课表正常显示闪一下然后全白」。overlay 不占布局，WebView 尺寸恒定。
                if (progress in 1..99) {
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter),
                    )
                }

                (pageState as? PageState.Error)?.let { err ->
                    ErrorOverlay(
                        error = err,
                        onRetry = {
                            pageState = PageState.Loading
                            // 用户显式重试 = 新的一轮，自动重试闸门重新打开
                            autoRetryUsed = false
                            // err.retryUrl 为空 = 失败页可安全重放（如课表页自己 5xx）；
                            // 非空 = 必须回入口重新认证（CAS ticket 一次性）
                            val target = err.retryUrl
                            if (target.isNullOrBlank()) {
                                statusNote = "重试中…"
                                webView?.reload()
                            } else {
                                statusNote = "重新打开统一认证…"
                                webView?.loadUrl(target)
                            }
                        },
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }

            val pageKind = JwUrls.schedulePageKind(currentUrl)
            Surface(tonalElevation = 3.dp, shadowElevation = 4.dp) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // 两个入口分开：两张课表结构不同，走哪个入口就用哪个解析器，
                    // 不让用户在「导入」时再猜自己开的是哪一页
                    if (mode == JwImportMode.Schedule) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // 1. 改为“教务主页”按钮
                            ScheduleEntryButton(
                                label = "教务主页",
                                active = false, // 首页不需要高亮
                                enabled = !busy,
                                onClick = {
                                    pageState = PageState.Loading
                                    statusNote = "正在打开教务主页..."
                                    autoRetryUsed = false
                                    // 直接加载你的学校教务主页
                                    webView?.loadUrl("https://jwcjw.gcp.edu.cn/")
                                },
                                modifier = Modifier.weight(1f),
                            )

                            // 2. 保留“考试安排”按钮
                            ScheduleEntryButton(
                                label = "考试安排",
                                active = pageKind == JwSchedulePage.Exam,
                                enabled = !busy,
                                onClick = {
                                    pageState = PageState.Loading
                                    statusNote = "打开考试安排..."
                                    autoRetryUsed = false
                                    webView?.loadUrl(JwUrls.EXAM_QUERY)
                                },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    } else {
                        // 成绩模式不能靠「登录后自动跳」了（见上），必须有显式入口
                        ScheduleEntryButton(
                            label = "成绩查询页",
                            active = JwUrls.SCORE_FRM.substringBefore('?') in currentUrl,
                            enabled = !busy,
                            onClick = {
                                pageState = PageState.Loading
                                statusNote = "正在打开成绩查询页…"
                                autoRetryUsed = false
                                webView?.loadUrl(JwUrls.SCORE_FRM)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Button(
                        onClick = {
                            when {
                                mode == JwImportMode.Scores -> scope.launch { runScoreImport(webView) }
                                JwUrls.schedulePageKind(currentUrl) == JwSchedulePage.Exam ->
                                    scope.launch { runExamImport(webView) }
                                else -> runImport(webView)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        // 防误触：currentUrl 在 onPageFinished 才更新，页面没就绪就点导入
                        // 会拿旧 URL 判型、误报「当前不是课表页」。只在课表页且加载就绪时可用。
                        enabled = !busy &&
                            pageState == PageState.Ready &&
                            (mode == JwImportMode.Scores || pageKind != JwSchedulePage.None),
                    ) {
                        Text(
                            when {
                                busy -> "解析中…"
                                mode == JwImportMode.Scores -> "导入成绩"
                                pageKind == JwSchedulePage.Exam -> "导入考试安排"
                                pageKind == JwSchedulePage.Lab -> "导入实验课表"
                                pageKind == JwSchedulePage.Theory -> "导入理论课表"
                                else -> "打开课表页后可导入"
                            },
                        )
                    }
                }
            }
        }
    }

    showMergeDialog?.let { draft ->
        val courses = draft.courses
        // 目标课表强制选择（DESIGN §4.9）：多课表之后「导到当前课表」不再是唯一合理解释，
        // 每次都让用户明确选目标（可新建）与覆盖/合并方式，避免静默覆盖正在用的数据
        ImportTargetDialogHost(
            courses = courses,
            term = draft.term,
            note = draft.note,
            title = when {
                courses.all { it.kind == CourseKind.Exam } ->
                    "解析到 ${courses.size} 场考试"
                courses.all { it.kind == CourseKind.Lab } ->
                    "解析到 ${courses.size} 条实验课"
                courses.any { it.kind == CourseKind.Lab } ->
                    "解析到 ${courses.size} 条（实验课 ${courses.count { it.kind == CourseKind.Lab }} 条）"
                else -> "解析到 ${courses.size} 条课次"
            },
            // 实验/考试与理论课表是两批数据，合并是更常见的意图；覆盖会连普通课程一起清空
            defaultMerge = courses.any { it.kind != CourseKind.Theory },
            repo = repo,
            onConfirm = { target, merge ->
                showMergeDialog = null
                val examDraft = pendingExamImport
                pendingExamImport = null
                scope.launch {
                    val targetId = resolveImportTarget(repo, target)
                    // 考试导入按目标课表的开学日重算周次（DESIGN §4.14）：
                    // 解析时用当前课表配置出的映射只作预览，落表前必须换成目标课表的口径
                    val toImport = if (examDraft != null) {
                        val (rows, examTerm) = examDraft
                        ExamMapper.toExamCourses(
                            rows,
                            repo.timeSlots.first(),
                            repo.semesterFor(targetId),
                            examTerm,
                        ).courses
                    } else {
                        courses
                    }
                    val imported = repo.importParsedCourses(toImport, merge, targetId)
                    // 教务数据成为本地数据 → 检测基线随之推进（DESIGN §4.17）；
                    // 考试条目不参与检测，仓库侧过滤，只有理论/实验导入才动基线
                    if (examDraft == null) {
                        repo.refreshBaselineFromJwImport(targetId, toImport, draft.term)
                    }
                    // 导入到非当前课表后切过去，返回主界面直接看到结果
                    repo.setCurrentTimetable(targetId)
                    // 终态反馈后再返回（DESIGN §3.3）：此前导入成功直接 onBack，
                    // 「到底导没导成、导了几门」全靠回主界面猜
                    importSuccess = imported to merge
                }
            },
            onDismiss = {
                showMergeDialog = null
                pendingExamImport = null
            },
        )
    }

    pendingScores?.let { grouped ->
        val total = grouped.sumOf { it.second.size }
        AlertDialog(
            onDismissRequest = { pendingScores = null },
            title = { Text("确认导入成绩") },
            text = {
                Column {
                    Text("将导入 ${grouped.size} 个学期共 $total 条成绩，相同学期的旧数据会被替换：")
                    Spacer(Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        grouped.forEach { (term, list) ->
                            Text(
                                "$term · ${list.size} 条",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pending = grouped
                        pendingScores = null
                        scope.launch {
                            pending.forEach { (term, list) -> scoreRepo.replaceTerm(term, list) }
                            // 终态反馈后再返回（DESIGN §3.3），与课表导入一致
                            importSuccess = total to true
                        }
                    },
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { pendingScores = null }) { Text("取消") }
            },
        )
    }

    importSuccess?.let { (count, _) ->
        AlertDialog(
            onDismissRequest = onBack,
            title = { Text(if (mode == JwImportMode.Scores) "成绩导入完成" else "导入完成") },
            text = {
                Text(
                    if (mode == JwImportMode.Scores) "已导入 $count 条成绩，按学期替换存储。"
                    else "已合并导入 $count 门新课到目标课表（重复课程已跳过）。",
                )
            },
            confirmButton = {
                TextButton(onClick = onBack) { Text("完成") }
            },
        )
    }
}

/** 课表入口按钮：当前所在那张课表用实心，另一张描边，一眼看出「导入」会导哪一张。 */
@Composable
private fun ScheduleEntryButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val buttonModifier = modifier.height(40.dp)
    if (active) {
        Button(onClick = onClick, modifier = buttonModifier, enabled = enabled) { Text(label) }
    } else {
        OutlinedButton(onClick = onClick, modifier = buttonModifier, enabled = enabled) { Text(label) }
    }
}

private sealed interface PageState {
    data object Loading : PageState
    data object Ready : PageState

    /**
     * 失败态。`retryUrl` 非空时必须改走该 URL——CAS ticket 是一次性票，
     * 对失败页 reload 只会重放已消费的票据（见 [JwImportDiagnosis.retryUrl]）。
     */
    data class Error(val title: String, val body: String, val retryUrl: String? = null) : PageState
}

/**
 * 顶部提示条：只留一行状态文字。
 *
 * 根因（也是白屏修复的一半）：旧版还有 URL 行 + 「统一认证/强智直登」两个入口按钮，
 * 文字换行数变化会让这一条的高度跟着变，挤压下方 WebView——容器被 resize 会触发
 * 硬件 surface 重建，表现就是页面闪一下变白。现在固定单行 + 省略，高度恒定；
 * 强智直登入口已移除（默认走学校统一认证，备用入口没有存在场景）。
 */
@Composable
private fun StatusStrip(note: String) {
    Text(
        note,
        style = MaterialTheme.typography.bodyMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

@Composable
private fun ErrorOverlay(
    error: PageState.Error,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(error.title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            error.body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onRetry) { Text("重试") }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun configureForJw(webView: WebView) {
    // 根因（Redmi K70 / MIUI 实测）：硬件加速下课表页渲染完成后整面变白——
    // 但 DOM 正常、evaluateJavascript 能跑、导入功能完好，说明死的是 GPU 合成/显示层，
    // 不是加载流程。这是 MIUI WebView 合成层丢失的已知顽疾（上轮去掉容器 resize 只除掉了
    // 一个触发面，合成层照丢）。软件渲染绕开 GPU 合成，是这类「功能正常但显示全白」的
    // 确定性兜底；教务页是简单表格，CPU 绘制的性能足够。
    webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
    webView.setBackgroundColor(android.graphics.Color.WHITE)

    webView.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        loadsImagesAutomatically = true

        // 根因：教务登录页（/jsxsd/、Logon.do）**没有 <meta viewport>**，是 400–480px 固定宽的
        // 桌面布局；而原先 useWideViewPort = false 会忽略页面声明的 viewport，把它硬塞进
        // 屏幕宽度的视口里，桌面布局于是错位、被裁，看着就是「界面渲染不出来」。
        //
        // 改成允许页面用自身 viewport（无 meta 时退化为 980px），再由 WebView 整体缩放到屏幕：
        // 先保证「看得全」，细节交给双指放大。课表页/主页自带 width=device-width，同样受益。
        useWideViewPort = true
        loadWithOverviewMode = true

        setSupportZoom(true)
        builtInZoomControls = true
        displayZoomControls = false
        textZoom = 100
        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        cacheMode = WebSettings.LOAD_DEFAULT
        mediaPlaybackRequiresUserGesture = false
    }
    webView.isHorizontalScrollBarEnabled = true
    webView.isVerticalScrollBarEnabled = true
    android.webkit.CookieManager.getInstance().setAcceptCookie(true)
    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
}

/**
 * 课表页适配：让宽表格能横向滚动，并按屏幕调紧凑度与可读性。
 *
 * **不再改写 viewport meta**。课表页自带 `width=device-width`，改写它没有收益，
 * 反而会和 WebView 的 useWideViewPort / loadWithOverviewMode 两套缩放机制互相打架
 * （旧实现既改 meta 又关掉 useWideViewPort，方向是矛盾的）。
 *
 * 尺寸基准（用户拍板）：格子 min-width 110→96px、表格总宽 900→730px 同步下调——
 * 表格越窄，overview 缩放越接近 1:1，字在屏上就越大；同时教室/时间摘要文字
 * （`.qz-hasCourse-abbrinfo`，理论页为「老师/时间/地点」整段、实验页为地点）
 * 显式放大到 12.5px 并放宽行距，两页共用同一组 class，一并受益。
 */
private val SCHEDULE_FIT_JS: String = """
(function(){
  try{
    if(document.getElementById('juw-schedule-fit')) return;
    var css=document.createElement('style'); css.id='juw-schedule-fit';
    css.textContent=
      'html,body{overflow-x:auto !important;max-width:none !important;}'+
      'table.qz-weeklyTable{min-width:730px !important;}'+
      '#tableDiv,.qz-weeklyTableWrap,.qz-table-wrap{overflow-x:auto !important;}'+
      'td[name=kbDataTd]{min-width:96px !important;}'+
      '.qz-hasCourse-detailitem,.qz-hasCourse-abbrinfo{'+
        'font-size:12.5px !important;line-height:1.5 !important;}';
    (document.head||document.documentElement).appendChild(css);
  }catch(e){}
})()
""".trimIndent()

/**
 * 登录 / SSO 页适配：把固定宽表单收成页面宽度的百分比并居中。
 *
 * 同样不改写 viewport meta——这些页面没有 meta，正好交给 WebView 的
 * 「按页面宽度渲染 → 缩放到屏幕」来处理整页可见性；这里只补两件事：
 * 固定宽容器给百分比上限、表单控件不撑出屏幕。
 *
 * 注意：只对真正的登录页注入。学生端主页等桌面页不要碰，越改越乱。
 */
private val LOGIN_FIT_JS: String = """
(function(){
  try{
    if(document.getElementById('juw-login-fit')) return;
    var css=document.createElement('style'); css.id='juw-login-fit';
    css.textContent=
      '.login{height:auto !important;min-height:0 !important;overflow:visible !important;}'+
      '.form-container,.login-box,.login-container,.layui-form{'+
        'position:static !important;right:auto !important;top:auto !important;'+
        'width:92% !important;max-width:560px !important;height:auto !important;'+
        'overflow:visible !important;margin:28px auto !important;}'+
      'input,select,textarea{box-sizing:border-box !important;max-width:100% !important;}'+
      '.layui-input-wrap>input{width:100% !important;}'+
      'img{max-width:100% !important;height:auto !important;}';
    (document.head||document.documentElement).appendChild(css);
  }catch(e){}
})()
""".trimIndent()

/**
 * 只有真正的登录页才需要表单兜底与「请登录」提示。
 *
 * 强智（旧链路）：`/cas/login`、`Logon.do`、`/sso.jsp`；
 * 正方：`/jwglxt/xtgl/login_slogin.html`——登录失效与登录失败都回落到它，按 URL 判定即可，
 * 不用赌登录页的 DOM 结构。
 */
private fun isLoginLikeUrl(url: String): Boolean =
    "/cas/login" in url || "Logon.do" in url || "/sso.jsp" in url ||
        "login_slogin" in url || "/xtgl/login" in url

/**
 * 学生端主页：正方登录成功后落在 `/jwglxt/xtgl/index_init.html`（旧强智是 `xsMainV`）。
 * 两套都认，自动跳转目标才不会因换学校而失效。
 */
private fun isStudentHomeUrl(url: String): Boolean =
    "index_init" in url || "xsMainV" in url

/** 按页面类型注入适配样式；幂等，重复调用无副作用。 */
private fun applyPageFit(view: WebView?, url: String?) {
    val u = url.orEmpty()
    if (u.isBlank() || u == "about:blank") return
    val js = when {
        JwUrls.schedulePageKind(u) != JwSchedulePage.None -> SCHEDULE_FIT_JS
        isLoginLikeUrl(u) -> LOGIN_FIT_JS
        else -> return
    }
    view?.evaluateJavascript(js, null)
}

private const val TAG = "JwWebView"

/** 电脑版 UA：切到电脑版时用；切回手机版恢复 WebView 默认 UA（见顶栏切换按钮）。 */
private const val DESKTOP_USER_AGENT: String =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

/**
 * 布局后 reflow + 视口探针：给页面补派一次 resize（Compose 0×0 → 全屏的测量时序
 * 可能把页面按错误视口定格成白屏），并回读页面自身的布局数据用于 logcat 定位。
 */
private val VIEWPORT_PROBE_JS: String = """
(function(){
  try{
    window.dispatchEvent(new Event('resize'));
    return JSON.stringify({
      iw:window.innerWidth, ih:window.innerHeight,
      dw:document.documentElement.scrollWidth, dh:document.documentElement.scrollHeight,
      sx:window.scrollX, sy:window.scrollY,
      dpr:window.devicePixelRatio
    });
  }catch(e){ return 'err'; }
})()
""".trimIndent()

/**
 * 判断页面是不是「加载完了但什么都没渲染」。
 *
 * 返回文本长度 + 5 × 关键元素数，正常页面是三位数以上；
 * 小于 20 基本可以断定是空白页（而不是"只是排版乱"），这样可以给出准确的提示，
 * 而不是让用户面对白屏猜发生了什么。
 */
private val PAGE_CONTENT_PROBE_JS: String = """
(function(){
  try{
    var b=document.body;
    if(!b) return 0;
    var t=(b.innerText||'').replace(/\s+/g,'').length;
    var n=b.querySelectorAll('table,img,input,button,a').length;
    return t + n*5;
  }catch(e){ return 0; }
})()
""".trimIndent()

/**
 * 判定当前页是不是教务登录页（= 未登录）。
 *
 * 特征取自实测（2026-09-18，手机 5G 无代理）：
 * - 未登录的 `xsMainV` / `xskb_list` / `Logon.do` 都是 HTTP 200 就地渲染登录页，
 *   含 `id="loginDiv"` 与 `type="password"` 输入框，`<title>登录</title>`；
 * - 已登录的课表/主页快照里这两者均为 0 处（`scripts/out/xskb_vt0.html`、`syxkb.html`）。
 *
 * ⚠️ 不要改回按文案判定。曾用过「用户没有登录」，而实测该页面上此文案出现 **0 次**，
 * 那种判据永远不会命中（旧实现的「会话失效」提示因此从未生效过）。
 */
private val SESSION_PROBE_JS: String = """
(function(){
  try{
    var hasLoginDiv = !!document.getElementById('loginDiv');
    var hasPwd = !!document.querySelector('input[type=password]');
    var title = (document.title||'').trim();
    return (hasLoginDiv?'loginDiv ':'') + (hasPwd?'password ':'') + 'title=' + title;
  }catch(e){ return ''; }
})()
""".trimIndent()
