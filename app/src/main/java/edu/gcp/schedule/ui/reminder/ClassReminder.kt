package edu.gcp.schedule.ui.reminder

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import edu.gcp.schedule.Graph
import edu.gcp.schedule.MainActivity
import edu.gcp.schedule.R
import edu.gcp.schedule.domain.ReminderDefaults
import edu.gcp.schedule.domain.dueReminderPlan
import edu.gcp.schedule.domain.metaLine
import edu.gcp.schedule.domain.nextReminderPlan
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 上课提醒调度（DESIGN §3.7）。
 *
 * 与桌面小组件（`ui/widget/TodayWidgetRefresh`）同构的三层兜底：
 * 边界闹钟（下一次「上课时刻 − 提前量」，可跨天）为主，
 * WorkManager 15 分钟周期核对补发/补排，冷启动 / 数据变化 / 开机广播立即重排。
 * 同样用 `setAndAllowWhileIdle` 而不是精确闹钟：推迟几分钟可接受，
 * 不申请 `SCHEDULE_EXACT_ALARM` 敏感权限。
 */
object ClassReminder {

    private const val TAG = "ClassReminder"
    private const val PERIODIC_WORK = "class_reminder_periodic"
    private const val ONE_SHOT_WORK = "class_reminder_check"
    private const val ALARM_REQUEST_CODE = 4002

    /**
     * 重排下一个提醒。提醒关、课表空、不在学期内时撤销已排闹钟——
     * 「关掉开关后通知还在响」比不响严重得多。
     */
    suspend fun scheduleNext(context: Context) {
        runCatching {
            val repo = Graph.repository(context)
            val manager = context.getSystemService(AlarmManager::class.java)
                ?: return
            val pending = alarmPendingIntent(context)
            if (!repo.reminderEnabled.first()) {
                manager.cancel(pending)
                return
            }
            val plan = nextReminderPlan(
                semester = repo.semester.first(),
                slots = repo.timeSlots.first(),
                courses = repo.courses.first(),
                now = LocalDateTime.now(),
                leadMinutes = repo.reminderLeadMinutes.first(),
            )
            if (plan == null) {
                manager.cancel(pending)
                return
            }
            val triggerAtMillis = plan.triggerAt
                .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pending)
        }.onFailure { Log.w(TAG, "schedule reminder failed", it) }
    }

    /** 一次性核对（闹钟触发 / 开机 / 设置变更落点）：BroadcastReceiver 里不能做长任务。 */
    fun enqueueCheck(context: Context) {
        val request = OneTimeWorkRequestBuilder<ReminderCheckWorker>()
            .setConstraints(Constraints.NONE)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(ONE_SHOT_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    /** 15 分钟周期核对兜底：闹钟被 ROM 推迟/丢失时还能在窗口内补发。幂等（KEEP）。 */
    fun ensurePeriodicWork(context: Context) {
        val request = PeriodicWorkRequestBuilder<ReminderCheckWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    private fun alarmPendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ALARM_REQUEST_CODE,
        Intent(context, ReminderAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}

/**
 * 通知的构建与去重落盘。同一节课只发一次：DataStore 记「已发键」，
 * 闹钟与周期核对共用；窗口已过（迟到型）不发，避免「已经上课了还提醒 10 分钟后上课」。
 */
internal object ReminderNotifications {

    private const val CHANNEL_ID = "class_reminder"
    private const val NOTIFICATION_TAG = "class_reminder"
    /** 固定 id：新提醒覆盖上一条，不堆叠。 */
    private const val NOTIFICATION_ID = 1001

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "上课提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply { description = "上课前按设定的提前量发出通知" }
        manager.createNotificationChannel(channel)
    }

    suspend fun postDueReminderIfAny(context: Context) {
        val repo = Graph.repository(context)
        if (!repo.reminderEnabled.first()) return
        // 通知被系统/用户整体关闭时静默跳过：写不出去也不该在核对里报错
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val slots = repo.timeSlots.first()
        val due = dueReminderPlan(
            semester = repo.semester.first(),
            slots = slots,
            courses = repo.courses.first(),
            now = LocalDateTime.now(),
            leadMinutes = repo.reminderLeadMinutes.first(),
        ) ?: return
        val key = due.dedupKey
        if (repo.reminderLastKey() == key) return

        ensureChannel(context)
        val lead = repo.reminderLeadMinutes.first()
        val text = metaLine(slots, due.course)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("${ReminderDefaults.leadLabel(lead)}后上课 · ${due.course.name}")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .build()
        // 无 POST_NOTIFICATIONS 权限时系统静默丢弃；SecurityException 等异常也不外漏
        val posted = runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_TAG, NOTIFICATION_ID, notification)
        }.isSuccess
        if (posted) repo.setReminderLastKey(key)
    }
}

/** 边界闹钟落点：只把核对排进 WorkManager，立即返回。 */
class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ClassReminder.enqueueCheck(context)
    }
}

/** 重启后闹钟全部丢失，开机补排一次。 */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            ClassReminder.enqueueCheck(context)
        }
    }
}

/** 真正的核对：先看窗口内有没有该发的（去重后发），再重排下一个闹钟。 */
class ReminderCheckWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching {
            ReminderNotifications.postDueReminderIfAny(applicationContext)
            ClassReminder.scheduleNext(applicationContext)
        }
        return Result.success()
    }
}
