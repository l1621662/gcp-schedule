package edu.jxslu.schedule.ui.me

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.data.calendar.CalendarSyncer
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.domain.CalendarSyncDefaults
import edu.jxslu.schedule.domain.ScheduleExporter
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.WheelValueDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CalendarAdd01
import me.rerere.hugeicons.stroke.CalendarClock
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.Delete02

/** 同步前置条件检测结果：驱动同步行的可用态与说明文案（DESIGN §4.12 的就绪口径）。 */
sealed interface SyncReadiness {
    /** 学期与作息齐全，可展开出 N 条课程事件。 */
    data class Ready(val eventCount: Int) : SyncReadiness

    /** 学期未配置（展开需要学期起始日）。 */
    data object NoSemester : SyncReadiness

    /** 当前课表没有课程。 */
    data object Empty : SyncReadiness
}

/**
 * 日历同步子页（DESIGN §4.12）：同步 / 一键删除已同步 / 提前提醒时长。
 *
 * 从「我的 → 日历同步」进入。同步口径与课表页分享弹层完全一致
 * （[ScheduleRepository.expandedCalendarEvents]），权限也由本页运行时申请。
 * 页面进阶反馈：进页即展示就绪状态（学期/课程/事件数）；同步与删除期间整页忙碌
 * （进度条 + 动作禁用），防止重复触发——写整学期事件在低端机上要好几秒。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSettingsScreen(
    onBack: () -> Unit,
    viewModel: CalendarSettingsViewModel = viewModel(
        factory = CalendarSettingsViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val reminder by viewModel.reminderMinutes.collectAsStateWithLifecycle()
    val readiness by viewModel.readiness.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showReminderPicker by remember { mutableStateOf(false) }

    // 授权后是否继续同步：只有从「同步」入口发起的授权才续跑，直接点权限行不附带写动作
    var syncAfterGrant by remember { mutableStateOf(false) }
    var pendingSync by remember { mutableStateOf(false) }

    // 日历权限状态：进页与从系统设置返回（ON_RESUME）时各读一次
    var calendarGranted by remember {
        mutableStateOf(hasCalendarPermission(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                calendarGranted = hasCalendarPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    val calendarPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grants ->
        calendarGranted = grants.values.all { it }
        // syncAfterGrant 是一次性意图：无论授予与否都消费掉，
        // 避免「点同步→拒绝→事后从权限行授权」被误判为要续跑同步
        val continueSync = syncAfterGrant
        syncAfterGrant = false
        if (grants.values.all { it }) {
            pendingSync = continueSync
        } else {
            viewModel.onPermissionDenied()
        }
    }

    // 权限授予后的续跑放 LaunchedEffect，避免在 launcher 回调里直接调 VM 引发重组竞态
    //（与 WeekScreen 分享弹层的同步入口同一模式）
    LaunchedEffect(pendingSync) {
        if (pendingSync) {
            pendingSync = false
            viewModel.sync(context)
        }
    }

    fun startSync() {
        syncAfterGrant = true
        val needed = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        ).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            viewModel.sync(context)
        } else {
            calendarPermissionLauncher.launch(needed.toTypedArray())
        }
    }

    fun requestCalendarPermission() {
        syncAfterGrant = false
        val needed = listOf(
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
        ).filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) calendarPermissionLauncher.launch(needed.toTypedArray())
    }

    // 未授权不禁用同步行：点「同步」本身就是授权入口（运行时申请后续跑）
    val syncEnabled = readiness is SyncReadiness.Ready && !busy

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日历同步") },
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
                .padding(16.dp),
        ) {
            SettingsSection(
                title = "同步到手机日历",
                subtitle = "把当前课表写入系统日历（课程名 / 教室 / 教师）；重复同步先删旧事件再整表重写。",
            ) {
                if (busy) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(6.dp))
                }
                SettingItem(
                    title = "同步课表到日历",
                    subtitle = when {
                        busy -> "正在写入日历…"
                        readiness is SyncReadiness.Ready ->
                            "将写入 ${(readiness as SyncReadiness.Ready).eventCount} 条日程 · 删除重写：以 App 课表为准"
                        readiness is SyncReadiness.NoSemester -> "先到「我的 → 课表设置」配置学期"
                        readiness == null -> "正在统计…"
                        else -> "当前课表还没有课程"
                    },
                    onClick = { startSync() },
                    enabled = syncEnabled,
                    icon = HugeIcons.CalendarAdd01,
                )
                SettingItem(
                    title = "一键删除已同步课程",
                    subtitle = if (busy) "请等当前动作完成" else "只删除本应用写入的日历事件",
                    onClick = { showDeleteConfirm = true },
                    enabled = !busy,
                    icon = HugeIcons.Delete02,
                )
            }

            SettingsSection(
                title = "提前提醒",
                subtitle = "对同步到日历的课程生效；修改后重新同步即可更新已同步事件的提醒。",
            ) {
                SettingItem(
                    title = "提醒时长",
                    subtitle = "同步时写入日历的提前提醒",
                    value = CalendarSyncDefaults.reminderLabel(reminder),
                    onClick = { showReminderPicker = true },
                    enabled = !busy,
                    icon = HugeIcons.CalendarClock,
                )
            }

            SettingsSection(
                title = "日历权限",
                subtitle = "同步与删除都需要读写手机日历；未授权时点上面「同步课表到日历」也会发起授权。",
            ) {
                SettingItem(
                    title = "日历读写权限",
                    subtitle = if (calendarGranted) "已授予，可以同步" else "未授予，点此授权",
                    onClick = if (calendarGranted) {
                        null
                    } else {
                        { requestCalendarPermission() }
                    },
                    icon = HugeIcons.CalendarSetting01,
                    showArrow = !calendarGranted,
                )
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("删除已同步课程") },
            text = { Text("将从手机日历中删除全部由水贝贝同步的课程事件（不影响你自己添加的日程）。") },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        showDeleteConfirm = false
                        viewModel.deleteSynced(context)
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("取消") }
            },
        )
    }

    if (showReminderPicker) {
        WheelValueDialog(
            title = "提前提醒时长",
            values = CalendarSyncDefaults.REMINDER_CHOICES.map { CalendarSyncDefaults.reminderLabel(it) },
            initialIndex = CalendarSyncDefaults.reminderChoiceIndex(reminder),
            onConfirm = { index ->
                showReminderPicker = false
                viewModel.setReminderMinutes(CalendarSyncDefaults.REMINDER_CHOICES[index])
            },
            onDismiss = { showReminderPicker = false },
        )
    }
}

private fun hasCalendarPermission(context: Context): Boolean =
    listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR).all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

class CalendarSettingsViewModel(
    private val repo: ScheduleRepository,
) : ViewModel() {

    /** 提前提醒分钟数（全局）。 */
    val reminderMinutes: StateFlow<Int> = repo.calendarReminderMinutes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CalendarSyncDefaults.DEFAULT_REMINDER_MINUTES)

    /** 就绪状态随学期/课表数据自动刷新；null = 还在算。展开是纯 JVM 计算，放 Default 池。 */
    val readiness: StateFlow<SyncReadiness?> = combine(
        repo.semester,
        repo.courses,
        repo.timeSlots,
    ) { semester, courses, slots ->
        when {
            semester == null -> SyncReadiness.NoSemester
            courses.isEmpty() -> SyncReadiness.Empty
            else -> SyncReadiness.Ready(ScheduleExporter.expandEvents(courses, slots, semester).ok.size)
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _busy = MutableStateFlow(false)

    /** 同步/删除进行中：UI 据此禁用入口并显示进度。 */
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)

    /** 动作结果，UI 侧消费后置空（Snackbar 一次性展示）。 */
    val message: StateFlow<String?> = _message

    fun consumeMessage() {
        _message.value = null
    }

    fun setReminderMinutes(minutes: Int) {
        viewModelScope.launch {
            repo.setCalendarReminderMinutes(minutes)
            _message.value = "提醒时长已改为${CalendarSyncDefaults.reminderLabel(minutes)}，重新同步后生效"
        }
    }

    fun sync(context: Context) {
        // 忙碌标志在进入协程前同步置位：viewModelScope 的调度有延迟，
        // 放协程体内会让同帧内的第二次点击也通过防线
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val events = repo.expandedCalendarEvents()
                if (events == null) {
                    _message.value = "请先在「我的 → 课表设置」配置学期，并确认课表不为空"
                    return@launch
                }
                _message.value = when (val r = CalendarSyncer.sync(context, events, reminderMinutes.value)) {
                    is CalendarSyncer.CalendarSyncResult.Success ->
                        "已同步 ${r.count} 条课程到手机日历（${CalendarSyncDefaults.reminderLabel(reminderMinutes.value)}）"
                    is CalendarSyncer.CalendarSyncResult.Deleted ->
                        "已删除 ${r.count} 条课程日历"
                    is CalendarSyncer.CalendarSyncResult.NoCalendarAccount ->
                        "手机上没有可用日历账户，请先在系统日历中登录或添加账户"
                    is CalendarSyncer.CalendarSyncResult.NoPermission ->
                        "日历权限未授予，无法同步"
                    is CalendarSyncer.CalendarSyncResult.Error ->
                        "同步失败：${r.message}"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun deleteSynced(context: Context) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                _message.value = when (val r = CalendarSyncer.deleteSynced(context)) {
                    is CalendarSyncer.CalendarSyncResult.Deleted ->
                        if (r.count == 0) "日历中没有本应用同步的课程" else "已从日历删除 ${r.count} 条课程"
                    is CalendarSyncer.CalendarSyncResult.NoPermission ->
                        "日历权限未授予，无法删除"
                    is CalendarSyncer.CalendarSyncResult.Error ->
                        "删除失败：${r.message}"
                    is CalendarSyncer.CalendarSyncResult.Success ->
                        "已从日历删除课程"
                    is CalendarSyncer.CalendarSyncResult.NoCalendarAccount ->
                        "手机上没有可用日历账户"
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun onPermissionDenied() {
        _message.value = "未授予日历权限，无法同步"
    }

    class Factory(private val repo: ScheduleRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            CalendarSettingsViewModel(repo) as T
    }
}
