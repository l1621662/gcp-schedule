package edu.jxslu.schedule.ui.me

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import edu.jxslu.schedule.ui.widget.TodayWidgetReceiverLarge
import edu.jxslu.schedule.ui.widget.TodayWidgetReceiverSmall
import edu.jxslu.schedule.ui.widget.TodayWidgetReceiverWide

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

    /** 是否已把某条目添加到桌面（状态徽标 + 设置页计数用）。 */
    fun addedCount(context: Context, receiver: Class<*>): Int =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, receiver)).size

    /**
     * 一键添加条目到桌面。返回 false 表示当前桌面不支持，由调用方提示手动添加。
     *
     * 系统确认框（「要添加小组件吗？」）由桌面弹出——这就是
     * 「进入设置界面自动向用户申请添加桌面快捷方式」的实际落点：我们发起申请，
     * 用户在系统框里一键确认，不需要用户自己去小组件选择器里翻。
     */
    fun requestPin(context: Context, receiver: Class<*>): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        if (!manager.isRequestPinAppWidgetSupported) return false
        return runCatching {
            manager.requestPinAppWidget(ComponentName(context, receiver), null, null)
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

    private fun tryStart(context: Context, intent: Intent): Boolean =
        runCatching {
            if (intent.resolveActivity(context.packageManager) == null) return@runCatching false
            context.startActivity(intent)
            true
        }.getOrDefault(false)
}

/** 小组件的三个条目与默认尺寸（与 res/xml/widget_info_*.xml 一一对应）。 */
internal data class WidgetEntry(
    val name: String,
    val summary: String,
    val receiver: Class<*>,
)

internal val widgetEntries = listOf(
    WidgetEntry("2×2", "紧凑日期 + 正在上 / 下一节课", TodayWidgetReceiverSmall::class.java),
    WidgetEntry("4×2", "日期 + 下一节课（横条）", TodayWidgetReceiverWide::class.java),
    WidgetEntry("4×4", "完整今日 + 明日预告", TodayWidgetReceiverLarge::class.java),
)
