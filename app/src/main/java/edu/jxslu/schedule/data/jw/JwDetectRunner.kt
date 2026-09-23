package edu.jxslu.schedule.data.jw

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import androidx.work.WorkerParameters
import edu.jxslu.schedule.Graph
import edu.jxslu.schedule.SubpageActivity
import edu.jxslu.schedule.SubpageScreen
import edu.jxslu.schedule.R
import edu.jxslu.schedule.data.prefs.DetectSettings
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.domain.Course
import edu.jxslu.schedule.domain.DetectGroupDto
import edu.jxslu.schedule.domain.DetectReportPayload
import edu.jxslu.schedule.domain.DetectSnapshotPayload
import edu.jxslu.schedule.domain.ScheduleDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * 调课自动检测的编排（DESIGN §4.17）：登录 → 抓两页课表 → 与基线三方合并 → 落报告/通知。
 *
 * 前置门（任一不满足即 [Outcome.Skipped]，不算失败）：开关开 → 有凭证 → 无 VPN/代理出网
 * （学校对代理出口区别对待，§4.4）。失败分类落 DataStore：凭证错单独计数，
 * 连续 3 次自动停用 + 通知（防反复自动登录触发验证码锁号）；网络/协议错误静默记录。
 *
 * 学期口径：先抓**无参数**理论页读教务当前学期——与基线学期不一致说明教务已换学期，
 * 跨学期不 diff（那是新建基线的机会），提示用户重新导入；一致则直接用这页数据，
 * 实验页带同学期参数抓取。
 */
class JwDetectRunner(private val context: Context) {

    enum class Trigger { Scheduled, Manual }

    sealed class Outcome {
        /** 无差异；[baselineCreated] = 本次是首次检测（只建基线，下次开始才报差异）。 */
        data class NoDiff(val baselineCreated: Boolean) : Outcome()

        /** 发现差异，报告已落库、气泡与通知已就位。 */
        data object DiffFound : Outcome()

        /** 前置门拦截 / 教务换学期。reason 面向用户，可直接展示。 */
        data class Skipped(val reason: String) : Outcome()

        /** 检测失败。message 面向用户。 */
        data class Failed(val message: String) : Outcome()
    }

    /**
     * @param liveSession 手动路径里已经用验证码登录好的会话（设置页「立即检测」传入）。
     *   后台定时检测传 null：走加密存储里的会话 cookie，过期即跳过并提示手动验证一次
     *   ——本校准登录必须填验证码，后台自己登不了（DESIGN §4.17）。
     */
    suspend fun run(trigger: Trigger, liveSession: ZfJwSession? = null): Outcome {
        val repo = Graph.repository(context)
        val prefs = Graph.displayPrefs(context)
        // 前置门。读取环节自身也可能失败（凭证 keyset 损坏时 EncryptedSharedPreferences 会抛），
        // 统一收口成结果——run() 对调用方「不抛」是硬约定，理由见方法尾部的兜底 catch。
        val settings = try {
            prefs.detectSettings.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed("读取检测配置失败：${e.message ?: e.javaClass.simpleName}")
        }
        if (!settings.enabled || settings.disabled) {
            return Outcome.Skipped("调课自动检测未开启")
        }
        val credentials = try {
            Graph.jwCredentialStore(context).read()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Outcome.Failed("读取教务凭证失败（本机存储可能已损坏）：${e.message ?: e.javaClass.simpleName}")
        } ?: return Outcome.Skipped("尚未保存教务账号密码，请到设置页重新开启")
        if (JwVpnDetector.isVpnActive(context)) {
            return Outcome.Skipped("正在通过 VPN/代理出网，教务不认代理出口，已跳过本次检测")
        }

        return try {
            val session = liveSession ?: ZfJwSession.create()
            val ownedSession = liveSession == null
            try {
                if (ownedSession && !session.importSession(credentials.sessionCookie)) {
                    // 没有可用会话：不是错误，是「需要人去填一次验证码」
                    return Outcome.Skipped("需要先在设置页用验证码验证一次（后台检测靠会话复用）")
                }

                // 课表 JSON 抓取（正方把实验课也排在同一张表里，因此只有一份课表）
                val schedule = try {
                    session.fetchSchedule()
                } catch (e: ZfJwSession.ZfException.Protocol) {
                    // 接口回登录页/结构变化：按会话过期处理，清掉失效 cookie 并提示手动验证
                    if (ownedSession) Graph.jwCredentialStore(context).clearSession()
                    return Outcome.Skipped("教务会话已过期，请到本页点「立即检测」重新验证一次")
                }
                val jwTerm = schedule.term
                val theory = schedule.courses
                if (theory.isEmpty()) {
                    return failProtocol(prefs, "教务课表为空：学期参数可能没对上，或这学期确实没课")
                }

                val ttId = repo.currentTimetableId.first()
                val baseline = repo.detectBaseline(ttId)
                if (baseline != null && !baseline.term.isNullOrBlank() &&
                    jwTerm != null && jwTerm != baseline.term
                ) {
                    // 检测本身完成了（时间推进），差异不报：跨学期没有可比性
                    prefs.recordDetectNotice(System.currentTimeMillis(), "教务已切换到 $jwTerm，请重新导入课表或重新开启检测以重建基线")
                    return Outcome.Skipped("教务已切换学期（$jwTerm）")
                }

                val snapshot = DetectSnapshotPayload.fromCourses(jwTerm, theory, emptyList())
                if (baseline == null) {
                    repo.saveDetectBaseline(ttId, snapshot)
                    prefs.recordDetectSuccess(System.currentTimeMillis())
                    return Outcome.NoDiff(baselineCreated = true)
                }

                val ours = repo.detectLocalCourses(ttId)
                val groups = ScheduleDetector.detect(baseline.allCourses(), ours, snapshot.allCourses())
                if (groups.isEmpty()) {
                    prefs.recordDetectSuccess(System.currentTimeMillis())
                    return Outcome.NoDiff(baselineCreated = false)
                }
                repo.saveDetectReport(
                    ttId,
                    DetectReportPayload(
                        term = jwTerm,
                        checkedAt = System.currentTimeMillis(),
                        groups = groups.map { DetectGroupDto.fromDomain(it) },
                        snapshot = snapshot,
                    ),
                )
                prefs.recordDetectSuccess(System.currentTimeMillis())
                JwDetectNotifier.notifyDiff(context, groups.size)
                Outcome.DiffFound
            } finally {
                session.shutdown()
            }
        } catch (e: ZfJwSession.ZfException.Credential) {
            recordFailureSafely(prefs, e.message ?: "登录失败", credentialFailed = true)
            // 记录失败后重读停用标记：写失败时读也大概率失败，一律吞掉——
            // 通知发不出去也比异常逃逸把界面卡在转圈态强。
            val disabled = runCatching { prefs.detectSettings.first().disabled }.getOrDefault(false)
            if (disabled) {
                JwDetectNotifier.notifyDisabled(context)
            }
            Outcome.Failed(e.message ?: "登录失败")
        } catch (e: ZfJwSession.ZfException) {
            recordFailureSafely(prefs, e.message ?: "检测失败", credentialFailed = false)
            Outcome.Failed(e.message ?: "检测失败")
        } catch (e: CancellationException) {
            // 取消（协程作用域销毁 / 调用方的总超时）原样抛出，不当失败记。
            throw e
        } catch (e: Exception) {
            // 硬约定：run() 对调用方「不抛」。
            // 根因：全部调用点都是「置 busy → run() → 回调里复位 busy」的形态，
            // 任何逃逸异常（Room/DataStore IO、序列化、解析器越界等）都会让 busy
            // 永远不复位——界面卡在转圈态，且用户看不到任何解释。
            // 异常在此收口为 Failed，并落 lastError 供状态区回看。
            val message = "检测异常：${e.message ?: e.javaClass.simpleName}"
            recordFailureSafely(prefs, message, credentialFailed = false)
            Outcome.Failed(message)
        }
    }

    /** 落库失败不该让「报告失败」这件事本身再抛异常（同 [run] 的「不抛」约定）。 */
    private suspend fun recordFailureSafely(
        prefs: DisplayPrefsStore,
        message: String,
        credentialFailed: Boolean,
    ) {
        try {
            prefs.recordDetectFailure(message, credentialFailed)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 落库失败时无声：调用方仍需拿到 Failed 结果去复位 UI
        }
    }

    private suspend fun failProtocol(
        prefs: DisplayPrefsStore,
        message: String,
    ): Outcome.Failed {
        recordFailureSafely(prefs, message, credentialFailed = false)
        return Outcome.Failed(message)
    }
}

/**
 * 检测结果通知（DESIGN §4.17）：差异与「凭证错停用」两种，其余静默。
 * 无 POST_NOTIFICATIONS 权限时系统静默丢弃，不外漏异常。
 */
internal object JwDetectNotifier {

    private const val CHANNEL_ID = "jw_detect"
    private const val TAG = "jw_detect"
    private const val ID_DIFF = 2001
    private const val ID_DISABLED = 2002

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "调课检测",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = "教务课表与本地不一致时提醒" }
        manager.createNotificationChannel(channel)
    }

    private fun contentIntent(context: Context, screen: SubpageScreen, requestCode: Int): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode,
            SubpageActivity.intent(context, screen),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun notifyDiff(context: Context, groupCount: Int) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("教务课表可能已调课")
            .setContentText("检测到 $groupCount 门课与本地不一致，点击查看并选择是否更新")
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, SubpageScreen.SCHEDULE_UPDATE, 0))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(TAG, ID_DIFF, notification)
        }
    }

    fun notifyDisabled(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bell)
            .setContentTitle("调课自动检测已停用")
            .setContentText("教务登录连续失败（可能触发验证码），请到设置页重新验证账号")
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent(context, SubpageScreen.TWEAK_DETECT, 1))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(TAG, ID_DISABLED, notification)
        }
    }
}

/** 检测设置变化的调度收口：开 + 未停用 → 排周期任务；否则撤销。 */
object JwDetectScheduler {

    private const val PERIODIC_WORK = "tweak_detect_periodic"
    private const val ONE_TIME_WORK = "tweak_detect_onetime"

    fun ensurePeriodicWork(context: Context, settings: DetectSettings) {
        val wm = WorkManager.getInstance(context)
        if (settings.enabled && !settings.disabled) {
            val request = PeriodicWorkRequestBuilder<JwDetectWorker>(settings.periodHours.toLong(), TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            wm.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.REPLACE, request)
        } else {
            wm.cancelUniqueWork(PERIODIC_WORK)
        }
    }

    /** 手动/冷启动补测：立即跑一次（有网络约束，ROM 推迟时进程内下一轮再兜）。 */
    fun enqueueOneTime(context: Context) {
        val request = OneTimeWorkRequestBuilder<JwDetectWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_TIME_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }
}

/** WorkManager 落点：任何失败都在 Runner 内消化，worker 恒 success（重试走下一次周期）。 */
class JwDetectWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        runCatching {
            JwDetectRunner(applicationContext).run(JwDetectRunner.Trigger.Scheduled)
        }
        return Result.success()
    }
}
