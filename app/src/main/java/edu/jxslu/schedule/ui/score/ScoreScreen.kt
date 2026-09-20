package edu.jxslu.schedule.ui.score

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.JwImportActivity
import edu.jxslu.schedule.domain.ScoreCalculator
import edu.jxslu.schedule.domain.ScoreGroups
import edu.jxslu.schedule.domain.ScoreRecord
import edu.jxslu.schedule.domain.ScoreSortMode
import edu.jxslu.schedule.domain.TermSummary
import edu.jxslu.schedule.domain.YearGroup
import edu.jxslu.schedule.ui.common.EmptyHint
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.jwvw.JwImportMode
import kotlinx.coroutines.launch

/** 「全部」档的选中哨兵值：学期号/学年号不可能出现的字符串，避免与真实数据撞值。 */
private const val SCOPE_ALL = "\u0000all"

/**
 * 成绩查询（DESIGN §4.15）：按学期 / 按学年两种分组切换展示 + 汇总 + 课程卡列表，
 * 两种视图首位都有「全部」档（全量聚合：总加权平均 / 总绩点 / 总修得学分）。
 *
 * 数据全局归属学生、按学期整体替换；页内「导入」复用教务 WebView（[JwImportMode.Scores]），
 * 「删除」清空当前学期（带确认，仅按学期视图提供——按学年是聚合视图，删除以学期为单位）。
 * 评教未完成（pendingReview）的课程不显示分数也不进统计，单独呈现「待评教」标记，
 * 避免用户误以为成绩丢失。
 *
 * 列表排序（DESIGN §4.15）：顶栏菜单三档——默认/成绩/绩点，只作用于学期分组内部；
 * 加权平均分与平均绩点不计任选课（通识任选、专业任选等），汇总卡展示计算公式并标注「仅供参考」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scoreRepo = remember { Graph.scoreRepository(context) }
    val scope = rememberCoroutineScope()

    // null = Room 流首帧未到（未就绪），非 null 空列表 = 确实没有成绩。
    // 不区分的话，首帧会先渲染「还没有成绩」空态再跳真实数据（与今日页 ready
    // 门闸同根因的闪屏，今日页修在 ViewModel，这里数据流是冷流直订，收口在 UI 侧）
    val termsState by scoreRepo.observeTerms()
        .collectAsState(initial = null)
    val allByTermState by scoreRepo.observeAllGroupedByTerm()
        .collectAsState(initial = null)
    val ready = termsState != null && allByTermState != null
    val terms = termsState.orEmpty()
    val allByTerm = allByTermState.orEmpty()
    val yearGroups = remember(allByTerm) { ScoreGroups.group(allByTerm.keys.toList()) }

    // 分组模式/排序/任选课口径持久化在 DataStore（DESIGN §4.15）：重进页面不重置；
    // 学期内具体选中项页内记忆即可
    val prefsStore = remember { Graph.displayPrefs(context) }
    val includeFreeElectives by prefsStore.scoreIncludeFreeElectives.collectAsState(initial = false)
    val groupByYear by prefsStore.scoreGroupByYear.collectAsState(initial = false)
    val sortMode by prefsStore.scoreSortMode.collectAsState(initial = ScoreSortMode.Default)
    var selectedTerm by remember { mutableStateOf<String?>(null) }
    var selectedYear by remember { mutableStateOf<String?>(null) }
    var sortMenuOpen by remember { mutableStateOf(false) }

    // 列表到位后的默认选择：最新有数据的学期/学年；用户切换后以用户为准
    LaunchedEffect(terms) {
        if (selectedTerm == null || (selectedTerm != SCOPE_ALL && selectedTerm !in terms)) {
            selectedTerm = terms.firstOrNull()
        }
    }
    LaunchedEffect(yearGroups) {
        if (selectedYear != SCOPE_ALL && yearGroups.none { it.academicYear == selectedYear }) {
            selectedYear = yearGroups.firstOrNull()?.academicYear
        }
    }

    val selectedGroup = yearGroups.firstOrNull { it.academicYear == selectedYear }
    val summary: TermSummary? = when {
        groupByYear && selectedYear == SCOPE_ALL ->
            ScoreCalculator.summarize("全部", allByTerm.values.flatten(), includeFreeElectives)
        groupByYear -> selectedGroup?.let { group ->
            ScoreCalculator.summarize(
                group.academicYear,
                group.terms.flatMap { allByTerm[it].orEmpty() },
                includeFreeElectives,
            )
        }
        selectedTerm == SCOPE_ALL ->
            ScoreCalculator.summarize("全部", allByTerm.values.flatten(), includeFreeElectives)
        else -> allByTerm[selectedTerm]?.let {
            ScoreCalculator.summarize(selectedTerm.orEmpty(), it, includeFreeElectives)
        }
    }

    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("成绩查询", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(onClick = { sortMenuOpen = true }) {
                        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "排序")
                    }
                    DropdownMenu(
                        expanded = sortMenuOpen,
                        onDismissRequest = { sortMenuOpen = false },
                    ) {
                        SortMenuItem("默认顺序", ScoreSortMode.Default, sortMode) { mode ->
                            scope.launch { prefsStore.setScoreSortMode(mode) }
                        }
                        SortMenuItem("成绩从高到低", ScoreSortMode.ByScore, sortMode) { mode ->
                            scope.launch { prefsStore.setScoreSortMode(mode) }
                        }
                        SortMenuItem("绩点从高到低", ScoreSortMode.ByGradePoint, sortMode) { mode ->
                            scope.launch { prefsStore.setScoreSortMode(mode) }
                        }
                    }
                    IconButton(onClick = {
                        JwImportActivity.start(context, JwImportMode.Scores)
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "从教务导入")
                    }
                    if (!groupByYear && selectedTerm != null && selectedTerm != SCOPE_ALL) {
                        IconButton(onClick = { confirmDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "清空本学期")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            // 未就绪：水滴呼吸加载态，避免「还没有成绩」空态闪现
            !ready -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingHint("正在读取成绩")
                }
            }
            terms.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    verticalArrangement = Arrangement.Center,
                ) {
                    EmptyHint(
                        title = "还没有成绩",
                        body = "从教务导入全部学期的成绩后，这里会按学期展示。\n导入只读教务数据，不会写入课表。",
                        actionLabel = "去导入",
                        onAction = {
                            JwImportActivity.start(context, JwImportMode.Scores)
                        },
                    )
                }
            }
            else -> {
                val rows: List<ScoreRow> = when {
                    // 「全部」档：按学期小节头平铺全量（两种分组模式同一口径）
                    (!groupByYear && selectedTerm == SCOPE_ALL) ||
                        (groupByYear && selectedYear == SCOPE_ALL) ->
                        allByTerm.entries.sortedByDescending { it.key }.flatMap { (term, list) ->
                            listOf(ScoreRow.Header(term, list.size)) +
                                ScoreCalculator.sort(list, sortMode).map { ScoreRow.Record(it) }
                        }
                    groupByYear -> selectedGroup?.terms?.flatMap { term ->
                        val list = allByTerm[term].orEmpty()
                        listOf(ScoreRow.Header(term, list.size)) +
                            ScoreCalculator.sort(list, sortMode).map { ScoreRow.Record(it) }
                    }.orEmpty()
                    else -> ScoreCalculator.sort(allByTerm[selectedTerm].orEmpty(), sortMode)
                        .map { ScoreRow.Record(it) }
                }

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 16.dp,
                        vertical = 12.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            SegmentedButton(
                                selected = !groupByYear,
                                onClick = { scope.launch { prefsStore.setScoreGroupByYear(false) } },
                                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                            ) { Text("按学期") }
                            SegmentedButton(
                                selected = groupByYear,
                                onClick = { scope.launch { prefsStore.setScoreGroupByYear(true) } },
                                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                            ) { Text("按学年") }
                        }
                    }
                    item {
                        if (groupByYear) {
                            YearChips(
                                groups = yearGroups,
                                selected = selectedYear,
                                onSelect = { selectedYear = it },
                            )
                        } else {
                            TermChips(
                                terms = terms,
                                selected = selectedTerm,
                                onSelect = { selectedTerm = it },
                            )
                        }
                    }
                    item {
                        SummaryCard(
                            summary,
                            total = rows.count { it is ScoreRow.Record },
                            includeFreeElectives = includeFreeElectives,
                            onIncludeFreeElectives = { value ->
                                scope.launch { prefsStore.setScoreIncludeFreeElectives(value) }
                            },
                        )
                    }
                    // 不设自定义 key：同学期同课号可能有多行（补考/重修批次），任何业务键组合
                    // 都可能撞 key 导致 LazyColumn 直接抛异常；本列表静态无重排，默认位置键即可
                    items(rows) { row ->
                        when (row) {
                            is ScoreRow.Header -> TermSectionHeader(row.term, row.count)
                            is ScoreRow.Record -> ScoreCard(row.record)
                        }
                    }
                    item {
                        Text(
                            "数据按学期整体替换存储；重新导入相同学期会覆盖。最长保留到手动删除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("清空本学期成绩？") },
            text = {
                Text("将删除「${selectedTerm.orEmpty()}」的全部成绩记录；需要时可重新从教务导入。")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val term = selectedTerm
                        confirmDelete = false
                        if (term != null) {
                            scope.launch { scoreRepo.deleteTerm(term) }
                        }
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("取消") }
            },
        )
    }
}

/** 列表行：按学年视图里学期小节头与成绩卡混排。 */
private sealed interface ScoreRow {
    data class Header(val term: String, val count: Int) : ScoreRow
    data class Record(val record: ScoreRecord) : ScoreRow
}

@Composable
private fun TermChips(
    terms: List<String>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    GroupChips(
        items = listOf("全部" to SCOPE_ALL) + terms.map { it to it },
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun YearChips(
    groups: List<YearGroup>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    GroupChips(
        items = listOf("全部" to SCOPE_ALL) + groups.map { g ->
            val label = g.gradeLabel?.let { "$it ${g.academicYear}" } ?: g.academicYear
            label to g.academicYear
        },
        selected = selected,
        onSelect = onSelect,
    )
}

@Composable
private fun GroupChips(
    items: List<Pair<String, String>>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { (label, value) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun SummaryCard(
    summary: TermSummary?,
    total: Int,
    includeFreeElectives: Boolean,
    onIncludeFreeElectives: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                if (summary?.weightedAverage != null) {
                    Text(
                        text = trimNum(summary.weightedAverage),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  加权平均分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                if (summary?.gpa != null) {
                    Spacer(Modifier.width(16.dp))
                    Text(
                        text = trimNum(summary.gpa),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        "  平均绩点",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
                // 计算口径与教务正式单据可能有出入，数值旁固定标注（2026-09-19 用户要求）
                if (summary?.weightedAverage != null || summary?.gpa != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "仅供参考",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // 公式与页面统计口径一一对应（DESIGN §4.15）：有值才显示对应行
            if (summary?.weightedAverage != null) {
                FormulaLine("加权平均分 = Σ(课程成绩 × 学分) ÷ Σ学分")
            }
            if (summary?.gpa != null) {
                FormulaLine("平均绩点 = Σ(课程绩点 × 学分) ÷ Σ学分")
            }
            Spacer(Modifier.height(4.dp))
            val note = buildString {
                append("共 $total 门")
                if (summary != null && summary.credits > 0.0) {
                    append(" · 修得 ${trimNum(summary.credits)} 学分")
                }
                if (summary != null && summary.excludedCount > 0) {
                    if (includeFreeElectives) {
                        append(" · 已计入 ${summary.excludedCount} 门任选课")
                    } else {
                        append(" · 已不计 ${summary.excludedCount} 门任选课")
                    }
                }
                if (summary != null && summary.lockedCount > 0) {
                    append(" · ${summary.lockedCount} 门待评教暂不显示分数")
                }
                if (summary != null && summary.courseCount <= 0) append(" · 暂无有效分数")
            }
            Text(
                note,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("任选课计入统计", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "默认排除：本校综测同样不计任选课",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    )
                }
                Switch(checked = includeFreeElectives, onCheckedChange = onIncludeFreeElectives)
            }
        }
    }
}

/** 汇总卡里的口径说明行。 */
@Composable
private fun FormulaLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
    )
}

/** 排序菜单项：当前选中的档位带对勾。 */
@Composable
private fun SortMenuItem(
    label: String,
    mode: ScoreSortMode,
    current: ScoreSortMode,
    onSelect: (ScoreSortMode) -> Unit,
) {
    DropdownMenuItem(
        text = { Text(label) },
        trailingIcon = {
            if (mode == current) {
                Icon(Icons.Filled.Check, contentDescription = null)
            }
        },
        onClick = { onSelect(mode) },
    )
}

/** 学年内按学期分组的小节头：一门不落都看得出来源学期。 */
@Composable
private fun TermSectionHeader(term: String, count: Int) {
    Text(
        "$term · ${count}门",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun ScoreCard(record: ScoreRecord) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = record.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val tags = buildList {
                    if (record.category.isNotBlank()) add(record.category)
                    if (record.examForm.isNotBlank()) add(record.examForm)
                    if (record.status.isNotBlank() && record.status != "正常考试") add(record.status)
                    if (record.credit > 0.0) add("${trimNum(record.credit)} 学分")
                }
                if (tags.isNotEmpty()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tags.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(horizontalAlignment = Alignment.End) {
                if (record.pendingReview) {
                    Box(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.secondaryContainer,
                                RoundedCornerShape(8.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            "待评教",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                } else {
                    Text(
                        text = record.scoreStr.ifBlank { "—" },
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (record.gradePoint != null) {
                        Text(
                            "绩点 ${trimNum(record.gradePoint)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                    }
                }
            }
        }
    }
}

/** 去掉无意义的尾部 0：3.0 → 3、84.50 → 84.5、84.0 → 84。 */
private fun trimNum(v: Double?): String {
    if (v == null) return "—"
    var s = "%.2f".format(v)
    s = s.trimEnd('0').trimEnd('.')
    return s.ifEmpty { "0" }
}
