package edu.gcp.schedule.data.jw

/**
 * WebView `evaluateJavascript` 返回值的解码。
 *
 * 根因：该回调给的是 **JSON 编码后的字符串**（外层带引号，内部按 JSON 规则转义），
 * 而是否把中文转义成 `\uXXXX` 随 WebView 版本/内容而变。旧实现只做了一轮
 * `\\n` / `\\"` 替换，一旦出现 `\uXXXX`，「用户没有登录」这类中文匹配就会
 * **静默失效**——没有报错，只是判不出来，正是最难排查的那种。
 *
 * 纯逻辑、不依赖 android.*，可 JVM 单测。
 */
internal fun unwrapJsString(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var s = raw.trim()
    if (s == "null") return ""
    if (s.startsWith("\"") && s.endsWith("\"")) {
        s = s.substring(1, s.length - 1)
    }
    s = s
        .replace("\\\\", "\\")
        .replace("\\\"", "\"")
        .replace("\\n", "\n")
    // 顺序有讲究：\uXXXX 必须最后解——先解会让 `\\u006e`（字面量 \u006e）被误解成换行
    return if ("\\u" in s) decodeUnicodeEscapes(s) else s
}

/** 把 `\uXXXX` 解回字符；非法转义原样保留（宁可留着让调用方看见，也不要丢字符）。 */
internal fun decodeUnicodeEscapes(s: String): String {
    if ("\\u" !in s) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        if (s[i] == '\\' && i + 5 < s.length && s[i + 1] == 'u') {
            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
            if (code != null) {
                sb.append(code.toChar())
                i += 6
                continue
            }
        }
        sb.append(s[i])
        i++
    }
    return sb.toString()
}
