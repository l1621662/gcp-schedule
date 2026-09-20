package edu.jxslu.schedule.data.ykt

import edu.jxslu.schedule.data.local.JuwDatabase
import edu.jxslu.schedule.data.local.YktTurnoverEntity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 消费流水增量同步器（DESIGN §4.19 L3）。
 *
 * 策略：时间倒序翻页，**遇到已入库 `orderId` 即停**——本地已有近月数据时通常 1 页
 * （100 条）内完成；空库首同步会拉完整个全量（约 8 页）。upsert 幂等（余额快照等
 * 字段随服务端重算更新）。`orderId` 为空的记录用「时间原文+金额」合成键兜底。
 *
 * 同步结果由调用方感知（Room 流自动刷新 UI），本类只做「拉 → 转换 → 落库」。
 */
class YktTurnoverSyncer(
    private val repo: YktRepository,
    private val db: JuwDatabase,
) {

    /** 同步结果概要（供日志/测试断言）。 */
    data class Result(val fetchedPages: Int, val fetchedRecords: Int, val stoppedEarly: Boolean)

    /**
     * 执行一次增量同步。[maxPages] 限制单轮页数（默认全量拉完；进页刷新可传小值）。
     * 凭证缺失返回 null（调用方静默）；网络/协议异常向上抛，由 UI 层给文案。
     */
    suspend fun sync(
        username: String,
        password: String,
        maxPages: Int = Int.MAX_VALUE,
    ): Result? {
        val dao = db.yktTurnoverDao()
        val knownIds = dao.allOrderIds().toHashSet()
        var page = 1
        var pages = 0
        var records = 0
        var stoppedEarly = false
        while (page <= maxPages) {
            val data = repo.turnover(username, password, page = page)
            pages = page
            if (data.records.isEmpty()) break // 空页 = 拉完全量
            val entities = data.records.map { it.toEntity() }
            dao.upsertAll(entities)
            records += entities.size
            // 增量停止：本页出现任何已入库订单（含 upsert 前检查的 knownIds）→ 之后的页全是旧数据
            if (entities.any { it.orderId in knownIds }) {
                stoppedEarly = true
                break
            }
            entities.forEach { knownIds.add(it.orderId) }
            if (page >= data.pages) break // 全量拉完
            page++
        }
        return Result(fetchedPages = pages, fetchedRecords = records, stoppedEarly = stoppedEarly)
    }

    companion object {
        private val SERVER_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        /** 流水记录 → 实体（jndatetime 解析失败为 0 沉底；orderId 空用合成键）。 */
        fun YktTurnover.toEntity(now: Long = System.currentTimeMillis()): YktTurnoverEntity {
            val epoch = runCatching {
                LocalDateTime.parse(jndatetimeStr, SERVER_FORMAT)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            }.getOrDefault(0L)
            val key = if (orderId.isNullOrBlank()) "syn_${jndatetimeStr}_$tranamtFen" else orderId
            return YktTurnoverEntity(
                orderId = key,
                jndatetime = epoch,
                jndatetimeStr = jndatetimeStr,
                tranamtFen = tranamtFen,
                income = income,
                turnoverType = turnoverType,
                remark = remark,
                resume = resume,
                balanceAfterFen = balanceAfterFen,
                locationName = locationName,
                syncedAt = now,
            )
        }
    }
}
