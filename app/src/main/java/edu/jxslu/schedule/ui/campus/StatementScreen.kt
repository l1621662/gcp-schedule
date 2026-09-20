package edu.jxslu.schedule.ui.campus

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.data.local.YktTurnoverEntity
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.InlineNoticeRow
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.CreditCard

/**
 * 消费流水页（DESIGN §4.19 L3/L4）：本地库优先（秒开、离线可查）+ 下拉/进页增量同步。
 * 头部 = 月汇总（支出/收入）+ 月切换 + 分类 Top；列表 = 日期分组流水，行点详情弹层。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatementScreen(
    onBack: () -> Unit = {},
    /** 详情弹层内「凭证/开关」引导跳设置页（凭证缺失时）。 */
    onOpenSettings: () -> Unit = {},
    viewModel: StatementViewModel = viewModel(factory = StatementViewModel.Factory(LocalContext.current)),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberAppHaptics()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is StatementEvent.Notice ->
                    snackbar.showSnackbar(AppNoticeVisuals(event.text, tone = event.tone))
            }
        }
    }

    Scaffold(
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("消费流水") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.syncing,
            onRefresh = {
                haptics.tap()
                viewModel.refresh()
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
                SummaryHeader(
                    monthLabel = viewModel.monthLabel(state.month),
                    expensesFen = state.expensesFen,
                    incomeFen = state.incomeFen,
                    byType = state.byType,
                    localCount = state.localCount,
                    monthlyExpenses = state.monthlyExpenses,
                    onPrev = {
                        haptics.tap()
                        viewModel.loadMonth(state.month.minusMonths(1))
                    },
                    onNext = {
                        haptics.tap()
                        viewModel.loadMonth(state.month.plusMonths(1))
                    },
                    nextEnabled = !state.month.isAfter(java.time.YearMonth.now().minusMonths(1)),
                )

                when {
                    state.noCredentials -> {
                        EmptyBody(
                            icon = { Icon(HugeIcons.CreditCard, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) },
                            text = "凭证未配置或已清除，请先在「我的 → 校园卡」开启并验证。\n已保存的历史账单仍可离线查看。",
                            actionLabel = "去设置",
                            onAction = onOpenSettings,
                        )
                    }
                    state.error != null && state.records.isEmpty() -> {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            InlineNoticeRow(message = state.error ?: "", tone = NoticeTone.Error)
                            if (state.canRetry) {
                                Spacer(Modifier.size(8.dp))
                                Button(onClick = { viewModel.retry() }, modifier = Modifier.fillMaxWidth()) {
                                    Text("重试")
                                }
                            }
                        }
                    }
                    else -> StatementList(state, dayGroupLabel = viewModel::dayGroupLabel)
                }
            }
        }
    }
}

@Composable
private fun SummaryHeader(
    monthLabel: String,
    expensesFen: Long,
    incomeFen: Long,
    byType: List<edu.jxslu.schedule.data.local.TypeAmountRow>,
    localCount: Int,
    monthlyExpenses: Map<String, Long>,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    nextEnabled: Boolean,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(16.dp),
    ) {
        // 月切换 + 汇总
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onPrev) { Text("‹") }
            Text(
                monthLabel,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            TextButton(onClick = onNext, enabled = nextEnabled) { Text("›") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            SummaryItem("支出", expensesFen, color = MaterialTheme.colorScheme.onSurface)
            SummaryItem("收入", incomeFen, color = MaterialTheme.colorScheme.primary)
        }

        // 分类 Top3（DESIGN §4.19 L4）：金额降序取前三，正数支出/负数收入
        val top = byType.take(3)
        if (top.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            top.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        row.type.ifBlank { "其他" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${row.count} 笔 · ¥%.2f".format(row.amountFen / 100.0),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }

        // 年视图：近 12 个月支出柱状（自绘 Canvas，DESIGN §4.19 L4；无图表库依赖）
        MonthlyBars(
            monthlyExpenses = monthlyExpenses,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
        )

        if (localCount > 0) {
            Spacer(Modifier.height(6.dp))
            Text(
                "本地已存 $localCount 条（下拉刷新同步最新）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

/**
 * 近 12 个月支出柱状（DESIGN §4.19 L4）：自绘 Canvas。
 * 键序 = 从最旧到当月（VM recentMonthKeys 同口径）；无数据月高度为 0 但保留刻度位；
 * 当月柱用主题色高亮，其余 35% 透明度。最大值动态缩放（防某月峰值压扁其余柱）。
 */
@Composable
private fun MonthlyBars(
    monthlyExpenses: Map<String, Long>,
    modifier: Modifier = Modifier,
) {
    if (monthlyExpenses.isEmpty()) return
    val now = java.time.YearMonth.now()
    val keys = (11 downTo 0).map { offset ->
        val ym = now.minusMonths(offset.toLong())
        "%04d-%02d".format(ym.year, ym.monthValue)
    }
    val currentKey = keys.last()
    val maxFen = monthlyExpenses.values.maxOrNull() ?: return
    if (maxFen <= 0) return

    Column(modifier) {
        Text(
            "近 12 个月支出",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
        Spacer(Modifier.height(8.dp))
        val barColor = MaterialTheme.colorScheme.primary
        val dimColor = barColor.copy(alpha = 0.35f)
        val outline = MaterialTheme.colorScheme.outlineVariant
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            val n = keys.size
            val slot = size.width / n
            val barWidth = slot * 0.55f
            keys.forEachIndexed { index, key ->
                val amount = monthlyExpenses[key] ?: 0L
                val h = if (amount > 0) (amount.toFloat() / maxFen) * (size.height - 2f) else 0f
                val x = slot * index + (slot - barWidth) / 2
                drawLine(
                    color = if (key == currentKey) barColor else dimColor,
                    start = Offset(x + barWidth / 2, size.height),
                    end = Offset(x + barWidth / 2, size.height - h),
                    strokeWidth = barWidth,
                    cap = StrokeCap.Round,
                )
            }
            // 底线
            drawLine(outline, Offset(0f, size.height - 0.5f), Offset(size.width, size.height - 0.5f), 1f)
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            // 首尾月标签（中段省略，柱状已足够读图）
            val first = keys.first().substring(5) // "01"
            val last = keys.last().substring(5)
            Text(
                "$first 月",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "$last 月（今）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            )
        }
    }
}

@Composable
private fun SummaryItem(label: String, fen: Long, color: Color) {
    Column {
        Text(
            text = "¥%.2f".format(fen / 100.0),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
}

/** 日期分组后的列表项：分组头或流水行。 */
private sealed interface RowItem {
    data class Header(val label: String) : RowItem
    data class Entry(val record: YktTurnoverEntity) : RowItem
}

@Composable
private fun StatementList(
    state: StatementUiState,
    dayGroupLabel: (LocalDate) -> String,
) {
    val rows = remember(state.records) {
        buildList {
            var lastDate: String? = null
            state.records.forEach { record ->
                val datePart = record.jndatetimeStr.split(" ").firstOrNull().orEmpty()
                if (datePart != lastDate) {
                    val date = runCatching {
                        LocalDate.parse(datePart, DateTimeFormatter.ofPattern("yyyy-MM-dd"))
                    }.getOrNull()
                    add(RowItem.Header(if (date != null) dayGroupLabel(date) else datePart))
                    lastDate = datePart
                }
                add(RowItem.Entry(record))
            }
        }
    }

    if (rows.isEmpty()) {
        EmptyBody(
            icon = null,
            text = if (state.syncing) "正在同步该月流水…" else "该月没有流水记录",
            actionLabel = null,
            onAction = null,
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(rows, key = { _, item ->
            when (item) {
                is RowItem.Header -> "h_${item.label}"
                is RowItem.Entry -> "e_${item.record.orderId}"
            }
        }) { _, item ->
            when (item) {
                is RowItem.Header -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
                ) {
                    Text(
                        item.label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                is RowItem.Entry -> TurnoverRow(item.record)
            }
        }
    }
}

/** 单条流水行：类型 + 摘要 + 时间 / 金额（支出 − 收入 +）。点击弹详情。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TurnoverRow(record: YktTurnoverEntity) {
    val shape = RoundedCornerShape(12.dp)
    var showDetail by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable { showDetail = true }
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val amountColor = if (record.income) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurface
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = record.turnoverType.ifBlank { "交易" },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = listOfNotNull(
                record.remark?.takeIf { it.isNotBlank() },
                record.locationName?.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (secondary.isNotEmpty()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = record.jndatetimeStr.split(" ").getOrNull(1)?.take(5).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            )
        }
        Text(
            text = (if (record.income) "+" else "−") + "¥%.2f".format(record.tranamtFen / 100.0),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = amountColor,
        )
    }

    if (showDetail) {
        val sheetState = rememberModalBottomSheetState()
        ModalBottomSheet(onDismissRequest = { showDetail = false }, sheetState = sheetState) {
            TurnoverDetail(record)
        }
    }
}

/** 详情弹层内容：完整时间/类型/商户/订单号/余额快照。 */
@Composable
private fun TurnoverDetail(record: YktTurnoverEntity) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = record.turnoverType.ifBlank { "交易" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        DetailRow("交易时间", record.jndatetimeStr)
        DetailRow("金额", (if (record.income) "+" else "−") + "¥%.2f".format(record.tranamtFen / 100.0))
        record.balanceAfterFen?.let { DetailRow("交易后余额", "¥%.2f".format(it / 100.0)) }
        record.locationName?.let { DetailRow("商户/终端", it) }
        record.remark?.let { DetailRow("摘要", it) }
        record.resume?.takeIf { it != record.remark }?.let { DetailRow("说明", it) }
        DetailRow("订单号", record.orderId)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(92.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EmptyBody(
    icon: (@Composable () -> Unit)?,
    text: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon?.invoke()
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}
