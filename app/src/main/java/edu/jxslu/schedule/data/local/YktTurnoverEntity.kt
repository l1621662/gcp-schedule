package edu.jxslu.schedule.data.local

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * 一卡通消费流水本地副本（DESIGN §4.19 L1–L5）。
 *
 * 服务端接口只支持无时间参数的全量倒序分页（历史窗口约 10 个月），本地持久化后
 * 数据越攒越全、离线可查、进页秒开。`orderId` 是服务端订单号，天然唯一——同步去重
 * 与 upsert 都靠它。个人消费记录非凭证，随云备份（与课表/成绩同口径）。
 *
 * `@Index` 必须与 [edu.jxslu.schedule.data.local.JuwDatabase] `MIGRATION_5_6` 里的
 * `CREATE INDEX index_ykt_turnovers_jndatetime` 对齐：Room 迁移校验比对期望 schema
 * （实体注解）与实际表结构，实体漏声明会导致「迁移建了索引 → 校验不一致 → 崩溃」
 * （2026-09-20 实测，仅影响从 v5 升级的设备，全新安装不崩）。
 */
@Entity(
    tableName = "ykt_turnovers",
    indices = [Index(value = ["jndatetime"])],
)
data class YktTurnoverEntity(
    /** 服务端订单号（唯一；部分历史记录可能为空串——用「时间+金额」合成键兜底）。 */
    @PrimaryKey val orderId: String,
    /** 交易时间（epoch 毫秒，来自 jndatetimeStr 解析；解析失败为 0，排序沉底）。 */
    val jndatetime: Long,
    /** 交易时间原文（分组/展示用，如 `2026-09-20 18:50:11`）。 */
    val jndatetimeStr: String,
    /** 金额（分）。 */
    val tranamtFen: Long,
    /** 方向：true = 收入（typeFrom "1"）。 */
    val income: Boolean,
    /** 类型名（消费 / 二维码支付 / 充值…）。 */
    val turnoverType: String,
    val remark: String?,
    val resume: String?,
    /** 交易后余额快照（分）；缺失为 null。 */
    val balanceAfterFen: Long?,
    val locationName: String?,
    /** 同步落库时间（epoch 毫秒）。 */
    val syncedAt: Long,
)

@Dao
interface YktTurnoverDao {

    /** 某月的流水（按交易时间倒序）。monthKey 形如 `2026-09`。 */
    @Query(
        "SELECT * FROM ykt_turnovers " +
            "WHERE substr(jndatetimeStr, 1, 7) = :monthKey " +
            "ORDER BY jndatetime DESC, orderId DESC",
    )
    fun observeMonth(monthKey: String): kotlinx.coroutines.flow.Flow<List<YktTurnoverEntity>>

    /** 某月支出/收入汇总（分；响应式，表变化自动重发）。 */
    @Query(
        "SELECT " +
            "COALESCE(SUM(CASE WHEN income = 0 THEN tranamtFen ELSE 0 END), 0) AS expensesFen, " +
            "COALESCE(SUM(CASE WHEN income = 1 THEN tranamtFen ELSE 0 END), 0) AS incomeFen " +
            "FROM ykt_turnovers WHERE substr(jndatetimeStr, 1, 7) = :monthKey",
    )
    fun observeMonthSummary(monthKey: String): kotlinx.coroutines.flow.Flow<MonthSummaryRow>

    /** 某月按类型聚合（分类 Top；amountFen 正数支出/负数收入，按金额降序；响应式）。 */
    @Query(
        "SELECT turnoverType AS type, " +
            "COALESCE(SUM(CASE WHEN income = 0 THEN tranamtFen ELSE -tranamtFen END), 0) AS amountFen, " +
            "COUNT(*) AS count " +
            "FROM ykt_turnovers WHERE substr(jndatetimeStr, 1, 7) = :monthKey " +
            "GROUP BY turnoverType ORDER BY amountFen DESC",
    )
    fun observeMonthByType(monthKey: String): kotlinx.coroutines.flow.Flow<List<TypeAmountRow>>

    /** 近 12 个月逐月支出（年视图柱状；monthKey 形如 `2026-09`）。 */
    @Query(
        "SELECT substr(jndatetimeStr, 1, 7) AS monthKey, " +
            "COALESCE(SUM(CASE WHEN income = 0 THEN tranamtFen ELSE 0 END), 0) AS amountFen " +
            "FROM ykt_turnovers WHERE substr(jndatetimeStr, 1, 7) IN (:monthKeys) " +
            "GROUP BY monthKey",
    )
    suspend fun monthlyExpenses(monthKeys: List<String>): List<MonthlyAmountRow>

    /** 已入库的全部订单号（增量同步的停止判定）。 */
    @Query("SELECT orderId FROM ykt_turnovers")
    suspend fun allOrderIds(): List<String>

    /** 已入库的最新交易时间（epoch 毫秒；空库为 null）。 */
    @Query("SELECT MAX(jndatetime) FROM ykt_turnovers")
    suspend fun latestJndatetime(): Long?

    /** 某时刻之后的收入流水条数（充值到账判定：出现晚于下单时刻的新记录即到账）。 */
    @Query("SELECT COUNT(*) FROM ykt_turnovers WHERE income = 1 AND jndatetime > :epochMs")
    suspend fun countIncomeSince(epochMs: Long): Int

    /** upsert（orderId 冲突即覆盖——余额快照等字段可能随服务端重算更新）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<YktTurnoverEntity>)

    /** 入库总条数（响应式，同步后自动刷新）。 */
    @Query("SELECT COUNT(*) FROM ykt_turnovers")
    fun observeCount(): kotlinx.coroutines.flow.Flow<Int>
}

/** [YktTurnoverDao.monthSummary] 的投影行。 */
data class MonthSummaryRow(val expensesFen: Long, val incomeFen: Long)

/** [YktTurnoverDao.monthByType] 的投影行。 */
data class TypeAmountRow(val type: String, val amountFen: Long, val count: Int)

/** [YktTurnoverDao.monthlyExpenses] 的投影行。 */
data class MonthlyAmountRow(val monthKey: String, val amountFen: Long)
