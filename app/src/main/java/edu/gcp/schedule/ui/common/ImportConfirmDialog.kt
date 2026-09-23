package edu.gcp.schedule.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import edu.gcp.schedule.data.repo.ImportStats
import edu.gcp.schedule.data.repo.ScheduleRepository
import edu.gcp.schedule.domain.Course
import edu.gcp.schedule.domain.Timetable

/**
 * 导入目标。用户拍板：导入落点**每次强制选择**——既可导入已有课表，也可新建一张。
 */
sealed interface ImportTarget {
    data class Existing(val id: Long) : ImportTarget
    data class New(val name: String) : ImportTarget
}

/**
 * 导入目标选择弹窗（教务解析结果 / JSON 文件 / 剪贴板三处共用）。
 *
 * 职责：目标课表单选（默认预选当前课表）+「新建课表…」入口 + 覆盖/合并模式，
 * 并按所选目标实时展示覆盖/合并统计。统计由 [ScheduleRepository.importStatsFor]
 * 按目标课表的 mergeKey 现算，切目标即刷新。
 *
 * 根因（为什么要强制选择）：多课表之后「导入到当前课表」不再是唯一合理解释——
 * 用户完全可能想把教务课表导进一张全新的空表；默认静默写入当前课表会覆盖掉正在用的数据。
 */
@Composable
fun ImportTargetDialogHost(
    courses: List<Course>?,
    title: String,
    defaultMerge: Boolean,
    onConfirm: (target: ImportTarget, merge: Boolean) -> Unit,
    onDismiss: () -> Unit,
    repo: ScheduleRepository,
    /** 数据声明的学年学期（如 2026-2027-1），来自教务页面或 JSON 顶层；空则不展示。 */
    term: String? = null,
    /** 需要确认前知情的补充说明（如估算周次口径）；空则不展示。 */
    note: String? = null,
) {
    val timetables by repo.timetables.collectAsStateWithLifecycle(emptyList())
    val currentId by repo.currentTimetableId.collectAsStateWithLifecycle(0L)

    // 每个目标课表的统计现算一次（内存里的 mergeKey 对比，量级是几十门课，可忽略）
    var stats by remember { mutableStateOf<Map<Long, ImportStats>>(emptyMap()) }
    LaunchedEffect(courses, timetables) {
        val list = courses ?: return@LaunchedEffect
        stats = timetables.associate { it.id to repo.importStatsFor(list, it.id) }
    }

    if (courses == null) return

    var selectedId by remember(courses) { mutableStateOf<Long?>(currentId.takeIf { id -> timetables.any { it.id == id } }) }
    var createNew by remember(courses) { mutableStateOf(timetables.isEmpty()) }
    var newName by remember(courses) { mutableStateOf("") }
    var merge by remember(courses) { mutableStateOf(defaultMerge) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!term.isNullOrBlank()) {
                    Text(
                        "数据学期：$term",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                if (!note.isNullOrBlank()) {
                    Text(
                        note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(
                    "共 ${courses.size} 门课。示例：${courses.take(3).joinToString { it.name }}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
                Text("导入到哪张课表？", style = MaterialTheme.typography.titleSmall)

                Column(
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    timetables.forEach { t ->
                        val selected = !createNew && selectedId == t.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    createNew = false
                                    selectedId = t.id
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = selected, onClick = {
                                createNew = false
                                selectedId = t.id
                            })
                            Column(Modifier.weight(1f)) {
                                Text(
                                    t.name + if (t.id == currentId) "（当前）" else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                stats[t.id]?.let { s ->
                                    Text(
                                        "已有 ${s.existing} 门 · 合并将新增 ${s.newCount} 门",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                    )
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { createNew = true }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = createNew, onClick = { createNew = true })
                        Text("新建课表…", style = MaterialTheme.typography.bodyMedium)
                    }
                }

                if (createNew) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("新课表名称") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !merge,
                        onClick = { merge = false },
                        label = { Text("覆盖") },
                    )
                    FilterChip(
                        selected = merge,
                        onClick = { merge = true },
                        label = { Text("合并") },
                    )
                }
                Text(
                    if (merge) {
                        "合并：按课程名+星期+节次+教师+类型去重后追加"
                    } else {
                        "覆盖：清空目标课表后写入全部课程，不可撤销"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (merge) {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
        },
        confirmButton = {
            val enabled = if (createNew) newName.isNotBlank() else selectedId != null
            TextButton(
                enabled = enabled,
                onClick = {
                    val target = if (createNew) {
                        ImportTarget.New(newName.trim())
                    } else {
                        // enabled 已守卫非空；这里再防一手，不靠 selectedId!! 赌时序
                        ImportTarget.Existing(selectedId ?: return@TextButton)
                    }
                    onConfirm(target, merge)
                },
            ) { Text("导入") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

/** 统一解析导入目标为课表 id：新建时先建表（配置取默认配置源）。 */
suspend fun resolveImportTarget(repo: ScheduleRepository, target: ImportTarget): Long =
    when (target) {
        is ImportTarget.Existing -> target.id
        is ImportTarget.New -> repo.createTimetable(target.name)
    }
