package edu.jxslu.schedule.ui.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
// Intent 版与 ComponentName/类版同名（分处两个包）：Kotlin 按实参类型消歧，
// 传 Intent 走这个、`actionStartActivity<MainActivity>()` 走上面的类版
import androidx.glance.appwidget.action.actionStartActivity as actionStartActivityIntent
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.MainActivity
import edu.jxslu.schedule.domain.LocalTimeLike
import edu.jxslu.schedule.domain.buildTodayState
import edu.jxslu.schedule.domain.dayLabel
import edu.jxslu.schedule.ui.common.courseColor
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * 课表小组件（DESIGN §3.6）。
 *
 * **单条目**（2026-09-20 改版）：选择器里只有一条「水贝贝 · 课表」，尺寸由
 * [SizeMode.Exact] 全权交给宿主——给多少 dp 就按多少算，[WidgetMetrics] 决定渲染形态：
 * 紧凑（日期 + 焦点卡）/ 列表（焦点 + 按高度算行数的剩余）/ 周网格（4×4 及以上）。
 *
 * 数据来源与今日页**完全同源**：Room → `buildTodayState()` → [buildWidgetSnapshot]。
 *
 * ## 刷新机制（重要：别改成「在组合里起协程」）
 *
 * Glance 不是完整的 Compose 运行时：`provideContent` 会**挂起到会话关闭**，组合里既没有
 * `LaunchedEffect` 也没有 `rememberCoroutineScope`。而会话（`AppWidgetSession`）对状态的
 * 处理有两个硬约束（反编译 1.2.0 源码核实，是桌面不显示课程的根因）：
 *
 * 1. **会话的 `LocalState` 只在组合开始时读一次 DataStore，且先于 `provideGlance`**——
 *    在 `provideGlance` 里写状态，首帧组合读不到，要等下一次 `update()` 推
 *    `UpdateGlanceState` 事件。所以首帧数据必须由 `provideGlance` 直接捕获进组合；
 * 2. **`updateAppWidgetState` 只是写 DataStore，不推送 RemoteViews**——没有活跃会话时
 *    光写状态桌面纹丝不动。所以后台刷新必须「写状态 + `widget.update()`」两步
 *    （活跃会话收事件后重读状态重组；已关闭的会话重跑 `provideGlance` 产出新 RemoteViews）。
 *
 * 第 1 步的触发者见 [TodayWidgetRefresh]：边界闹钟（上下课时刻）+ WorkManager 15 分钟兜底
 * + 冷启动/数据变更时主动刷。三层都为「后台及时性」，设置页会引导用户开电池优化白名单。
 */
open class ScheduleWidget : GlanceAppWidget() {

    /**
     * **不声明尺寸档**（2026-09-20 起）：`Exact` 让宿主把实际可用尺寸直接给到组合，
     * 单条目在任意格位（2×2 … 5×5，以及拖动过程中的中间尺寸）都按实测 dp 渲染。
     *
     * 旧版是 `Responsive(4 档)`：实测尺寸被吸到最近的一档，拖动后形态和别的条目一样，
     * 三个条目因此显得完全冗余（用户反馈的原话）。
     */
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // 首帧：算一次快照写进状态并**捕获进组合**（会话的状态先于 provideGlance 读取，
        // 这里写入的值首帧读不到，见类 KDoc）——首帧内容不依赖 DataStore 回读。
        val initial = WidgetSnapshotStore.computeAndStore(context, id)

        provideContent {
            // currentState 只承接后续 update() 推来的新状态；空了（写入/解码失败）退回首帧快照
            val snapshot = WidgetSnapshotStore.read() ?: initial
            val size = LocalSize.current
            val metrics = widgetMetricsFor(
                widthDp = size.width.value.toInt(),
                heightDp = size.height.value.toInt(),
            )
            WidgetContent(snapshot.forSize(metrics), metrics)
        }
    }
}

/**
 * 快照在 Glance 状态里的键。
 *
 * 存 JSON 字符串而不是结构化 Preferences：快照有嵌套（焦点 / 明日 / 行列表 / 周网格），
 * 拆成多个 key 只会把编解码散到两处（见 [WidgetSnapshotCodec]）。
 */
private val SnapshotKey = stringPreferencesKey("today_snapshot")

/** 快照读写；widget 组合与 [TodayWidgetRefresh] 共用。 */
internal object WidgetSnapshotStore {

    /** 读当前实例的快照。解码失败返回 null（组合显示兜底文案，不崩）。 */
    @Composable
    fun read(): WidgetSnapshot? = WidgetSnapshotCodec.decode(currentState(SnapshotKey))

    /** 算一份当前时刻的快照（Room 三路 + now + 本周网格）。 */
    private suspend fun computeSnapshot(context: Context): WidgetSnapshot {
        val repo = Graph.repository(context)
        // widget 可能先于任何界面被拉起（桌面重启、系统重启），这里自负初始化
        repo.ensureDefaults()
        val semester = repo.semester.first()
        val slots = repo.timeSlots.first()
        val courses = repo.courses.first()
        val prefs = repo.displayPrefs.first()
        val today = LocalDate.now()
        val now = LocalTimeLike.now()

        val state = buildTodayState(
            semester = semester,
            slots = slots,
            courses = courses,
            today = today,
            now = now,
        )
        // 周网格按当前周次构建；不在学期内时用第 1 周兜底——网格至少给出星期表头与
        // 节次结构，比一整块空白强（课表为空时同样只有骨架，不额外解释）
        val week = if (state.inTerm) state.week else 1
        // 高亮列：今天还有待上课程 → 今天；否则 → 明天（回答「接下来」而不是「过去了」）
        val highlightDay = state.focus?.day
            ?: state.tomorrowDay.takeIf { state.tomorrowVisible }
        val grid = buildWidgetWeek(
            courses = courses,
            week = week,
            showSaturday = prefs.showSaturday,
            showSunday = prefs.showSunday,
            filter = prefs.courseFilter,
            highlightDay = highlightDay,
            slots = slots,
            now = now,
            // 「现在在第几节」只在今天还有课可上时标；今天上完/没课时高亮列已是明天，
            // 在今天画「现在」没意义（也与高亮列语义一致）
            markNow = state.focus != null,
            nowDay = state.focus?.let { state.day },
        )
        return buildWidgetSnapshot(state, grid)
    }

    /**
     * provideGlance 首帧用：算快照、写进该实例状态，并**原样返回**给组合捕获。
     *
     * 返回值不是可有可无——会话的 `LocalState` 先于 `provideGlance` 读取，这里写进
     * DataStore 的快照首帧读不到（见 [ScheduleWidget] 的类 KDoc），组合必须直接用这份返回值。
     */
    internal suspend fun computeAndStore(context: Context, id: GlanceId): WidgetSnapshot {
        val snapshot = computeSnapshot(context)
        write(context, id, snapshot)
        return snapshot
    }

    /**
     * 刷新**全部**已添加的实例。
     *
     * 必须「写状态 + `update()`」两步，缺一不可：
     * - 光写状态：活跃会话**不观察** DataStore，不会重组；没有会话时更不会推到桌面；
     * - `update()`：活跃会话收到 `UpdateGlanceState` 事件、重读刚写进的状态重组；
     *   已关闭的会话则重跑 `provideGlance` 产出新 RemoteViews（此时快照会算两遍——
     *   一次这里、一次 `provideGlance` 里。刷新频率低，可接受）。
     *
     * 实例若已被用户删掉，`update()` 会失败——`runCatching` 吞掉即可，
     * 反正下次遍历就查不到了。
     */
    suspend fun refreshAll(context: Context) {
        val snapshot = computeSnapshot(context)
        val manager = GlanceAppWidgetManager(context)
        val widget = ScheduleWidget()
        manager.getGlanceIds(ScheduleWidget::class.java).forEach { id ->
            runCatching {
                write(context, id, snapshot)
                widget.update(context, id)
            }
        }
    }

    /** 是否还有任一实例绑在桌面上（无实例时刷新与闹钟调度都是空跑）。 */
    suspend fun hasAnyBound(context: Context): Boolean {
        val manager = GlanceAppWidgetManager(context)
        return manager.getGlanceIds(ScheduleWidget::class.java).isNotEmpty()
    }

    private suspend fun write(context: Context, id: GlanceId, snapshot: WidgetSnapshot) {
        updateAppWidgetState(context, id) { prefs ->
            prefs[SnapshotKey] = WidgetSnapshotCodec.encode(snapshot)
        }
    }
}

// ---------------------------------------------------------------------------
// 渲染
// ---------------------------------------------------------------------------

/**
 * 小组件内容（DESIGN §3.6）。
 *
 * - [WidgetLayout.Compact]（2×2 / 4×2）：日期行 + 焦点卡；明日接棒时焦点位换成一行状态，
 *   下方补 1–2 行明日课程
 * - [WidgetLayout.List]（2×4 / 4×3 等）：日期行 + 焦点卡（含进度条）+ 按高度算行数的列表
 * - [WidgetLayout.Week]（4×4 及以上）：日期行 + 摘要行 + 本周迷你网格
 */
@Composable
private fun WidgetContent(model: WidgetModel, metrics: WidgetMetrics) {
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(16.dp)
            .background(GlanceTheme.colors.widgetBackground)
            // 整卡点击进 App 今日页。不做「点某一节就编辑」：小组件里没有键盘与校验，
            // 误触成本高于便利（DESIGN §3.6 明确不做）。周网格那一块单独指向课表页。
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = model.header,
                style = TextStyle(
                    fontSize = if (metrics.layout == WidgetLayout.Compact) 10.sp else 11.sp,
                    color = GlanceTheme.colors.onSurfaceVariant,
                ),
                maxLines = 1,
            )
            Spacer(GlanceModifier.height(if (metrics.layout == WidgetLayout.Compact) 4.dp else 6.dp))

            if (metrics.layout == WidgetLayout.Week && model.week != null) {
                WeekBlock(model, metrics)
                return@Column
            }

            FocusBlock(model, metrics)

            // 明日接棒时打一行「明天 · 周五 · 3 节」，紧接状态行下方——
            // 旧版把明日块用弹性空白推到底部，中间一大段空白，见 WidgetModel 的类 KDoc
            model.listTitle?.let { title ->
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = title,
                    style = TextStyle(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }

            model.rows.forEach { row ->
                Spacer(GlanceModifier.height(5.dp))
                // 紧凑档的明日接棒用单行式（110dp 高放不下两行的色卡式行）；
                // 其余档一律色卡式，与今日页观感一致
                if (model.compactRows) CompactCourseRow(row) else CourseRow(row)
            }
            model.rowsMoreLabel?.let { label ->
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = label,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 摘要行高度（一行 10sp + 间距）。 */
private val SummaryHeightDp = 18.dp

/** 星期表头高度（一行 9sp）。 */
private val DayHeaderHeightDp = 14.dp

/** 左侧节次栏宽度。 */
private val RailWidthDp = 13.dp

/** 节次号字号。 */
private const val RailFontSp = 7

/** 网格行高下限：再挤就成一条线，看不出跨节结构。 */
private const val MinRowHeightDp = 8

/**
 * 周网格行高：把「总高 − 卡片内边距 − 日期行 − 摘要行与间距 − 表头」均分给小节。
 *
 * 不做测量（Glance 没有测量回调）：按实测高度算一个固定行高，网格铺满剩余空间。
 * 周网格档最小 230dp（去掉上述固定开销后 ≈160dp），11 小节均分每行 ≈14dp，
 * 放得下 7sp 的课名首行。
 *
 * 竖向开销的每一项都必须与渲染代码逐一对上（内边距 [VerticalPaddingDp] + 日期行
 * [HeaderLineDp] + 摘要行 [SummaryHeightDp] + 摘要与网格间的 6dp + 表头
 * [DayHeaderHeightDp]），否则网格会比卡高——Glance 没有测量回调，
 * 超出的部分会直接被桌面裁掉。
 */
internal fun weekRowHeightDp(heightDp: Int, sectionCount: Int): Int {
    val available = heightDp - VerticalPaddingDp - HeaderLineDp -
        (SummaryHeightDp.value + 6f + DayHeaderHeightDp.value).toInt()
    return (available / sectionCount.coerceAtLeast(1)).coerceAtLeast(MinRowHeightDp)
}

/**
 * 周网格档：摘要行 + 迷你网格。
 *
 * 网格整体（含表头与节次栏）点击进**课表页**——`MainActivity` 读 route extra 后切 Tab；
 * 卡内其余区域仍是整卡点击进今日页（外层 Box）。
 */
@Composable
private fun WeekBlock(model: WidgetModel, metrics: WidgetMetrics) {
    val week = model.week ?: return

    val accentColor = (model.focus as? WidgetFocus.Course)?.let { courseColor(it.colorIndex) }
    val accent: ColorProvider = accentColor?.let { ColorProvider(it) }
        ?: GlanceTheme.colors.onSurfaceVariant
    Text(
        text = model.summary ?: "",
        style = TextStyle(
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            color = accent,
        ),
        maxLines = 1,
        modifier = GlanceModifier.fillMaxWidth().height(SummaryHeightDp),
    )
    Spacer(GlanceModifier.height(6.dp))

    val rowHeight = weekRowHeightDp(metrics.heightDp, week.sectionCount)
    val context = LocalContext.current
    val toWeek = actionStartActivityIntent(widgetIntent(context, edu.jxslu.schedule.ROUTE_WEEK))

    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .clickable(toWeek),
    ) {
        Rail(week, rowHeight)
        Spacer(GlanceModifier.width(2.dp))
        week.days.forEach { day ->
            // `defaultWeight` 是 RowScope 的扩展：只能在 Row 的 lambda 里取，
            // DayColumn 是独立 composable，得由调用方把权重传进去
            DayColumn(week, day, rowHeight, GlanceModifier.defaultWeight())
        }
    }
}

/** 左侧节次栏：表头留白 + 1…N 小节号（每行高度与右侧一致，横向对齐）。 */
@Composable
private fun Rail(week: WidgetWeek, rowHeight: Int) {
    Column(modifier = GlanceModifier.width(RailWidthDp)) {
        Spacer(GlanceModifier.height(DayHeaderHeightDp))
        for (section in 1..week.sectionCount) {
            Box(
                modifier = GlanceModifier.width(RailWidthDp).height(rowHeight.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = section.toString(),
                    style = TextStyle(
                        fontSize = RailFontSp.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 一天的列：星期表头 + 该天课块。
 *
 * **按列排版而不是按行**：跨节的课块高度 = 跨节数 × 行高，若按行铺，一个高块会把
 * 该列后续行整体推下去、与左侧节次栏错位（Glance 没有绝对定位，也没有跨行合并）。
 * 按列铺时自然对齐：每列高度恒为「表头 + N × 行高」，与节次栏逐行对上。
 */
@Composable
private fun DayColumn(
    week: WidgetWeek,
    day: Int,
    rowHeight: Int,
    weightModifier: GlanceModifier,
) {
    val highlighted = day == week.highlightDay
    val dayBlocks = week.blocks.filter { it.day == day }.associateBy { it.startSection }
    val covered = coveredSections(week, day)

    Column(modifier = weightModifier) {
        Box(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(DayHeaderHeightDp)
                .padding(horizontal = 1.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = dayLabel(day),
                style = TextStyle(
                    fontSize = 9.sp,
                    fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Normal,
                    color = if (highlighted) {
                        GlanceTheme.colors.primary
                    } else {
                        GlanceTheme.colors.onSurfaceVariant
                    },
                ),
                maxLines = 1,
            )
        }
        for (section in 1..week.sectionCount) {
            // 被上一块跨节覆盖的行不再出格子（高度已算在那一块里）
            if (section in covered) continue
            val block = dayBlocks[section]
            // 「此刻在第几节」：**必须同时命中今天与本节**——只看节次号会把
            // 周一到周日整行都点亮（Glance 没有 offset，逐格着色是唯一表达方式）
            val isNow = day == week.nowDay && week.nowSection == section
            val fill: ColorProvider = when {
                isNow -> tinted(GlanceTheme.colors.primary, 0.14f)
                highlighted -> tinted(GlanceTheme.colors.primary, 0.06f)
                else -> ColorProvider(Color.Transparent)
            }
            Box(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .height((rowHeight * (block?.let { it.endSection - it.startSection + 1 } ?: 1)).dp)
                    .padding(horizontal = 1.dp, vertical = 1.dp)
                    .background(fill),
            ) {
                if (block != null) WeekCourseCell(block)
            }
        }
    }
}

/**
 * `ColorProvider.copy(alpha = …)` 在 Glance 里不存在——[ColorProvider] 是接口，
 * 只有 `getColor(context)`。要一层「同色但更淡」的底就得在组合里解出真实颜色再包一次。
 *
 * 传入的是主题色 Provider（[GlanceTheme.colors] 的字段），解一次没有额外开销。
 */
@Composable
private fun tinted(provider: ColorProvider, alpha: Float): ColorProvider {
    val context = LocalContext.current
    return ColorProvider(provider.getColor(context).copy(alpha = alpha))
}

/**
 * 该列被跨节课块「吃掉」的小节号（块的起始小节不在内——那一行要出块）。
 *
 * 例：第 3-4 节的课 → 返回 {4}，第 4 行不再单独出格子（高度已算在块里）。
 */
private fun coveredSections(week: WidgetWeek, day: Int): Set<Int> =
    week.blocks.filter { it.day == day }
        .flatMap { (it.startSection + 1)..it.endSection }
        .toSet()

/** 单个课块：色底 + 课名（高度够才写字）。 */
@Composable
private fun WeekCourseCell(block: WidgetWeekBlock) {
    val span = (block.endSection - block.startSection + 1).coerceAtLeast(1)
    val accent = courseColor(block.colorIndex)
    Box(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(accent)
            .cornerRadius(3.dp),
        // 跨两节及以上才排得下文字；单节 40 分钟的格子只留色块（挤半个字更糟）
        contentAlignment = if (span >= 2) Alignment.TopStart else Alignment.Center,
    ) {
        if (span >= 2) {
            Text(
                text = block.name,
                style = TextStyle(
                    fontSize = 7.sp,
                    color = ColorProvider(Color.White),
                ),
                maxLines = 2,
                modifier = GlanceModifier.padding(horizontal = 2.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * 小组件点击用的显式 Intent。
 *
 * `SINGLE_TOP | CLEAR_TOP`：App 已在后台时复用同一个 MainActivity 实例并把栈上
 * 的二级页收起（点小组件就是要「回到课表」），而不是又叠一个实例；
 * 实例复用走 `onNewIntent`，`MainActivity` 在那里重新读 route extra。
 *
 * `route` 用 `MainActivity` 的常量（[edu.jxslu.schedule.EXTRA_ROUTE] / `ROUTE_WEEK`），
 * 不在这里另立一套——两处字符串漂移会让点击静默失效。
 */
private fun widgetIntent(context: Context, route: String?): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        if (route != null) putExtra(edu.jxslu.schedule.EXTRA_ROUTE, route)
    }

/** 焦点块：正在上 / 下一节，或一句话状态（今日上完 / 没课 / 假期）。 */
@Composable
private fun FocusBlock(model: WidgetModel, metrics: WidgetMetrics) {
    when (val focus = model.focus) {
        is WidgetFocus.Course -> {
            val accent = courseColor(focus.colorIndex)
            Column(
                modifier = GlanceModifier
                    .fillMaxWidth()
                    .cornerRadius(12.dp)
                    .background(accent.copy(alpha = 0.16f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Text(
                        text = focus.label,
                        style = TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = ColorProvider(accent),
                        ),
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    val note = focus.countdown ?: focus.fallbackNote
                    if (note != null) {
                        Text(
                            text = note,
                            style = TextStyle(fontSize = 11.sp, color = ColorProvider(accent)),
                            maxLines = 1,
                        )
                    }
                }
                Spacer(GlanceModifier.height(3.dp))
                if (metrics.metaInline && focus.meta.isNotBlank()) {
                    // 宽矮形态（4×2）：课名与副行同排，把宽度用起来——
                    // 竖排需要 ~66dp，110dp 高的卡给不出（见 WidgetMetrics.metaInline）
                    Row(
                        modifier = GlanceModifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Vertical.CenterVertically,
                    ) {
                        Text(
                            text = focus.name,
                            style = TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = GlanceTheme.colors.onSurface,
                            ),
                            maxLines = 1,
                            modifier = GlanceModifier.defaultWeight(),
                        )
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = focus.meta,
                            style = TextStyle(
                                fontSize = 10.sp,
                                color = GlanceTheme.colors.onSurfaceVariant,
                            ),
                            maxLines = 1,
                        )
                    }
                } else {
                    Text(
                        text = focus.name,
                        style = TextStyle(
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = GlanceTheme.colors.onSurface,
                        ),
                        maxLines = metrics.focusNameLines,
                    )
                    if (focus.meta.isNotBlank()) {
                        Spacer(GlanceModifier.height(2.dp))
                        Text(
                            text = focus.meta,
                            style = TextStyle(
                                fontSize = 10.sp,
                                color = GlanceTheme.colors.onSurfaceVariant,
                            ),
                            maxLines = 1,
                        )
                    }
                }
                val progress = focus.progress
                if (progress != null && metrics.showProgress) {
                    Spacer(GlanceModifier.height(6.dp))
                    ProgressLine(progress, accent)
                }
            }
        }

        is WidgetFocus.Idle -> Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.Horizontal.Start,
        ) {
            Text(
                text = focus.title,
                style = TextStyle(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
            )
            val detail = focus.detail
            // 副行（「明天没有课，可以放松一下」/「去『我的』设置开学日期」）只在
            // **没有列表接棒时**显示——列表标题 + 首行已经把「明天怎么了」交代清楚，
            // 再放一句是重复；紧凑档若也接了棒（compactRows），同样交给列表。
            // 假期 / 明日也没课这两种「没有后续内容」的状态则必须给，否则用户不知道下一步。
            if (detail != null && !(metrics.layout == WidgetLayout.Compact && model.rows.isNotEmpty())) {
                Spacer(GlanceModifier.height(2.dp))
                Text(
                    text = detail,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/** 进度条分段数：Glance 没有 `fillMaxWidth(fraction)`，用 N 段拼接表达比例。 */
private const val ProgressSegments = 12

/**
 * 整门课进度条。
 *
 * 为什么是分段而不是连续条：Glance 的 `fillMaxWidth` 没有按比例的重载（只有整宽），
 * 而 `LinearProgressIndicator` 在 glance-appwidget 里只提供不定态实现，
 * 且宽度要等测量后才知道（无法按 fraction 给内层定宽）。分段是唯一既准确又不依赖测量的画法。
 */
@Composable
private fun ProgressLine(progress: Float, accent: Color) {
    val filled = (progress.coerceIn(0f, 1f) * ProgressSegments).toInt()
    Row(modifier = GlanceModifier.fillMaxWidth().height(3.dp)) {
        repeat(ProgressSegments) { index ->
            if (index > 0) Spacer(GlanceModifier.width(1.dp))
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxSize()
                    .background(if (index < filled) accent else accent.copy(alpha = 0.22f)),
            ) {}
        }
    }
}

/** 一行课程：行首时刻 + 色卡（课名 / 副行）。 */
@Composable
private fun CourseRow(row: WidgetCourseRow) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        Text(
            text = row.clock,
            style = TextStyle(
                fontSize = 11.sp,
                color = GlanceTheme.colors.onSurfaceVariant,
            ),
            maxLines = 1,
            modifier = GlanceModifier.width(38.dp),
        )
        Spacer(GlanceModifier.width(6.dp))
        Column(
            modifier = GlanceModifier
                .defaultWeight()
                .cornerRadius(8.dp)
                .background(courseColor(row.colorIndex).copy(alpha = 0.16f))
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                text = row.name,
                style = TextStyle(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = GlanceTheme.colors.onSurface,
                ),
                maxLines = 1,
            )
            if (row.meta.isNotBlank()) {
                Text(
                    text = row.meta,
                    style = TextStyle(
                        fontSize = 10.sp,
                        color = GlanceTheme.colors.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * 紧凑档的单行课程行：`10:15 高数 · @北B102`。
 *
 * 为什么不用 [CourseRow]：2×2 / 4×2 只有 110dp 高，明日接棒时那点余量（约 60dp）
 * 放不下两行的色卡式行；压成一行才能在「今天上完了」下面给出真正有用的那句
 * 「明天第一节几点、上什么、在哪」。
 */
@Composable
private fun CompactCourseRow(row: WidgetCourseRow) {
    Text(
        text = listOf(row.clock, row.name, row.meta)
            .filter { it.isNotBlank() }
            .joinToString(" "),
        style = TextStyle(
            fontSize = 11.sp,
            color = GlanceTheme.colors.onSurface,
        ),
        maxLines = 1,
        modifier = GlanceModifier.fillMaxWidth(),
    )
}
