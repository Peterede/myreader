package com.myreader.app.data

import java.util.Locale

/**
 * Decodes HTML entities and converts a span of HTML (an EPUB/XHTML content
 * document) into plain text suitable for text-to-speech.
 *
 * Newlines present in the HTML source are only formatting (line wrapping), so
 * they are collapsed; real paragraph structure is preserved via block tags.
 */
object HtmlText {

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "hellip" to "…",
        "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "middot" to "·", "copy" to "©", "reg" to "®", "trade" to "™",
        "times" to "×", "sect" to "§", "para" to "¶",
    )

    private val BLOCK_TAGS =
        Regex("""(?i)</?\s*(p|div|h[1-6]|li|tr|br|blockquote|section|article|header|footer|nav|table|ul|ol|pre)\b[^>]*>""")
    private val SCRIPT_STYLE =
        Regex("""(?is)<(script|style|head)\b.*?</\1>""")
    private val TAG = Regex("""(?s)<[^>]*>""")
    private val ENTITY = Regex("""&(#x?[0-9a-fA-F]+|[a-zA-Z]+);""")

    /** Sentinel marking a paragraph break while whitespace is normalized. */
    private const val BREAK = ""

    fun toText(html: String): String {
        val entityCleaned = ENTITY.replace(html) { m ->
            decodeEntity(m.groupValues[1]) ?: m.value
        }
        var s = SCRIPT_STYLE.replace(entityCleaned, " ")
        s = BLOCK_TAGS.replace(s) { BREAK }
        s = TAG.replace(s, "")
        s = s.replace(Regex("+"), BREAK)
        // All remaining whitespace is source formatting: collapse it.
        s = s.replace(Regex("""\s+"""), " ")
        s = s.replace(BREAK, "\n\n")
        return s.trim()
    }

    private fun decodeEntity(body: String): String? {
        if (body.startsWith("#x") || body.startsWith("#X")) {
            body.substring(2).toIntOrNull(16)?.let { codePointToString(it) }?.let { return it }
            return null
        }
        if (body.startsWith("#")) {
            body.substring(1).toIntOrNull()?.let { codePointToString(it) }?.let { return it }
            return null
        }
        return NAMED[body.lowercase(Locale.ROOT)]
    }

    private fun codePointToString(cp: Int): String? =
        if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else null
}
