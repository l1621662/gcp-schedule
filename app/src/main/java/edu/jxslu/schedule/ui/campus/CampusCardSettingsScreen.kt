package edu.jxslu.schedule.ui.campus

import android.content.Context
import android.app.Activity
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.ykt.YktClient
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.data.ykt.YktException
import edu.jxslu.schedule.data.ykt.YktRechargeOrder
import edu.jxslu.schedule.data.ykt.YktRepository
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeFeedback
import edu.jxslu.schedule.ui.common.NoticeTone
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.font.FontWeight

/**
 * 我的 → 校园卡（DESIGN §3.10）。管开关与凭证，不看付款码。
 *
 * 凭证交互与「调课自动检测」（§4.17）同口径（用户拍板的文案语义）：
 * **默认关闭**；开启 = 输入学号密码先真实登录验证一次，成功才落库并置开关；
 * 关闭 = 二次确认后清除凭证。密码框留空 = 沿用已保存的密码（覆盖场景才需要重输）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CampusCardSettingsScreen(
    onBack: () -> Unit,
    /** 跳消费流水页（DESIGN §4.19 B4/B5） */
    onOpenStatement: () -> Unit = {},
    /** 跳付款码页（充值成功弹窗「查看付款码」直达） */
    onOpenPayCode: () -> Unit = {},
    viewModel: CampusCardViewModel = viewModel(
        factory = CampusCardViewModel.Factory(LocalContext.current.applicationContext),
    ),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val arrivalState by viewModel.arrivalState.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // 超时安抚提示（只弹一次：Timeout 状态被确认后转 Idle）
    androidx.compose.runtime.LaunchedEffect(arrivalState) {
        val st = arrivalState
        if (st is CampusCardViewModel.ArrivalState.Timeout) {
            snackbar.showSnackbar(
                AppNoticeVisuals(
                    "暂未检测到到账——充值常有延迟，到账后「消费流水」会自动显示",
                    tone = NoticeTone.Info,
                ),
            )
            viewModel.dismissArrival()
        }
    }

    // 从微信/浏览器返回的瞬间立即补检一轮（不等 5 秒周期）；同时兜进程被杀重启恢复
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onHostResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    var showRechargeSheet by remember { mutableStateOf(false) }

    fun feedback(notice: NoticeFeedback) {
        busy = false
        if (notice.tone != NoticeTone.Error) password = ""
        scope.launch {
            snackbar.showSnackbar(AppNoticeVisuals(notice.text, tone = notice.tone))
        }
    }

    Scaffold(
        // 与其余二级页同口径：M3 默认 inset（本页跑在 SubpageActivity 独立窗口里，无外层垫 inset）
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("水宝宝一卡通") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(
                title = "水宝宝一卡通",
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("在今日页显示付款码入口", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (enabled) "已开启 · 今日页底部显示入口" else "默认关闭",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            )
                        }
                        if (busy) {
                            // size 而非 height：height-only 约束下 40×40 的圆环被画成 40×24 椭圆
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Spacer(Modifier.width(12.dp))
                        }
                        Switch(
                            checked = enabled,
                            onCheckedChange = { wantOn ->
                                if (busy) return@Switch
                                if (wantOn) {
                                    busy = true
                                    viewModel.saveAndEnable(username, password, ::feedback)
                                } else {
                                    confirmDisable = true
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "付款码等同现金，会随时变更，请勿截图或分享给他人，否则可能被盗刷。" +
                            "学号与密码仅保存在本机（Android Keystore 加密存储），不进入云备份、" +
                            "不发送到任何第三方服务器；可随时关闭本功能，关闭即清除已保存的凭证。" +
                            "若因启用本功能造成损失，开发者概不负责。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                },
            )

            // 余额状态区（DESIGN §4.19 B5）：开启即显示（骨架占位避免进页跳动），点击跳流水页
            val balanceSnapshot = balance
            if (enabled) {
                SettingsCard(
                    title = "余额",
                    content = {
                        if (balanceSnapshot != null) {
                            Text(
                                "卡余额 ¥%.2f".format(balanceSnapshot.totalFen / 100.0) +
                                    if (balanceSnapshot.elecFen > 0) " · 电费 ¥%.2f".format(balanceSnapshot.elecFen / 100.0) else "",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        } else {
                            // 骨架：进页拉余额的 1–3 秒内占位，数据到达平滑填充
                            Text(
                                "卡余额 获取中…",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "充值后回到本页，余额与流水会自动刷新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(8.dp))
                        // 到账等待状态行（Watching 时替换按钮区上方，格式与余额一致不跳动）
                        val watching = arrivalState as? CampusCardViewModel.ArrivalState.Watching
                        if (watching != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "正在等待充值到账（¥%.2f）· 每 5 秒自动检测".format(watching.orderFen / 100.0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.OutlinedButton(
                                onClick = onOpenStatement,
                                modifier = Modifier.weight(1f),
                            ) { Text("消费流水") }
                            androidx.compose.material3.OutlinedButton(
                                onClick = { showRechargeSheet = true },
                                modifier = Modifier.weight(1f),
                                enabled = watching == null,
                            ) { Text("充值") }
                        }
                    },
                )
            }

            SettingsCard(
                title = "校园卡账号",
                content = {
                    Text(
                        "账号为学号，默认密码通常为身份证后六位（仅支持数字密码）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it.filter { c -> c.isDigit() } },
                        label = { Text(if (viewModel.hasSavedAccount) "学号（已保存，可覆盖）" else "学号") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it.filter { c -> c.isDigit() } },
                        label = { Text(if (viewModel.hasSavedAccount) "密码（留空沿用已保存）" else "密码") },
                        singleLine = true,
                        visualTransformation =
                            if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showPassword = !showPassword }) {
                                Icon(
                                    if (showPassword) HugeIcons.ViewOff else HugeIcons.View,
                                    contentDescription = if (showPassword) "隐藏密码" else "显示密码",
                                )
                            }
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        }
    }

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text("关闭校园卡付款码？") },
            text = { Text("将清除已保存的学号密码，今日页入口同时隐藏；重新开启时需要重新输入并验证。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisable = false
                        busy = true
                        viewModel.disable {
                            busy = false
                            username = ""
                            scope.launch {
                                snackbar.showSnackbar(
                                    AppNoticeVisuals("已关闭并清除凭证", tone = NoticeTone.Info),
                                )
                            }
                        }
                    },
                ) { Text("关闭并清除") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) { Text("取消") }
            },
        )
    }

    // 充值流程（DESIGN §4.19「充值」）：金额弹层 → 二次确认 → 下单 → 直拉微信 → 等待到账
    val context = LocalContext.current
    if (showRechargeSheet) {
        RechargeSheet(
            balanceFen = balance?.totalFen,
            onDismiss = { showRechargeSheet = false },
            onLaunch = { yuan ->
                showRechargeSheet = false
                busy = true
                viewModel.recharge(
                    yuan,
                    // Activity context 直接启动（不设 NEW_TASK）：微信/浏览器在调用方 task
                    // 内打开，返回无缝、无 task 重排 → 顶栏不跳动
                    launchExternal = { intent ->
                        runCatching {
                            (context as? Activity)?.startActivity(intent)
                                ?: context.startActivity(
                                    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            true
                        }.getOrDefault(false)
                    },
                ) { notice ->
                    busy = false
                    scope.launch {
                        snackbar.showSnackbar(AppNoticeVisuals(notice.text, tone = notice.tone))
                    }
                }
            },
        )
    }

    // 「正在确认到账」弹窗（微信返回且未立即到账时；关闭不影响轮询）
    val pendingConfirm by viewModel.pendingConfirmVisible.collectAsStateWithLifecycle()
    val watchingForDialog = arrivalState as? CampusCardViewModel.ArrivalState.Watching
    if (pendingConfirm && watchingForDialog != null) {
        CampusPendingConfirmDialog(
            orderFen = watchingForDialog.orderFen,
            onDismiss = { viewModel.dismissPendingConfirm() },
        )
    }

    // 充值成功弹窗（共享组件， CampusRechargeUi.kt）；确认按钮直达付款码页
    val arrived = arrivalState as? CampusCardViewModel.ArrivalState.Arrived
    if (arrived != null) {
        CampusArrivalDialog(
            arrived = arrived,
            onDismiss = { viewModel.dismissArrival() },
            onOpenPayCode = onOpenPayCode,
        )
    }
}


@Composable
private fun SettingsCard(
    title: String,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            content()
        }
    }
}

class CampusCardViewModel(private val appContext: Context) : ViewModel() {

    private val prefs = Graph.displayPrefs(appContext)
    private val credentialStore = Graph.yktCredentialStore(appContext)
    private val repo = Graph.yktRepository(appContext)
    private val db = edu.jxslu.schedule.data.local.JuwDatabase.get(appContext)
    private val syncer = edu.jxslu.schedule.data.ykt.YktTurnoverSyncer(repo, db)

    val hasSavedAccount: Boolean get() = credentialStore.read() != null

    val enabled: StateFlow<Boolean> = prefs.campusCardEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 余额快照（开启后进页静默拉一次；失败静默——设置页只做引导不做主流程）。 */
    private val _balance = MutableStateFlow<PayCodeViewModel.BalanceSnapshot?>(null)
    val balance: StateFlow<PayCodeViewModel.BalanceSnapshot?> = _balance

    init {
        // 今日页也挂本 VM（卡片余额+弹窗充值）；开关关时绝不发起任何一卡通网络动作
        if (credentialStore.read() != null) {
            viewModelScope.launch {
                val enabledNow = prefs.campusCardEnabled.first()
                if (!enabledNow) return@launch
                try {
                    val saved = credentialStore.read() ?: return@launch
                    val cards = repo.cards(saved.username, saved.password)
                    if (cards.isNotEmpty()) {
                        _balance.value = PayCodeViewModel.BalanceSnapshot(
                            cards = cards,
                            totalFen = cards.sumOf { it.cardBalanceFen },
                            elecFen = cards.sumOf { it.elecBalanceFen },
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 静默：余额取不到不影响设置页其余功能
                }
                // 恢复未确认充值（进程被杀场景，DESIGN §4.19「充值」）：窗口内重启轮询，超窗清除
                val pending = runCatching { prefs.pendingRecharge.first() }.getOrNull()
                if (pending != null) {
                    val (orderFen, startedAt) = pending
                    val saved = credentialStore.read()
                    if (saved == null || System.currentTimeMillis() - startedAt >= ARRIVAL_WATCH_MS) {
                        prefs.clearPendingRecharge()
                    } else {
                        _arrivalState.value = ArrivalState.Watching(orderFen = orderFen, startedAt = startedAt)
                        startArrivalWatch(saved.username, saved.password, orderFen, startedAt)
                    }
                }
            }
        }
    }

    /** 开启流程：验证（空白密码沿用已存）→ 保存凭证 → 置开关。失败保持关并给原因。 */
    fun saveAndEnable(username: String, password: String, onResult: (NoticeFeedback) -> Unit) {
        viewModelScope.launch {
            val user = username.trim()
            val saved = credentialStore.read()
            val pwd = password.ifBlank { saved?.password.orEmpty() }
            if (user.isEmpty() || pwd.isEmpty()) {
                onResult(NoticeFeedback("请填入学号和密码", NoticeTone.Warning))
                return@launch
            }
            // 整链总超时兜底：键盘 + 登录 + 账户三跳，正常 1–3 秒
            val result = withTimeoutOrNull(LOGIN_TOTAL_TIMEOUT_MS) { verifyAndEnable(user, pwd) }
            if (result == null) {
                onResult(
                    NoticeFeedback(
                        "登录超时（${LOGIN_TOTAL_TIMEOUT_MS / 1000} 秒无响应），请检查网络后重试",
                        NoticeTone.Error,
                    ),
                )
                return@launch
            }
            onResult(result)
        }
    }

    private suspend fun verifyAndEnable(user: String, pwd: String): NoticeFeedback {
        try {
            // 真实登录验证一次（顺带校验字形表/协议），成功才落库
            repo.login(user, pwd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: YktException) {
            return NoticeFeedback(e.message ?: "登录失败，请检查账号密码", NoticeTone.Error)
        } catch (e: Exception) {
            return NoticeFeedback(
                "登录异常：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim(),
                NoticeTone.Error,
            )
        }
        // 开启即验证 CARD 账户（DESIGN §4.19 小优化）：绑多账号/无实体卡在这里暴露，
        // 而不是等第一次取码才发现。查询失败（网络）不阻塞开启——凭证已验证有效。
        val cards = try {
            repo.cards(user, pwd)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (cards != null && cards.isEmpty()) {
            return NoticeFeedback("该账号下没有可用的校园卡账户，无法出示付款码", NoticeTone.Error)
        }
        cards?.let {
            _balance.value = PayCodeViewModel.BalanceSnapshot(
                cards = it,
                totalFen = it.sumOf { c -> c.cardBalanceFen },
                elecFen = it.sumOf { c -> c.elecBalanceFen },
            )
        }
        credentialStore.save(user, pwd)
        prefs.setCampusCardEnabled(true)
        return NoticeFeedback("已开启，付款码入口已显示在今日页", NoticeTone.Success)
    }

    fun disable(onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setCampusCardEnabled(false)
            credentialStore.clear()
            onDone()
        }
    }

    /**
     * 充值：下单 → 拉起微信/收银台 → 回流轮询到账（DESIGN §4.19「充值」）。
     *
     * [launchExternal] 由 UI 层注入（当前 Activity 直接 startActivity，**不带
     * FLAG_ACTIVITY_NEW_TASK**）——外部浏览器/微信支付会在调用方 task 内打开并在支付后
     * 无缝返回，避免 appContext+NEW_TASK 的 task 重排导致顶栏/界面跳动（2026-09-21 实测）。
     * 返回 false = 无法打开（未装微信等），调用方给文案。
     */
    fun recharge(
        yuan: String,
        launchExternal: (android.content.Intent) -> Boolean,
        onResult: (NoticeFeedback) -> Unit,
    ) {
        viewModelScope.launch {
            val saved = credentialStore.read()
            if (saved == null) {
                onResult(NoticeFeedback("凭证已失效，请重新填写学号密码", NoticeTone.Warning))
                return@launch
            }
            val order = try {
                withTimeoutOrNull(RECHARGE_TIMEOUT_MS) {
                    repo.rechargeCreate(saved.username, saved.password, yuan)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: YktException) {
                onResult(NoticeFeedback(e.message ?: "下单失败，请稍后重试", NoticeTone.Error))
                return@launch
            } catch (e: Exception) {
                onResult(
                    NoticeFeedback(
                        "下单异常：${e.javaClass.simpleName}（平台可能已改版）",
                        NoticeTone.Error,
                    ),
                )
                return@launch
            }
            if (order == null) {
                onResult(NoticeFeedback("下单超时，请检查网络后重试", NoticeTone.Error))
                return@launch
            }
            // 到账检测基线 + 持久化未确认充值（进程被杀后可恢复，DESIGN §4.19「充值」）
            val orderFen = parsedFenOf(yuan)
            val startedAt = System.currentTimeMillis()
            _arrivalBaseFen = _balance.value?.totalFen
            prefs.setPendingRecharge(orderFen, startedAt)
            when (order) {
                is YktRechargeOrder.WechatPay -> {
                    // 直拉微信（跳过浏览器与收银台页；weixin://wap/pay 由微信客户端接手）
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(order.wechatUrl),
                    )
                    if (launchExternal(intent)) {
                        onResult(NoticeFeedback("已拉起微信支付，支付完成后回到本页等待到账", NoticeTone.Info))
                    } else {
                        onResult(NoticeFeedback("未找到微信，请确认已安装微信后重试", NoticeTone.Error))
                    }
                }
                is YktRechargeOrder.Cashier -> {
                    // 兜底：打开官方收银台（服务端 302 下发的完整 URL）
                    val intent = android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(order.cashierUrl),
                    )
                    if (!launchExternal(intent)) {
                        onResult(NoticeFeedback("无法打开浏览器，请检查设备后重试", NoticeTone.Error))
                        return@launch
                    }
                    onResult(NoticeFeedback("已跳转官方收银台，支付完成后回到本页等待到账", NoticeTone.Info))
                }
            }
            watchRechargeArrivalLaunch(
                username = saved.username,
                password = saved.password,
                orderFen = orderFen,
                startedAt = startedAt,
            )
        }
    }

    /** 下单后启动轮询（基线/持久化已就绪；startedAt=下单时刻，进程重启恢复也用它）。 */
    private fun watchRechargeArrivalLaunch(username: String, password: String, orderFen: Long, startedAt: Long) {
        startArrivalWatch(username, password, orderFen, startedAt)
    }

    /** "12.50" → 1250 分；调用前已经 RechargeSheet 校验过，异常值返回 0（只影响流水判定精度）。 */
    private fun parsedFenOf(yuan: String): Long =
        yuan.toBigDecimalOrNull()
            ?.multiply(java.math.BigDecimal(100))
            ?.toLong()
            ?: 0L

    /**
     * 到账轮询：5 秒一轮余额检测（用户要求的高频口径）+ 每 3 轮（15 秒）一次流水增量。
     * 从微信返回、甚至进程被杀重启后，[onHostResume]/init 都会立即补检一轮（见下）。
     */
    private fun startArrivalWatch(username: String, password: String, orderFen: Long, startedAt: Long) {
        watchJob?.cancel()
        _arrivalState.value = ArrivalState.Watching(orderFen = orderFen, startedAt = startedAt)
        watchJob = viewModelScope.launch {
            val deadline = startedAt + ARRIVAL_WATCH_MS
            var round = 0
            while (isActive && System.currentTimeMillis() < deadline) {
                kotlinx.coroutines.delay(ARRIVAL_POLL_MS)
                round++
                if (checkArrivalOnce(username, password, orderFen, startedAt)) return@launch
                // 流水同步降频：每 3 轮（15s）一次，减轻服务端压力
                if (round % 3 == 0) {
                    if (checkTurnoverArrival(username, password, orderFen, startedAt)) return@launch
                }
            }
            prefs.clearPendingRecharge()
            _pendingConfirmVisible.value = false
            _arrivalState.value = ArrivalState.Timeout(orderFen = orderFen)
        }
    }

    /**
     * 单轮到账检测。**余额口径有基线才判**（startFen 未知时不能用"余额 > 基线"——
     * 会把未到账误报成到账），未知基线时依赖流水口径。
     */
    private suspend fun checkArrivalOnce(
        username: String,
        password: String,
        orderFen: Long,
        startedAt: Long,
    ): Boolean {
        try {
            val cards = repo.cards(username, password)
            if (cards.isNotEmpty()) {
                val snapshot = PayCodeViewModel.BalanceSnapshot(
                    cards = cards,
                    totalFen = cards.sumOf { it.cardBalanceFen },
                    elecFen = cards.sumOf { it.elecBalanceFen },
                )
                _balance.value = snapshot
                val base = _arrivalBaseFen
                if (base != null && snapshot.totalFen >= base + orderFen) {
                    _arrivalState.value = ArrivalState.Arrived(orderFen = orderFen, newBalanceFen = snapshot.totalFen)
                    _pendingConfirmVisible.value = false
                    prefs.clearPendingRecharge()
                    return true
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 余额失败不中断，流水口径再试
        }
        return false
    }

    private suspend fun checkTurnoverArrival(
        username: String,
        password: String,
        orderFen: Long,
        startedAt: Long,
    ): Boolean {
        try {
            syncer.sync(username, password, maxPages = 1)
            val count = db.yktTurnoverDao().countIncomeSince(startedAt)
            if (count > 0) {
                _arrivalState.value = ArrivalState.Arrived(orderFen = orderFen, newBalanceFen = _balance.value?.totalFen)
                _pendingConfirmVisible.value = false
                prefs.clearPendingRecharge()
                return true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // 下一轮再试
        }
        return false
    }

    /**
     * 页面回到前台（从微信返回等）：立即补检一轮——不等下一个 5 秒周期，
     * 这是「支付完回来马上看到结果」的关键；进程被杀重启时同时恢复等待态。
     * 恢复/等待中弹出「正在确认到账」提示弹窗（用户关掉不影响轮询）。
     */
    fun onHostResume() {
        val st = _arrivalState.value
        if (st is ArrivalState.Arrived || st is ArrivalState.Timeout) return // 已有结论待用户确认
        viewModelScope.launch {
            val saved = credentialStore.read() ?: return@launch
            val pending = prefs.pendingRecharge.first()
            val (orderFen, startedAt) = when {
                st is ArrivalState.Watching -> st.orderFen to st.startedAt
                pending != null -> pending.first to pending.second
                else -> return@launch
            }
            if (_arrivalBaseFen == null) _arrivalBaseFen = _balance.value?.totalFen
            if (_arrivalState.value !is ArrivalState.Watching) {
                _arrivalState.value = ArrivalState.Watching(orderFen = orderFen, startedAt = startedAt)
            }
            val arrived = checkArrivalOnce(saved.username, saved.password, orderFen, startedAt)
            if (arrived) return@launch // 成功弹窗已就位
            // 未立即到账：给出「正在确认」反馈弹窗——延迟一拍（等返回动画/窗口稳定），
            // 避免回到前台瞬间弹窗引发顶栏 insets 重算跳动（2026-09-21 实测）
            kotlinx.coroutines.delay(PENDING_CONFIRM_DELAY_MS)
            if (_arrivalState.value is ArrivalState.Arrived) return@launch
            _pendingConfirmVisible.value = true
            if (!checkTurnoverArrival(saved.username, saved.password, orderFen, startedAt)) {
                startArrivalWatch(saved.username, saved.password, orderFen, startedAt)
            }
        }
    }

    /** 「正在确认到账」提示弹窗可见性（回到前台且未到账时显示；到账/超时/用户关闭即撤）。 */
    private val _pendingConfirmVisible = MutableStateFlow(false)
    val pendingConfirmVisible: StateFlow<Boolean> = _pendingConfirmVisible

    fun dismissPendingConfirm() {
        _pendingConfirmVisible.value = false
    }

    /** 到账判定的余额基线（下单时刻快照；进程重启后为 null → 只用流水口径）。 */
    @Volatile
    private var _arrivalBaseFen: Long? = null

    private var watchJob: kotlinx.coroutines.Job? = null

    /** 充值到账状态（UI 据此渲染等待卡/成功弹窗/超时提示）。 */
    sealed interface ArrivalState {
        data object Idle : ArrivalState

        /** 等待到账：[orderFen] 下单金额（分），[startedAt] 下单时刻。 */
        data class Watching(val orderFen: Long, val startedAt: Long) : ArrivalState

        /** 已到账：[newBalanceFen] 可为 null（流水先出而余额视图未刷新）。 */
        data class Arrived(val orderFen: Long, val newBalanceFen: Long?) : ArrivalState

        /** 窗口内未检测到（多为到账延迟，账单页会自动补显）。 */
        data class Timeout(val orderFen: Long) : ArrivalState
    }

    private val _arrivalState = MutableStateFlow<ArrivalState>(ArrivalState.Idle)
    val arrivalState: StateFlow<ArrivalState> = _arrivalState

    fun dismissArrival() {
        _arrivalState.value = ArrivalState.Idle
    }

    companion object {
        private const val LOGIN_TOTAL_TIMEOUT_MS = 30_000L

        /** 充值下单总超时（queryCard + thirdOrder 两跳，正常 1–3 秒）。 */
        private const val RECHARGE_TIMEOUT_MS = 20_000L

        /** 到账轮询总时长（30 分钟：饭卡充值常见延迟数分钟至数十分钟）。 */
        private const val ARRIVAL_WATCH_MS = 30 * 60_000L

        /** 轮询间隔。 */
        private const val ARRIVAL_POLL_MS = 5_000L

        /** 返回前台后「确认中」弹窗的延迟（ms）：避开返回动画期的窗口/insets 重排。 */
        private const val PENDING_CONFIRM_DELAY_MS = 800L

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CampusCardViewModel(context.applicationContext) as T
        }
    }
}
