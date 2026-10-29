package com.edens.myreader.tts

import java.util.Locale

/**
 * Shapes a sentence into the form text-to-speech engines read most naturally.
 *
 * The reader always displays the book's own text; this only affects the string
 * handed to the engine. Android's TTS API exposes no SSML on the common
 * engines, so punctuation is the only lever for prosody:
 *
 *  - `;` is spoken as `,` — many engines give a semicolon a clipped, oddly
 *    stressed contour on the word before it, while a comma reads as the light
 *    clause pause the author intended.
 *  - the orthographic dot in an abbreviation (`Mr.`, `Dr.`, `St.`) is dropped
 *    when a name follows, so `Mr. Sophistication` is not read as `Mr.` plus a
 *    new sentence.
 *  - Latin abbreviations and symbols are written out as words (`e.g.` →
 *    `for example`, `etc.` → `et cetera`, `&` → `and`, `No. 5` → `number 5`),
 *    because engines either spell them out letter by letter or skip them.
 *  - footnote markers (`[12]`, `¹`) are dropped: no narrator reads them, and an
 *    engine that does blurts a stray number into the middle of a sentence.
 *  - asterisks used for emphasis in plain-text books are dropped instead of
 *    being announced as "asterisk".
 *  - whitespace before punctuation is dropped, so `word .` is not read as an
 *    emphasised word followed by an extra pause.
 *  - dashes (`—`, `–`, `--`, and a spaced hyphen) get spaces around them, so
 *    `word—word` is not pronounced as a single token.
 *  - runs of dots collapse to `…`, and `!!!` / `?!?` collapse to a single mark,
 *    which stops the engine from shouting or stretching the word.
 */
object SpeechText {

    /**
     * Abbreviations whose dot is not a sentence end. Only stripped when another
     * word follows, so a genuine full stop at the end of a sentence stays.
     */
    private val ABBREVIATION_DOT =
        Regex("""\b(Mr|Mrs|Ms|Dr|Prof|St|Sr|Jr|Mt)\.(?=\s+\S)""")

    /** Abbreviations and symbols a narrator says as ordinary words. */
    private val SPOKEN_ABBREVIATION =
        Regex("""(?i)\b(e\.g|i\.e|etc|vs)\.(?=\s|$|[,;:!?)\]»”’])""")
    private val AMPERSAND = Regex("""(?<![A-Za-z])&(?![A-Za-z])""")
    private val NUMBER_SIGN = Regex("""(?i)\bNo\.(?=\s*\d)""")
    private val FOOTNOTE_MARK = Regex("""\[\s*\d{1,3}\s*]""")
    private val SUPERSCRIPT_DIGITS = Regex("""[\u00B9\u00B2\u00B3\u2070-\u2079]+""")
    // Emphasis markers hug a word (`*very*`); a spaced scene break (`* * *`)
    // does not, and is left for the engine to announce rather than vanish.
    private val EMPHASIS_OPEN = Regex("""\*+(?=[A-Za-z0-9])""")
    private val EMPHASIS_CLOSE = Regex("""(?<=[A-Za-z0-9])\*+""")
    private val ELLIPSIS = Regex("""\.{2,}""")
    private val EXCLAMATION_RUN = Regex("""[!?]{2,}""")
    private val DASH = Regex("""\s*(?:—|–|--)\s*""")
    private val SPACED_HYPHEN = Regex("""\s+-\s+""")
    private val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([,.;:!?…)\]}»”’])""")
    private val WHITESPACE = Regex("""\s+""")

    fun forSpeech(sentence: String): String {
        var s = sentence.replace(';', ',')
        s = SPOKEN_ABBREVIATION.replace(s) { m ->
            val abbreviation = m.groupValues[1].lowercase(Locale.ROOT)
            val spoken = when (abbreviation) {
                "e.g" -> "for example"
                "i.e" -> "that is"
                "etc" -> "et cetera"
                else -> "versus"
            }
            val next = s.getOrNull(m.range.last + 1)
            when {
                // A sentence-final abbreviation still needs its full stop.
                next == null -> "$spoken."
                // A parenthetical aside is set off as the narrator pauses:
                // "for example, this one" rather than "for example this one".
                next.isWhitespace() && abbreviation != "etc" && abbreviation != "vs" -> "$spoken,"
                else -> spoken
            }
        }
        s = ABBREVIATION_DOT.replace(s) { it.groupValues[1] }
        s = NUMBER_SIGN.replace(s, "number ")
        s = FOOTNOTE_MARK.replace(s, "")
        s = SUPERSCRIPT_DIGITS.replace(s, "")
        s = EMPHASIS_OPEN.replace(s, "")
        s = EMPHASIS_CLOSE.replace(s, "")
        s = AMPERSAND.replace(s, "and")
        s = ELLIPSIS.replace(s, "…")
        s = EXCLAMATION_RUN.replace(s) { it.value.first().toString() }
        s = DASH.replace(s, " — ")
        s = SPACED_HYPHEN.replace(s, " — ")
        s = SPACE_BEFORE_PUNCT.replace(s) { it.groupValues[1] }
        return WHITESPACE.replace(s, " ").trim()
    }
}
