package edu.gcp.schedule

import android.content.Context
import edu.gcp.schedule.data.local.JuwDatabase
import edu.gcp.schedule.data.jw.JwCredentialStore
import edu.gcp.schedule.data.prefs.DisplayPrefsStore
import edu.gcp.schedule.data.repo.ScheduleRepository
import edu.gcp.schedule.data.repo.ScoreRepository

object Graph {
    @Volatile
    private var repository: ScheduleRepository? = null

    @Volatile
    private var prefsStore: DisplayPrefsStore? = null

    @Volatile
    private var scoreRepository: ScoreRepository? = null

    @Volatile
    private var jwCredentialStore: JwCredentialStore? = null

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
}
