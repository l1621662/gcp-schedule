package edu.jxslu.schedule.ui.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * 课表小组件的 receiver（DESIGN §3.6）。
 *
 * **一个条目**（2026-09-20 改版）：选择器里只有「水贝贝 · 课表」，尺寸靠
 * `SizeMode.Exact` 自适应（[ScheduleWidget]），不再拆成 2×2 / 4×2 / 4×4 三条——
 * 三条目都能自由拖动，落到同一形态后内容完全相同，纯冗余（用户反馈的原话）。
 *
 * 历史说明：改版删掉了 `TodayWidgetReceiverSmall/Wide/Large` 三个 receiver 与三份
 * `res/xml/widget_info_*.xml`。**组件名变化会让更新前放在桌面上的旧组件失效**，
 * 用户需重新添加一次（已与用户确认接受）。
 */
class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        TodayWidgetRefresh.ensurePeriodicWork(context)
        TodayWidgetRefresh.enqueueRefresh(context)
    }
}
