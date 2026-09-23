package edu.gcp.schedule.ui.common

import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.os.Build
import androidx.annotation.RequiresApi
import edu.gcp.schedule.R
import edu.gcp.schedule.domain.ShortcutItem

/**
 * 把快捷方式钉到桌面（DESIGN §3.8）：长按 chip → 「添加到桌面」。
 * 走 requestPinShortcut，桌面会弹系统确认框——用户取消我们不感知也不需要感知。
 * 意图构造与今日页拉起共用 [ShortcutIntents]，钉出去的图标打开的就是同一个页面。
 */
object ShortcutPinner {

    /** 返回 null = 已提交系统确认；非 null = 用户可读错误。 */
    fun pin(context: Context, item: ShortcutItem): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            // minSdk 26 实际到不了这里，防御一下
            return "系统版本过低，不支持添加到桌面"
        }
        val manager = context.getSystemService(Context.SHORTCUT_SERVICE) as? ShortcutManager
        if (manager == null || !manager.isRequestPinShortcutSupported) {
            return "当前桌面不支持添加快捷方式"
        }
        val intent = when (val plan = edu.gcp.schedule.domain.Shortcuts.resolveLaunchPlan(item)) {
            is edu.gcp.schedule.domain.ShortcutLaunchPlan.ExplicitComponent ->
                ShortcutIntents.explicit(plan.pkg, plan.activity)
            is edu.gcp.schedule.domain.ShortcutLaunchPlan.UriView ->
                ShortcutIntents.uri(plan.uri, plan.pkg)
            is edu.gcp.schedule.domain.ShortcutLaunchPlan.LaunchPackage ->
                ShortcutIntents.launcher(context, plan.pkg)
                ?: return "未安装「${item.name}」"
            null -> return "「${item.name}」还没有配置目标"
        }
        val info = ShortcutInfo.Builder(context, "gcp_sc_${item.id}")
            .setShortLabel(item.name)
            .setLongLabel(item.name)
            .setIcon(pinIcon(context, item))
            .setIntent(intent)
            .build()
        return try {
            // 返回值是 API 契约的一部分：过了 isRequestPinShortcutSupported 检查，
            // 桌面仍可能在提交时拒绝，这时不能当成功
            if (manager.requestPinShortcut(info, null)) null
            else "当前桌面不支持添加快捷方式"
        } catch (_: IllegalArgumentException) {
            "添加失败，请重试"
        }
    }

    /** 预设用品牌资源图；自定义用「名称首字 + 品牌色圆底」位图（Compose ImageVector 离屏画不了）。 */
    @RequiresApi(Build.VERSION_CODES.O)
    private fun pinIcon(context: Context, item: ShortcutItem): android.graphics.drawable.Icon {
        val res = presetShortcutIconRes(item)
        if (res != null) return android.graphics.drawable.Icon.createWithResource(context, res)
        return android.graphics.drawable.Icon.createWithBitmap(letterBadge(item.name))
    }

    /**
     * 128px 圆底首字徽章。颜色固定用品牌青（ui/theme 的 PrimaryLight）：
     * Pin 发生在任意 Activity 上下文之外，取不到当前主题，取固定值保证观感稳定。
     */
    private fun letterBadge(name: String): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0F7C7C.toInt() }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, fill)
        val glyph = name.trim().take(1).ifEmpty { "?" }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            textSize = 64f
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            // 贴上底色圆再挖掉首字范围外的溢出，防长字符出圈
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        val baseline = size / 2f - (text.descent() + text.ascent()) / 2f
        canvas.drawText(glyph, size / 2f, baseline, text)
        return bitmap
    }
}
