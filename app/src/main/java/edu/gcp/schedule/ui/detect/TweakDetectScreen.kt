package edu.gcp.schedule.ui.detect

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.gcp.schedule.Graph
import edu.gcp.schedule.data.jw.JwDetectRunner
import edu.gcp.schedule.data.jw.JwDetectScheduler
import edu.gcp.schedule.data.jw.ZfJwSession
import edu.gcp.schedule.data.prefs.DetectDefaults
import edu.gcp.schedule.data.prefs.DetectSettings
import edu.gcp.schedule.ui.common.AppNoticeVisuals
import edu.gcp.schedule.ui.common.AppSnackbarHost
import edu.gcp.schedule.ui.common.NoticeFeedback
import edu.gcp.schedule.ui.common.NoticeTone
import edu.gcp.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    var captchaCode by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }
    val captchaState by viewModel.captcha.collectAsStateWithLifecycle()

    /**
     * 开启 / 重新验证共用同一条口径：验证码一次性，**成败都要清空输入框**，
     * 失败时 ViewModel 会顺手换一张新图（旧码再用必然还是错）。
     */
    fun verifyAndEnable() {
        if (busy) return
        busy = true
        viewModel.saveAndEnable(
            username = username,
            password = password,
            captchaCode = captchaCode,
        ) { feedback ->
            busy = false
            captchaCode = ""
            if (feedback.tone != NoticeTone.Error) password = ""
            scope.launch { snackbar.showSnackbar(AppNoticeVisuals(feedback.text, tone = feedback.tone)) }
        }
    }

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
                                    verifyAndEnable()
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
                    Spacer(Modifier.height(8.dp))
                    CaptchaRow(
                        state = captchaState,
                        code = captchaCode,
                        onCodeChange = { captchaCode = it },
                        onRefresh = {
                            captchaCode = ""
                            viewModel.refreshCaptcha()
                        },
                    )
                    if (settings.enabled) {
                        Spacer(Modifier.height(8.dp))
                        // 会话过期后靠这个按钮补一次验证（学号密码不用重打，只补验证码）
                        OutlinedButton(
                            onClick = { verifyAndEnable() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text("重新验证（更新登录会话）") }
                    }
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

/**
 * 验证码行（DESIGN §4.17）：左边输入框、右边图片，图片与输入框齐平（48dp），点图换一张。
 *
 * 图片就是登录页同源 `GET /jwglxt/kaptcha?time=…` 原样返回的 JPEG，只解码到内存 Bitmap：
 * 不落盘、不进相册、不进日志——验证码属于登录凭证的一部分。
 */
@Composable
private fun CaptchaRow(
    state: TweakDetectViewModel.CaptchaState,
    code: String,
    onCodeChange: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = code,
            onValueChange = onCodeChange,
            label = { Text("验证码") },
            singleLine = true,
            // 正方验证码区分大小写（登录页 dlmmsfknt=1），这里不做任何大小写转换
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier.weight(1f),
        )
        CaptchaImage(state = state, onRefresh = onRefresh)
    }
}

/** 验证码图片位：加载中转圈、失败显示「点击重试」，点击即换一张（带触感）。 */
@Composable
private fun CaptchaImage(state: TweakDetectViewModel.CaptchaState, onRefresh: () -> Unit) {
    val haptics = rememberAppHaptics()
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier = Modifier
            .size(width = 128.dp, height = 48.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
            .clickable {
                haptics.tap()
                onRefresh()
            },
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is TweakDetectViewModel.CaptchaState.Loading ->
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)

            is TweakDetectViewModel.CaptchaState.Failed ->
                Text("点击重试", style = MaterialTheme.typography.labelMedium)

            is TweakDetectViewModel.CaptchaState.Ready -> {
                val bytes = state.bytes
                if (bytes == null) {
                    Text("无需验证码", style = MaterialTheme.typography.labelMedium)
                } else {
                    val bitmap = remember(bytes) {
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                    }
                    if (bitmap == null) {
                        Text("点击重试", style = MaterialTheme.typography.labelMedium)
                    } else {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "验证码，点击刷新",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
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

    /** 验证码现场：加载中 / 就绪（bytes = null 表示这套部署不需要验证码）/ 失败。 */
    sealed interface CaptchaState {
        data object Loading : CaptchaState
        data class Ready(val bytes: ByteArray?) : CaptchaState
        data class Failed(val message: String) : CaptchaState
    }

    private val _captcha = MutableStateFlow<CaptchaState>(CaptchaState.Loading)
    val captcha: StateFlow<CaptchaState> = _captcha.asStateFlow()

    /**
     * 与当前验证码**同一个会话**的登录现场：csrf 与图片必须成对，换图就换会话。
     * 只活在内存里，登录成功后由 [sessionCookie] 的持久化版本接手后台复用。
     */
    // volatile：在 IO 线程里赋值、主线程读取（点「重新验证」时要用它）
    @Volatile
    private var pendingSession: ZfJwSession? = null

    val hasSavedAccount: Boolean get() = credentialStore.read() != null

    init {
        // 进页面就备一张：用户随时可能直接开验证码登录
        refreshCaptcha()
    }

    /**
     * 拉一张新验证码（进入页面 / 点图片 / 登录失败后都走这里）。
     *
     * 换图必须换会话：csrftoken 与验证码图片都要跟当前 cookie 配对，
     * 拿旧会话的 csrf 提交新图片必然失败。
     */
    fun refreshCaptcha() {
        viewModelScope.launch {
            _captcha.value = CaptchaState.Loading
            // 整段（含关闭旧连接池、建 client）都在 IO 线程：真机曾因主线程碰网络崩溃
            val next = try {
                withContext(Dispatchers.IO) {
                    val previous = pendingSession
                    pendingSession = null
                    previous?.shutdown()
                    val session = ZfJwSession.create()
                    try {
                        val page = session.prepareLogin()
                        pendingSession = session
                        CaptchaState.Ready(page.captcha)
                    } catch (e: Throwable) {
                        session.shutdown()
                        throw e
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ZfJwSession.ZfException) {
                CaptchaState.Failed(e.message ?: "验证码加载失败")
            } catch (e: Exception) {
                Log.w(TAG, "captcha load failed", e)
                CaptchaState.Failed("验证码加载异常：" + e.javaClass.simpleName)
            }
            _captcha.value = next
        }
    }

    val settings: StateFlow<DetectSettings> = prefs.detectSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetectSettings())

    /**
     * 开启流程：验证（空白密码沿用已存）→ 保存凭证 → 开启 → 立即首检（建基线）。
     * [onResult] 的 ok 表示「凭证有效、开关已打开」；首检本身的结果单独带语气
     * （凭证对了但首检失败时，开关确实是开的，语气该是警告而不是错误）。
     */
    fun saveAndEnable(
        username: String,
        password: String,
        captchaCode: String,
        onResult: (NoticeFeedback) -> Unit,
    ) {
        viewModelScope.launch {
            val user = username.trim()
            val saved = credentialStore.read()
            val pwd = password.ifBlank { saved?.password.orEmpty() }
            if (user.isEmpty() || pwd.isEmpty()) {
                onResult(NoticeFeedback("请填入学号和密码", NoticeTone.Warning))
                return@launch
            }
            val needCaptcha = (_captcha.value as? CaptchaState.Ready)?.bytes != null
            if (needCaptcha && captchaCode.isBlank()) {
                onResult(NoticeFeedback("请填入验证码", NoticeTone.Warning))
                return@launch
            }
            val session = pendingSession
            if (needCaptcha && session == null) {
                onResult(NoticeFeedback("验证码还没加载好，点图片刷新后再试", NoticeTone.Warning))
                refreshCaptcha()
                return@launch
            }
            // 整链总超时兜底：单请求各有 connect/read 超时，但链路含多步重定向，
            // 最坏情况叠加起来仍可能长时间无结果。宁可明确报「超时」也不要一直转圈。
            val result = withTimeoutOrNull(LOGIN_TOTAL_TIMEOUT_MS) {
                // 登录 + 落库 + 首检整条链都在 IO：主线程只拿结果
                withContext(Dispatchers.IO) { verifyAndEnable(user, pwd, captchaCode, session) }
            }
            if (result == null) {
                refreshCaptcha()
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

/**
 * 登录校验 + 落库（凭证 + 会话 cookie）+ 首检；返回面向用户的反馈（含语气）。
 *
 * 验证码一次性：**任何失败都顺手换一张新图**，用户不用自己想到去点刷新。
 * 成功后把会话 cookie 存进加密 prefs，后台定时检测在有效期内直接复用它
 * （本校准登录必须填验证码，后台自己登不了，见 JwDetectRunner.run 的说明）。
 */
    private suspend fun verifyAndEnable(
        user: String,
        pwd: String,
        captchaCode: String,
        session: ZfJwSession?,
    ): NoticeFeedback {
        if (session == null) return NoticeFeedback("验证码会话已失效，请重新获取验证码", NoticeTone.Warning)
        try {
            session.login(user, pwd, captchaCode)
        } catch (e: CancellationException) {
            // 总超时靠协程取消实现：CancellationException 必须原样抛出，
            // 被下面 catch (Exception) 吞掉的话超时会误报成「登录异常」。
            throw e
        } catch (e: ZfJwSession.ZfException.Credential) {
            refreshCaptcha()
            return NoticeFeedback(e.message ?: "登录失败，请检查账号密码", NoticeTone.Error)
        } catch (e: ZfJwSession.ZfException) {
            refreshCaptcha()
            return NoticeFeedback(e.message ?: "登录失败", NoticeTone.Error)
        } catch (e: Exception) {
            // 兜底：链路里任何未归类异常都不能把开关卡在转圈态
            Log.w(TAG, "verify failed", e)
            refreshCaptcha()
            return NoticeFeedback(
                "登录异常：${e.javaClass.simpleName} ${e.message.orEmpty()}".trim(),
                NoticeTone.Error,
            )
        }
        // 登录成功：凭证 + 会话 cookie 一起落库
        credentialStore.save(user, pwd, session.exportSession())
        prefs.setDetectEnabled(true)
        JwDetectScheduler.ensurePeriodicWork(appContext, prefs.detectSettings.first())
        // 首检失败（网络/协议）不回滚开关——凭证已验证通过，开关是有效状态；
        // 失败原因由 outcome 文案透出，状态区同时记录 lastError 供回看。
        val outcome = try {
            // 刚登进来的会话直接给首检用，省一次登录（也避免后台那条「无会话」分支）
            JwDetectRunner(appContext).run(JwDetectRunner.Trigger.Manual, liveSession = session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            JwDetectRunner.Outcome.Failed("首次检测异常：${e.message.orEmpty()}")
        } finally {
            // 首检用完即释放连接池；下次检测走存下来的 cookie
            session.shutdown()
            pendingSession = null
            refreshCaptcha()
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
            val outcome = JwDetectRunner(appContext).run(JwDetectRunner.Trigger.Manual)
            // 会话过期（本校准登录必须填验证码）：顺手换一张验证码，
            // 用户填完点「重新验证」即可恢复后台检测
            if (outcome is JwDetectRunner.Outcome.Skipped && "会话" in outcome.reason) refreshCaptcha()
            onDone(outcome)
        }
    }
    companion object {
        /**
         * 开启流程的整链总超时。链路含 CAS 登录 + SSO + 两页课表抓取，正常 2–5 秒；
         * 校园网络偶尔每步 20 秒级（实测正常课表抓取 0.1–0.3 秒，异常时不封顶）。
         * 超过这个时间明确报错，好过无限转圈让用户不知道发生了什么。
         */
        private const val LOGIN_TOTAL_TIMEOUT_MS = 60_000L

        /** 真机排错用：异常都带堆栈落到 logcat（TAG=uid 过滤 "TweakDetect"）。 */
        private const val TAG = "TweakDetect"

        fun Factory(context: Context) = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                TweakDetectViewModel(context.applicationContext) as T
        }
    }
}
