package edu.gcp.schedule.ui.common

/**
 * 带撤销动作的一次性反馈（DESIGN §3.3）。
 *
 * 删除课程、清空课表、调课应用这类破坏性动作不再只靠「不可撤销」警示——
 * 执行后给 Snackbar，用户在消失前点「撤销」就跑 [undo] 回滚。
 * 快照放在闭包里由 ViewModel 持有，不进数据库，5 秒 Snackbar 窗口外自然作废。
 */
data class UndoableMessage(
    val text: String,
    val undo: suspend () -> Unit,
)
