package edu.jxslu.schedule.ui.campus

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer

/**
 * 校园卡充值/到账共享 UI（DESIGN §4.19「充值」、§3.10）。
 *
 * 设置页与今日页弹窗共用：[RechargeSheet]（金额弹层+二次确认）与
 * [CampusArrivalDialog]（到账成功弹窗）都是无状态组件，下单/到账逻辑在
 * [CampusCardViewModel]；[CampusEntrySheet] 是今日页点余额弹出的功能入口弹层。
 */

/** 充值金额弹层 + 二次确认。通过校验后 [onLaunch] 回调元金额（两位小数字符串）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RechargeSheet(
    balanceFen: Long?,
    onDismiss: () -> Unit,
    onLaunch: (String) -> Unit,
) {
    var amount by rememberSaveable { mutableStateOf("") }
    var confirmStep by remember { mutableStateOf(false) }

    val parsed = amount.toBigDecimalOrNull()
    val valid = parsed != null && parsed >= java.math.BigDecimal("0.01") &&
        parsed <= java.math.BigDecimal("500.00")

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("校园卡充值", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            balanceFen?.let {
                Text(
                    "当前卡余额 ¥%.2f".format(it / 100.0),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                )
            }
            androidx.compose.material3.OutlinedTextField(
                value = amount,
                onValueChange = { raw ->
                    // 两位小数内、一位小数点；空串放行
                    val cleaned = raw.filter { c -> c.isDigit() || c == '.' }
                    amount = if (cleaned.count { it == '.' } > 1) amount else {
                        val dot = cleaned.indexOf('.')
                        if (dot >= 0 && cleaned.length - dot - 1 > 2) amount else cleaned
                    }
                },
                label = { Text("充值金额（元）") },
                supportingText = {
                    Text(
                        when {
                            amount.isEmpty() -> "0.01 – 500.00 元；将直接拉起微信支付"
                            !valid -> "金额需在 0.01 – 500.00 元之间"
                            else -> "确认后在微信内完成支付"
                        },
                    )
                },
                isError = amount.isNotEmpty() && !valid,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("20", "50", "100", "200").forEach { preset ->
                    androidx.compose.material3.OutlinedButton(
                        onClick = { amount = preset },
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(0.dp),
                    ) { Text(preset) }
                }
            }
            androidx.compose.material3.Button(
                onClick = { confirmStep = true },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("下一步") }
        }
    }

    if (confirmStep) {
        AlertDialog(
            onDismissRequest = { confirmStep = false },
            title = { Text("确认充值金额？") },
            text = {
                Text(
                    "将为校园卡账户充值 ¥$amount。\n\n" +
                        "点击「去支付」会直接拉起微信（微信充值渠道），在微信内确认支付；" +
                        "未支付的订单会自动失效，不会扣款。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStep = false
                    onLaunch(amount)
                }) { Text("去支付") }
            },
            dismissButton = {
                TextButton(onClick = { confirmStep = false }) { Text("取消") }
            },
        )
    }
}

/** 充值成功弹窗（到账状态机 Arrived 时显示）；[onOpenPayCode] 一键跳付款码页（可为空则不显示该按钮）。 */
@Composable
fun CampusArrivalDialog(
    arrived: CampusCardViewModel.ArrivalState.Arrived,
    onDismiss: () -> Unit,
    onOpenPayCode: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("充值成功") },
        text = {
            Text(
                buildString {
                    append("¥%.2f 已到账".format(arrived.orderFen / 100.0))
                    arrived.newBalanceFen?.let {
                        append("，当前卡余额 ¥%.2f".format(it / 100.0))
                    }
                    append("。")
                    append("\n\n本次交易已同步至「消费流水」。")
                },
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onOpenPayCode?.invoke()
            }) { Text("查看付款码") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
    )
}

/**
 * 「正在确认到账」弹窗（DESIGN §4.19「充值」）：微信支付完成返回后、余额尚未更新时给出
 * 反馈——说明在每 5 秒自动检测，确认到账后会再弹成功弹窗；用户关闭**不影响**后台轮询。
 */
@Composable
fun CampusPendingConfirmDialog(
    orderFen: Long,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("支付完成，正在确认到账") },
        text = {
            Text(
                "已收到 ¥%.2f 的充值支付，校园卡系统余额更新有延迟（常见数分钟）。\n\n".format(orderFen / 100.0) +
                    "App 正在每 5 秒自动检测，确认到账后会立即提示；您可以先做别的，" +
                    "关闭本提示不影响检测。",
            )
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/**
 * 今日页点余额弹出的功能入口弹层（DESIGN §3.10）：当前余额 + 充值/消费流水/认证码三入口。
 * 卡片整体点击仍直达付款码页（不变）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusEntrySheet(
    balanceFen: Long?,
    onDismiss: () -> Unit,
    onOpenRecharge: () -> Unit,
    onOpenStatement: () -> Unit,
    onOpenPayCode: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("水宝宝一卡通", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = balanceFen?.let { "当前卡余额 ¥%.2f".format(it / 100.0) } ?: "余额获取中…",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            EntryRow("充值", "微信充值，支付完成后自动检测到账", onOpenRecharge)
            EntryRow("消费流水", "当月收支与交易记录", onOpenStatement)
            EntryRow("认证码（付款码）", "出示后扫码消费，等同现金", onOpenPayCode)
        }
    }
}

@Composable
private fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }
    }
}
