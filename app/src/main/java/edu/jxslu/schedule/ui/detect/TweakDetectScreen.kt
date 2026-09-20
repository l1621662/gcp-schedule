package edu.jxslu.schedule.ui.detect

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.jw.JwDetectRunner
import edu.jxslu.schedule.data.jw.JwDetectScheduler
import edu.jxslu.schedule.data.jw.JwHttpSession
import edu.jxslu.schedule.data.prefs.DetectDefaults
import edu.jxslu.schedule.data.prefs.DetectSettings
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.NoticeFeedback
import edu.jxslu.schedule.ui.common.NoticeTone
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 我的 → 调课自动检测（DESIGN §4.17）。管配置不看报告：差异报告在「更新课表」页。
 *
 * 凭证交互（用户拍板的文案与语义）：学号密码**仅存本机**（EncryptedSharedPreferences，
 * 已排除云备份），默认关闭，开启前先真实登录校验，关闭即清除。
 * 密码框留空 = 沿用已保存的密码（覆盖场景才需要重新输入）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TweakDetectScreen(
    onBack: () -> Unit,
    onOpenScheduleUpdate: () -> Unit = {},
    viewModel: TweakDetectViewModel = viewModel(
        factory = TweakDetectViewModel.Factory(LocalContext.current.applicationContext),
    ),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }

    fun showOutcome(outcome: JwDetectRunner.Outcome) {
        when (outcome) {
            is JwDetectRunner.Outcome.DiffFound -> onOpenScheduleUpdate()
            else -> scope.launch {
                val notice = detectOutcomeNotice(outcome)
                snackbar.showSnackbar(AppNoticeVisuals(notice.text, tone = notice.tone))
            }
        }
    }

    Scaffold(
        // 根因：本页跑在 SubpageActivity 独立窗口里，**没有外层 Scaffold 垫状态栏**。
        // 此前沿用的是嵌 NavHost 时期「inset 归零防双倍空白」的老写法，
        // 归零后 TopAppBar 不消费状态栏 → 顶栏顶进状态栏（用户反馈「顶栏偏高」）。
        // 现走 M3 默认：TopAppBar 自行消费顶部，contentWindowInsets 管住手势条，
        // 与 DataSettingsScreen 等其余二级页同口径。
        snackbarHost = { AppSnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text("调课自动检测") },
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
                title = "启用",
                content = {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("自动登录教务检测课表变化", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                if (settings.disabled) "已因连续登录失败自动停用，重新开启并验证后恢复" else
                                    if (settings.enabled) "已开启 · ${DetectDefaults.label(settings.periodHours)}检测一次"
                                    else "默认关闭",
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
                            checked = settings.enabled,
                            onCheckedChange = { wantOn ->
                                if (busy) return@Switch
                                if (wantOn) {
                                    busy = true
                                    viewModel.saveAndEnable(
                                        username = username,
                                        password = password,
                                    ) { feedback ->
                                        // ok 由语气体现：非错误即凭证有效、开关已打开，
                                        // 输入框随之清空（下次进入不必重打密码）
                                        busy = false
                                        if (feedback.tone != NoticeTone.Error) password = ""
                                        scope.launch {
                                            snackbar.showSnackbar(
                                                AppNoticeVisuals(feedback.text, tone = feedback.tone),
                                            )
                                        }
                                    }
                                } else {
                                    confirmDisable = true
                                }
                            },
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "学号与密码仅保存在本机（Android Keystore 加密存储），不进入云备份、" +
                            "不发送到任何第三方服务器；若仍有顾虑，可随时关闭本功能，" +
                            "关闭即清除已保存的凭证。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    )
                },
            )

            SettingsCard(
                title = "教务账号",
                content = {
                    OutlinedTextField(
                        value = username,
                        onValueChange = { username = it },
                        label = { Text(if (viewModel.hasSavedAccount) "学号（已保存，可覆盖）" else "学号") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it },
                        label = { Text(if (viewModel.hasSavedAccount) "密码（留空沿用已保存）" else "密码") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )

            if (settings.enabled) {
                SettingsCard(
                    title = "检测周期",
                    content = {
                        SegmentedPeriodRow(
                            current = settings.periodHours,
                            onSelect = viewModel::setPeriodHours,
                        )
                    },
                )
            }

            SettingsCard(
                title = "状态",
                content = {
                    Text(
                        detectStatusLine(settings),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (!busy) {
                                busy = true
                                viewModel.checkNow { outcome ->
                                    busy = false
                                    showOutcome(outcome)
                                }
                            }
                        },
                        enabled = settings.enabled && !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("立即检测") }
                },
            )
        }
    }

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text("关闭调课自动检测？") },
            text = { Text("将清除已保存的学号密码与检测基线；重新开启时需要重新输入并验证。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisable = false
                        busy = true
                        viewModel.disable {
                            busy = false
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

@Composable
private fun SegmentedPeriodRow(current: Int, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DetectDefaults.PERIOD_HOURS.forEach { hours ->
            val selected = hours == current
            OutlinedButton(
                onClick = { onSelect(hours) },
                modifier = Modifier.weight(1f),
                colors = if (selected) {
                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    )
                } else {
                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors()
                },
            ) { Text(DetectDefaults.label(hours)) }
        }
    }
}

internal fun detectStatusLine(settings: DetectSettings): String {
    val checkedAt = if (settings.lastCheckedAt > 0) {
        DateTimeFormatter.ofPattern("M月d日 HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(settings.lastCheckedAt))
    } else {
        null
    }
    return buildString {
        if (checkedAt == null) {
            append("尚未检测过")
        } else {
            append("上次检测：$checkedAt")
        }
        if (settings.lastError.isNotBlank()) {
            append("\n")
            append(settings.lastError)
        }
    }
}

class TweakDetectViewModel(private val appContext: Context) : ViewModel() {

    private val prefs = Graph.displayPrefs(appContext)
    private val credentialStore = Graph.jwCredentialStore(appContext)

    val hasSavedAccount: Boolean get() = credentialStore.read() != null

    val settings: StateFlow<DetectSettings> = prefs.detectSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetectSettings())

    /**
     * 开启流程：验证（空白密码沿用已存）→ 保存凭证 → 开启 → 立即首检（建基线）。
     * [onResult] 的 ok 表示「凭证有效、开关已打开」；首检本身的结果单独带语气
     * （凭证对了但首检失败时，开关确实是开的，语气该是警告而不是错误）。
     */
    fun saveAndEnable(username: String, password: String, onResult: (NoticeFeedback) -> Unit) {
        viewModelScope.launch {
            val user = username.trim()
            val saved = credentialStore.read()
            val pwd = password.ifBlank { saved?.password.orEmpty() }
            if (user.isEmpty() || pwd.isEmpty()) {
                onResult(NoticeFeedback("请填入学号和密码", NoticeTone.Warning))
                return@launch
            }
            // 整链总超时兜底：单请求各有 connect/read 超时，但链路含多步重定向，
            // 最坏情况叠加起来仍可能长时间无结果。宁可明确报「超时」也不要一直转圈。
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

    /** 登录校验 + 落库 + 首检；返回面向用户的反馈（含语气）。 */
    private suspend fun verifyAndEnable(user: String, pwd: String): NoticeFeedback {
        val session = JwHttpSession.create()
        try {
            session.login(user, pwd)
        } catch (e: CancellationException) {
            // 总超时靠协程取消实现：CancellationException 必须原样抛出，
            // 被下面 catch (Exception) 吞掉的话超时会误报成「登录异常」。
            throw e
        } catch (e: JwHttpSession.JwHttpException) {
            return NoticeFeedback(e.message ?: "登录失败，请检查账号密码", NoticeTone.Error)
        } catch (e: Exception) {
            // 兜底：链路里任何未归类异常都不能把开关卡在转圈态
            return NoticeFeedback(
                "登录异常：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim(),
                NoticeTone.Error,
            )
        } finally {
            // 成功 / 失败 / 取消三条路径都要释放连接池与线程池
            session.shutdown()
        }
        credentialStore.save(user, pwd)
        prefs.setDetectEnabled(true)
        JwDetectScheduler.ensurePeriodicWork(appContext, prefs.detectSettings.first())
        // 首检失败（网络/协议）不回滚开关——凭证已验证通过，开关是有效状态；
        // 失败原因由 outcome 文案透出，状态区同时记录 lastError 供回看。
        val outcome = try {
            JwDetectRunner(appContext).run(JwDetectRunner.Trigger.Manual)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JwDetectRunner.Outcome.Failed("首次检测异常：${e.message.orEmpty()}")
        }
        return detectOutcomeNotice(outcome)
    }

    fun disable(onDone: () -> Unit) {
        viewModelScope.launch {
            prefs.setDetectEnabled(false)
            credentialStore.clear()
            Graph.repository(appContext).clearDetectData()
            JwDetectScheduler.ensurePeriodicWork(appContext, DetectSettings())
            onDone()
        }
    }

    fun setPeriodHours(hours: Int) {
        viewModelScope.launch {
            prefs.setDetectPeriodHours(hours)
            JwDetectScheduler.ensurePeriodicWork(appContext, prefs.detectSettings.first())
        }
    }

    fun checkNow(onDone: (JwDetectRunner.Outcome) -> Unit) {
        viewModelScope.launch {
            onDone(JwDetectRunner(appContext).run(JwDetectRunner.Trigger.Manual))
        }
    }
    companion object {
        /**
         * 开启流程的整链总超时。链路含 CAS 登录 + SSO + 两页课表抓取，正常 2–5 秒；
         * 校园网络偶尔每步 20 秒级（实测正常课表抓取 0.1–0.3 秒，异常时不封顶）。
         * 超过这个时间明确报错，好过无限转圈让用户不知道发生了什么。
         */
        private const val LOGIN_TOTAL_TIMEOUT_MS = 60_000L

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TweakDetectViewModel(context.applicationContext) as T
        }
    }
}
