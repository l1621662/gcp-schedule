package edu.gcp.schedule.ui.tweak

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.gcp.schedule.Graph
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.domain.TweakMode
import edu.gcp.schedule.ui.common.AppSnackbarHost
import edu.gcp.schedule.ui.common.SettingsSection
import edu.gcp.schedule.domain.compactPosition
import edu.gcp.schedule.ui.common.courseColor
import edu.gcp.schedule.ui.common.rememberAppHaptics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import edu.gcp.schedule.domain.MONTH_DAY_FORMAT
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowDownDouble
import me.rerere.hugeicons.stroke.ArrowLeftRight
import me.rerere.hugeicons.stroke.Calendar03

/**
 * 调课页（DESIGN §4.11）：选「被调整日期」与「调整到日期」，预览两侧课程，选模式后执行。
 *
 * 与拾光的差别（详见 DESIGN）：本页固定作用于**当前课表**，不做跨课表调课；
 * 覆盖/交换属破坏性操作，执行前弹一次确认（拾光直接生效）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseTweakScreen(
    onBack: () -> Unit,
    viewModel: CourseTweakViewModel = viewModel(
        factory = CourseTweakViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val message by viewModel.messages.collectAsStateWithLifecycle()
    val undoableMessage by viewModel.undoableMessage.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberAppHaptics()

    var fromPickerOpen by remember { mutableStateOf(false) }
    var toPickerOpen by remember { mutableStateOf(false) }
    var confirmOpen by remember { mutableStateOf(false) }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    // 调课成功 → Snackbar 带「撤销」；覆盖/交换误操作有 5 秒后悔窗口
    LaunchedEffect(undoableMessage) {
        undoableMessage?.let { m ->
            val result = snackbar.showSnackbar(m.text, actionLabel = "撤销", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) m.undo()
            viewModel.consumeUndoableMessage()
        }
    }

    val execute: () -> Unit = {
        haptics.tap()
        if (state.mode.destructive) confirmOpen = true else viewModel.execute()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("调课") },
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
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(
                title = "日期",
                subtitle = "作用课表：${state.timetableName.ifBlank { "—" }}（跨课表请先在课表页切换）",
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    DateCard(
                        label = "被调整日期",
                        slice = state.from,
                        modifier = Modifier.weight(1f),
                        onClick = { fromPickerOpen = true },
                    )
                    DateCard(
                        label = "调整到日期",
                        slice = state.to,
                        modifier = Modifier.weight(1f),
                        onClick = { toPickerOpen = true },
                    )
                }
            }

            SettingsSection(
                title = "方式",
                subtitle = state.mode.description,
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TweakMode.entries.forEach { mode ->
                        FilterChip(
                            selected = state.mode == mode,
                            onClick = {
                                haptics.toggle()
                                viewModel.setMode(mode)
                            },
                            label = { Text(mode.label) },
                            leadingIcon = {
                                Icon(
                                    imageVector = when (mode) {
                                        TweakMode.Merge -> HugeIcons.ArrowDown01
                                        TweakMode.Overwrite -> HugeIcons.ArrowDownDouble
                                        TweakMode.Exchange -> HugeIcons.ArrowLeftRight
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                    }
                }
            }

            // 两侧预览：源在上、目标在下，与「从上往下搬运」的直觉一致
            CoursePreviewCard(
                label = "被调整的课程",
                slice = state.from,
                slots = state.slots,
            )
            CoursePreviewCard(
                label = "调整到日期的课程",
                slice = state.to,
                slots = state.slots,
            )

            state.blockReason?.let { reason ->
                Text(
                    reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            Button(
                onClick = execute,
                enabled = state.canExecute,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(if (state.running) "执行中…" else "执行调课")
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    if (fromPickerOpen) {
        DatePickDialog(
            initial = state.fromDate,
            onPick = { viewModel.setFromDate(it); fromPickerOpen = false },
            onDismiss = { fromPickerOpen = false },
        )
    }
    if (toPickerOpen) {
        DatePickDialog(
            initial = state.toDate,
            onPick = { viewModel.setToDate(it); toPickerOpen = false },
            onDismiss = { toPickerOpen = false },
        )
    }

    if (confirmOpen) {
        val purgedCount = state.to.courses.count { it.weeks.size <= 1 }
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("确认${state.mode.label}？") },
            text = {
                Text(
                    buildString {
                        append("${dateText(state.fromDate)}（${state.from.courses.size} 门）→ ")
                        append("${dateText(state.toDate)}（${state.to.courses.size} 门）\n\n")
                        when (state.mode) {
                            TweakMode.Overwrite -> {
                                append("目标日期当周的课程将被清空")
                                if (state.to.courses.isNotEmpty()) {
                                    append("（其中 $purgedCount 门课只在这一周有课，将被整门删除）")
                                }
                                append("，随后放入源日期的课程。不可撤销。")
                            }
                            TweakMode.Exchange -> {
                                append("两个日期的课程将互换位置，不可撤销。")
                            }
                            TweakMode.Merge -> Unit
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOpen = false
                        viewModel.execute()
                    },
                ) { Text("执行", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("取消") }
            },
        )
    }
}

private fun dateText(date: LocalDate): String = date.format(MONTH_DAY_FORMAT)

/** 一张日期卡：标题、日期、周次与星期；不在学期内时整卡标红。 */
@Composable
private fun DateCard(
    label: String,
    slice: DaySlice,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    val invalid = slice.week == null
    Card(
        modifier = modifier.clickable {
            haptics.tap()
            onClick()
        },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (invalid) {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(top = 4.dp),
            ) {
                Text(
                    dateText(slice.date),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Icon(
                    HugeIcons.Calendar03,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
            Text(
                text = if (slice.week != null) {
                    "${weekdayLabel(slice.day)} · 第 ${slice.week} 周"
                } else {
                    "不在本学期"
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (invalid) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                },
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 某一天的课程预览：无课时给一句明确说明，不留空白卡让人猜。 */
@Composable
private fun CoursePreviewCard(
    label: String,
    slice: DaySlice,
    slots: List<TimeSlot>,
) {
    SettingsSection(title = label, subtitle = null) {
        if (slice.courses.isEmpty()) {
            Text(
                "无课程",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            slice.courses.forEach { course ->
                PreviewRow(course = course, slots = slots)
            }
        }
    }
}

@Composable
private fun PreviewRow(course: Course, slots: List<TimeSlot>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .width(3.dp)
                .height(32.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(courseColor(course.colorIndex)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                course.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                metaLine(slots, course),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 周次：调课看的就是「这门课在哪些周」，加上去才知道动的是全局还是单周
        Text(
            weeksShort(course.weeks),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

/** `第2-4节 · 10:15–11:40 · @南B208` —— 时刻走 courseStart/EndMinutes 唯一口径。 */
private fun metaLine(slots: List<TimeSlot>, course: Course): String {
    val start = ScheduleCalculator.courseStartMinutes(slots, course)
    val end = ScheduleCalculator.courseEndMinutes(slots, course)
    val clock = if (start != null && end != null) "${clock(start)}–${clock(end)}" else null
    val section = if (course.endSection > course.startSection) {
        "第${course.startSection}-${course.endSection}节"
    } else {
        "第${course.startSection}节"
    }
    val place = compactPosition(course.position).takeIf { it.isNotBlank() }?.let { "@$it" }
    return listOfNotNull(section, clock, place, course.teacher.takeIf { it.isNotBlank() })
        .joinToString(" · ")
}

private fun clock(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun weeksShort(weeks: Set<Int>): String {
    val text = ScheduleCalculator.formatWeeks(weeks)
    return if (text.isBlank()) "" else "${text}周"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatePickDialog(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    // rememberDatePickerState 放在调用处包着的 if 块内：每次打开重建，初始选中跟随当前值，
    // 避免上一次取消的选择残留（与课表设置页同一处理）。
    val dateState = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    dateState.selectedDateMillis?.let { millis ->
                        onPick(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    } ?: onDismiss()
                },
            ) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    ) {
        DatePicker(state = dateState)
    }
}
