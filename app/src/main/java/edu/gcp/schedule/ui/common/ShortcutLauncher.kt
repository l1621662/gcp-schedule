package edu.gcp.schedule.ui.common

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import edu.gcp.schedule.domain.ShortcutItem
import edu.gcp.schedule.domain.ShortcutLaunchPlan
import edu.gcp.schedule.domain.Shortcuts

/**
 * 快捷方式执行层（DESIGN §4.16）：domain 折算出的 [ShortcutLaunchPlan] 在这里变成
 * Intent 并拉起，系统异常统一翻译成用户可读文案。
 *
 * 返回 null = 已拉起；非 null = 错误文案（调用方直接展示）。今日页点击与设置页
 * 「测试」共用这一个入口，两条路径的错误口径才不会漂移。
 */
object ShortcutLauncher {

    fun launch(context: Context, item: ShortcutItem): String? {
        val plan = Shortcuts.resolveLaunchPlan(item) ?: return "「${item.name}」还没有配置目标"
        return try {
            when (plan) {
                is ShortcutLaunchPlan.ExplicitComponent ->
                    launchComponent(context, item.name, plan)
                is ShortcutLaunchPlan.UriView -> launchUri(context, item.name, plan)
                is ShortcutLaunchPlan.LaunchPackage -> launchPackage(context, item.name, plan)
            }
        } catch (_: SecurityException) {
            "系统拒绝了「${item.name}」的启动"
        }
    }

    /** 显式组件直达，无兜底——Activity 不存在就是不存在，报错让用户去编辑表单改目标。 */
    private fun launchComponent(
        context: Context,
        name: String,
        plan: ShortcutLaunchPlan.ExplicitComponent,
    ): String? = try {
        context.startActivity(ShortcutIntents.explicit(plan.pkg, plan.activity))
        null
    } catch (_: ActivityNotFoundException) {
        "未安装「$name」，或它的这个页面已不存在"
    }

    /**
     * 打开链接。带包名时先限定宿主（淘宝身份码要在淘宝 App 内打开，落进浏览器就没意义了），
     * 宿主拉不起再去掉包名给任意能处理的应用——light-life 验证过的兜底链。
     */
    private fun launchUri(
        context: Context,
        name: String,
        plan: ShortcutLaunchPlan.UriView,
    ): String? {
        if (plan.pkg != null) {
            try {
                context.startActivity(ShortcutIntents.uri(plan.uri, plan.pkg))
                return null
            } catch (_: ActivityNotFoundException) {
                // 落到无包名重试
            }
        }
        return try {
            context.startActivity(ShortcutIntents.uri(plan.uri, null))
            null
        } catch (_: ActivityNotFoundException) {
            "没有应用可以打开「$name」的链接"
        }
    }

    /** 只填包名：拉起目标 App 启动页；包不可见/未安装时 getLaunchIntentForPackage 返回 null。 */
    private fun launchPackage(
        context: Context,
        name: String,
        plan: ShortcutLaunchPlan.LaunchPackage,
    ): String? {
        val intent = ShortcutIntents.launcher(context, plan.pkg)
            ?: return "未安装「$name」"
        return try {
            context.startActivity(intent)
            null
        } catch (_: ActivityNotFoundException) {
            "未安装「$name」"
        }
    }
}

/**
 * Intent 构造的唯一出口：今日页拉起与桌面 Pin（[ShortcutPinner]）共用，
 * 保证「点 chip」和「点桌面图标」落点是同一个页面。
 * Pin 场景没有运行时兜底机会，uri 带包名时就固定带包名的版本。
 */
internal object ShortcutIntents {

    fun explicit(pkg: String, activity: String): Intent =
        Intent()
            .setClassName(pkg, activity)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun uri(uri: String, pkg: String?): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse(uri)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (pkg != null) setPackage(pkg)
        }

    /** null = 包不可见/未安装/无启动页。 */
    fun launcher(context: Context, pkg: String): Intent? =
        context.packageManager.getLaunchIntentForPackage(pkg)?.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK,
        )
}
