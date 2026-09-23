package edu.jxslu.schedule.ui.me

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import edu.jxslu.schedule.BuildConfig
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.R
import edu.jxslu.schedule.domain.ThemeMode
import edu.jxslu.schedule.ui.common.AppNoticeVisuals
import edu.jxslu.schedule.ui.common.AppSnackbarHost
import edu.jxslu.schedule.ui.common.LoadingHint
import edu.jxslu.schedule.ui.common.NoticeTone
import edu.jxslu.schedule.ui.common.SettingChoiceRow
import edu.jxslu.schedule.ui.common.SettingItem
import edu.jxslu.schedule.ui.common.SettingSwitchRow
import edu.jxslu.schedule.ui.common.SettingsSection
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CalendarSetting01
import me.rerere.hugeicons.stroke.BellRing
import me.rerere.hugeicons.stroke.CalendarSync
import me.rerere.hugeicons.stroke.ColorPicker
import me.rerere.hugeicons.stroke.Clock01
import me.rerere.hugeicons.stroke.Database
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.GraduationScroll
import me.rerere.hugeicons.stroke.Github
import me.rerere.hugeicons.stroke.GridView
import me.rerere.hugeicons.stroke.Import
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Layout2Row
import me.rerere.hugeicons.stroke.Palette
import me.rerere.hugeicons.stroke.Radar01
import me.rerere.hugeicons.stroke.Vibrate

/** 公开仓库地址（MIT）；「开源仓库」点击后经系统浏览器打开。 */
private const val REPO_URL = "https://github.com/Inonvation/JUWP-Schedule"

/**
 * 用系统意图打开链接。返回 null = 已拉起；非 null = 用户可读错误，由调用方展示
 * （不在这里弹提示：函数没有 Compose 作用域，而提示要走页面统一的 [AppSnackbarHost]，
 * 观感与全 App 其余提示一致，也免得在纯函数里塞 Activity 依赖）。
 */
private fun openUrl(context: Context, url: String): String? = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    null
} catch (_: ActivityNotFoundException) {
    "没有可打开链接的应用"
}

/**
 * 「我的」= 设置枢纽（DESIGN §3.3）。
 *
 * 五个分区（2026-09-20 重排）：通用（全局观感）→ 课表（随当前课表，
 * 组内按「配置 → 使用 → 数据」流排）→ 小组件与日历（全局）→ 扩展服务（第三方 ·
 * 非学校官方 + 今日页快捷入口）→ 关于。每卡至少 2 条，不再有单条目卡。
 * 排版约定（DESIGN §3.3）：分区卡不写副标题（「课表」卡保留当前课表名锚点、
 * 「扩展服务」卡保留第三方免责副标题）；入口行只说「这是什么」，子页内部功能
 * 不罗列；二/三选一用整行分段按钮，不放标题行尾部（三段选项在窄屏必然溢出）。
 * 显示设置入口 2026-09-20 起移除：唯一入口 = 课表页顶栏眼睛图标（DESIGN §3.1）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenJwImport: () -> Unit = {},
    onOpenScores: () -> Unit = {},
    onOpenTimetableManage: () -> Unit = {},
    onOpenTimetableSettings: () -> Unit = {},
    onOpenDataSettings: () -> Unit = {},
    onOpenCourseTweak: () -> Unit = {},
    onOpenWidgetSettings: () -> Unit = {},
    onOpenCalendarSettings: () -> Unit = {},
    onOpenReminderSettings: () -> Unit = {},
    onOpenShortcuts: () -> Unit = {},

    /** 我的 → 调课自动检测设置（DESIGN §4.17） */
    onOpenTweakDetect: () -> Unit = {},

    viewModel: MeViewModel = viewModel(
        factory = MeViewModel.Factory(Graph.repository(LocalContext.current)),
    ),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = rememberAppHaptics()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 设置页唯一的提示出口（目前只有「开源仓库」打不开一种）；走全 App 统一卡片
    val showNotice: (String) -> Unit = { message ->
        scope.launch {
            snackbar.showSnackbar(AppNoticeVisuals(message, tone = NoticeTone.Warning))
        }
    }

    Scaffold(
        // 根因：外层 JuwApp Scaffold 无 topBar，contentWindowInsets（systemBars）已垫了一个
        // 状态栏高度；TopAppBar 默认 windowInsets 再消费一次 → 顶栏上方双倍空白。
        // 顶部 inset 统一只由外层消费，这里归零。
        // 内容 inset 同理归零：底部导航栏 inset 已由外层（底栏高度）提供，
        // 内层再消费一次就是底部双倍空白。
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = WindowInsets(0, 0, 0, 0),
                title = { Text(stringResource(R.string.tab_me)) },
            )
        },
        snackbarHost = { AppSnackbarHost(snackbar) },
    ) { padding ->
        if (state.loading) {
            LoadingHint("正在读取设置", modifier = Modifier.fillMaxSize())
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- 通用（全局观感） ----
            SettingsSection(title = "通用") {
                SettingChoiceRow(
                    title = "外观主题",
                    icon = HugeIcons.Palette,
                    options = ThemeMode.entries.map { it.label },
                    selectedIndex = ThemeMode.entries.indexOf(state.themeMode),
                    onSelect = { index ->
                        haptics.toggle()
                        viewModel.setThemeMode(ThemeMode.entries[index])
                    },
                )
                SettingSwitchRow(
                    title = "触感反馈",
                    checked = state.displayPrefs.hapticsEnabled,
                    onCheckedChange = viewModel::setHapticsEnabled,
                    icon = HugeIcons.Vibrate,
                )
                // 关掉 Material You 壁纸取色，回到固定的蓝绿品牌色（Android 12+ 才有动态取色）
                SettingSwitchRow(
                    title = "动态取色",
                    subtitle = "跟随壁纸配色（Material You）",
                    checked = state.displayPrefs.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                    icon = HugeIcons.ColorPicker,
                )
            }

            // ---- 课表（当前课表级；导出与清空也是当前课表口径）----
            // 组内按「配置 → 使用 → 数据」流排：管理/设置/导入是配置，
            // 提醒/调课/检测是使用，成绩与数据是产出（DESIGN §3.3，2026-09-20 重排）
            SettingsSection(
                title = "课表",
                subtitle = "当前：${state.timetableName.ifBlank { "—" }}",
            ) {
                SettingItem(
                    title = "课表管理",
                    subtitle = "新建 · 切换 · 删除",
                    icon = HugeIcons.GridView,
                    onClick = onOpenTimetableManage,
                )
                SettingItem(
                    title = "课表设置",
                    subtitle = "学期起止 · 作息时间",
                    icon = HugeIcons.Clock01,
                    onClick = onOpenTimetableSettings,
                )
                SettingItem(
                    title = "教务导入",
                    subtitle = "从学校教务拉取课表",
                    icon = HugeIcons.Import,
                    onClick = onOpenJwImport,
                )
                SettingItem(
                    title = "上课提醒",
                    subtitle = "上课前提前通知，点开直达",
                    icon = HugeIcons.BellRing,
                    onClick = onOpenReminderSettings,
                )
                SettingItem(
                    title = "调课",
                    subtitle = "把某天的课调到另一天",
                    icon = HugeIcons.CalendarSync,
                    onClick = onOpenCourseTweak,
                )
                // 调课自动检测设置（DESIGN §4.17）：默认关闭；手动检测在课表页导入弹层
                SettingItem(
                    title = "调课自动检测",
                    subtitle = "自动登录教务比对课表 · 默认关闭",
                    icon = HugeIcons.Radar01,
                    onClick = onOpenTweakDetect,
                )
                SettingItem(
                    title = "成绩查询",
                    subtitle = "按学期查看 · 从教务导入",
                    icon = HugeIcons.GraduationScroll,
                    onClick = onOpenScores,
                )
                SettingItem(
                    title = "课表数据",
                    subtitle = "JSON 导出 / 导入 · 清空课程",
                    icon = HugeIcons.Database,
                    onClick = onOpenDataSettings,
                )
            }

            // ---- 小组件与日历（全局：条目与权限不随课表变化） ----
            SettingsSection(title = "小组件与日历") {
                SettingItem(
                    title = "桌面小组件",
                    subtitle = "三种尺寸 · 一键添加",
                    icon = HugeIcons.Layout2Row,
                    onClick = onOpenWidgetSettings,
                )
                SettingItem(
                    title = "日历同步",
                    subtitle = "写入系统日历 · 提前提醒",
                    icon = HugeIcons.CalendarSetting01,
                    onClick = onOpenCalendarSettings,
                )
            }

            // ---- 今日页（展示型入口，DESIGN §3.8） ----
            SettingsSection(title = "今日页") {
                SettingItem(
                    title = "快捷方式",
                    subtitle = "今日页快捷入口 · 添加与编辑",
                    icon = HugeIcons.Flash,
                    onClick = onOpenShortcuts,
                )
            }

            // ---- 关于 ----
            SettingsSection(title = "关于") {
                SettingItem(
                    title = "关于 水贝贝",
                    subtitle = "非学校官方应用 · 教务与第三方接口风险自负",
                    icon = HugeIcons.InformationCircle,
                    value = "v${BuildConfig.VERSION_NAME}",
                    showArrow = false,
                )
                SettingItem(
                    title = "开源仓库",
                    subtitle = "github.com/Inonvation/JUWP-Schedule",
                    icon = HugeIcons.Github,
                    onClick = { openUrl(context, REPO_URL)?.let(showNotice) },
                )
            }
        }
    }
}
