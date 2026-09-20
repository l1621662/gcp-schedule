package edu.jxslu.schedule.ui.campus

import android.content.Context
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
    viewModel: CampusCardViewModel = viewModel(
        factory = CampusCardViewModel.Factory(LocalContext.current.applicationContext),
    ),
) {
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val balance by viewModel.balance.collectAsStateWithLifecycle()
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }

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

            // 余额状态区（DESIGN §4.19 B5）：开启且取到余额才显示；点击跳流水页
            val balanceSnapshot = balance
            if (enabled && balanceSnapshot != null) {
                SettingsCard(
                    title = "余额",
                    content = {
                        Text(
                            "卡余额 ¥%.2f".format(balanceSnapshot.totalFen / 100.0) +
                                if (balanceSnapshot.elecFen > 0) " · 电费 ¥%.2f".format(balanceSnapshot.elecFen / 100.0) else "",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "点击「消费流水」查看当月收支与交易记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        )
                        Spacer(Modifier.height(8.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = onOpenStatement,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("消费流水") }
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

    val hasSavedAccount: Boolean get() = credentialStore.read() != null

    val enabled: StateFlow<Boolean> = prefs.campusCardEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 余额快照（开启后进页静默拉一次；失败静默——设置页只做引导不做主流程）。 */
    private val _balance = MutableStateFlow<PayCodeViewModel.BalanceSnapshot?>(null)
    val balance: StateFlow<PayCodeViewModel.BalanceSnapshot?> = _balance

    init {
        if (credentialStore.read() != null) {
            viewModelScope.launch {
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

    companion object {
        private const val LOGIN_TOTAL_TIMEOUT_MS = 30_000L

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CampusCardViewModel(context.applicationContext) as T
        }
    }
}
