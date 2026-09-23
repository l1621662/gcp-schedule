package edu.gcp.schedule.ui.me

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.gcp.schedule.Graph
import edu.gcp.schedule.data.DefaultData
import edu.gcp.schedule.ui.common.AppSnackbarHost
import edu.gcp.schedule.ui.common.SettingItem
import edu.gcp.schedule.ui.common.SettingsSection
import edu.gcp.schedule.ui.common.WheelValueDialog
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 课表设置子页（DESIGN §3.3）：学期 + 作息，都归**当前课表**。
 *
 * 与拾光「课表配置」对齐：换课表即换这两块；通用项（主题）留在「我的」主页。
 *
 * 交互重构：开学日期从手输文本框改为**日历弹窗**（系统 DatePicker），
 * 总周数从手输改为**滚轮选择**——日期格式/周数范围由控件兜住，
 * 不再依赖用户敲对 yyyy-MM-dd，也省掉一个「保存」按钮（选完即存）。
 *
 * 2026-09-19：学期区支持**切换课表**（chips，默认选中当前课表）——每张课表各有
 * 独立的开学日期/总周数，选中哪张就编辑哪张，读写都按目标课表落（DESIGN §4.9）；
 * 作息区仍固定编辑当前课表。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableSettingsScreen(
    onBack: () -> Unit,
    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val oneShot by viewModel.oneShot.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var datePickerOpen by remember { mutableStateOf(false) }
    var weeksPickerOpen by remember { mutableStateOf(false) }

    LaunchedEffect(oneShot) {
        when (val s = oneShot) {
            is OneShot.Message -> {
                snackbar.showSnackbar(s.text)
                viewModel.consumeOneShot()
            }
            else -> Unit
        }
    }

    // 编辑目标 = 学期区 chips 选中的课表（configTargetId）；无配置时展示默认值兜底
    val semester = state.configSemester
    val startDateText = semester?.startDate ?: DefaultData.defaultSemester.startDate
    val totalWeeks = semester?.totalWeeks ?: DefaultData.defaultSemester.totalWeeks
    val targetName = state.timetables.firstOrNull { it.id == state.configTargetId }?.name.orEmpty()

    // 根因：迁到 SubpageActivity 独立窗口后没有外层 Scaffold 垫状态栏，
    // windowInsets 归零（嵌 NavHost 时期防双倍空白的老规避）会让顶栏顶进状态栏；
    // 现走 M3 默认——TopAppBar 自行消费状态栏，contentWindowInsets 管住手势条。
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("课表设置") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(
                title = if (targetName.isBlank()) "学期" else "学期 · $targetName",
                subtitle = "每张课表各有独立的开学日期与总周数（新建课表拷贝「默认配置」源）；" +
                    "默认编辑当前课表，点下方可切换",
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.timetables.forEach { timetable ->
                        FilterChip(
                            selected = timetable.id == state.configTargetId,
                            onClick = { viewModel.selectConfigTarget(timetable.id) },
                            label = {
                                Text(
                                    if (timetable.id == state.currentTimetableId) {
                                        "${timetable.name} · 当前"
                                    } else {
                                        timetable.name
                                    },
                                )
                            },
                        )
                    }
                }
                Text(
                    if (state.configSemester == null) {
                        "该课表还没配过学期，选一次开学日期即自动创建"
                    } else {
                        "该课表第 ${state.configWeek} 周 · 共 ${state.configCourseCount} 门课"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                )
                SettingItem(
                    title = "开学日期",
                    subtitle = "第 1 周周一所在周为开学周",
                    value = startDateText,
                    onClick = { datePickerOpen = true },
                )
                SettingItem(
                    title = "总周数",
                    subtitle = "周次选择器与「第 N 周」的范围",
                    value = "$totalWeeks 周",
                    onClick = { weeksPickerOpen = true },
                )
            }

            Text(
                "作息表固定编辑当前课表「${state.timetableName.ifBlank { "—" }}」，不随上方切换",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            TimeSlotEditorCard(
                slots = state.timeSlots,
                onSave = viewModel::saveTimeSlots,
                onReset = viewModel::resetTimeSlots,
            )
        }
    }

    if (datePickerOpen) {
        // rememberDatePickerState 放在 if 块内：每次打开重建，初始选中跟随当前值，
        // 避免上一次取消的选择残留
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(startDateText).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull(),
        )
        DatePickerDialog(
            onDismissRequest = { datePickerOpen = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerOpen = false
                        val picked = dateState.selectedDateMillis?.let { millis ->
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        }
                        if (picked != null) {
                            // 选完即存：日期由控件保证合法，不再需要「保存」按钮收口
                            viewModel.saveSemester(
                                picked.format(DateTimeFormatter.ISO_LOCAL_DATE),
                                totalWeeks,
                            )
                        }
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { datePickerOpen = false }) { Text("取消") }
            },
        ) {
            DatePicker(state = dateState)
        }
    }

    if (weeksPickerOpen) {
        WheelValueDialog(
            title = "总周数",
            values = (1..30).map { "$it 周" },
            initialIndex = totalWeeks - 1,
            onConfirm = { index ->
                weeksPickerOpen = false
                viewModel.saveSemester(startDateText, index + 1)
            },
            onDismiss = { weeksPickerOpen = false },
        )
    }
}
