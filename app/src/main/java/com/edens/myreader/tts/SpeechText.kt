package com.edens.myreader.tts

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
        Regex("""\b(Mr|Mrs|Ms|Dr|Prof|St|Sr|Jr|vs|Mt)\.(?=\s+\S)""")
    private val ELLIPSIS = Regex("""\.{2,}""")
    private val EXCLAMATION_RUN = Regex("""[!?]{2,}""")
    private val DASH = Regex("""\s*(?:—|–|--)\s*""")
    private val SPACED_HYPHEN = Regex("""\s+-\s+""")
    private val SPACE_BEFORE_PUNCT = Regex("""[ \t]+([,.;:!?…)\]}»”’])""")
    private val WHITESPACE = Regex("""\s+""")

    fun forSpeech(sentence: String): String {
        var s = sentence.replace(';', ',')
        s = ABBREVIATION_DOT.replace(s) { it.groupValues[1] }
        s = ELLIPSIS.replace(s, "…")
        s = EXCLAMATION_RUN.replace(s) { it.value.first().toString() }
        s = DASH.replace(s, " — ")
        s = SPACED_HYPHEN.replace(s, " — ")
        s = SPACE_BEFORE_PUNCT.replace(s) { it.groupValues[1] }
        return WHITESPACE.replace(s, " ").trim()
    }
}
