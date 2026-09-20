package edu.jxslu.schedule.ui.week

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.JwDetectRunner
import edu.jxslu.schedule.data.repo.ImportPreview
import edu.jxslu.schedule.data.repo.ImportResult
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.CourseFilter
import edu.jxslu.schedule.domain.GridFont
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.SemesterConfig
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.TimetablePrefs
import edu.jxslu.schedule.ui.common.CourseDetailSheet
import edu.jxslu.schedule.ui.common.CourseEditSheet
import edu.jxslu.schedule.ui.common.DeleteConfirmDialog
import edu.jxslu.schedule.ui.common.GhostCourseCard
import edu.jxslu.schedule.ui.common.GridCellStyle
import edu.jxslu.schedule.ui.common.GridCourseCard
import edu.jxslu.schedule.ui.common.ImportTargetDialogHost
import edu.jxslu.schedule.ui.common.SingleSectionCard
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.readTextFromUri
import edu.jxslu.schedule.ui.common.resolveImportTarget
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.detect.DetectNotice
import edu.jxslu.schedule.ui.detect.detectOutcomeNotice
import edu.jxslu.schedule.ui.me.DisplaySettingsContent
import edu.jxslu.schedule.ui.me.MeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.Eye
import me.rerere.hugeicons.stroke.Import
import me.rerere.hugeicons.stroke.Share08
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// dayLabels / monthDayFmt 在 WeekGrid.kt（表头与顶栏共用）；fullDateFmt 仅顶栏用
private val fullDateFmt = DateTimeFormatter.ofPattern("yyyy/M/d")

// 顶栏高度保留本文件定义；时间轴栏宽 / 表头高度已用户可调（显示设置滑块），
// 默认值与滑块范围统一收敛到 TimetablePrefs companion（单一来源），几何唯一来源是
// buildGridLayout（WeekGridLayout.kt）产出的 GridLayout（railWidth / dayHeaderHeight 字段）。
private val TopBarHeight = 56.dp

/**
 * 周课表：小节 × 星期网格。
 * 默认周一至周日，可在「显示设置」里关掉周末；整页 HorizontalPager 横滑切周。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WeekScreen(
    onOpenJwImport: () -> Unit = {},
    onOpenTimetableManage: () -> Unit = {},
    /** 有未处理调课提醒时点导入图标直达「更新课表」（DESIGN §4.17） */
    onOpenScheduleUpdate: () -> Unit = {},
    /** 外部请求打开显示设置（「我的 → 显示设置」跨 Tab 触发），与眼睛图标同一弹层 */
    openDisplayRequests: Flow<Unit> = emptyFlow(),
    viewModel: WeekViewModel = viewModel(
        factory = WeekViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Course?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    // 入口与弹层：周次区（顶栏日期块）→ 选周；课表名 → 切换课表；
    // 眼睛 → 页内**覆盖弹层**（DisplaySettingsContent）。
    // 根因：显示设置先前跳独立子页，课表显示被整页替换/挤压；现在面板只盖住下半屏，
    // 上方课表保持原样，样式改动在真实网格上即时生效（拾光的交互形态）。
    var pickerOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var switchOpen by remember { mutableStateOf(false) }
    var displaySheetOpen by remember { mutableStateOf(false) }
    var shareOpen by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<Course?>(null) }

    // JSON 文件导入（弹层入口）：读文件 → preview → 目标课表选择弹窗 → importJson，
    // 结果走 Snackbar。目标选择与教务导入共用 ImportTargetDialogHost，语义两处一致。
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val repo = remember { Graph.repository(context) }
    // 调课检测（DESIGN §4.17）：未处理的差异报告驱动导入图标的气泡（null = 无提醒）
    val pendingDetect by remember { repo.pendingDetectReport }
        .collectAsStateWithLifecycle(initialValue = null)
    // 手动检测进行中态：导入弹层里「检测课表更新」一行的文案与防重复点击
    var detectChecking by remember { mutableStateOf(false) }
    // 上一次手动检测的结果，内联显示在导入弹层里（弹层窗口盖住 Snackbar，见该处注释）
    var detectNotice by remember { mutableStateOf<DetectNotice?>(null) }
    var pendingJsonText by remember { mutableStateOf<String?>(null) }
    var jsonPreview by remember { mutableStateOf<ImportPreview.Ok?>(null) }

    val jsonLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val text = readTextFromUri(context, uri)
            if (text.isNullOrBlank()) {
                snackbar.showSnackbar(
                    AppNoticeVisuals("读取文件失败", tone = NoticeTone.Error),
                )
                return@launch
            }
            when (val p = repo.previewImport(text)) {
                is ImportPreview.Error -> snackbar.showSnackbar(
                    AppNoticeVisuals(p.message, tone = NoticeTone.Error),
                )
                is ImportPreview.Ok -> {
                    pendingJsonText = text
                    jsonPreview = p
                }
            }
        }
    }

    suspend fun runJsonImport(text: String, merge: Boolean, targetId: Long) {
        when (val r = repo.importJson(text, merge, targetId)) {
            is ImportResult.Success -> snackbar.showSnackbar(
                AppNoticeVisuals(
                    if (merge) "合并完成：新增 ${r.added} / 文件共 ${r.total}" else "已覆盖导入 ${r.total} 门课",
                    tone = NoticeTone.Success,
                ),
            )
            is ImportResult.Failure -> snackbar.showSnackbar(
                AppNoticeVisuals(r.message, tone = NoticeTone.Error),
            )
        }
    }

    // ---- 分享（DESIGN §4.12）----
    // CSV/JSON 走 SAF 另存为（与「我的 → 导出 JSON」同形态）；日历同步先运行时申请
    // READ/WRITE_CALENDAR，授予后续跑（授予回调里执行同步），拒绝则 Snackbar 提示。
    val fileDateTag = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")) }
    val csvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri -> if (uri != null) viewModel.exportCsv(context, uri) }
    val jsonExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportJson(context, uri) }
    var pendingCalendarSync by remember { mutableStateOf(false) }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        if (grants.values.all { it }) {
            pendingCalendarSync = true
        } else {
            // 拒绝也要有反馈：弹层已在发起申请时关闭（见 startCalendarSync），
            // 这里的提示才看得见——此前拒绝分支不关弹层，Snackbar 被分享弹层整个盖住
            scope.launch {
                snackbar.showSnackbar(
                    AppNoticeVisuals("未授予日历权限，无法同步", tone = NoticeTone.Warning),
                )
            }
            pendingCalendarSync = false
        }
    }

    // 权限授予后的续跑放 LaunchedEffect，避免在 launcher 回调里直接调 VM 引发重组竞态
    LaunchedEffect(pendingCalendarSync) {
        if (pendingCalendarSync) {
            pendingCalendarSync = false
            viewModel.syncToCalendar(context)
        }
    }

    fun startCalendarSync() {
        val needed = listOf(
            android.Manifest.permission.READ_CALENDAR,
            android.Manifest.permission.WRITE_CALENDAR,
        ).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        // 先关分享弹层再进任何一条分支：弹层是独立窗口，盖在它下面的提醒一律不可见
        //（已授权分支此前就在这里关，拒绝分支漏了 → 提示被盖住）
        shareOpen = false
        if (needed.isEmpty()) {
            viewModel.syncToCalendar(context)
        } else {
            calendarPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    // VM 分享结果 → 提示卡（一次性，消费后置空）
    val shareMessage by viewModel.shareMessage.collectAsStateWithLifecycle()
    LaunchedEffect(shareMessage) {
        shareMessage?.let {
            snackbar.showSnackbar(AppNoticeVisuals(it.text, tone = it.tone))
            viewModel.consumeShareMessage()
        }
    }

    // 撤销型反馈（DESIGN §3.3）：删除课程等动作执行后给「撤销」，点了就回滚
    val undoableMessage by viewModel.undoableMessage.collectAsStateWithLifecycle()
    LaunchedEffect(undoableMessage) {
        undoableMessage?.let { m ->
            val result = snackbar.showSnackbar(m.text, actionLabel = "撤销", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.undo()
            viewModel.consumeUndoableMessage()
        }
    }

    val today = LocalDate.now()
    val todayDay = today.dayOfWeek.value
    val totalWeeks = maxOf(state.semester?.totalWeeks ?: 20, 1)
    val sectionCount = maxOf(11, state.slots.maxOfOrNull { it.number } ?: 11)
    val visibleDays = state.visibleDays
    val days = visibleDays.size
    val weeksWithCourses = remember(state.allCourses) {
        state.allCourses.flatMap { it.weeks }.toSet()
    }

    // Pager 初始页必须一次到位。
    // 根因：initialPage 只在首次创建时生效；若在 loading 期间以第 1 周创建 Pager，
    // 数据就绪后 scrollToPage 到本周，肉眼可见「先第一周、再跳本周」的闪跳。
    // 方案：VM 初始化（含初始周次解析）完成前不创建 Pager，创建时 initialPage
    // 直接取解析好的本周周次；加载期间顶栏与网格都不渲染，首帧即终态。
    val pagerState = if (!state.loading) {
        rememberPagerState(
            initialPage = (state.week - 1).coerceIn(0, (totalWeeks - 1).coerceAtLeast(0)),
        ) { totalWeeks }
    } else {
        null
    }

    // 顶栏周次必须**跟手**，不能等松手后 settledPage 才更新。
    // 根因：此前顶栏读 VM 的 `state.week`，而 VM 只被 `snapshotFlow { settledPage }` 驱动——
    // 滑动过程中 settledPage 恒为旧值，松手（含 fling 衰减小 1s）后才变，于是「第 N 周」滞后。
    // 方案：横滑进行中由 currentPage + currentPageOffsetFraction 就地推算出「离得最近的那一周」，
    // 手指划过一半就翻牌；停稳后再由 settledPage 回写 VM，两者取值一致时不会抖动。
    val pagerWeek = pagerState?.let { ps ->
        val nearest = ps.currentPage + if (ps.currentPageOffsetFraction > 0.5f) 1 else 0
        nearest.coerceIn(0, (totalWeeks - 1).coerceAtLeast(0)) + 1
    }
    val displayWeek = pagerWeek ?: state.week

    // VM → Pager（回到本周 / 外部改周）
    LaunchedEffect(pagerState, totalWeeks, state.week) {
        val ps = pagerState ?: return@LaunchedEffect
        val target = state.week.coerceIn(1, totalWeeks) - 1
        if (ps.settledPage != target) {
            // 相邻周动画滚回（回本周最常见就是从 ±1 页滑走）；远距瞬移，
            // 避免动画一路掠过中间几周造成无意义闪烁
            if (kotlin.math.abs(target - ps.settledPage) == 1) {
                ps.animateScrollToPage(target)
            } else {
                ps.scrollToPage(target)
            }
        }
    }

    // Pager → VM（整页横滑切周）。用 settledPage 而非 currentPage：
    // 回写只在停稳后发生，避免手指划过中间页时把 VM 周次刷成中间值。
    LaunchedEffect(pagerState) {
        val ps = pagerState ?: return@LaunchedEffect
        snapshotFlow { ps.settledPage }
            .collectLatest { page ->
                val week = page + 1
                if (week != state.week) viewModel.setWeek(week)
            }
    }

    // 当前时刻线要跟着时间走
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshNow()
            delay(30_000)
        }
    }

    // 「我的 → 显示设置」跨 Tab 触发：跳到课表 Tab 后自动弹出与眼睛图标相同的覆盖面板。
    // 用冷流 + collectLatest：每次跳 Tab 只发一个事件，重复点击也不会重复置位。
    LaunchedEffect(openDisplayRequests) {
        openDisplayRequests.collectLatest { displaySheetOpen = true }
    }

    // ---- 网格字号换算（不依赖可用约束，提到 Scaffold 外）----
    // systemFontScale/gridScale 及各字号 sp 只由设置与列数决定，与 BoxWithConstraints 的
    // 可用宽高无关；上提到函数级是因为「表头高度」的动态下限 headerMinDp 要同时喂给两处：
    // 布局（渲染兜底防日期文字截断）与显示设置弹层（滑块 valueRange 从有效下限起步）。
    val systemFontScale = LocalDensity.current.fontScale
    // 网格字号：课名目标字号 dp（用户设置或跟随系统）→ 网格内统一倍率，换算规则见 GridFont
    val gridScale = GridFont.scaleFromDp(
        GridFont.resolveDp(systemFontScale, state.gridFontDp, days),
        days,
    )
    // 教室/教师：用户设置过目标 dp 时换算成卡片内的实际 sp——网格还套着 gridScale
    // 的密度倍率，预除一次才能让净渲染值等于目标 dp；未设置传 null，跟随课名等比（旧行为）
    val roomFontSp = state.gridRoomDp?.let {
        GridFont.resolveDetailDp(systemFontScale, it, days) / gridScale
    }
    val teacherFontSp = state.gridTeacherDp?.let {
        GridFont.resolveDetailDp(systemFontScale, it, days) / gridScale
    }
    // 时间轴 / 日期表头：与课名**完全解耦**。二者不在 gridScale 的语义范围内
    // （它们是定位参照，不是内容），但仍渲染在 GridTypography 提供的密度里，
    // 所以要把课名倍率除回去，使净渲染值只由自己的设置决定。
    //
    // 根因：这里不能写成 `state.gridRailDp?.let{...}`——用户没拖过滑块时结果是 null，
    // TimeRail 便回落到硬编码 sp，而那仍会被 gridScale 乘一遍，
    // 于是「调课名字号，时间轴/日期跟着变」的老问题在默认态下根本没被修掉。
    // 方案：无论用户是否设置过都解析出一个值——null 走 resolveXxxDp 的
    // 「基准 × 系统倍率」分支（跟随系统、与课名无关），设置过则取用户值。
    val railFontSp = GridFont.resolveRailDp(systemFontScale, state.gridRailDp, days) / gridScale
    val dateFontSp = GridFont.resolveDateDp(systemFontScale, state.gridDateDp, days) / gridScale
    // 表头高度有效下限：装得下两行日期文字的最小高度（已夹进滑块范围）
    val headerMinDp = minHeaderHeightForDateFont(dateFontSp)

    Scaffold(
        // 底部导航栏 inset 已由外层底栏高度提供，内层不再消费（防底部双倍空白）；
        // 顶栏为自绘 56dp Row，本就不消费状态栏 inset，顶部由外层 padding 避让。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            // 加载中不渲染顶栏：否则会先显示默认的「第 1 周」，就绪后再跳成本周（同 Pager 的闪跳根因）
            if (!state.loading) {
                WeekTopBar(
                    week = displayWeek,
                    today = today,
                    timetableName = state.timetableName,
                    onOpenPicker = { pickerOpen = true },
                    onOpenDisplay = { displaySheetOpen = true },
                    onOpenTimetables = { switchOpen = true },
                    onOpenImport = { importOpen = true },
                    onOpenShare = { shareOpen = true },
                    hasDetectAlert = pendingDetect != null,
                    onOpenScheduleUpdate = onOpenScheduleUpdate,
                )
            }
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val layout = buildGridLayout(
                maxHeight = maxHeight,
                maxWidth = maxWidth,
                sectionCount = sectionCount,
                dayCount = days,
                rowHeightScale = state.rowHeightScale,
                railWidth = state.railWidthDp.dp,
                // 表头高度用户值与「装得下两行日期文字」的下限取大：用户把表头拖小、
                // 日期字号拉大时兜底防截断。只抬高渲染值，不改写存储值（滑块位置不动）。
                // 下限同时是显示设置弹层里滑块的动态起点（见 headerMinDp），两端一致
                dayHeaderHeight = maxOf(state.dayHeaderHeightDp.dp, headerMinDp.dp),
            )
            val fitsOneScreen = layout.gridHeight + layout.dayHeaderHeight <= maxHeight

            val body: @Composable () -> Unit = {
                val ps = pagerState
                if (ps == null) {
                    // 初始化未完成：网格不渲染，避免 Pager 以第 1 周先出一帧（根因见 pagerState 注释）。
                    // 但也不能纯白屏——给一个居中的进度指示（此前是无任何反馈的空白）
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        androidx.compose.material3.CircularProgressIndicator()
                    }
                } else {
                    Row(Modifier.fillMaxSize()) {
                        TimeRail(
                            layout = layout,
                            slots = state.slots,
                            monthLabel = weekDate(state.semester, displayWeek, 1)
                                ?.let { "${it.monthValue}月" },
                            railFontSp = railFontSp,
                            dateFontSp = dateFontSp,
                        )
                        HorizontalPager(
                            state = ps,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            verticalAlignment = Alignment.Top,
                        ) { pageIndex ->
                        val week = pageIndex + 1
                        WeekPage(
                            week = week,
                            layout = layout,
                            visibleDays = visibleDays,
                            allCourses = state.allCourses,
                            slots = state.slots,
                            semester = state.semester,
                            isTodayWeek = week == state.todayWeek,
                            todayDay = todayDay,
                            now = state.now,
                            showNonCurrentWeek = state.showNonCurrentWeek,
                            cellStyle = GridCellStyle(
                                cornerRadiusDp = state.cellRadiusDp,
                                opacity = state.cellOpacity,
                                centerHorizontal = state.cellCenterH,
                                centerVertical = state.cellCenterV,
                                showTeacher = state.showTeacher,
                                showBorder = state.showCellBorder,
                                roomFontSp = roomFontSp,
                                teacherFontSp = teacherFontSp,
                                showAtSign = state.showAtSign,
                            ),
                            showNowLine = state.showNowLine,
                            showGridLines = state.showGridLines,
                            dateFontSp = dateFontSp,
                            tapBlankToAdd = state.tapBlankToAdd,
                            onAddEmpty = { day, section ->
                                editing = Course(
                                    id = 0,
                                    name = "",
                                    teacher = "",
                                    position = "",
                                    day = day,
                                    startSection = section,
                                    endSection = section,
                                    weeks = setOf(week),
                                )
                                editorOpen = true
                            },
                            onOpenCourse = { detailCourse = it },
                        )
                        }
                    }
                }
            }

            if (fitsOneScreen) {
                GridTypography(gridScale) { body() }
            } else {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                ) {
                    Box(Modifier.height(layout.dayHeaderHeight + layout.gridHeight)) {
                        GridTypography(gridScale) { body() }
                    }
                    if (state.totalCourseCount == 0) {
                        EmptyScheduleHint(
                            filter = state.courseFilter,
                            onOpenJwImport = onOpenJwImport,
                            tapBlankToAdd = state.tapBlankToAdd,
                        )
                    }
                    Spacer(Modifier.height(72.dp))
                }
            }

            if (fitsOneScreen && state.totalCourseCount == 0 && !state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    EmptyScheduleHint(
                        filter = state.courseFilter,
                        onOpenJwImport = onOpenJwImport,
                        tapBlankToAdd = state.tapBlankToAdd,
                        modifier = Modifier.padding(bottom = 24.dp),
                    )
                }
            }

            // 「回到本周」悬浮按钮：非本周才出现，贴课表页右下角（与空态提示同层，故后者下移避让）。
            // 根因：顶栏右侧图标区已排满（眼睛 + 导入），且「回到本周」是低频的**纠偏**动作、
            // 不是常驻导航——放顶栏会与高频入口抢位置，也让顶栏在切周时左右跳动。
            // 放右下角后：位置固定不参与顶栏布局，拇指可达，底部再抬 24dp 避让外层底栏。
            val showBackToWeek = !state.loading && displayWeek != state.todayWeek
            if (showBackToWeek) {
                BackToCurrentWeekButton(
                    onClick = { viewModel.goToToday() },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 24.dp),
                )
            }
        }
    }

    // 显示设置覆盖面板：不跳页、不挤压课表——真实网格在上层保持不变，
    // 面板盖住下半屏，改动即时生效。
    //
    // 根因（本需求的 bug）：此前用的是 ModalBottomSheet + confirmValueChange = { false }。
    // ModalBottomSheet 内部注册了 ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection，
    // 面板内容（DisplaySettingsContent）自己又是 verticalScroll——于是「在面板里上下滑」这件事
    // 会同时喂给两个滚动消费者：内层列表滚到顶/底之后剩余位移继续向上冒泡，
    // sheet 的 anchoredDraggable 认为用户在拖面板并触发 settleToDismiss，
    // 动画先行、confirmValueChange 的否决来不及拦住（它只否决状态落点，不否决动画），
    // 结果「手动划一下窗口就被自动关闭」。
    //
    // 方案：不再用 ModalBottomSheet，改成**自绘的固定锚定面板**——
    // 结构上不存在 sheet 的拖拽手势与 nestedScroll 连接，面板只有三种退出口：
    // 「完成」按钮 / 点遮罩 / 系统返回。滚动冲突从根上消失，而不是靠参数对冲。
    if (displaySheetOpen) {
        DisplaySettingsOverlay(
            onDismiss = { displaySheetOpen = false },
            viewModel = viewModel(factory = MeViewModel.Factory(Graph.repository(context))),
            // 表头滑块的动态下限与布局兜底同源（dateFontSp 同一换算），两端不会各说各话
            headerMinDp = headerMinDp,
        )
    }

    if (pickerOpen) {
        WeekPickerSheet(
            currentWeek = state.week,
            todayWeek = state.todayWeek,
            totalWeeks = totalWeeks,
            weeksWithCourses = weeksWithCourses,
            onPickWeek = { week ->
                pickerOpen = false
                viewModel.setWeek(week)
            },
            onBackToCurrentWeek = {
                pickerOpen = false
                viewModel.goToToday()
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (importOpen) {
        ImportEntrySheet(
            onManualAdd = {
                importOpen = false
                editing = null
                editorOpen = true
            },
            onJsonImport = {
                importOpen = false
                jsonLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream", "*/*"),
                )
            },
            onJwImport = {
                importOpen = false
                onOpenJwImport()
            },
            // 手动检测（DESIGN §4.17）：检测中行内文案切换；结果**内联在弹层里**
            //（弹层是独立窗口，Snackbar 会被它盖住），有差异才关弹层进「更新课表」。
            // 弹层保持打开——检测 1–3 秒，关掉会让用户以为已开始跳转；
            // 无差异留在弹层让用户接着选别的，结果就显示在刚才点的那一行下面。
            onDetectUpdate = {
                if (!detectChecking) {
                    detectChecking = true
                    detectNotice = null
                    scope.launch {
                        // finally 复位：JwDetectRunner.run() 已约定不抛异常，
                        // 这里再兜一层——busy 卡住就是「导入弹层永远显示正在检测…」。
                        try {
                            val outcome = JwDetectRunner(context.applicationContext)
                                .run(JwDetectRunner.Trigger.Manual)
                            if (outcome is JwDetectRunner.Outcome.DiffFound) {
                                importOpen = false
                                onOpenScheduleUpdate()
                            } else {
                                detectNotice = detectOutcomeNotice(outcome)
                            }
                        } finally {
                            detectChecking = false
                        }
                    }
                }
            },
            detectChecking = detectChecking,
            detectNotice = detectNotice,
            onDismiss = {
                importOpen = false
                // 关掉弹层就把结果丢掉：下次打开不该还挂着上次的旧结论
                detectNotice = null
            },
        )
    }

    if (shareOpen) {
        ShareEntrySheet(
            onSyncCalendar = { startCalendarSync() },
            onExportCsv = {
                shareOpen = false
                csvLauncher.launch("水贝贝课表_$fileDateTag.csv")
            },
            onExportJson = {
                shareOpen = false
                jsonExportLauncher.launch("水贝贝课表_$fileDateTag.json")
            },
            onDismiss = { shareOpen = false },
        )
    }

    jsonPreview?.let { preview ->
        ImportTargetDialogHost(
            courses = preview.courses,
            title = "导入 JSON 课表",
            term = preview.term,
            defaultMerge = false,
            repo = repo,
            onConfirm = { target, merge ->
                val text = pendingJsonText
                jsonPreview = null
                pendingJsonText = null
                if (text != null) {
                    scope.launch {
                        val targetId = resolveImportTarget(repo, target)
                        runJsonImport(text, merge, targetId)
                        // 导入到非当前课表后切过去，让用户立刻看到结果
                        repo.setCurrentTimetable(targetId)
                    }
                }
            },
            onDismiss = {
                jsonPreview = null
                pendingJsonText = null
            },
        )
    }

    if (switchOpen) {
        TimetableSwitchSheet(
            timetables = state.timetables,
            currentTimetableId = state.currentTimetableId,
            onSelect = { id ->
                switchOpen = false
                viewModel.selectTimetable(id)
            },
            onOpenManage = {
                switchOpen = false
                onOpenTimetableManage()
            },
            onDismiss = { switchOpen = false },
        )
    }

    detailCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            slots = state.slots,
            currentWeek = state.week,
            onEdit = {
                detailCourse = null
                editing = course
                editorOpen = true
            },
            onDelete = {
                detailCourse = null
                pendingDelete = course
            },
            onDismiss = { detailCourse = null },
        )
    }

    if (editorOpen) {
        CourseEditSheet(
            course = editing,
            onDismiss = { editorOpen = false },
            onSave = { c ->
                viewModel.upsert(c)
                editorOpen = false
            },
            onDelete = editing?.takeIf { it.id > 0 }?.let { c ->
                {
                    pendingDelete = c
                    editorOpen = false
                }
            },
        )
    }

    pendingDelete?.let { c ->
        DeleteConfirmDialog(
            courseName = c.name,
            onConfirm = {
                viewModel.delete(c)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
}

/**
 * 把网格区域的字号倍率收进可排版区间。
 * 只包住网格本身——顶栏、弹层、空态提示仍跟随系统字体设置。
 */
@Composable
private fun GridTypography(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale),
    ) {
        content()
    }
}

/**
 * 顶栏对齐 WakeUp：左侧大号日期 + 「第 N 周 周X」小字，右侧动作图标。
 * 左侧日期块 → 周次选择；眼睛图标 → 显示设置覆盖面板。
 *
 * 本轮调整（按需求）：
 * - 去掉右侧「+」：它与「导入」弹层里的「手动添加课程」是同一件事（都开编辑器），
 *   两个入口等于把同一动作的两种叫法摆在顶栏，去掉图标后仍可从导入弹层进入。
 * - 「回到本周」移出顶栏：它不是常驻导航，会随周次反复出现/消失把右侧图标挤来挤去；
 *   改为课表页右下角悬浮按钮（见 [BackToCurrentWeekButton]），顶栏布局因此恒定。
 */
@Composable
private fun WeekTopBar(
    week: Int,
    today: LocalDate,
    timetableName: String,
    onOpenPicker: () -> Unit,
    onOpenDisplay: () -> Unit,
    onOpenTimetables: () -> Unit,
    onOpenImport: () -> Unit,
    onOpenShare: () -> Unit,
    /** 有未处理的调课差异报告（DESIGN §4.17）：导入图标挂气泡，点击直达更新课表 */
    hasDetectAlert: Boolean = false,
    onOpenScheduleUpdate: () -> Unit = {},
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val haptics = rememberAppHaptics()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(TopBarHeight)
            .padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(
                    onClickLabel = "选择周次",
                    role = Role.Button,
                    onClick = onOpenPicker,
                )
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = today.format(fullDateFmt),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = onSurface,
            )
            // 小字行放两个独立可点区：周次信息 → 周次选择器；课表名 ▾ → 切换课表。
            // 放同一行是空间取舍：顶栏横向放不下第四个常驻入口，而课表名与「第 N 周」
            // 同属"当前看的是哪张课表的哪一周"这一语义层。
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "第 $week 周 ${dayLabels[today.dayOfWeek.value - 1]}",
                    style = MaterialTheme.typography.labelMedium,
                    color = onSurface.copy(alpha = 0.55f),
                )
                if (timetableName.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "$timetableName ▾",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(
                                onClickLabel = "切换课表",
                                role = Role.Button,
                                onClick = onOpenTimetables,
                            )
                            .padding(horizontal = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { haptics.tap(); onOpenShare() }) {
            Icon(
                HugeIcons.Share08,
                contentDescription = "分享课表",
                tint = onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
        IconButton(onClick = { haptics.tap(); onOpenDisplay() }) {
            Icon(
                HugeIcons.Eye,
                contentDescription = "显示设置",
                tint = onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp),
            )
        }
        // 导入图标双语义（DESIGN §4.17）：有未处理调课提醒时挂气泡、点击进「更新课表」；
        // 无提醒时保持原行为（打开教务导入弹层）。
        IconButton(onClick = {
            haptics.tap()
            if (hasDetectAlert) onOpenScheduleUpdate() else onOpenImport()
        }) {
            if (hasDetectAlert) {
                BadgedBox(
                    badge = {
                        Badge(containerColor = MaterialTheme.colorScheme.error)
                    },
                ) {
                    Icon(
                        HugeIcons.Import,
                        contentDescription = "有调课提醒，点击查看更新",
                        tint = onSurface.copy(alpha = 0.75f),
                        modifier = Modifier.size(20.dp),
                    )
                }
            } else {
                Icon(
                    HugeIcons.Import,
                    contentDescription = "导入课表",
                    tint = onSurface.copy(alpha = 0.75f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
/**
 * 课表页右下角的「回到本周」悬浮按钮（非本周才显示）。
 *
 * 根因：这是纠偏动作、不是常驻入口——放顶栏会在切周时反复出现/消失，
 * 把右侧图标左右推挤（用户明确反馈过位置跳动）。移到右下角后：
 * - 不参与顶栏布局，顶栏恒定；
 * - 拇指可达（右手握持主区）；
 * - 用底色调 + 阴影与课表卡区分，压住空白格但不遮课程内容（避开最后一节与底栏）。
 */
@Composable
private fun BackToCurrentWeekButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberAppHaptics()
    Surface(
        modifier = modifier
            .shadow(4.dp, RoundedCornerShape(50))
            .clip(RoundedCornerShape(50))
            .clickable(
                onClickLabel = "回到本周",
                role = Role.Button,
            ) {
                haptics.tap()
                onClick()
            },
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                HugeIcons.ArrowDown01,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text("回到本周", style = MaterialTheme.typography.labelLarge)
        }
    }
}
