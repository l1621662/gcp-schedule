package edu.jxslu.schedule

import android.content.Context
import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.jw.JwCredentialStore
import edu.jxslu.schedule.data.prefs.DisplayPrefsStore
import edu.jxslu.schedule.data.qiekj.QiekjOrderHistoryStore
import edu.jxslu.schedule.data.qiekj.QiekjRepository
import edu.jxslu.schedule.data.qiekj.QiekjTokenStore
import edu.jxslu.schedule.data.repo.ScheduleRepository
import edu.jxslu.schedule.data.repo.ScoreRepository
import edu.jxslu.schedule.data.ykt.YktClient
import edu.jxslu.schedule.data.ykt.YktCredentialStore
import edu.jxslu.schedule.data.ykt.YktRepository

object Graph {
    @Volatile
    private var repository: ScheduleRepository? = null

    @Volatile
    private var prefsStore: DisplayPrefsStore? = null

    @Volatile
    private var qiekjRepository: QiekjRepository? = null

    @Volatile
    private var scoreRepository: ScoreRepository? = null

    @Volatile
    private var jwCredentialStore: JwCredentialStore? = null

    @Volatile
    private var yktCredentialStore: YktCredentialStore? = null

    @Volatile
    private var yktRepository: YktRepository? = null

    /** 进程级 applicationContext（后台协程里落盘等场景复用，免 Activity 引用泄漏）。 */
    val appContext: Context by lazy { contextProvider() }

    /** 由 [JuwApplication.onCreate] 注入；首次访问早于注入说明时序有问题，直接抛错暴露。 */
    internal lateinit var contextProvider: () -> Context

    /** 教务登录凭证存储单例（DESIGN §4.17）：EncryptedSharedPreferences 创建有开销，进程内一份。 */
    fun jwCredentialStore(context: Context): JwCredentialStore =
        jwCredentialStore ?: synchronized(this) {
            jwCredentialStore ?: JwCredentialStore(context.applicationContext).also { jwCredentialStore = it }
        }

    /** 显示偏好用 applicationContext 建，保证与 Activity 生命周期无关。 */
    fun displayPrefs(context: Context): DisplayPrefsStore =
        prefsStore ?: synchronized(this) {
            prefsStore ?: DisplayPrefsStore(context.applicationContext).also { prefsStore = it }
        }

    fun repository(context: Context): ScheduleRepository =
        repository ?: synchronized(this) {
            repository ?: ScheduleRepository(
                JuwDatabase.get(context),
                displayPrefs(context),
            ).also { repository = it }
        }

    /** 成绩仓库单例（DESIGN §4.15）：与课表共用数据库，按学期整体替换。 */
    fun scoreRepository(context: Context): ScoreRepository =
        scoreRepository ?: synchronized(this) {
            scoreRepository ?: ScoreRepository(JuwDatabase.get(context)).also { scoreRepository = it }
        }

    /** 胖乖仓库单例（DESIGN §4.10）：Retrofit client 只建一次，token 存加密 prefs。 */
    fun qiekj(context: Context): QiekjRepository =
        qiekjRepository ?: synchronized(this) {
            qiekjRepository ?: QiekjRepository(
                QiekjTokenStore(context.applicationContext),
                QiekjOrderHistoryStore(context.applicationContext),
            ).also { qiekjRepository = it }
        }

    /** 校园卡凭证存储单例（DESIGN §4.19）：EncryptedSharedPreferences，进程内一份。 */
    fun yktCredentialStore(context: Context): YktCredentialStore =
        yktCredentialStore ?: synchronized(this) {
            yktCredentialStore ?: YktCredentialStore(context.applicationContext).also { yktCredentialStore = it }
        }

    /** 校园卡仓库单例（DESIGN §4.19）：OkHttp client 只建一次；token 只在仓库内存里。 */
    fun yktRepository(context: Context): YktRepository =
        yktRepository ?: synchronized(this) {
            yktRepository ?: YktRepository(YktClient.create()).also { yktRepository = it }
        }
}
