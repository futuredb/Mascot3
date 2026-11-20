package com.generativemascot.app.ui

/** Extract only complete characters of the streamed JSON string; never show control fields. */
internal fun partialReplyFromJson(json: String): String? {
    val field = Regex("(?:\\{|,)\\s*\"reply_ru\"\\s*:\\s*\"").find(json) ?: return null
    val out = StringBuilder()
    var i = field.range.last + 1
    while (i < json.length) {
        val c = json[i++]
        if (c == '"') break
        if (c != '\\') { out.append(c); continue }
        if (i >= json.length) break
        when (val escaped = json[i++]) {
            '"', '\\', '/' -> out.append(escaped)
            'b' -> out.append('\b')
            'f' -> out.append('\u000c')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> {
                if (i + 4 > json.length) break
                val code = json.substring(i, i + 4).toIntOrNull(16) ?: break
                i += 4
                val unit = code.toChar()
                if (unit.isHighSurrogate()) {
                    if (i + 6 > json.length || json[i] != '\\' || json[i + 1] != 'u') break
                    val low = json.substring(i + 2, i + 6).toIntOrNull(16)?.toChar() ?: break
                    if (!low.isLowSurrogate()) break
                    out.append(unit).append(low); i += 6
                } else if (!unit.isLowSurrogate()) out.append(unit)
            }
            else -> break
        }
    }
    if (out.isNotEmpty() && out.last().isHighSurrogate()) out.deleteCharAt(out.lastIndex)
    return out.toString().trimStart()
}
