package edu.gcp.schedule.data

import edu.gcp.schedule.domain.SemesterConfig
import edu.gcp.schedule.domain.TimeSlot

object DefaultData {

    /**
     * 作息表：每小节 40 分钟，大节内两小节之间休息 5 分钟，大节之间 20 分钟换教室。
     *
     * 根因：旧版本这里只放了 5 条「大节」（08:00/10:00/14:00/16:00/19:00），
     * 而 `Course.startSection/endSection` 存的是教务返回的**小节号** 1–11。
     * 两者语义不一致，`WeekPage` 又直接把小节号当行号用，导致 7-8 节的课被画到第 7 行、9-10 节画到第 9 行，
     * 也就是课块溢出网格。现在作息细化到小节，行号 = 小节号，不再需要折算。
     *
     * 2026-09-23 按本校实际作息更新 7–11 节（下午从 15:40 开始、晚上 18:30 开始）：
     * 每节仍是 40 分钟、大节内仍是 5 分钟；注意 **6→7 只有 15 分钟**（不是 20），
     * 17:05→18:30 是 85 分钟晚饭。逐节递推后 5 个大节结束于
     * 09:55 / 11:40 / 15:25 / 17:05 / 20:40（见 DESIGN 3.5）。
     */
    val defaultTimeSlots: List<TimeSlot> = listOf(
        TimeSlot(1, "08:30", "09:10"),
        TimeSlot(2, "09:15", "09:55"),
        TimeSlot(3, "10:15", "10:55"),
        TimeSlot(4, "11:00", "11:40"),
        TimeSlot(5, "14:00", "14:40"),
        TimeSlot(6, "14:45", "15:25"),
        TimeSlot(7, "15:40", "16:20"),
        TimeSlot(8, "16:25", "17:05"),
        TimeSlot(9, "18:30", "19:10"),
        TimeSlot(10, "19:15", "19:55"),
        TimeSlot(11, "20:00", "20:40"),
    )

    /**
     * 作息表结构版本。
     * 0 = 旧的 5 条大节；1 = 11 条小节；2 = 7–11 节改为本校实际作息
     * （7 节 15:40 起、9 节 18:30 起、11 节 20:00–20:40）。
     *
     * 升级时一次性覆盖**未自定义**过的课表节次表；用户手动改过的课表（`slotsCustomized`）
     * 一律不动，需要时在「我的 → 课表设置 → 作息表」点「恢复默认作息」。
     */
    const val SLOT_SCHEMA_VERSION = 2

    /**
     * 2026-2027-1 默认开学日：2026-09-07（周一）。
     * 可在设置中修改。
     */
    val defaultSemester: SemesterConfig = SemesterConfig(
        startDate = "2026-09-07",
        totalWeeks = 20,
        firstDayOfWeek = 1,
    )
}
