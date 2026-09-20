package edu.jxslu.schedule.ui.detect

import edu.jxslu.schedule.data.jw.JwDetectRunner
import edu.jxslu.schedule.ui.common.NoticeFeedback
import edu.jxslu.schedule.ui.common.NoticeTone

/**
 * 检测结果的一句话 + 语气（三个入口共用：导入弹层「检测课表更新」的**内联行**、
 * 设置页「立即检测」与更新课表页空态按钮的 Snackbar）。语气集中在这里定，
 * 同一结果在哪儿的颜色与图标都一致。
 */
typealias DetectNotice = NoticeFeedback

/**
 * 检测结果 → 用户可读文案。三个入口共用同一套，保证同一结果在哪儿都是同一句话。
 */
internal fun detectOutcomeMessage(outcome: JwDetectRunner.Outcome): String =
    detectOutcomeNotice(outcome).text

internal fun detectOutcomeNotice(outcome: JwDetectRunner.Outcome): DetectNotice = when (outcome) {
    is JwDetectRunner.Outcome.NoDiff ->
        if (outcome.baselineCreated) {
            // 首次跑只是建基线，既不是「查过了没变化」也不是成功写入，给中性语气
            NoticeFeedback("已建立教务基线，下次检测开始报告课表变化", NoticeTone.Info)
        } else {
            NoticeFeedback("教务课表无变化", NoticeTone.Success)
        }
    is JwDetectRunner.Outcome.DiffFound -> NoticeFeedback("检测到课表变化", NoticeTone.Info)
    is JwDetectRunner.Outcome.Skipped -> NoticeFeedback(outcome.reason, NoticeTone.Warning)
    is JwDetectRunner.Outcome.Failed -> NoticeFeedback("检测失败：${outcome.message}", NoticeTone.Error)
}
