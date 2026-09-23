package edu.gcp.schedule.ui.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.gcp.schedule.Graph
import edu.gcp.schedule.domain.LocalTimeLike
import edu.gcp.schedule.domain.nextTodayBoundaryMinutes
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 小组件刷新调度（DESIGN §3.6「刷新策略」）。
 *
 * 两个触发源，互补：
 *
 * | 触发源 | 时机 | 作用 |
 * |--------|------|------|
 * | 边界闹钟 | 今天下一个「上课 / 下课」时刻 | 桌面真正需要变样子的只有这几个瞬间，准时 |
 * | WorkManager | 15 分钟周期 | 进程被杀、闹钟被 ROM 延迟、日期跨天时兜底 |
 *
 * **为什么用「闹钟 + Worker」而不是 widget 自己定时**：Glance 的 `provideContent` 挂起到会话
 * 关闭，组合里无法起常驻协程（见 [ScheduleWidget] 的说明）。系统级闹钟与 WorkManager 不依赖
 * App 存活，才能真正做到后台更新。
 *
 * **精度说明**：这里用 `setAndAllowWhileIdle`（不需要 `SCHEDULE_EXACT_ALARM` 权限，
 * 也不会因缺权限抛异常），在 Doze 下可能被推迟到下一个维护窗口。这是刻意的取舍：
 * 课表小组件不需要秒级准确，而精确闹钟权限会被应用商店与用户视为敏感权限。
 * 设置页的「忽略电池优化」引导能显著降低被推迟的概率。
 *
 * 文件名保留 `TodayWidget*`（改版只改了条目结构与渲染，刷新链路一行未动）——
 * 重命名会牵动 `JuwApplication` 与 Manifest，收益为零。
 */
internal object TodayWidgetRefresh {

    private const val TAG = "TodayWidgetRefresh"
    private const val PERIODIC_WORK = "today_widget_periodic"
    private const val ONE_SHOT_WORK = "today_widget_refresh"
    private const val ALARM_REQUEST_CODE = 4001

    /** 冷启动、数据变更、闹钟触发后统一走这里：立刻刷一次 + 排下一个边界闹钟。 */
    suspend fun refreshNow(context: Context) {
        // 没有任何实例绑在桌面时是纯空跑：不读库、不排闹钟（Application 每次冷启动都会调它）
        if (!runCatching { WidgetSnapshotStore.hasAnyBound(context) }.getOrDefault(false)) return
        // 调用方是 Application 冷启动协程与后台 Worker，绝不能把读库失败漏成进程崩溃
        runCatching {
            WidgetSnapshotStore.refreshAll(context)
            scheduleNextBoundary(context)
        }.onFailure { Log.w(TAG, "widget refresh failed", it) }
    }

    /** 把一次性的刷新排进 WorkManager 队列（供 BroadcastReceiver 调用，保证不阻塞主线程）。 */
    fun enqueueRefresh(context: Context) {
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** 15 分钟周期兜底。重复调用是幂等的（KEEP 策略）。 */
    fun ensurePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * 排「今天下一个上下课时刻」的闹钟。
     *
     * 无边界可排（今天没有剩余课程 / 不在学期内）时，就把闹钟挪到次日 0:05——
     * 跨天后今天的列表要换成明天的，需要一次刷新。用次日 0:05 而不是 0:00：
     * 0:00 前后系统忙碌，容易撞上 Doze 维护窗口而被进一步推迟。
     */
    private suspend fun scheduleNextBoundary(context: Context) {
        val repo = Graph.repository(context)
        val semester = repo.semester.first()
        val slots = repo.timeSlots.first()
        val courses = repo.courses.first()
        val today = LocalDate.now()
        val now = LocalTimeLike.now()

        val zone = ZoneId.systemDefault()
        val triggerAtMillis: Long
        val boundary = nextTodayBoundaryMinutes(semester, slots, courses, today, now)
        if (boundary != null) {
            triggerAtMillis = today.atStartOfDay(zone).plusMinutes(boundary.toLong()).toInstant().toEpochMilli()
        } else {
            triggerAtMillis = today.plusDays(1).atStartOfDay(zone).plusMinutes(5).toInstant().toEpochMilli()
        }

        // 边界已过（闹钟被延迟、设备刚唤醒）时不排过去时刻，直接顺延 1 分钟立即刷
        val safeTrigger = triggerAtMillis.coerceAtLeast(System.currentTimeMillis() + 60_000L)

        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, WidgetBoundaryReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // setAndAllowWhileIdle：Doze 下仍会触发（可能被推迟），且不需要精确闹钟权限
        runCatching { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, safeTrigger, pending) }
    }
}

/** 边界闹钟落点：只负责把刷新排进 WorkManager，立即返回（BroadcastReceiver 里不能做长任务）。 */
class WidgetBoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TodayWidgetRefresh.enqueueRefresh(context)
    }
}

/** 真正执行的刷新：读库 → 算快照 → 写进每个 widget 实例的状态 → 排下一个边界闹钟。 */
class WidgetRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        TodayWidgetRefresh.refreshNow(applicationContext)
        return Result.success()
    }
}
