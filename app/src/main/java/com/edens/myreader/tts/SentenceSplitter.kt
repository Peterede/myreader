package com.edens.myreader.tts

/**
 * Splits chapter text into natural sentence-sized units for text-to-speech.
 *
 * Handles common abbreviations, decimal numbers and closing quotes/brackets.
 * Newlines are treated as *soft* boundaries: a line break only ends a sentence
 * when the text so far already ends with terminal punctuation. Soft-wrapped
 * lines (plain-text books, source formatting) are therefore merged into one
 * natural sentence instead of being read as separate fragments.
 */
object SentenceSplitter {

    private const val MAX_CHUNK = 380

    private val ABBREVIATIONS = setOf(
        "mr", "mrs", "ms", "dr", "prof", "st", "sr", "jr", "rev", "hon",
        "gen", "col", "lt", "sgt", "capt", "insp", "supt", "gov", "sen",
        "rep", "pres", "amb", "fr", "mt", "vs", "etc", "cf", "ca", "approx",
        "dept", "est", "fig", "vol", "no", "p", "pp", "ed", "eds", "al",
        "inc", "ltd", "co", "corp", "bros", "ave", "blvd", "rd", "min", "max",
        "e.g", "i.e", "a.m", "p.m",
    )

    private val CLOSERS = charArrayOf('"', '”', '’', '\'', ')', ']', '}', '»')

    fun split(text: String): List<String> {
        val sentences = mutableListOf<String>()
        val chars = text.toCharArray()
        var start = 0
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            if (c == '\n' || c == '\r') {
                // Soft boundary: only a real sentence end breaks here.
                if (endsSentence(chars, start, i)) {
                    emit(String(chars, start, i - start), sentences)
                    start = skipWhitespace(chars, i)
                }
                i = skipWhitespace(chars, i)
                continue
            }
            if (c == '.' || c == '!' || c == '?' || c == '…') {
                var end = i + 1
                while (end < chars.size && isTrailer(chars[end])) end++
                val boundary = end >= chars.size || chars[end].isWhitespace()
                if (boundary && isBreak(chars, start, i)) {
                    emit(String(chars, start, end - start), sentences)
                    start = end
                    i = end
                    continue
                }
            }
            i++
        }
        emit(String(chars, start, chars.size - start), sentences)
        return sentences
    }

    private fun skipWhitespace(chars: CharArray, from: Int): Int {
        var j = from
        while (j < chars.size && chars[j].isWhitespace()) j++
        return j
    }

    private fun isTrailer(c: Char): Boolean =
        c in CLOSERS || c == '.' || c == '!' || c == '?' || c == '…'

    /** True if [start, end) ends with terminal punctuation (ignoring closers). */
    private fun endsSentence(chars: CharArray, start: Int, end: Int): Boolean {
        var b = end - 1
        while (b >= start && (chars[b].isWhitespace() || isTrailer(chars[b]))) {
            // Only skip closers after a terminal mark; stop if we hit text.
            if (!chars[b].isWhitespace() && !isTrailer(chars[b])) break
            b--
        }
        if (b < start) return false
        val c = chars[b]
        return c == '.' || c == '!' || c == '?' || c == '…'
    }

    /** Decides whether the terminal punctuation at [dotIndex] is a real sentence end. */
    private fun isBreak(chars: CharArray, start: Int, dotIndex: Int): Boolean {
        if (chars[dotIndex] != '.') return true

        val prev = if (dotIndex > start) chars[dotIndex - 1] else ' '
        if (prev.isDigit()) {
            var j = dotIndex + 1
            while (j < chars.size && chars[j].isWhitespace()) j++
            if (j < chars.size && chars[j].isDigit()) return false
        }

        // Single-letter initials: "J. R. R. Tolkien"
        if (prev.isUpperCase() && dotIndex - 1 <= start) return false

        // Known abbreviations: look at the word before the dot.
        var b = dotIndex - 1
        while (b >= start && (chars[b].isLetter() || chars[b] == '.')) b--
        val wordStart = b + 1
        if (wordStart < dotIndex) {
            val word = String(chars, wordStart, dotIndex - wordStart).trim('.').lowercase()
            if (word in ABBREVIATIONS) return false
            if (word.length == 1 && word[0].isLetter()) return false
        }
        return true
    }

    private fun emit(raw: String, out: MutableList<String>) {
        val sentence = raw.replace(Regex("""\s+"""), " ").trim()
        if (sentence.isEmpty()) return
        if (sentence.length <= MAX_CHUNK) {
            out += sentence
        } else {
            out += chunkLongSentence(sentence)
        }
    }

    /** Breaks overlong units at soft punctuation, then hard-cuts as a last resort. */
    private fun chunkLongSentence(sentence: String): List<String> {
        val chunks = mutableListOf<String>()
        val parts = mutableListOf<String>()
        val sb = StringBuilder()
        for (c in sentence) {
            sb.append(c)
            if (c == ',' || c == ';' || c == ':' || c == '—' || c == '–' || c == ' ') {
                if (sb.length >= MAX_CHUNK / 2) {
                    parts += sb.toString()
                    sb.clear()
                }
            }
        }
        if (sb.isNotEmpty()) parts += sb.toString()

        for (part in parts) {
            var remaining = part
            while (remaining.length > MAX_CHUNK) {
                var cut = remaining.lastIndexOf(' ', MAX_CHUNK)
                if (cut < MAX_CHUNK / 2) cut = MAX_CHUNK
                chunks += remaining.substring(0, cut).trim()
                remaining = remaining.substring(cut).trim()
            }
            if (remaining.isNotBlank()) chunks += remaining
        }
        return chunks.filter { it.isNotBlank() }
    }
}
