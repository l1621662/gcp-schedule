package edu.jxslu.schedule.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import edu.jxslu.schedule.domain.TimetablePrefs

@Database(
    entities = [
        TimetableEntity::class,
        CourseEntity::class,
        TimeSlotEntity::class,
        SemesterConfigEntity::class,
        ScoreEntity::class,
        DetectBaselineEntity::class,
        DetectReportEntity::class,
    ],
    version = 6,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class JuwDatabase : RoomDatabase() {
    abstract fun timetableDao(): TimetableDao
    abstract fun courseDao(): CourseDao
    abstract fun timeSlotDao(): TimeSlotDao
    abstract fun semesterConfigDao(): SemesterConfigDao
    abstract fun scoreDao(): ScoreDao
    abstract fun detectBaselineDao(): DetectBaselineDao
    abstract fun detectReportDao(): DetectReportDao

    companion object {

        /**
         * v1 → v2：courses 表加 `kind` 列（区分理论课 / 实验课）。
         *
         * 用 ALTER TABLE 而不是重建表：老用户库里已经有课表，
         * 走 destructive migration 会直接清空——这是不可接受的数据损失。
         * DEFAULT 'theory' 让历史数据自动落成理论课，语义正确且无需回填。
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE courses ADD COLUMN kind TEXT NOT NULL DEFAULT 'theory'")
            }
        }

        /**
         * v2 → v3：多课表（DESIGN §4.9）。
         *
         * - 新表 `timetables`：课表身份 + 课表级设置（显示偏好 JSON + 作息自定义标记）。
         *   迁移插入 id=1「我的课表」承接全部旧数据；显示偏好的实际值搬迁是异步的
         *   （Room migration 是同步的读不了 DataStore），放在 ScheduleRepository.ensureDefaults
         *   里按 `timetable_prefs_migrated` 标记一次性完成。
         * - `courses` 加 timetableId（DEFAULT 1 = 全部旧课程归入默认课表）+ 索引。
         * - `time_slots` / `semester_config` 主键要从「全局单份」变成「每课表一份」，
         *   SQLite 不能改主键，只能建新表 → 搬数据 → 改名。**禁用 destructive**：
         *   用户设备上有真实课表数据，这一步搬错就是数据丢失。
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS timetables (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "createdAt INTEGER NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "slotsCustomized INTEGER NOT NULL, " +
                        "prefsJson TEXT NOT NULL)",
                )
                // 旧数据的显示偏好先落内置默认；ensureDefaults 的一次性迁移会用
                // DataStore 里的旧全局值覆盖这一行（幂等标记防重放）
                db.execSQL(
                    "INSERT INTO timetables (id, name, createdAt, sortOrder, slotsCustomized, prefsJson) " +
                        "VALUES (1, '我的课表', ?, 0, 0, ?)",
                    arrayOf(System.currentTimeMillis(), TimetablePrefs().encode()),
                )

                db.execSQL(
                    "ALTER TABLE courses ADD COLUMN timetableId INTEGER NOT NULL DEFAULT 1",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_courses_timetableId ON courses (timetableId)",
                )

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS time_slots_new (" +
                        "timetableId INTEGER NOT NULL, " +
                        "number INTEGER NOT NULL, " +
                        "startTime TEXT NOT NULL, " +
                        "endTime TEXT NOT NULL, " +
                        "PRIMARY KEY(timetableId, number))",
                )
                db.execSQL(
                    "INSERT INTO time_slots_new (timetableId, number, startTime, endTime) " +
                        "SELECT 1, number, startTime, endTime FROM time_slots",
                )
                db.execSQL("DROP TABLE time_slots")
                db.execSQL("ALTER TABLE time_slots_new RENAME TO time_slots")

                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS semester_config_new (" +
                        "timetableId INTEGER NOT NULL, " +
                        "startDate TEXT NOT NULL, " +
                        "totalWeeks INTEGER NOT NULL, " +
                        "firstDayOfWeek INTEGER NOT NULL, " +
                        "PRIMARY KEY(timetableId))",
                )
                db.execSQL(
                    "INSERT INTO semester_config_new (timetableId, startDate, totalWeeks, firstDayOfWeek) " +
                        "SELECT 1, startDate, totalWeeks, firstDayOfWeek FROM semester_config",
                )
                db.execSQL("DROP TABLE semester_config")
                db.execSQL("ALTER TABLE semester_config_new RENAME TO semester_config")
            }
        }

        /**
         * v3 → v4：成绩按学期存储（DESIGN §4.15）。
         * 新表 `scores`，全局归属学生（不挂 timetableId）；CREATE TABLE 非 destructive。
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS scores (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "term TEXT NOT NULL, " +
                        "courseNo TEXT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "unit TEXT NOT NULL, " +
                        "credit REAL NOT NULL, " +
                        "hours REAL NOT NULL, " +
                        "examForm TEXT NOT NULL, " +
                        "courseAttr TEXT NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "score REAL, " +
                        "scoreStr TEXT NOT NULL, " +
                        "gradePoint REAL, " +
                        "status TEXT NOT NULL, " +
                        "pendingReview INTEGER NOT NULL, " +
                        "importedAt INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_scores_term ON scores(term)")
            }
        }

        /**
         * v4 → v5：调课自动检测（DESIGN §4.17）。
         * 新表 `detect_baselines`（教务基线快照）与 `detect_reports`（最新差异报告），
         * 都按 timetableId 主键、每课表一份；CREATE TABLE 非 destructive。
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS detect_baselines (" +
                        "timetableId INTEGER NOT NULL, " +
                        "term TEXT NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(timetableId))",
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS detect_reports (" +
                        "timetableId INTEGER NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "unread INTEGER NOT NULL, " +
                        "updatedAt INTEGER NOT NULL, " +
                        "PRIMARY KEY(timetableId))",
                )
            }
        }

        /**
         * v5 → v6：校园卡消费流水本地副本（校园卡功能 2026-09-23 已移除）。
         * 表 `ykt_turnovers` 保留在迁移里、不再有对应实体：既有安装的库里这张表继续存在
         * （空表，无人读写），免得为了删表再叠一层迁移去动用户已落库的数据。
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS ykt_turnovers (" +
                        "orderId TEXT NOT NULL PRIMARY KEY, " +
                        "jndatetime INTEGER NOT NULL, " +
                        "jndatetimeStr TEXT NOT NULL, " +
                        "tranamtFen INTEGER NOT NULL, " +
                        "income INTEGER NOT NULL, " +
                        "turnoverType TEXT NOT NULL, " +
                        "remark TEXT, " +
                        "resume TEXT, " +
                        "balanceAfterFen INTEGER, " +
                        "locationName TEXT, " +
                        "syncedAt INTEGER NOT NULL)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_ykt_turnovers_jndatetime ON ykt_turnovers(jndatetime)",
                )
            }
        }

        @Volatile
        private var instance: JuwDatabase? = null

        fun get(context: Context): JuwDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    JuwDatabase::class.java,
                    "juw_schedule.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { instance = it }
            }
    }
}
