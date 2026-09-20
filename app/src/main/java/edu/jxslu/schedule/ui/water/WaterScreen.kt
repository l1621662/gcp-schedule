package edu.jxslu.schedule.ui.water

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.qiekj.DeviceItem
import edu.jxslu.schedule.data.qiekj.OrderHistoryItem
import edu.jxslu.schedule.domain.UnlockFlowState
import edu.jxslu.schedule.domain.calculateActualCost
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.WaterUnlockButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.Droplet
import me.rerere.hugeicons.stroke.History
import me.rerere.hugeicons.stroke.Logout04
import me.rerere.hugeicons.stroke.Refresh
import me.rerere.hugeicons.stroke.Wallet01

private val DATE_FORMAT = SimpleDateFormat("MM-dd HH:mm", Locale.CHINA)

/**
 * 胖乖开水页（DESIGN 3.4 / 4.16）。
 * 交互：未登录 → 登录卡（验证码 / Token 两种）；已登录 → 余额行、设备选择、
 * 大按钮开水、状态原地切换（Idle/进行中/成功/失败）、订单快照列表。
 * 风格克制：无大圆角卡片、无 elevation，错误一行主因 + 详情弹窗。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaterScreen(
    onBack: () -> Unit = {},
    viewModel: WaterViewModel = viewModel(
        factory = WaterViewModel.Factory(Graph.qiekj(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showDeviceSheet by remember { mutableStateOf(false) }
    var detailItem by remember { mutableStateOf<Any?>(null) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                // 登录/出水/查询的结果都在页面自己的提示卡里显示（不再用系统 Toast 的黑框）；
                // 事件都源自页面内的点击与随后的异步回调，此刻不会有我们的弹层盖在上面
                is WaterEvent.Notice -> snackbar.showSnackbar(
                    AppNoticeVisuals(event.text, tone = event.tone),
                )
            }
        }
    }

    // 根因：迁到 SubpageActivity 独立窗口后没有外层 Scaffold 垫状态栏，
    // windowInsets 归零（嵌 NavHost 时期防双倍空白的老规避）会让顶栏顶进状态栏；
    // 现走 M3 默认——TopAppBar 自行消费状态栏，contentWindowInsets 管住手势条。
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("胖乖生活") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
                    }
                },
                actions = {
                    if (state.loggedIn) {
                        IconButton(onClick = { viewModel.logout() }) {
                            Icon(HugeIcons.Logout04, contentDescription = "退出登录")
                        }
                    }
                },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (!state.loggedIn) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                LoginSection(state = state, viewModel = viewModel)
                Spacer(Modifier.height(24.dp))
                Disclaimer()
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BalanceRow(state = state, onRefresh = { viewModel.refreshBalance(); viewModel.refreshDevices() })

                // 设备行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = state.flow is UnlockFlowState.Idle && state.devices.isNotEmpty()) {
                            showDeviceSheet = true
                        }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        HugeIcons.Droplet,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = state.selectedDevice?.goodsName?.ifBlank { "未命名设备" } ?: "暂无设备",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    if (state.loadingDevices) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            "更换",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                UnlockStatusArea(
                    state = state,
                    onToggleUsePoints = { viewModel.toggleUsePoints() },
                    onShowDetail = { detailItem = it },
                )

                when (val flow = state.flow) {
                    is UnlockFlowState.Idle -> WaterUnlockButton(
                        enabled = state.selectedDevice != null,
                        onUnlock = { viewModel.unlock() },
                        fillMaxWidth = true,
                        height = 48.dp,
                    )
                    is UnlockFlowState.PreChecking, is UnlockFlowState.Working -> Button(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("开水进行中…") }
                    is UnlockFlowState.Success -> OutlinedButton(
                        onClick = { viewModel.dismissFlow() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) { Text("完成") }
                    is UnlockFlowState.Failed -> Row(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { viewModel.unlock() },
                            modifier = Modifier.weight(1f).height(48.dp),
                        ) { Text("重试") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = { viewModel.dismissFlow() },
                            modifier = Modifier.height(48.dp),
                        ) { Text("关闭") }
                    }
                }

                OrderSection(
                    orders = state.orderHistory,
                    onClick = { detailItem = it },
                )

                Spacer(Modifier.height(8.dp))
                Disclaimer()
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showDeviceSheet) {
        DeviceSheet(
            devices = state.devices,
            selected = state.selectedDevice,
            onSelect = {
                viewModel.selectDevice(it)
                showDeviceSheet = false
            },
            onDismiss = { showDeviceSheet = false },
        )
    }

    when (val d = detailItem) {
        is UnlockFlowState.Failed -> ErrorDetailDialog(item = d, onDismiss = { detailItem = null })
        is edu.jxslu.schedule.domain.UnlockResult -> OrderDetailDialog(item = d, onDismiss = { detailItem = null })
        is OrderHistoryItem -> OrderDetailDialog(item = toResult(d), onDismiss = { detailItem = null })
        null -> {}
    }
}

// ── 登录 ──

@Composable
private fun LoginSection(state: WaterUiState, viewModel: WaterViewModel) {
    Text("登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(10.dp))
    OutlinedTextField(
        value = state.phone,
        onValueChange = viewModel::updatePhone,
        label = { Text("手机号") },
        isError = state.phoneError != null,
        supportingText = state.phoneError?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.code,
            onValueChange = viewModel::updateCode,
            label = { Text("验证码") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Button(onClick = { viewModel.sendCode() }, enabled = !state.sendingCode && state.phone.isNotBlank()) {
            Text(if (state.sendingCode) "发送中" else "发送验证码")
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = { viewModel.login() },
        enabled = !state.loggingIn && state.phone.isNotBlank() && state.code.isNotBlank(),
        modifier = Modifier.fillMaxWidth().height(48.dp),
    ) {
        Text("登录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
    Spacer(Modifier.height(14.dp))
    HorizontalDivider()
    Spacer(Modifier.height(14.dp))
    if (!state.showTokenLogin) {
        OutlinedButton(onClick = { viewModel.toggleTokenLogin() }, modifier = Modifier.fillMaxWidth()) {
            Text("Token 登录")
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "已从其他渠道拿到 Token 时可直接粘贴登录；手机号登录会使旧 Token 失效。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    } else {
        OutlinedTextField(
            value = state.tokenLoginInput,
            onValueChange = viewModel::updateTokenLoginInput,
            label = { Text("Token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { viewModel.loginWithToken() },
            enabled = !state.tokenLoggingIn && state.tokenLoginInput.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp),
        ) { Text("Token 登录") }
    }
}

@Composable
private fun Disclaimer() {
    Text(
        "胖乖功能模拟客户端操作，仅供学习测试，可能违反平台服务条款；账号与封禁风险自负。本应用与学校、胖乖官方均无关。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
    )
}

// ── 已登录内容 ──

@Composable
private fun BalanceRow(state: WaterUiState, onRefresh: () -> Unit) {
    val balance = state.balance
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            HugeIcons.Wallet01,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "小票 ¥${balance?.ticketText ?: "-"} · 积分 ${balance?.pointsText ?: "-"}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.weight(1f))
        IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
            if (state.loadingBalance || state.loadingDevices) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            } else {
                Icon(HugeIcons.Refresh, contentDescription = "刷新", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** 开水状态区：Idle 显示积分抵扣开关，其余状态原地切换，不弹新卡。 */
@Composable
private fun UnlockStatusArea(
    state: WaterUiState,
    onToggleUsePoints: () -> Unit,
    onShowDetail: (Any) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        when (val flow = state.flow) {
            is UnlockFlowState.Idle -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("使用积分抵扣", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "关闭后开水不消耗积分",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                Switch(checked = state.usePoints, onCheckedChange = { onToggleUsePoints() })
            }
            is UnlockFlowState.PreChecking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(10.dp))
                Text(
                    flow.step,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                )
            }
            is UnlockFlowState.Working -> Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "正在出水 ${formatClock(flow.elapsedSeconds)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "${(WaterViewModel.AUTO_SETTLE_SECONDS - flow.elapsedSeconds).coerceAtLeast(0)} 秒后自动关闭",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = {
                        (flow.elapsedSeconds.toFloat() / WaterViewModel.AUTO_SETTLE_SECONDS).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    flow.step,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            is UnlockFlowState.Success -> Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "花费 ¥${calculateActualCost(flow.result)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "订单详情 ›",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onShowDetail(flow.result) },
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    listOf(
                        "小票 ${flow.result.ticketCost}",
                        "积分 ${flow.result.integralCost}".takeIf { flow.result.integralCost != "-" },
                    ).filterNotNull().joinToString(" · ").ifBlank { "开水成功" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            is UnlockFlowState.Failed -> Column {
                Text(flow.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "失败步骤：${flow.step}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "查看详情 ›",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { onShowDetail(flow) },
                    )
                }
            }
        }
    }
}

private fun formatClock(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

// ── 弹层与列表 ──

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(
    devices: List<DeviceItem>,
    selected: DeviceItem?,
    onSelect: (DeviceItem) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Text("选择开水设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(12.dp))
            if (devices.isEmpty()) {
                Text(
                    "暂无设备，请确认已在胖乖生活用过饮水机后下拉刷新",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
            }
            devices.forEach { device ->
                val isSelected = device.effectiveGoodsId == selected?.effectiveGoodsId
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(device) }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        HugeIcons.Droplet,
                        contentDescription = null,
                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        device.goodsName.ifBlank { "未命名设备" },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderSection(orders: List<OrderHistoryItem>, onClick: (OrderHistoryItem) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            HugeIcons.History,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            "历史订单（本地快照）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
        )
    }
    if (orders.isEmpty()) {
        Text(
            "暂无订单记录",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f),
        )
        return
    }
    orders.take(20).forEach { order ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onClick(order) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    order.goodsName,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    DATE_FORMAT.format(Date(order.completedAt)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
            }
            Text(
                "¥${calculateActualCost(toResult(order))}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

private fun toResult(item: OrderHistoryItem) = edu.jxslu.schedule.domain.UnlockResult(
    orderNo = item.orderNo,
    orderId = item.orderId,
    originPrice = item.originPrice,
    ticketCost = item.ticketCost,
    integralCost = item.integralCost,
    otherPromotions = item.otherPromotions,
    completedAt = item.completedAt,
)

@Composable
private fun ErrorDetailDialog(item: UnlockFlowState.Failed, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("失败详情") },
        text = {
            Column {
                Text(item.message, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "失败步骤：${item.step}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                )
                if (item.rawError.isNotBlank() && item.rawError != item.message) {
                    Text(
                        "错误详情：${item.rawError}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    )
                }
                item.suggestions.forEach { s ->
                    Spacer(Modifier.height(4.dp))
                    Text("• $s", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

@Composable
private fun OrderDetailDialog(item: edu.jxslu.schedule.domain.UnlockResult, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("订单详情") },
        text = {
            Column {
                DetailRow("订单号", item.orderNo)
                DetailRow("原价", "¥${item.originPrice}")
                DetailRow("小票", item.ticketCost)
                if (item.integralCost != "-") DetailRow("积分", item.integralCost)
                item.otherPromotions.forEach { p ->
                    DetailRow("其他优惠", p.discountAmount ?: "-")
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                DetailRow("实付", "¥${calculateActualCost(item)}")
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.width(64.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
