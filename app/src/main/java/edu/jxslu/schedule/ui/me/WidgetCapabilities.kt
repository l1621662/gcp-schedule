package edu.jxslu.schedule.ui.me

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import edu.jxslu.schedule.ui.widget.ScheduleWidgetReceiver

/**
 * 小组件能力检测与系统跳转（DESIGN §3.6 设置页）。
 *
 * 原则：
 * - **检测全部只读**，不给系统弹任何东西；「去开启」都是用户点了才跳。
 * - **跳转一律 try/catch + resolveActivity 校验**，失败兜底到应用详情页——
 *   厂商设置的 Intent 随 ROM 版本变动，不能让它崩。
 */
internal object WidgetCapabilities {

    /** 桌面是否支持「应用内一键添加小组件」（API 26+ 的 requestPinAppWidget）。 */
    fun canPin(context: Context): Boolean =
        AppWidgetManager.getInstance(context).isRequestPinAppWidgetSupported

    /** 是否已把小组件添加到桌面（状态徽标 + 设置页计数用）。 */
    fun addedCount(context: Context): Int =
        AppWidgetManager.getInstance(context)
            .getAppWidgetIds(ComponentName(context, ScheduleWidgetReceiver::class.java)).size

    /**
     * 一键添加小组件到桌面。返回 false 表示当前桌面不支持，由调用方提示手动添加。
     *
     * 系统确认框（「要添加小组件吗？」）由桌面弹出——这就是
     * 「进入设置界面自动向用户申请添加桌面快捷方式」的实际落点：我们发起申请，
     * 用户在系统框里一键确认，不需要用户自己去小组件选择器里翻。
     */
    fun requestPin(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return runCatching {
            manager.requestPinAppWidget(
                ComponentName(context, ScheduleWidgetReceiver::class.java),
                null,
                null,
            )
        }.getOrDefault(false)
    }

    /** 是否已在电池优化白名单（加入后后台闹钟/任务不易被 Doze 推迟）。 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * 跳「请求忽略电池优化」系统确认框。
     *
     * 直接弹系统对话框需要 `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限（已声明）；
     * 个别 ROM 不支持该 action 时兜底到系统白名单列表页（用户手动找到本应用勾选）。
     */
    fun jumpBatteryOptimization(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
        if (tryStart(context, direct)) return
        tryStart(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }

    /**
     * 厂商自启动管理页。**尽力而为**：组件名是社区通行值、随 ROM 版本变动，
     * 全部失败则兜底到应用详情页（用户在里面找「自启动 / 允许后台运行」开关）。
     */
    fun jumpAutoStart(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            // 小米 HyperOS / MIUI：自启动管理
            "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
            // 华为 / 荣耀：应用启动管理
            "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            // OPPO / ColorOS：自启动管理
            "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
            // vivo：后台弹出与自启动
            "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        )
        candidates.forEach { (pkgName, cls) ->
            if (tryStart(context, Intent().setClassName(pkgName, cls))) return
        }
        // 兜底：应用详情页
        tryStart(
            context,
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:$pkg")),
        )
    }

    /**
     * 桌面不支持一键添加时的手动指引文案（不跳转，用户自己长按桌面）。
     * 只给文案不给弹法：提示统一由页面（WidgetSettingsScreen）的提示宿主展示，
     * 免得这里再拉一条与页面两套观感的系统 Toast。
     */
    const val manualAddHint: String = "当前桌面不支持一键添加，请长按桌面空白处 → 小组件 → 水贝贝"

    /**
     * 负一屏说明（DESIGN §3.6「负一屏」）。
     *
     * 澎湃OS / MIUI 的负一屏只收录「小米小部件」（需接入小米小部件开放平台并通过审核），
     * 本 App 是安卓原生小组件，系统不保证能拖进负一屏——这是平台限制，不是 App 的缺陷。
     * 文案给出两条可操作路径，避免用户以为「没做适配」。
     */
    const val launcherNote: String =
        "澎湃OS / MIUI 的负一屏只收录「小米小部件」，本应用是安卓原生小组件，可能搜不到。" +
            "替代：① 在负一屏右上角「+」搜索里试搜「水贝贝」；" +
            "② 打开「我的 → 日历同步」，课程进系统日历后由负一屏的「日历日程」卡片展示。"

    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching {
            if (intent.resolveActivity(context.packageManager) == null) return@runCatching false
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}

/**
 * 设置页的预览档位（DESIGN §3.6）。
 *
 * 单条目后**不再对应可添加的条目**：这四档只是「拖到多大长什么样」的形态说明
 * （2×2 / 4×2 紧凑、2×4 列表、4×4 周网格），预览缩略图按各自的实测 dp 走同一套
 * [edu.jxslu.schedule.ui.widget.widgetMetricsFor] 分档，保证预览与桌面所见一致。
 */
internal enum class WidgetPreviewSize(val label: String, val widthDp: Int, val heightDp: Int) {
    Small("2×2", 110, 110),
    Wide("4×2", 250, 110),
    Tall("2×4", 110, 250),
    Large("4×4", 250, 250),
}
