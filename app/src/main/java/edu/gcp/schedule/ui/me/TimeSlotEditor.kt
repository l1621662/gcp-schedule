package edu.gcp.schedule.ui.me

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import edu.gcp.schedule.data.DefaultData
import edu.gcp.schedule.domain.TimeSlot
import edu.gcp.schedule.domain.TimeSlotRules
import edu.gcp.schedule.ui.common.SettingsSection
import edu.gcp.schedule.ui.common.TimeWheelDialog
import edu.gcp.schedule.ui.common.rememberAppHaptics

/**
 * 作息表编辑。
 *
 * 作息不只是「展示用的时间」：课表网格行高、当前时刻线、「还剩多少分钟」、下一节判定
 * 全部由它推算，所以它在 DESIGN 3.3 里写了「可在设置改」，但一直没有入口——
 * 学校一调作息，整个 App 的时间就是错的，只能等发版。
 *
 * 只允许改时间，**不允许增删节次**：节次数量与大节分组（1-2 / 3-4 / 5-6 / 7-8 / 9-11）
 * 是课表结构的一部分，`Course.startSection/endSection` 存的也是节次号，
 * 改行数会让已导入的课程全部对不上位置。
 *
 * 交互重构：时间从手输文本框改为**滚轮弹窗**（对齐拾光）——
 * 时/分双列滚轮只产出合法 HH:mm，格式校验整体退场；
 * 保存按钮保留：作息表是 11 行整体提交 + validate 顺序校验，逐行即时存会产生中间非法态
 * （如第 3 节结束早于开始），整体保存才能保证落库的永远是一张一致的时间表。
 */
@Composable
fun TimeSlotEditorCard(
    slots: List<TimeSlot>,
    onSave: (List<TimeSlot>) -> Unit,
    onReset: () -> Unit,
) {
    val haptics = rememberAppHaptics()
    var resetConfirm by remember { mutableStateOf(false) }
    /** 正在编辑的行与字段；null = 关闭弹窗。 */
    var editing by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }

    // 以 slots 为 key 记住草稿：保存成功后 slots 变化会自动回填，
    // 而编辑过程中（校验失败、保存失败）slots 不变，用户刚选的内容不会被冲掉。
    var drafts by remember(slots) {
        mutableStateOf(slots.sortedBy { it.number }.map { it.startTime to it.endTime })
    }
    val rowCount = maxOf(drafts.size, DefaultData.defaultTimeSlots.size)

    SettingsSection(
        title = "作息表",
        subtitle = "默认每小节 40 分钟，大节内休息 5 分钟、大节间 20 分钟换教室。" +
            "课表行高与时刻线都按这张表推算；改过之后不会被版本升级覆盖",
    ) {
        for (index in 0 until rowCount) {
            val draft = drafts.getOrNull(index) ?: ("" to "")
            TimeSlotRow(
                number = index + 1,
                start = draft.first.ifBlank { "—" },
                end = draft.second.ifBlank { "—" },
                onStartClick = {
                    haptics.tap()
                    editing = index to true
                },
                onEndClick = {
                    haptics.tap()
                    editing = index to false
                },
            )
        }
        Button(
            onClick = {
                haptics.tap()
                onSave(drafts.toTimeSlots())
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text("保存作息")
        }
        OutlinedButton(
            onClick = {
                haptics.tap()
                resetConfirm = true
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            Text("恢复默认作息")
        }
    }

    editing?.let { (index, isStart) ->
        val initial = drafts.getOrNull(index)?.let { if (isStart) it.first else it.second }.orEmpty()
        TimeWheelDialog(
            title = "第 ${index + 1} 节 · ${if (isStart) "上课" else "下课"}时间",
            initial = initial.ifBlank { "08:30" },
            onConfirm = { value ->
                drafts = drafts.replaceAt(index) { pair ->
                    if (isStart) value to pair.second else pair.first to value
                }
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("恢复默认作息？") },
            text = {
                Text(
                    "将把 11 个小节改回学校默认时间（08:30 起、每节 40 分钟），" +
                        "当前未保存的修改会丢弃。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        resetConfirm = false
                        onReset()
                    },
                ) { Text("恢复默认") }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun TimeSlotRow(
    number: Int,
    start: String,
    end: String,
    onStartClick: () -> Unit,
    onEndClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "第 $number 节",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            modifier = Modifier.weight(1f),
        )
        // 固定宽度 + 居中：时间值是文本自然宽，行间右列会参差；定宽后 11 行的起止列严格对齐
        TimeChip(value = start, onClick = onStartClick, modifier = Modifier.width(56.dp))
        Text(
            text = "–",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
            modifier = Modifier.width(18.dp),
            textAlign = TextAlign.Center,
        )
        TimeChip(value = end, onClick = onEndClick, modifier = Modifier.width(56.dp))
    }
}

/** 时间值胶囊：点击弹滚轮。定宽居中，保证各行时间列对齐。 */
@Composable
private fun TimeChip(value: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
    )
}

/** 草稿 → 领域对象：滚轮产出必为 HH:mm，padTime 只做兜底。 */
private fun List<Pair<String, String>>.toTimeSlots(): List<TimeSlot> =
    mapIndexed { index, (start, end) ->
        TimeSlot(index + 1, TimeSlotRules.padTime(start), TimeSlotRules.padTime(end))
    }

/** 就地替换第 index 行，行数不足时补空行——首帧 drafts 还没回填时用户就点了一行也能接住。 */
private fun List<Pair<String, String>>.replaceAt(
    index: Int,
    transform: (Pair<String, String>) -> Pair<String, String>,
): List<Pair<String, String>> {
    val out = toMutableList()
    while (out.size <= index) out += "" to ""
    out[index] = transform(out[index])
    return out
}
