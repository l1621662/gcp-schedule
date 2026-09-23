package edu.gcp.schedule.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * v3 起所有课表数据的查询都按 timetableId 过滤（DESIGN §4.9）：
 * 不加过滤的话，跨课表的配色分配、合并去重、覆盖导入会互相污染。
 */
@Dao
interface CourseDao {
    @Query("SELECT * FROM courses WHERE timetableId = :timetableId ORDER BY day, startSection, name")
    fun observeForTimetable(timetableId: Long): Flow<List<CourseEntity>>

    @Query("SELECT * FROM courses WHERE timetableId = :timetableId ORDER BY day, startSection, name")
    suspend fun getForTimetable(timetableId: Long): List<CourseEntity>

    @Query("SELECT * FROM courses WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(course: CourseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(courses: List<CourseEntity>): List<Long>

    @Delete
    suspend fun delete(course: CourseEntity)

    /** 批量删除（调课摘空周次后的整行清除，DESIGN §4.11）。 */
    @Query("DELETE FROM courses WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM courses WHERE timetableId = :timetableId")
    suspend fun clearForTimetable(timetableId: Long)

    @Query("SELECT COUNT(*) FROM courses WHERE timetableId = :timetableId")
    suspend fun countForTimetable(timetableId: Long): Int

    /** 课程数观察流：课表设置页目标信息行（DESIGN §4.9）。 */
    @Query("SELECT COUNT(*) FROM courses WHERE timetableId = :timetableId")
    fun observeCountForTimetable(timetableId: Long): Flow<Int>
}

@Dao
interface TimetableDao {
    @Query("SELECT * FROM timetables ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<TimetableEntity>>

    @Query("SELECT * FROM timetables ORDER BY sortOrder, id")
    suspend fun getAll(): List<TimetableEntity>

    @Query("SELECT * FROM timetables WHERE id = :id")
    suspend fun getById(id: Long): TimetableEntity?

    @Query("SELECT COUNT(*) FROM timetables")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(timetable: TimetableEntity): Long

    @Query("DELETE FROM timetables WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface TimeSlotDao {
    @Query("SELECT * FROM time_slots WHERE timetableId = :timetableId ORDER BY number")
    fun observeForTimetable(timetableId: Long): Flow<List<TimeSlotEntity>>

    @Query("SELECT * FROM time_slots WHERE timetableId = :timetableId ORDER BY number")
    suspend fun getForTimetable(timetableId: Long): List<TimeSlotEntity>

    /** 全部课表的作息表：结构性迁移要按课表逐张覆盖。 */
    @Query("SELECT * FROM time_slots ORDER BY timetableId, number")
    suspend fun getAll(): List<TimeSlotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(slots: List<TimeSlotEntity>)

    @Query("DELETE FROM time_slots WHERE timetableId = :timetableId")
    suspend fun clearForTimetable(timetableId: Long)
}

@Dao
interface SemesterConfigDao {
    @Query("SELECT * FROM semester_config WHERE timetableId = :timetableId LIMIT 1")
    fun observeForTimetable(timetableId: Long): Flow<SemesterConfigEntity?>

    @Query("SELECT * FROM semester_config WHERE timetableId = :timetableId LIMIT 1")
    suspend fun getForTimetable(timetableId: Long): SemesterConfigEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(config: SemesterConfigEntity)

    @Query("DELETE FROM semester_config WHERE timetableId = :timetableId")
    suspend fun deleteForTimetable(timetableId: Long)
}

@Dao
interface ScoreDao {
    /** 有成绩的学期列表，倒序（字典序倒序即时间倒序：2026-2027-1 > 2025-2026-2）。 */
    @Query("SELECT DISTINCT term FROM scores ORDER BY term DESC")
    fun observeTerms(): Flow<List<String>>

    @Query("SELECT * FROM scores WHERE term = :term ORDER BY name")
    fun observeForTerm(term: String): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores ORDER BY term DESC")
    fun observeAll(): Flow<List<ScoreEntity>>

    @Query("SELECT * FROM scores WHERE term = :term")
    suspend fun getForTerm(term: String): List<ScoreEntity>

    /** 备份导出用全量快照（DESIGN §4.3）。 */
    @Query("SELECT * FROM scores ORDER BY term DESC")
    suspend fun getAll(): List<ScoreEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(scores: List<ScoreEntity>)

    @Query("DELETE FROM scores WHERE term = :term")
    suspend fun deleteForTerm(term: String)

    @Query("DELETE FROM scores")
    suspend fun deleteAll()
}

/** 调课自动检测的基线快照（DESIGN §4.17）：每课表一份，upsert 整体替换。 */
@Dao
interface DetectBaselineDao {
    @Query("SELECT * FROM detect_baselines WHERE timetableId = :timetableId")
    suspend fun get(timetableId: Long): DetectBaselineEntity?

    @Upsert
    suspend fun upsert(baseline: DetectBaselineEntity)

    @Query("DELETE FROM detect_baselines WHERE timetableId = :timetableId")
    suspend fun delete(timetableId: Long)

    @Query("DELETE FROM detect_baselines")
    suspend fun deleteAll()
}

/** 调课自动检测的最新差异报告（DESIGN §4.17）：每课表一份，气泡与入口读 `unread`。 */
@Dao
interface DetectReportDao {
    @Query("SELECT * FROM detect_reports WHERE timetableId = :timetableId")
    suspend fun get(timetableId: Long): DetectReportEntity?

    @Query("SELECT * FROM detect_reports WHERE timetableId = :timetableId")
    fun observe(timetableId: Long): Flow<DetectReportEntity?>

    @Upsert
    suspend fun upsert(report: DetectReportEntity)

    /** 应用/忽略后清气泡：只清标记，报告内容保留（设置页状态区可回看）。 */
    @Query("UPDATE detect_reports SET unread = 0 WHERE timetableId = :timetableId")
    suspend fun markRead(timetableId: Long)

    @Query("DELETE FROM detect_reports WHERE timetableId = :timetableId")
    suspend fun delete(timetableId: Long)
}
