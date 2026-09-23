package edu.gcp.schedule.ui.common

import edu.gcp.schedule.domain.compactPosition
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 地点压缩。
 *
 * 真机验证时发现课表色块上显示的是 `@教学…`：教务返回 `教学北大楼(北B102)`，
 * 但解析层 `.trim('；',';','）',')')` 把结尾右括号剥掉了，库里存成 `教学北大楼(北B102`，
 * 匹配不到成对括号就原样返回、再被截断。
 *
 * 所以这里钉住 [compactPosition]：必须能兜住**没闭合**的括号（库里已有脏数据，不可能要求
 * 用户重新导入）。旧强智解析器把右括号 trim 掉的那个坑随解析器一起删除；正方解析器直接取
 * 地点单元格文本、不做 trim，因此这里只保留压缩逻辑本身的契约。
 */
class CompactPositionTest {

    /** 库里现存的真实脏值（缺右括号） */
    @Test
    fun unclosedParenFromLegacyData() {
        assertEquals("北B102", compactPosition("教学北大楼(北B102"))
        assertEquals("南C303", compactPosition("教学南大楼(南C303"))
        assertEquals("北B213", compactPosition("教学北大楼(北B213"))
    }

    /** 只剩一个左括号（无地点课程 `()` 被 trim 的结果） */
    @Test
    fun loneOpenParenMeansNoLocation() {
        assertEquals("", compactPosition("("))
    }

    /** 正常与全角括号 */
    @Test
    fun pairedParens() {
        assertEquals("北B102", compactPosition("教学北大楼(北B102)"))
        assertEquals("南B206", compactPosition("教学南大楼（南B206）"))
        assertEquals("", compactPosition("()"))
        assertEquals("", compactPosition("（ ）"))
    }

    /** 没有括号就原样用 */
    @Test
    fun noParensKeepsRawText() {
        assertEquals("北B102", compactPosition("北B102"))
        assertEquals("实验楼", compactPosition(" 实验楼 "))
        assertEquals("", compactPosition("   "))
    }

    /** 多组括号取最后一组 */
    @Test
    fun lastParenGroupWins() {
        assertEquals("北B203", compactPosition("教学北大楼(北B102)(北B203)"))
    }

}
