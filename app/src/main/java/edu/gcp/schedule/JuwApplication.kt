package edu.gcp.schedule

import android.app.Application
import edu.gcp.schedule.data.jw.JwDetectScheduler
import edu.gcp.schedule.data.repo.ScheduleRepository
import edu.gcp.schedule.ui.reminder.ClassReminder
import edu.gcp.schedule.ui.widget.TodayWidgetRefresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class JuwApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // 进程级 context 注入（Graph.appContext）：后台协程落盘等场景免持 Activity 引用
        Graph.contextProvider = { this }
        val repo = Graph.repository(this)
        appScope.launch {
            // 仅保证节次与学期默认值；课表默认空，由教务导入
            repo.ensureDefaults()
            // 桌面小组件（DESIGN §3.6）：冷启动主动刷一次 + 排 15 分钟周期兜底。
            // refreshNow 内部无 widget 绑定时是廉价的空跑（getGlanceIds 为空），
            // 不会白做 RemoteViews 组装。
            TodayWidgetRefresh.refreshNow(this@JuwApplication)
            TodayWidgetRefresh.ensurePeriodicWork(this@JuwApplication)
            // 上课提醒（DESIGN §3.7）：冷启动重排下一个提醒 + 周期核对兜底。
            // 提醒关/无课时 scheduleNext 内部是撤销闹钟的空跑，很廉价。
            ClassReminder.scheduleNext(this@JuwApplication)
            ClassReminder.ensurePeriodicWork(this@JuwApplication)
            // 调课自动检测（DESIGN §4.17）：周期任务按当前设置重排（开→排/关→撤），
            // 距上次检测超过一个周期时冷启动立即补测一次（兜 WorkManager 被 ROM 推迟）。
            // 功能默认关闭，关闭态下这两步都是零成本空跑。
            val detectPrefs = Graph.displayPrefs(this@JuwApplication)
            val detectSettings = detectPrefs.detectSettings.first()
            JwDetectScheduler.ensurePeriodicWork(this@JuwApplication, detectSettings)
            val detectOverdue = detectSettings.lastCheckedAt == 0L ||
                System.currentTimeMillis() - detectSettings.lastCheckedAt >
                detectSettings.periodHours * 3_600_000L
            if (detectSettings.enabled && !detectSettings.disabled && detectOverdue) {
                JwDetectScheduler.enqueueOneTime(this@JuwApplication)
            }
        }
        // 课表数据一变就推给桌面：用户在 App 里改完课，回桌面立刻是新内容，
        // 不必等下一个 15 分钟兜底。flows 本身是 Room 驱动，只在真实写库时发射，无轮询。
        // 提醒也依赖这三份数据：同一处重排下一个提醒，改完课/换课表即刻生效。
        appScope.launch {
            kotlinx.coroutines.flow.combine(
                repo.courses,
                repo.semester,
                repo.timeSlots,
            ) { _, _, _ -> Unit }.collect {
                TodayWidgetRefresh.refreshNow(this@JuwApplication)
                ClassReminder.scheduleNext(this@JuwApplication)
            }
        }
    }
}
