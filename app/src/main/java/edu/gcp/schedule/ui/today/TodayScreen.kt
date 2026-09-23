package edu.gcp.schedule.ui.today

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.gcp.schedule.Graph
import edu.gcp.schedule.R
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.ShortcutItem
import edu.gcp.schedule.domain.ShortcutSettings
import edu.gcp.schedule.domain.TodayState
import edu.gcp.schedule.domain.clockOf
import edu.gcp.schedule.domain.dayLabel
import edu.gcp.schedule.domain.metaLine
import edu.gcp.schedule.domain.sectionRange
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.ui.common.AppNoticeVisuals
import edu.gcp.schedule.ui.common.AppSnackbarHost
import edu.gcp.schedule.ui.common.CourseDetailSheet
import edu.gcp.schedule.ui.common.CourseEditSheet
import edu.gcp.schedule.ui.common.DeleteConfirmDialog
import edu.gcp.schedule.ui.common.EmptyHint
import edu.gcp.schedule.ui.common.LoadingHint
import edu.gcp.schedule.ui.common.NoticeTone
import edu.gcp.schedule.ui.common.ShortcutIcon
import edu.gcp.schedule.ui.common.ShortcutLauncher
import edu.gcp.schedule.ui.common.ShortcutPinner
import edu.gcp.schedule.ui.common.courseColor
import edu.gcp.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Link01
import edu.gcp.schedule.domain.MONTH_DAY_FORMAT

/**
 * 今日课表。
 *
 * 页面骨架：**顶部焦点卡（正在上课 / 下一节）+ 一列时间轴课程行 + 贴底固定区**。
 * - 焦点课只出现一次：焦点卡拿走第一门课，[TodayState.listCourses] 已把该课剔除，
 *   旧版「状态卡 + 列表首项」显示同一节课的问题不复存在。
 * - 「下一节」有 60 分钟准入窗口（[TodayState.next]）：更远的课不冒充「下一节」，
 *   页面直接从列表开始 + 一行「今天的课 XX:XX 开始」轻提示。
 * - 焦点卡与时间轴行同为**满宽卡片**（同外边距/内边距/圆角，缘对缘对齐）；
 *   区分靠底色（主色 vs 课程色）与焦点卡状态行/进度条。节次编号只在焦点卡出现。
 * - 已结束的课不显示（保持既有取舍）；今天没有待上课程时才轮到「明天」上桌。
 * - 点课程（焦点卡/课程行）弹**只读详情**（[CourseDetailSheet]，与课表页同口径），
 *   编辑/删除是详情里的二级动作——直跳编辑器易误触。
 *
 * 底部固定区（DESIGN §3.3）：只有快捷方式三列图标网格（§3.8），
 * 用 [TodayBottomDock]**钉在滚动区下方**——此前它们是 LazyColumn 的最后两项，
 * 课少时悬在屏幕中段、课多时要滑到底才看得见，同一个「固定区」在空态（贴底）
 * 与有课态（跟滚）之间还是两种表现。三态共用同一个 dock，位置不随状态漂移。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenJwImport: () -> Unit = {},
    /** 「尚未开学」空态的 CTA：跳课表设置（学期起止） */
    onOpenTimetableSettings: () -> Unit = {},
    /** 快捷方式设置页（长按图标进；null=不定位，非 null=打开后直接编辑该条目，DESIGN §3.8） */
    onOpenShortcuts: (String?) -> Unit = {},
    viewModel: TodayViewModel = viewModel(
        factory = TodayViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<Course?>(null) }
    var editorOpen by remember { mutableStateOf(false) }
    var detailCourse by remember { mutableStateOf<Course?>(null) }
    var pendingDelete by remember { mutableStateOf<Course?>(null) }
    val snackbar = remember { SnackbarHostState() }

    // 快捷方式拉起失败的兜底通道（DESIGN §3.8）：Snackbar 带「去设置」动作，
    // 比纯 Toast 多一步「就地修正配置」的出口（菜鸟 Activity 改名这类配置失效场景）；
    // 动作携带失败条目 id，设置页打开后直接展开它的编辑弹层
    val scope = rememberCoroutineScope()
    val showShortcutError: (String, String?) -> Unit = { message, itemId ->
        scope.launch {
            val result = snackbar.showSnackbar(
                message,
                actionLabel = "去设置",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) onOpenShortcuts(itemId)
        }
    }

    // 「还剩 X 分钟」要跟着时间走
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.refreshTick()
            delay(30_000)
        }
    }


    // 单条结果提示（钉桌面失败等）：与快捷方式拉起失败共用同一条 Snackbar 队列，
    // 后到的消息自动排队，不会互相顶掉
    val showNotice: (String, NoticeTone) -> Unit = { message, tone ->
        scope.launch { snackbar.showSnackbar(AppNoticeVisuals(message, tone = tone)) }
    }

    // 撤销型反馈（DESIGN §3.3）：删除课程后给「撤销」
    val undoable by viewModel.undoable.collectAsStateWithLifecycle()
    LaunchedEffect(undoable) {
        undoable?.let { m ->
            val result = snackbar.showSnackbar(m.text, actionLabel = "撤销", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.undo()
            viewModel.consumeUndoable()
        }
    }

    // 底部固定区（DESIGN §3.3）三态共用：同一份组合函数喂给加载中/空态/有课态，
    // 贴底位置只有一处定义
    val bottomDock: @Composable () -> Unit = {
        TodayBottomDock(
            shortcuts = shortcuts,
            onOpenShortcuts = onOpenShortcuts,
            onShortcutError = showShortcutError,
            onNotice = showNotice,
        )
    }

    Scaffold(
        // 顶部 inset 由外层消费（防顶栏双倍空白）；底部导航栏 inset 已由外层底栏
        // 高度提供，内层 contentWindowInsets 归零防底部双倍空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                // 根因：外层 JuwApp Scaffold 无 topBar，contentWindowInsets（systemBars）已垫了一个
                // 状态栏高度；TopAppBar 默认 windowInsets 再消费一次 → 顶栏上方双倍空白。
                // 顶部 inset 统一只由外层消费，这里归零。
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = {
                    Column {
                        Text(stringResource(R.string.tab_today))
                        Text(
                            text = buildString {
                                append(state.date.format(MONTH_DAY_FORMAT))
                                append(" 周${dayLabel(state.day)}")
                                if (state.week > 0) append(" · 第 ${state.week} 周")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        when {
            state.loading -> TodayLoadingContent(padding, bottomDock)

            !state.inTerm -> TodayEmptyContent(
                padding,
                "尚未开学或未配置学期",
                "先在「课表设置」里填好开学日期与周数，也能手动加课。",
                actionLabel = "去设置学期",
                onAction = onOpenTimetableSettings,
                bottomDock = bottomDock,
            )

            state.totalCourseCount == 0 -> TodayEmptyContent(
                padding,
                "课表为空",
                "课表默认为空，请登录教务系统导入「学期理论课表」，也可手动加课。",
                actionLabel = "从教务导入",
                onAction = onOpenJwImport,
                bottomDock = bottomDock,
            )

            else -> TodayContent(
                state = state,
                padding = padding,
                onOpenCourse = { detailCourse = it },
                bottomDock = bottomDock,
            )
        }
    }

    // 只读详情（与课表页同口径）：编辑/删除是详情里的二级动作
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
            onDelete = editing?.let { c ->
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
                viewModel.deleteCourse(c)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }


}

/**
 * 底部固定区（DESIGN §3.3）：**钉在滚动区下方**，不随课表滚动。
 *
 * 结构：快捷方式三列图标网格（§3.8）。三态（加载中/空态/有课态）共用本组件，
 * 位置不随状态漂移；快捷方式开关关着或列表为空时整段不占位。
 *
 * **高度上限**：屏高 45%。8 条快捷方式（3 行）在大字体小屏上足以吃掉半屏，
 * 超过上限时 dock 内部可滚——保住课表的可视区，也保证每个入口都还能够到
 * （不设上限的话，超出的部分会被挤出屏幕且无法访问）。
 */
@Composable
private fun TodayBottomDock(
    shortcuts: ShortcutSettings,
    onOpenShortcuts: (String?) -> Unit,
    onShortcutError: (String, String?) -> Unit,
    onNotice: (String, NoticeTone) -> Unit = { _, _ -> },
) {
    val hasShortcuts = shortcuts.enabled && shortcuts.items.isNotEmpty()
    if (!hasShortcuts) return

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = 16.dp),
    ) {
        ShortcutQuickGrid(shortcuts.items, { onOpenShortcuts(null) }, onShortcutError, onNotice)
    }
}

/**
 * 今日页居中态（加载/空态）共用外壳：`weight(1f)` 居中区 + 贴底固定区。
 * 抽出统一壳是为了防再犯「Box 少 fillMaxWidth 贴左」的错——居中容器只有一处定义。
 */
@Composable
private fun TodayCenteredShell(
    padding: PaddingValues,
    bottomDock: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        // fillMaxWidth 必须带：Box 默认 wrap 内容、crossAxis 左对齐，
        // 少了它整个居中态贴左（旧版加载态左偏的根因）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
        bottomDock()
    }
}

/** 加载态：水滴呼吸居中（DESIGN §3.2），底部固定区照常在位（DESIGN §3.3「加载中不留白屏」）。 */
@Composable
private fun TodayLoadingContent(
    padding: PaddingValues,
    bottomDock: @Composable () -> Unit,
) {
    TodayCenteredShell(padding, bottomDock) {
        LoadingHint("正在读取本机课表")
    }
}

/**
 * 空态（未开学 / 课表为空）：居中提示 + 底部固定区。
 * 快捷方式网格与开水卡在空态也上桌（DESIGN §3.3/§3.8）——假期恰是取件码高频时段，
 * 课表为空不等于入口该消失。
 */
@Composable
private fun TodayEmptyContent(
    padding: PaddingValues,
    title: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    bottomDock: @Composable () -> Unit,
) {
    TodayCenteredShell(padding, bottomDock) {
        EmptyHint(title, body, actionLabel, onAction)
    }
}

@Composable
private fun TodayContent(
    state: TodayState,
    padding: PaddingValues,
    onOpenCourse: (Course) -> Unit,
    bottomDock: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(bottom = 16.dp),
        ) {
            val focus = state.ongoing ?: state.next
            // 焦点卡 ↔「上完/没课」的切换给淡入淡出：这是今日页最常发生的状态跳变
            //（下课瞬间），硬切显得突兀
            item(key = "focus") {
                AnimatedContent(
                    targetState = focus,
                    contentKey = { it?.id },
                    transitionSpec = {
                        fadeIn(tween(220)) togetherWith fadeOut(tween(220))
                    },
                    label = "todayFocus",
                ) { f ->
                    when {
                        f != null -> FocusCard(state, f, onOpenCourse)
                        // 远课态：下一节课在 60 分钟窗口外，不冒充「下一节」；
                        // 给一行轻量提示交代落点（DESIGN §3.3），不弹焦点卡
                        state.remaining.isNotEmpty() -> NextStartHint(state)
                        else -> DoneBlock(state)
                    }
                }
            }

            // 「今天还有」只列焦点之外的课；正在上的课已在焦点卡里，不重复出现。
            // 焦点课若是当天唯一剩余（focus 取走它后列表为空），整段标题也不出现——
            // 「今天还有 1 节」下面空着比不显示更费解。
            // 计数 = 列表里的课数（不含焦点卡那节）：旧版数 remaining，
            // 「今天还有 2 节」下面只列 1 节，数字对不上页面课块数
            if (state.listCourses.isNotEmpty()) {
                item(key = "remaining-header") {
                    SectionLabel("今天还有 ${state.listCourses.size} 节")
                }
                items(state.listCourses, key = { it.id }) { course ->
                    CourseTimelineRow(
                        course = course,
                        slots = state.slots,
                        onClick = { onOpenCourse(course) },
                        // 删除/新增课程时列表项平滑进出场，不再整列硬跳
                        modifier = Modifier.animateItem(),
                    )
                }
            }

            // 明天只在今天没有待上课程（上完 / 没课）时上桌，今天的信息优先
            if (state.tomorrowVisible) {
                item(key = "tomorrow") { TomorrowBlock(state, onOpenCourse) }
            }
        }

        // 底部固定区（DESIGN §3.3）：恒贴底，不随上面的课表滚动
        bottomDock()
    }
}

/**
 * 顶部焦点卡：正在上的课，或 60 分钟窗口内的下一节（DESIGN §3.3）。
 *
 * 满宽卡片样式（2026-09-21 定稿，用户拍板）：主色 8% 底 + 左缘 3dp 主色竖条，
 * 内部「状态行（正在上课 / 下一节 · 第N-M节 + 倒计时）→ 课名 → meta →
 * （正在上课时）进度条」。与时间轴行（[CourseTimelineRow]）**同一卡片形态**：
 * 同为满宽、同圆角、同内边距体系，两处卡片左右缘天然对齐；区分靠底色
 * （焦点卡主色 / 时间轴行课程色）与状态行。焦点课从列表里拿走（不两处重复）；
 * 点卡片弹只读详情（编辑/删除是详情里的二级动作，与课表页同口径）。
 */
@Composable
private fun FocusCard(
    state: TodayState,
    focus: Course,
    onOpenCourse: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val primary = MaterialTheme.colorScheme.primary
    val ongoing = state.ongoing != null
    val haptics = rememberAppHaptics()

    val countdown: String? = if (ongoing) {
        state.ongoingCountdown
            ?: state.minutesToOngoingEnd?.let { "还有 $it 分钟下课" }
    } else {
        // 「下一节」进焦点卡的前提就是 ≤60 分钟（TodayState 的准入窗口），直接说分钟数
        state.minutesToNext?.let { "还有 $it 分钟上课" }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(primary.copy(alpha = 0.08f))
            .clickable(onClickLabel = "查看课程") {
                haptics.tap()
                onOpenCourse(focus)
            },
    ) {
        // 左缘主色竖条：焦点卡的视觉锚
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(primary),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 11.dp, vertical = 13.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (if (ongoing) "正在上课" else "下一节") + " · " + sectionRange(focus),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = primary,
                )
                Spacer(Modifier.weight(1f))
                if (countdown != null) {
                    Text(
                        text = countdown,
                        style = MaterialTheme.typography.labelMedium,
                        color = primary,
                        textAlign = TextAlign.End,
                        maxLines = 2,
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = focus.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = metaLine(state.slots, focus),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = onSurface.copy(alpha = 0.62f),
            )
            if (ongoing) {
                val progress = state.ongoingProgress
                if (progress != null) {
                    Spacer(Modifier.height(8.dp))
                    ProgressBar(progress)
                }
            }
        }
    }
}

/**
 * 远课态的轻量提示（DESIGN §3.3）：下一节课在 60 分钟窗口外时不显示「下一节」焦点卡，
 * 给一行轻提示交代落点（「今天的课 14:00 开始」），随焦点卡淡入淡出同一动画通道。
 */
@Composable
private fun NextStartHint(state: TodayState) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val first = state.remaining.firstOrNull() ?: return
    Text(
        text = "今天的课 ${clockOf(state.slots, first)} 开始",
        style = MaterialTheme.typography.bodyMedium,
        color = onSurface.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}

/** 自绘进度条：不引 M3 的 LinearProgressIndicator，免去两端圆角/端点圆点的版本差异。 */
@Composable
private fun ProgressBar(progress: Float) {
    val primary = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(primary.copy(alpha = 0.18f)),
    ) {
        val fraction = progress.coerceIn(0f, 1f)
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(2.dp))
                    .background(primary),
            )
        }
    }
}

/** 今天结束后的落点：上完课 / 本来就没课。 */
@Composable
private fun DoneBlock(state: TodayState) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (state.todayAllDone) "今天的课都上完了" else "今天没有课",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.todayAllDone) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "今天共 ${state.todayTotal} 节",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}

/**
 * 时间轴课程行：**满宽课程色卡**（2026-09-21 改版，用户拍板）。
 *
 * 与焦点卡（[FocusCard]）**同一卡片形态**：同为满宽、同圆角 12dp、同内边距体系
 * （horizontal 16dp + 卡内 11/13dp），两处卡片左右缘天然对齐；区分靠底色
 * （焦点卡主色 8% / 时间轴行课程色 16%）与焦点卡的状态行/进度条。
 * 卡内三行：课名 → 时刻范围+@地点 · 教师（metaLine 已含 `10:15–11:40` 起止，
 * 旧版行首时刻列删除后时刻信息仍完整）；整行可点弹只读详情。
 */
@Composable
private fun CourseTimelineRow(
    course: Course,
    slots: List<TimeSlot>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val accent = courseColor(course.colorIndex)
    val meta = metaLine(slots, course)
    val haptics = rememberAppHaptics()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.16f))
            .clickable(onClickLabel = "查看课程") {
                haptics.tap()
                onClick()
            },
    ) {
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 11.dp, vertical = 13.dp),
        ) {
            Text(
                text = course.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = onSurface,
            )
            if (meta.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = meta,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = onSurface.copy(alpha = 0.68f),
                )
            }
        }
    }
}

/**
 * 明日预告。只在 [TodayState.tomorrowVisible]（今天已无待上课程）时渲染，
 * 用与今天相同的行组件，保证「明天也是课表」而不是另一套排版。
 */
@Composable
private fun TomorrowBlock(
    state: TodayState,
    onOpenCourse: (Course) -> Unit,
) {
    val onSurface = MaterialTheme.colorScheme.onSurface

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 22.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "明天 · 周${dayLabel(state.tomorrowDay)}",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = if (state.tomorrowCourses.isEmpty()) {
                    "没有课"
                } else {
                    "${state.tomorrowCourses.size} 节"
                },
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.5f),
            )
        }
        if (state.tomorrowCourses.isEmpty()) {
            // 标题行已交代「没有课」，这里只补一句收尾，不再复述一遍
            Spacer(Modifier.height(6.dp))
            Text(
                text = "可以放松一下",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            return@Column
        }
        Spacer(Modifier.height(6.dp))
        state.tomorrowCourses.forEach { course ->
            CourseTimelineRow(
                course = course,
                slots = state.slots,
                onClick = { onOpenCourse(course) },
            )
        }
    }
}

// monthDayFmt 已收拢为 domain/TodayFormat.kt 的 MONTH_DAY_FORMAT（与调课页共用）

/**
 * 快捷方式三列图标网格（DESIGN §3.8，2026-09-19 自横滑 chips 改，用户拍板）：
 * 应用图标样式——方形图标块 + 块下单行名称，三项一行、超出换行；新增条目按列表顺序
 * 落最后的新行，区块向上生长（开水卡恒在底部固定区最后一项）。
 * 点击立即拉起（执行层与错误口径见 [ShortcutLauncher]，失败走 [onShortcutError] 的
 * Snackbar 兜底，不做预检确认）；长按弹菜单：添加到桌面 / 快捷方式设置。
 */
@Composable
private fun ShortcutQuickGrid(
    items: List<ShortcutItem>,
    onOpenSettings: () -> Unit,
    onShortcutError: (String, String?) -> Unit,
    onNotice: (String, NoticeTone) -> Unit = { _, _ -> },
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items.chunked(3).forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowItems.forEach { item ->
                    ShortcutGridCell(
                        item = item,
                        onOpenSettings = onOpenSettings,
                        onShortcutError = onShortcutError,
                        onNotice = onNotice,
                        modifier = Modifier.weight(1f),
                    )
                }
                // 末行不满 3 个时补空位，保持三列对齐
                repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** 三列网格单元：方形图标块（52dp，圆角 12dp）+ 块下单行名称。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShortcutGridCell(
    item: ShortcutItem,
    onOpenSettings: () -> Unit,
    onShortcutError: (String, String?) -> Unit,
    onNotice: (String, NoticeTone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val haptics = rememberAppHaptics()
    val tileShape = RoundedCornerShape(12.dp)
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(tileShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .combinedClickable(
                        onClick = {
                            haptics.tap()
                            ShortcutLauncher.launch(context, item)?.let { message ->
                                onShortcutError(message, item.id)
                            }
                        },
                        onLongClick = {
                            haptics.tap()
                            menuOpen = true
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                ShortcutIcon(item, Modifier.size(28.dp))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("添加到桌面") },
                    leadingIcon = { Icon(HugeIcons.Link01, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        haptics.tap()
                        // 钉桌面会弹系统确认框，成功无需再提示；失败（桌面不支持等）报结果。
                        // 不走 onShortcutError：那条通道带「去设置」动作，而桌面不支持、
                        // 目标未安装都不是设置页能修的，给出口反而误导
                        ShortcutPinner.pin(context, item)?.let { message ->
                            onNotice(message, NoticeTone.Warning)
                        }
                    },
                )
                DropdownMenuItem(
                    text = { Text("快捷方式设置") },
                    leadingIcon = { Icon(HugeIcons.Edit02, null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        menuOpen = false
                        onOpenSettings()
                    },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.name,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
