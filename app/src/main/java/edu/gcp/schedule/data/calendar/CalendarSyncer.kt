package edu.gcp.schedule.data.calendar

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.CalendarContract
import edu.gcp.schedule.domain.CalendarSyncDefaults
import edu.gcp.schedule.domain.ScheduleExporter.CourseEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 课表一键同步到系统日历（DESIGN §4.12）。
 *
 * 策略：**删除再写入**——先删 description 含 [SYNC_MARKER] 的全部事件（只动本 App 写入的），
 * 再把展开后的课程事件整表插入第一个可写日历账户。教室/时间变更后重同步不留脏数据；
 * 代价是用户对旧事件的手工编辑被覆盖，以 App 课表为准。
 *
 * 权限（READ/WRITE_CALENDAR）由 UI 层运行时申请，本类不做检查——无权限时
 * ContentResolver 会直接抛 SecurityException，包装成 [CalendarSyncResult.NoPermission] 返回。
 */
object CalendarSyncer {

    /** 本 App 写入事件的标记，删除时以此为过滤条件（不碰用户其它事件）。 */
    internal const val SYNC_MARKER = "城职课表同步"

    /**
     * 改名前的旧标记（原项目「水贝贝」）。**删除时一并匹配**：用户在旧版本同步过日历的话，
     * 那些事件的 description 里写的是旧标记，只按新标记删会留下一堆再也不受管理的课表事件。
     */
    internal const val LEGACY_SYNC_MARKER = "水贝贝课表同步"

    /** 删除条件 = 新标记或旧标记（两个占位符按序对应 [DELETE_MARKER_ARGS]）。 */
    private const val DELETE_SELECTION =
        "${CalendarContract.Events.DESCRIPTION} LIKE ? OR ${CalendarContract.Events.DESCRIPTION} LIKE ?"

    private val DELETE_MARKER_ARGS = arrayOf("%$SYNC_MARKER%", "%$LEGACY_SYNC_MARKER%")

    /** 默认提前提醒分钟数（需求口径）；可被「我的 → 日历同步」的全局设置覆盖。 */
    const val DEFAULT_REMINDER_MINUTES = CalendarSyncDefaults.DEFAULT_REMINDER_MINUTES

    sealed interface CalendarSyncResult {
        /** 成功写入 [count] 个事件。 */
        data class Success(val count: Int) : CalendarSyncResult

        /** 成功删除 [count] 个本 App 写入的事件。 */
        data class Deleted(val count: Int) : CalendarSyncResult

        /** 设备上没有可写日历账户（用户未登录任何日历账号）。 */
        data object NoCalendarAccount : CalendarSyncResult

        /** 日历权限在调用瞬间仍不可用（理论上 UI 层已申请过）。 */
        data object NoPermission : CalendarSyncResult

        /** 其他 ContentResolver 失败。 */
        data class Error(val message: String) : CalendarSyncResult
    }

    /**
     * 第一个可写日历的 `_ID`；无账户返回 null。
     *
     * 不在 selection 里引用 [CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL]——
     * 部分厂商 Provider 对该列做 selection 会抛 IllegalArgumentException，
     * 改为取回后在客户端过滤（CAL_ACCESS_CONTRIBUTOR=500 起可写）。
     */
    internal fun firstWritableCalendarId(resolver: android.content.ContentResolver): Long? =
        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL),
            null,
            null,
            null,
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
            val levelIdx = cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
            while (cursor.moveToNext()) {
                if (cursor.getInt(levelIdx) >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) {
                    return cursor.getLong(idIdx)
                }
            }
            null
        }

    /**
     * 同步课程事件到系统日历。IO 全部在 [Dispatchers.IO]。
     *
     * @param events 已展开的课程事件（[edu.gcp.schedule.domain.ScheduleExporter.expandEvents]）
     * @param reminderMinutes 提前提醒分钟数；≤0 不写提醒（全局设置，「我的 → 日历同步」）
     */
    suspend fun sync(
        context: Context,
        events: List<CourseEvent>,
        reminderMinutes: Int = DEFAULT_REMINDER_MINUTES,
    ): CalendarSyncResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            if (events.isEmpty()) return@withContext CalendarSyncResult.Success(count = 0)

            val calendarId = try {
                firstWritableCalendarId(resolver)
            } catch (e: SecurityException) {
                return@withContext CalendarSyncResult.NoPermission
            } ?: return@withContext CalendarSyncResult.NoCalendarAccount

            try {
                // 删旧：只删带本 App 标记的事件（含改名前的旧标记）
                resolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    DELETE_SELECTION,
                    DELETE_MARKER_ARGS,
                )

                var inserted = 0
                for (event in events) {
                    val eventUri = insertEvent(resolver, calendarId, event) ?: continue
                    if (reminderMinutes > 0) insertReminder(resolver, eventUri, reminderMinutes)
                    inserted++
                }
                CalendarSyncResult.Success(count = inserted)
            } catch (e: SecurityException) {
                CalendarSyncResult.NoPermission
            } catch (e: Exception) {
                CalendarSyncResult.Error(e.message ?: "写入日历失败")
            }
        }

    /**
     * 一键删除本 App 写入的全部日历事件（「我的 → 日历同步」入口）。
     * 只删 description 含 [SYNC_MARKER] 的事件，用户自己的日历内容不受影响。
     */
    suspend fun deleteSynced(context: Context): CalendarSyncResult =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            try {
                val deleted = resolver.delete(
                    CalendarContract.Events.CONTENT_URI,
                    DELETE_SELECTION,
                    DELETE_MARKER_ARGS,
                )
                CalendarSyncResult.Deleted(count = deleted)
            } catch (e: SecurityException) {
                CalendarSyncResult.NoPermission
            } catch (e: Exception) {
                CalendarSyncResult.Error(e.message ?: "删除日历事件失败")
            }
        }

    /** 插入单个事件，返回事件 Uri（供追加提醒）；失败返回 null。 */
    private fun insertEvent(
        resolver: android.content.ContentResolver,
        calendarId: Long,
        event: CourseEvent,
    ): Uri? {
        val values = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, event.name)
            put(
                CalendarContract.Events.DESCRIPTION,
                buildString {
                    if (event.teacher.isNotBlank()) append("教师：${event.teacher}")
                    if (isNotEmpty()) append("\n")
                    append(SYNC_MARKER)
                },
            )
            if (event.position.isNotBlank()) {
                put(CalendarContract.Events.EVENT_LOCATION, event.position)
            }
            put(CalendarContract.Events.DTSTART, event.start.toEpochMilli())
            put(CalendarContract.Events.DTEND, event.end.toEpochMilli())
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
        }
        return resolver.insert(CalendarContract.Events.CONTENT_URI, values)
    }

    /** 给刚插入的事件追加一条提前 [minutes] 分钟的提醒。 */
    private fun insertReminder(
        resolver: android.content.ContentResolver,
        eventUri: Uri,
        minutes: Int,
    ) {
        val eventId = runCatching { ContentUris.parseId(eventUri) }.getOrNull() ?: return
        val values = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, eventId)
            put(CalendarContract.Reminders.MINUTES, minutes)
            put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_DEFAULT)
        }
        runCatching { resolver.insert(CalendarContract.Reminders.CONTENT_URI, values) }
    }
}

/** [LocalDateTime] → 纪元毫秒（系统默认时区），日历 Provider 口径。 */
private fun java.time.LocalDateTime.toEpochMilli(): Long =
    atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
