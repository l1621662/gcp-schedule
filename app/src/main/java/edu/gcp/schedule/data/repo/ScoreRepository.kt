package edu.gcp.schedule.data.repo

import androidx.room.withTransaction
import edu.gcp.schedule.data.local.JuwDatabase
import edu.gcp.schedule.data.local.ScoreEntity
import edu.gcp.schedule.domain.ScoreRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * 成绩仓库（DESIGN §4.15）：按学期存储、按学期整体替换。
 *
 * 成绩是学生学业记录，不属于任何课表（timetableId），与 [ScheduleRepository] 分开。
 * 所有写路径走「先删该学期再插入」，重复导入不产生重复行；未涉及的学期不动。
 */
class ScoreRepository(private val db: JuwDatabase) {

    private val dao = db.scoreDao()

    /** 有成绩的学期（倒序，最新在前）。 */
    fun observeTerms(): Flow<List<String>> = dao.observeTerms()

    fun observeForTerm(term: String): Flow<List<ScoreRecord>> =
        dao.observeForTerm(term).map { list -> list.map { it.toDomain() } }

    /** 全部成绩按学期分组（成绩页两个分组视图共用）。 */
    fun observeAllGroupedByTerm(): Flow<Map<String, List<ScoreRecord>>> =
        dao.observeAll().map { list -> list.map { it.toDomain() }.groupBy { it.term } }

    /** 按学期替换：该学期先删后插，importedAt 统一取本次导入时间。 */
    suspend fun replaceTerm(term: String, records: List<ScoreRecord>) {
        val now = System.currentTimeMillis()
        db.withTransaction {
            dao.deleteForTerm(term)
            dao.insertAll(records.map { ScoreEntity.fromDomain(it.copy(term = term), now) })
        }
    }

    suspend fun deleteTerm(term: String) = dao.deleteForTerm(term)

    suspend fun deleteAll() = dao.deleteAll()
}
