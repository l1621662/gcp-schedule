package edu.gcp.schedule.data.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `evaluateJavascript` 返回值解码的契约测试。
 *
 * 为什么值得单独钉：这段逻辑失效时**不报错**，只是中文匹配悄悄判不出来
 * （「用户没有登录」识别失败 → 回退页被当成正常页）。见 [unwrapJsString] 的注释。
 */
class JsStringDecodeTest {

    @Test
    fun unwrap_plainQuotedString() {
        assertEquals("hello", unwrapJsString("\"hello\""))
    }

    @Test
    fun unwrap_nullLiteralIsEmpty() {
        assertEquals("", unwrapJsString("null"))
        assertEquals("", unwrapJsString(null))
        assertEquals("", unwrapJsString(""))
    }

    @Test
    fun unwrap_escapedNewlineAndQuote() {
        assertEquals("a\"b", unwrapJsString("\"a\\\"b\""))
        assertEquals("a\nb", unwrapJsString("\"a\\nb\""))
    }

    @Test
    fun unwrap_unicodeEscapedChineseSurvives() {
        // 关键用例：WebView 可能把正文里的中文转义成 \uXXXX，
        // 解不出来就会让「用户没有登录」的识别静默失效
        val raw = "\"\\u7528\\u6237\\u6ca1\\u6709\\u767b\\u5f55\""
        assertEquals("用户没有登录", unwrapJsString(raw))
    }

    @Test
    fun unwrap_mixedEscapesAreAllDecoded() {
        val raw = "\"\\u671f\\u672b \\u8bfe\\u8868\\nline2\""
        assertEquals("期末 课表\nline2", unwrapJsString(raw))
    }

    @Test
    fun decode_leavesInvalidEscapeIntact() {
        // 非法十六进制不做处理，原样保留而不是吞掉
        assertEquals("\\uZZZZ", decodeUnicodeEscapes("\\uZZZZ"))
    }

    @Test
    fun decode_loneBackslashIsPreserved() {
        assertEquals("a\\b", decodeUnicodeEscapes("a\\b"))
        assertEquals("", decodeUnicodeEscapes(""))
    }

    @Test
    fun decode_truncatedEscapeAtEndIsPreserved() {
        // `\u12` 到结尾：长度不足，不能被 substring 越界搞崩
        assertEquals("x\\u12", decodeUnicodeEscapes("x\\u12"))
    }
}
