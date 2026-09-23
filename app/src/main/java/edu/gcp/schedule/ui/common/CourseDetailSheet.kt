package edu.gcp.schedule.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.CourseKind
import edu.gcp.schedule.domain.ScheduleCalculator
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.domain.compactPosition

/**
 * 课程详情（只读）。
 * 点课程直接进编辑页容易误触，这里先给一层只读信息，编辑/删除都是二级动作。
 *
 * 2026-09-19 自 WeekSheets 迁入 common：今日页点击课程（焦点卡/时间轴行/明天行）改为
 * 弹本面板而非直跳编辑器（DESIGN §3.3），与课表页口径一致。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: Course,
    slots: List<TimeSlot>,
    currentWeek: Int,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val accent = courseColor(course.colorIndex)
    val start = slots.firstOrNull { it.number == course.startSection }
    val end = slots.firstOrNull { it.number == course.endSection }
    val timeText = buildString {
        append("第 ${course.startSection}")
        if (course.endSection != course.startSection) append("-${course.endSection}")
        append(" 节")
        if (start != null && end != null) append("  ${start.startTime}–${end.endTime}")
    }
    val weeksText = ScheduleCalculator.formatWeeks(course.weeks)
    val inThisWeek = currentWeek in course.weeks

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(accent),
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = course.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            DetailRow("教师", course.teacher.ifBlank { "—" })
            DetailRow("地点", compactPosition(course.position).ifBlank { "—" })
            DetailRow("时间", timeText)
            DetailRow(
                "周次",
                buildString {
                    append(if (weeksText.isBlank()) "—" else "第 $weeksText 周")
                    if (weeksText.isNotBlank()) {
                        append("（共 ${course.weeks.size} 周")
                        append(if (inThisWeek) "，本周有课）" else "，本周无课）")
                    }
                },
            )
            // 类型单列一行：实验课的来源是另一张课表（教务不提供教师），
            // 考试的时刻与普通课不同（具体日期+起止时间），都需要向用户交代口径
            when (course.kind) {
                CourseKind.Lab -> DetailRow("类型", "实验课（实验课表页不含教师信息）")
                CourseKind.Exam -> DetailRow("类型", "考试（考场即「地点」）")
                else -> Unit
            }
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // 删除用浅底 + 错误色，编辑用实心主色：主次分明，也避免误触删除
                Button(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Text("删除")
                }
                Button(
                    onClick = onEdit,
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp),
                ) {
                    Text("编辑")
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.size(width = 44.dp, height = 20.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
