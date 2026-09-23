package edu.gcp.schedule.ui.detect

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.gcp.schedule.Graph
import edu.gcp.schedule.data.jw.JwDetectRunner
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.CourseKind
import edu.gcp.schedule.domain.DetectGroup
import edu.gcp.schedule.domain.DetectReportPayload
import edu.gcp.schedule.ui.common.AppNoticeVisuals
import edu.gcp.schedule.ui.common.AppSnackbarHost
import edu.gcp.schedule.ui.common.NoticeTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 更新课表（DESIGN §4.17）：调课检测差异的合并流程本体。
 *
 * 三个入口都落到这里——课表页导入图标的气泡、导入弹层的「检测课表更新」、
 * 差异通知的点击。按课程分组展示「哪节课可能被调动了」
 * （教务新增 / 教务停课 / 有变更 / 冲突四类），用户**手动勾选**哪些课进入更新范围：
 * 非冲突组默认勾选；冲突组是单选（保留本地 = 默认 / 以教务为准），因为本地也动过。
 * 「应用更新」= 仓库层一个事务内替换勾选组并推进基线；「忽略本次」什么都不动（下次照报）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleUpdateScreen(
    onBack: () -> Unit,
    viewModel: ScheduleUpdateViewModel = viewModel(
        factory = ScheduleUpdateViewModel.Factory(LocalContext.current.applicationContext),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    // 勾选状态（组键 = kind|name）：非冲突默认勾选；冲突组单选 keepLocal（默认）/applyRemote
    val checked = remember(state.report) { mutableStateMapOf<String, Boolean>() }
    val conflictRemote = remember(state.report) { mutableStateMapOf<String, Boolean>() }
    fun groupKey(g: DetectGroup) = "${g.kind.name}|${g.name}"
    state.groups.forEach { g ->
        val key = groupKey(g)
        if (key !in checked) checked[key] = !g.conflict
        if (key !in conflictRemote) conflictRemote[key] = false
    }

    Scaffold(
        // 与 TweakDetectScreen 同一根因：SubpageActivity 独立窗口里没有外层 Scaffold 垫状态栏，
        // inset 归零会让顶栏顶进状态栏。走 M3 默认，与其余二级页同口径。
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("更新课表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.loading -> {
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { Text("读取检测报告…") }
            }

            state.report == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("暂无待处理的调课提醒")
                    Text(
                        "可在「我的 → 调课自动检测」里开启自动检测，或点下方立即检测",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    var busy by remember { mutableStateOf(false) }
                    OutlinedButton(
                        onClick = {
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    // finally 复位：任何路径都要让按钮回到可点状态
                                    try {
                                        val outcome = JwDetectRunner(context)
                                            .run(JwDetectRunner.Trigger.Manual)
                                        // 结果带语气（成功/警告/错误），与弹层内联结果同一套映射
                                        val notice = detectOutcomeNotice(outcome)
                                        snackbar.showSnackbar(
                                            AppNoticeVisuals(notice.text, tone = notice.tone),
                                        )
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                        enabled = !busy,
                    ) { Text("立即检测") }
                }
            }

            else -> {
                val report = state.report!!
                Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                    Text(
                        buildString {
                            append("教务于 ")
                            append(
                                DateTimeFormatter.ofPattern("M月d日 HH:mm")
                                    .withZone(ZoneId.systemDefault())
                                    .format(Instant.ofEpochMilli(report.checkedAt)),
                            )
                            append(" 的课表与本地有 ")
                            append(state.groups.size)
                            append(" 处不一致")
                            report.term?.let { append("（$it）") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 16.dp, vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(state.groups, key = { groupKey(it) }) { g ->
                            DetectGroupCard(
                                group = g,
                                checked = checked[groupKey(g)] ?: !g.conflict,
                                onChecked = { checked[groupKey(g)] = it },
                                conflictApplyRemote = conflictRemote[groupKey(g)] ?: false,
                                onConflictApplyRemote = { conflictRemote[groupKey(g)] = it },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    viewModel.ignore()
                                    snackbar.showSnackbar(
                                        AppNoticeVisuals(
                                            "已忽略本次差异，下次检测仍会提醒",
                                            tone = NoticeTone.Info,
                                        ),
                                    )
                                    onBack()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("忽略本次") }
                        Button(
                            onClick = {
                                scope.launch {
                                    val chosen = state.groups.filter { g ->
                                        if (g.conflict) conflictRemote[groupKey(g)] == true
                                        else checked[groupKey(g)] == true
                                    }
                                    if (chosen.isEmpty()) {
                                        snackbar.showSnackbar(
                                            AppNoticeVisuals("未勾选任何课程", tone = NoticeTone.Warning),
                                        )
                                        return@launch
                                    }
                                    viewModel.apply(chosen)
                                    snackbar.showSnackbar(
                                        AppNoticeVisuals(
                                            "已更新 ${chosen.size} 门课",
                                            tone = NoticeTone.Success,
                                        ),
                                    )
                                    onBack()
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("应用更新") }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetectGroupCard(
    group: DetectGroup,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    conflictApplyRemote: Boolean,
    onConflictApplyRemote: (Boolean) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (group.conflict) {
                    Spacer(Modifier.width(8.dp))
                } else {
                    Checkbox(checked = checked, onCheckedChange = onChecked)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(group.name, fontWeight = FontWeight.Medium)
                    Text(
                        listOfNotNull(
                            group.kind.label,
                            group.typeLabel,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (group.conflict) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            DetectSideLines(group)
            if (group.conflict) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "这门课你本地也改过：",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = !conflictApplyRemote, onClick = { onConflictApplyRemote(false) })
                    Text("保留本地", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = conflictApplyRemote, onClick = { onConflictApplyRemote(true) })
                    Text("以教务为准", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** 双侧课程的时间描述；同一门课多行（多时段）时逐行列出。 */
@Composable
private fun DetectSideLines(group: DetectGroup) {
    if (group.remoteCourses.isEmpty()) {
        Text(
            "教务：已没有这门课",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        return
    }
    Text(
        "教务：${group.remoteCourses.joinToString("；") { it.timeLine() }}",
        style = MaterialTheme.typography.bodySmall,
    )
    if (group.localCourses.isEmpty()) {
        Text("本地：暂无此课", style = MaterialTheme.typography.bodySmall)
    } else {
        Text(
            "本地：${group.localCourses.joinToString("；") { it.timeLine() }}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        )
    }
}

private val DAY_NAMES = listOf("一", "二", "三", "四", "五", "六", "日")

private fun Course.timeLine(): String = buildString {
    append("周")
    append(DAY_NAMES.getOrElse(day - 1) { day.toString() })
    append(if (startSection == endSection) "${startSection}节" else "$startSection-${endSection}节")
    append(" ")
    append(weeksLine())
    if (position.isNotBlank()) append(" @").append(position)
    if (teacher.isNotBlank()) append(" ").append(teacher)
}

/** 周次压缩成 1-3,5 口径（连续段合并）。 */
private fun Course.weeksLine(): String {
    if (weeks.isEmpty()) return ""
    val sorted = weeks.sorted()
    val parts = mutableListOf<String>()
    var start = sorted.first()
    var prev = start
    for (w in sorted.drop(1)) {
        if (w == prev + 1) {
            prev = w
        } else {
            parts += if (start == prev) "$start" else "$start-$prev"
            start = w
            prev = w
        }
    }
    parts += if (start == prev) "$start" else "$start-$prev"
    return "${parts.joinToString(",")}周"
}

data class ScheduleUpdateUiState(
    val loading: Boolean = true,
    val report: DetectReportPayload? = null,
) {
    val groups: List<DetectGroup> get() = report?.toGroups().orEmpty()
}

class ScheduleUpdateViewModel(appContext: Context) : ViewModel() {

    private val repo = Graph.repository(appContext)

    val uiState: StateFlow<ScheduleUpdateUiState> = repo.pendingDetectReport
        .map { ScheduleUpdateUiState(loading = false, report = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScheduleUpdateUiState())

    /** 应用勾选组（冲突组只带「以教务为准」的），事务内替换并推进基线、清气泡。 */
    fun apply(groups: List<DetectGroup>) {
        val report = uiState.value.report ?: return
        viewModelScope.launch {
            repo.applyDetectGroups(groups, report)
        }
    }

    /** 忽略本次：不动库、不刷基线，只清气泡（下次检测照报）。 */
    fun ignore() {
        viewModelScope.launch {
            repo.markDetectReportRead()
        }
    }

    companion object {
        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                ScheduleUpdateViewModel(context.applicationContext) as T
        }
    }
}
