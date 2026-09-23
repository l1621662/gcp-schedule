package edu.gcp.schedule

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import edu.gcp.schedule.ui.jwvw.JwImportMode
import edu.gcp.schedule.ui.jwvw.JwImportScreen

/**
 * 教务导入独立窗口。
 *
 * 根因：此前 jw_import 与三个 tab 同 NavHost，底栏显隐绑定路由——进入导入页的瞬间
 * 外层 Scaffold padding 变化，切换动画里的课表被突然拉伸。改成独立 Activity：
 * 新窗口直接覆盖，底层课表的布局完全不动；导入写库走 Graph 单例 + 响应式流，
 * 返回后课表自动刷新。Cookie 在 CookieManager 全局生效，登录会话不受影响。
 *
 * [EXTRA_MODE] 决定导入对象：课表（默认）或成绩（DESIGN §4.15）。
 *
 * 动画与 [SubpageActivity] 同款：打开 = 新窗口从右缘推入覆盖主窗口（slide_in_right），
 * 关闭 = 向右滑出（slide_out_right），主窗口全程原地不动。此前该窗口没配转场，
 * 教务导入是从主界面进入频率最高的页面，缺席反而最扎眼。
 */
class JwImportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val mode = intent.getStringExtra(EXTRA_MODE)
            ?.let { name -> JwImportMode.entries.firstOrNull { it.name == name } }
            ?: JwImportMode.Schedule
        setContent {
            JuwRoot {
                JwImportScreen(onBack = { finish() }, mode = mode)
            }
        }
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION") // API 34+ 的 overrideActivityTransition 需要 34 才可用，minSdk 26 仍走这条
        overridePendingTransition(0, R.anim.slide_out_right)
    }

    companion object {
        private const val EXTRA_MODE = "mode"

        fun start(context: Context, mode: JwImportMode = JwImportMode.Schedule) {
            val intent = Intent(context, JwImportActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
            context.startActivity(intent)
            // 只有 context 是 Activity 时才有窗口动画可言
            (context as? Activity)?.let {
                @Suppress("DEPRECATION")
                it.overridePendingTransition(R.anim.slide_in_right, 0)
            }
        }
    }
}
