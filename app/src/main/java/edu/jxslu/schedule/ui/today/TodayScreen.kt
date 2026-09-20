package edu.jxslu.schedule.ui.today

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
import androidx.compose.foundation.layout.IntrinsicSize
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
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.ScheduleCalculator
import edu.jxslu.schedule.domain.ShortcutItem
import edu.jxslu.schedule.domain.ShortcutSettings
import edu.jxslu.schedule.domain.TodayState
import edu.jxslu.schedule.domain.clockOf
import edu.jxslu.schedule.domain.dayLabel
import edu.jxslu.schedule.domain.metaLine
import edu.jxslu.schedule.domain.sectionRange
import edu.jxslu.schedule.domain.TimeSlot
import edu.jxslu.schedule.domain.UnlockFlowState
import edu.jxslu.schedule.domain.calculateActualCost
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.CourseDetailSheet
import edu.jxslu.schedule.ui.common.CourseEditSheet
import edu.jxslu.schedule.ui.common.DeleteConfirmDialog
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.ShortcutIcon
import edu.jxslu.schedule.ui.common.ShortcutLauncher
import edu.jxslu.schedule.ui.common.ShortcutPinner
import edu.jxslu.schedule.ui.common.WaterUnlockButton
import edu.jxslu.schedule.ui.common.courseColor
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.common.rememberWaterRequireDoubleClick
import edu.jxslu.schedule.ui.water.WaterEvent
import edu.jxslu.schedule.ui.water.WaterViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.Edit02
import me.rerere.hugeicons.stroke.Link01
import edu.jxslu.schedule.domain.MONTH_DAY_FORMAT

/**
 * 今日课表。
 *
 * 页面骨架：**顶部焦点卡（正在上课 / 下一节）+ 一列时间轴课程行 + 贴底固定区**。
 * - 焦点课只出现一次：焦点卡拿走第一门课，[TodayState.listCourses] 已把该课剔除，
 *   旧版「状态卡 + 列表首项」显示同一节课的问题不复存在。
 * - 时间只在行首出现一次（`10:15`），行内不再重复「第 N 节」与时刻——
 *   节次编号只在焦点卡标题里出现（`正在上课 · 第3-4节`）。
 * - 已结束的课不显示（保持既有取舍）；今天没有待上课程时才轮到「明天」上桌。
 * - 点课程（焦点卡/课程行）弹**只读详情**（[CourseDetailSheet]，与课表页同口径），
 *   编辑/删除是详情里的二级动作——直跳编辑器易误触。
 *
 * 底部固定区（DESIGN §3.3）：快捷方式三列图标网格（§3.8）在上、一键开水卡在最底，
 * 用 [TodayBottomDock]**钉在滚动区下方**——此前它们是 LazyColumn 的最后两项，
 * 课少时悬在屏幕中段、课多时要滑到底才看得见，同一个「固定区」在空态（贴底）
 * 与有课态（跟滚）之间还是两种表现。三态共用同一个 dock，位置不随状态漂移。
 * 开水卡默认常显（未登录给未登录态，显示设置可关）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodayScreen(
    onOpenJwImport: () -> Unit = {},
    /** 「尚未开学」空态的 CTA：跳课表设置（学期起止） */
    onOpenTimetableSettings: () -> Unit = {},
    onOpenWater: () -> Unit = {},
    /** 快捷方式设置页（长按图标进；null=不定位，非 null=打开后直接编辑该条目，DESIGN §3.8） */
    onOpenShortcuts: (String?) -> Unit = {},
    /** 与开水页共享的 Activity 作用域实例；开水卡的解锁进度与登录态两页一致 */
    waterViewModel: WaterViewModel? = null,
    viewModel: TodayViewModel = viewModel(
        factory = TodayViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val shortcuts by viewModel.shortcuts.collectAsStateWithLifecycle()
    val waterCardEnabled by viewModel.waterCardEnabled.collectAsStateWithLifecycle()
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

    // 开水卡在今日页也能发起解锁，事件得有人收——此前只有开水页收，
    // 今日页点「开水」后的超时/失效提示全部静默丢弃（Channel 无人消费即无处可去）。
    // 今日页与开水二级页各持一份 WaterViewModel（两个 Activity，登录态/订单走仓库共享），
    // 所以两边各收自己那份的事件，不会重复消费。
    LaunchedEffect(waterViewModel) {
        waterViewModel?.events?.collect { event ->
            when (event) {
                is WaterEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
            }
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
    // 「快捷方式在上、开水卡最底」的顺序与贴底位置只有一处定义
    val bottomDock: @Composable () -> Unit = {
        TodayBottomDock(
            shortcuts = shortcuts,
            onOpenShortcuts = onOpenShortcuts,
            onShortcutError = showShortcutError,
            onNotice = showNotice,
            waterCard = if (waterCardEnabled && waterViewModel != null) {
                { WaterCard(waterViewModel, onOpenWater) }
            } else {
                null
            },
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
                // 一键开水入口不挂顶栏：底部固定区已有开水卡，顶栏图标重复
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
 * 结构：快捷方式三列图标网格（§3.8）在上，一键开水卡恒在最底。三态（加载中/空态/有课态）
 * 共用本组件，位置不随状态漂移；快捷方式开关关着或列表为空时整段不占位，
 * 开水卡关掉时同理（只剩一个空 Column，高度为 0）。
 *
 * **高度上限**：屏高 45%。8 条快捷方式（3 行）+ 开水卡在大字体小屏上足以吃掉半屏，
 * 超过上限时 dock 内部可滚——保住课表的可视区，也保证每个入口都还能够到
 * （不设上限的话，超出的部分会被挤出屏幕且无法访问）。
 */
@Composable
private fun TodayBottomDock(
    shortcuts: ShortcutSettings,
    onOpenShortcuts: (String?) -> Unit,
    onShortcutError: (String, String?) -> Unit,
    onNotice: (String, NoticeTone) -> Unit = { _, _ -> },
    /** 开水卡（含未登录态，显示设置可关）；恒为 dock 最后一项 */
    waterCard: (@Composable () -> Unit)? = null,
) {
    val hasShortcuts = shortcuts.enabled && shortcuts.items.isNotEmpty()
    if (!hasShortcuts && waterCard == null) return

    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.45f).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp, bottom = 16.dp),
    ) {
        if (hasShortcuts) {
            ShortcutQuickGrid(shortcuts.items, { onOpenShortcuts(null) }, onShortcutError, onNotice)
        }
        if (waterCard != null) {
            // 快捷方式在时给它一段呼吸距离；单独出现时不再顶一截空白
            Box(Modifier.padding(top = if (hasShortcuts) 16.dp else 0.dp)) { waterCard() }
        }
    }
}

/** 加载态：居中进度指示，底部固定区照常在位（DESIGN §3.3「加载中不留白屏」）。 */
@Composable
private fun TodayLoadingContent(
    padding: PaddingValues,
    bottomDock: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(10.dp))
                Text(
                    "正在读取本机课表",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
        }
        bottomDock()
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            EmptyHint(title, body, actionLabel, onAction)
        }
        bottomDock()
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
                        fadeIn(tween(220)) togetherWith fadeOut(tween(150))
                    },
                    label = "todayFocus",
                ) { f ->
                    if (f == null) DoneBlock(state) else FocusCard(state, f, onOpenCourse)
                }
            }

            // 「今天还有」只列焦点之外的课；正在上的课已在焦点卡里，不重复出现。
            // 焦点课若是当天唯一剩余（focus 取走它后列表为空），整段标题也不出现——
            // 「今天还有 1 节」下面空着比不显示更费解。
            if (state.listCourses.isNotEmpty()) {
                item(key = "remaining-header") {
                    SectionLabel("今天还有 ${state.remaining.size} 节")
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
 * 顶部焦点卡：正在上的课，或今天下一节。
 *
 * 与旧版「状态区」的差别：焦点课**从列表里拿走**（不再两处重复）；正在上课时补一条
 * 整门课进度条——「还剩 25 分钟」与「一共 80 分钟」是两件事，进度条把后者也交代了。
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
        val minutes = state.minutesToNext
        when {
            minutes == null -> null
            minutes <= 60 -> "还有 $minutes 分钟上课"
            else -> "${clockOf(state.slots, focus)} 上课"
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(14.dp))
            .background(primary.copy(alpha = 0.08f))
            .clickable {
                haptics.tap()
                onOpenCourse(focus)
            },
    ) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(primary),
        )
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 13.dp, vertical = 11.dp),
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
                color = onSurface.copy(alpha = 0.62f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
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
 * 时间轴课程行：行首时刻（`10:15`）+ 课程色卡。
 *
 * 行内只在副行放「@地点 · 教师」——时刻在行首、节次在焦点卡，重复摆放是旧版显乱的主因；
 * 2026-09-19 色卡纵向内边距 9dp→13dp 加高（DESIGN §3.3，用户要求卡片适当加高），
 * 整行约 68dp，4 节课一屏仍放得下。
 * **整行**可点弹只读详情（含时刻列，避免"点了没反应"），无行内删除（删除在详情二级动作）。
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
            .clickable(onClickLabel = "查看课程") {
                haptics.tap()
                onClick()
            }
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = clockOf(slots, course),
            style = MaterialTheme.typography.labelLarge,
            color = onSurface.copy(alpha = 0.72f),
            modifier = Modifier
                .width(50.dp)
                // 与色卡内课名同一起排（色卡上下内边距 13dp），两列文字视觉对齐
                .padding(top = 13.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(accent.copy(alpha = 0.16f))
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
 * 一键开水卡（DESIGN §3.3 底部固定区）：**默认常显**，按登录态分两形态——
 * 已登录 = 原一键开水交互（[WaterQuickEntry]，点「开水」直接走解锁流程，
 * 进度/结果原地显示；点卡片其余位置进开水页）；未登录 = 未登录态（[WaterLoggedOutCard]，
 * 点卡片跳开水页，登录表单就在该页）。
 */
@Composable
private fun WaterCard(vm: WaterViewModel, onOpen: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    if (state.loggedIn) {
        WaterQuickEntry(vm, onOpen)
    } else {
        WaterLoggedOutCard(onOpen)
    }
}

/**
 * 未登录态开水卡：与已登录卡同款 1dp 描边形态，只交代「未登录 + 点这里去登录」，
 * 不显示设备/解锁按钮——登录表单在开水页（SubpageActivity.WATER），点卡片直达。
 */
@Composable
private fun WaterLoggedOutCard(onOpen: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(14.dp)
    val haptics = rememberAppHaptics()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(onClickLabel = "去登录胖乖生活") {
                haptics.tap()
                onOpen()
            }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HugeIcons.Droplet,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = "胖乖生活 · 未登录",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "点击去登录，登录后可一键开水、查余额与订单",
                style = MaterialTheme.typography.bodySmall,
                color = onSurface.copy(alpha = 0.55f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 已登录的一键开水卡：与开水页共享同一 ViewModel，点「开水」直接走解锁流程，
 * 进度/结果原地显示；点卡片其余位置进开水页（选设备、看订单详情）。
 * 开水按钮支持单击/双击（全局偏好，默认双击）；进行中禁点防重复解锁（VM 内另有 Mutex 兜底）。
 *
 * 样式用 1dp 描边而不是主色底：焦点卡已是主色底，两个同款色块一个是信息一个是动作，
 * 分不清哪个能点。
 */
@Composable
private fun WaterQuickEntry(vm: WaterViewModel, onOpen: () -> Unit) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val flow = state.flow
    val primary = MaterialTheme.colorScheme.primary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val shape = RoundedCornerShape(14.dp)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable(enabled = flow is UnlockFlowState.Idle) { onOpen() }
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HugeIcons.Droplet,
            contentDescription = null,
            tint = primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = when (flow) {
                    is UnlockFlowState.Idle -> "一键开水 · " +
                        (state.selectedDevice?.goodsName?.ifBlank { "未命名设备" } ?: "未选择设备")
                    is UnlockFlowState.PreChecking -> flow.step
                    is UnlockFlowState.Working -> "正在出水 ${waterClock(flow.elapsedSeconds)}"
                    is UnlockFlowState.Success -> "开水成功 · 花费 ¥${calculateActualCost(flow.result)}"
                    is UnlockFlowState.Failed -> "开水失败 · ${flow.message}"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (flow is UnlockFlowState.Idle) {
                Text(
                    text = if (rememberWaterRequireDoubleClick()) {
                        "双击「开水」出水防误触，点卡片管理设备与订单"
                    } else {
                        "点「开水」立即出水，点卡片管理设备与订单"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        when (flow) {
            is UnlockFlowState.Idle -> WaterUnlockButton(
                enabled = state.selectedDevice != null,
                onUnlock = { vm.unlock() },
            )
            is UnlockFlowState.PreChecking, is UnlockFlowState.Working ->
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            is UnlockFlowState.Success ->
                TextButton(onClick = { vm.dismissFlow() }) { Text("完成") }
            is UnlockFlowState.Failed ->
                TextButton(onClick = { vm.unlock() }) { Text("重试") }
        }
    }
}

private fun waterClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

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
