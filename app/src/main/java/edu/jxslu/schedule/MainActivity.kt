package edu.jxslu.schedule

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import edu.jxslu.schedule.ui.common.rememberAppHaptics
import edu.jxslu.schedule.ui.me.SettingsScreen
import edu.jxslu.schedule.ui.theme.JuwTheme
import edu.jxslu.schedule.ui.today.TodayScreen
import edu.jxslu.schedule.ui.water.WaterViewModel
import edu.jxslu.schedule.ui.week.WeekScreen
import edu.jxslu.schedule.domain.ThemeMode
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import me.rerere.hugeicons.stroke.Book01
import me.rerere.hugeicons.stroke.Calendar01
import me.rerere.hugeicons.stroke.Settings01
import me.rerere.hugeicons.HugeIcons

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JuwRoot {
                JuwApp()
            }
        }
    }
}

/**
 * 主题在根上解析：深浅色由显示偏好里的 [ThemeMode] 决定（默认跟随系统），
 * 强制浅/深时忽略系统设置。放在 setContent 最外层，全 App（含弹层）统一生效。
 * 主界面与教务导入 Activity 共用，保证两个窗口深浅色一致。
 */
@Composable
internal fun JuwRoot(content: @Composable () -> Unit) {
    val prefs by Graph.repository(LocalContext.current).displayPrefs
        .collectAsStateWithLifecycle(initialValue = null)
    val darkTheme = when (prefs?.themeMode) {
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
        ThemeMode.System, null -> isSystemInDarkTheme()
    }
    // 动态取色可关（我的 → 通用）：用户想要固定的品牌蓝绿而不是壁纸色
    JuwTheme(darkTheme = darkTheme, dynamicColor = prefs?.dynamicColor ?: true) {
        content()
    }
}

private data class BottomTab(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
)

// ---- 转场动画 ----
// NavHost 现在只承载三个平级 tab：短促交叉淡入淡出，横向滑动感会误导层级。
// 设置类二级页已全部改为 SubpageActivity 独立窗口（底栏不可达），
// 它们的进出场动画是 Activity 级 overridePendingTransition（右侧推入/退出），不在这里。

private fun tabEnter(): EnterTransition = fadeIn(tween(180))

private fun tabExit(): ExitTransition = fadeOut(tween(180))

@Composable
fun JuwApp() {
    val context = LocalContext.current
    val navController = rememberNavController()
    val haptics = rememberAppHaptics()

    // 「我的 → 显示设置」的跨 Tab 触发：显示设置只有一个形态（课表页覆盖弹层），
    // 从「我的」发起时切到课表 Tab，并把这个事件喂给 WeekScreen 弹出与眼睛图标相同的面板。
    //
    // 为什么用 Channel 而不是 SharedFlow：SharedFlow(replay=0) 在**尚无订阅者**时
    // tryEmit 的值会被直接丢弃——navigate() 是异步的，WeekScreen 下一帧才进组合、
    // LaunchedEffect 才开始收集，同步 tryEmit 必然抢跑，面板永远弹不出来。
    // Channel 会把接收者出现前的发送缓冲住，消费后即清空（不会像 replay=1 那样
    // 每次回到课表 Tab 都重弹一次旧事件）。
    val displaySettingsRequests = remember { Channel<Unit>(Channel.BUFFERED) }
    val openDisplaySettings = {
        haptics.tap()
        navController.navigate(Routes.WEEK) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
        displaySettingsRequests.trySend(Unit)
        Unit
    }

    val tabs = listOf(
        BottomTab(Routes.TODAY, R.string.tab_today, HugeIcons.Calendar01),
        BottomTab(Routes.WEEK, R.string.tab_week, HugeIcons.Book01),
        BottomTab(Routes.ME, R.string.tab_me, HugeIcons.Settings01),
    )
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

    // 胖乖登录态没有 Flow：key 到 currentRoute，从开水页返回（或切 Tab）时重读 token。
    // 根因：无 key 的 remember 在登录成功返回后仍是旧值，入口卡片一直显示「未登录」。
    // 二级页改为独立窗口后，返回主窗口触发重组，这里随 currentRoute 重算。
    val waterLoggedIn = remember(currentRoute) { Graph.qiekj(context).localToken() != null }

    // 胖乖 ViewModel 挂 Activity 作用域：今日页快捷入口与开水页（独立窗口）各自持有，
    // 这里这份供今日页直接触发 unlock 时使用
    val waterViewModel: WaterViewModel = viewModel(
        viewModelStoreOwner = context as ComponentActivity,
        factory = WaterViewModel.Factory(Graph.qiekj(context)),
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                tabs.forEach { tab ->
                    NavigationBarItem(
                        selected = currentRoute == tab.route,
                        onClick = {
                            haptics.tap()
                            navController.navigate(tab.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = stringResource(tab.labelRes),
                            )
                        },
                        label = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.TODAY,
            modifier = Modifier.padding(padding),
            enterTransition = { tabEnter() },
            exitTransition = { tabExit() },
            popEnterTransition = { tabEnter() },
            popExitTransition = { tabExit() },
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    // 教务导入是独立 Activity：新窗口覆盖，底层课表布局不动
                    onOpenJwImport = {
                        JwImportActivity.start(context)
                    },
                    // 「尚未开学」空态 CTA：跳课表设置子页（独立窗口）
                    onOpenTimetableSettings = {
                        SubpageActivity.start(context, SubpageScreen.TIMETABLE_SETTINGS)
                    },
                    // 一键开水卡常显（未登录给未登录态，显示设置可关，DESIGN §3.3）；
                    // 登录态由 WaterViewModel 自带，外层不再按登录与否隐藏整卡
                    onOpenWater = { SubpageActivity.start(context, SubpageScreen.WATER) },
                    // 快捷方式网格：长按图标进设置页（null）；Snackbar「去设置」带失败条目
                    // id 直达该条目的编辑弹层（DESIGN §3.8 的就地修正闭环）
                    onOpenShortcuts = { focusItemId ->
                        SubpageActivity.start(context, SubpageScreen.SHORTCUTS, focusItemId)
                    },
                    waterViewModel = waterViewModel,
                )
            }
            composable(Routes.WEEK) {
                WeekScreen(
                    onOpenJwImport = {
                        JwImportActivity.start(context)
                    },
                    // 切换弹层「管理课表」→ 独立窗口；
                    // 眼睛是页内覆盖弹层（不跳页，课表保持可见，见 WeekScreen）
                    onOpenTimetableManage = {
                        SubpageActivity.start(context, SubpageScreen.TIMETABLE_MANAGE)
                    },
                    // 导入图标有调课提醒气泡时（DESIGN §4.17），点击直达「更新课表」
                    onOpenScheduleUpdate = {
                        SubpageActivity.start(context, SubpageScreen.SCHEDULE_UPDATE)
                    },
                    // 「我的 → 显示设置」跨 Tab 触发，弹出的面板与眼睛图标相同
                    // receiveAsFlow：WeekScreen 只需要消费事件；Channel 保证
                    // 订阅者出现前的事件不丢（SharedFlow replay=0 会直接丢）
                    openDisplayRequests = displaySettingsRequests.receiveAsFlow(),
                )
            }
            composable(Routes.ME) {
                SettingsScreen(
                    onOpenJwImport = {
                        JwImportActivity.start(context)
                    },
                    onOpenScores = {
                        SubpageActivity.start(context, SubpageScreen.SCORES)
                    },
                    // 二级页统一独立窗口：底栏不可达，返回栈语义清晰（根因见 SubpageActivity）
                    onOpenTimetableManage = {
                        SubpageActivity.start(context, SubpageScreen.TIMETABLE_MANAGE)
                    },
                    onOpenTimetableSettings = {
                        SubpageActivity.start(context, SubpageScreen.TIMETABLE_SETTINGS)
                    },
                    // 显示设置 = 课表页覆盖弹层：跨 Tab 触发（见 displaySettingsRequests），
                    // 不再是独立子页
                    onOpenDisplaySettings = openDisplaySettings,
                    onOpenDataSettings = {
                        SubpageActivity.start(context, SubpageScreen.DATA_SETTINGS)
                    },
                    onOpenCourseTweak = {
                        SubpageActivity.start(context, SubpageScreen.COURSE_TWEAK)
                    },
                    onOpenTweakDetect = {
                        SubpageActivity.start(context, SubpageScreen.TWEAK_DETECT)
                    },
                    onOpenWidgetSettings = {
                        SubpageActivity.start(context, SubpageScreen.WIDGET_SETTINGS)
                    },
                    onOpenCalendarSettings = {
                        SubpageActivity.start(context, SubpageScreen.CALENDAR_SETTINGS)
                    },
                    onOpenReminderSettings = {
                        SubpageActivity.start(context, SubpageScreen.REMINDER_SETTINGS)
                    },
                    onOpenShortcuts = {
                        SubpageActivity.start(context, SubpageScreen.SHORTCUTS)
                    },
                    onOpenWater = {
                        SubpageActivity.start(context, SubpageScreen.WATER)
                    },
                    onOpenWaterSettings = {
                        SubpageActivity.start(context, SubpageScreen.WATER_SETTINGS)
                    },
                    waterLoggedIn = waterLoggedIn,
                )
            }
        }
    }
}

object Routes {
    const val TODAY = "today"
    const val WEEK = "week"
    const val ME = "me"
}
