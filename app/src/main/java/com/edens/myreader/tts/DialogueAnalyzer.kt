package com.edens.myreader.tts

/** A span of a book sentence that is read in a single voice. */
internal data class SpeechPart(
    /** Index of the sentence in the chapter this piece belongs to. */
    val sentence: Int,
    /** Character reading this piece, or null for the narrator. */
    val speaker: String?,
    /** The book's own text for this piece. */
    val text: String,
    /** True when this piece opens a paragraph. */
    val startsParagraph: Boolean,
    /** True when the piece is quoted speech, even if its speaker is unknown. */
    val isDialogue: Boolean = false,
)

/** A character detected in a chapter, with the number of quotes attributed. */
internal data class DialogueSpeaker(val name: String, val lineCount: Int)

/** The cast of one chapter and how its sentences split between voices. */
internal data class ChapterDialogue(
    val speakers: List<DialogueSpeaker>,
    val parts: List<SpeechPart>,
)

/**
 * Splits a chapter into the pieces that are read in different voices, and
 * attributes quoted speech to characters.
 *
 * A speech engine knows nothing about a book: left alone it reads every line in
 * one voice. This analyser recovers the cast from dialogue tags (`said Maria`,
 * `Maria asked`, `, she said`) and from the alternation of untagged lines, so
 * the player can hand each quote to a character's voice and everything else to
 * the narrator.
 *
 * Attribution is deliberately conservative: when a line cannot be tied to a
 * character with reasonable confidence it stays with the narrator, and the
 * reader can always correct the cast by hand.
 */
internal object DialogueAnalyzer {

    /** Characters of narration inspected on each side of a quote for a tag. */
    private const val WINDOW = 100

    private val WHITESPACE = Regex("""\s+""")

    /**
     * Analyzes a chapter.
     *
     * @param sentences the chapter's sentences, as displayed to the reader.
     * @param startsParagraph per sentence: true when it opens a paragraph.
     */
    fun analyze(sentences: List<String>, startsParagraph: BooleanArray?): ChapterDialogue {
        if (sentences.isEmpty()) return ChapterDialogue(emptyList(), emptyList())

        val sentenceStarts = IntArray(sentences.size)
        val full = buildString {
            sentences.forEachIndexed { index, sentence ->
                if (index > 0) append(' ')
                sentenceStarts[index] = length
                append(sentence)
            }
        }

        val tracker = Tracker()
        val quotes = findQuotes(full, tracker)
        return ChapterDialogue(
            speakers = tracker.cast(),
            parts = buildParts(sentences, sentenceStarts, startsParagraph, quotes),
        )
    }

    // ------------------------------------------------------------ quote scan

    private data class Quote(val start: Int, val end: Int, val speaker: String?)

    /**
     * Finds quoted speech across the whole chapter, so a quote that runs over a
     * sentence break (the sentence splitter cuts inside quotes) still closes on
     * its own closing mark instead of swallowing the rest of the chapter.
     */
    private fun findQuotes(full: String, tracker: Tracker): List<Quote> {
        val quotes = mutableListOf<Quote>()
        var open = -1
        var narrationFrom = 0
        for (index in full.indices) {
            val char = full[index]
            if (open < 0) {
                if (char == '"' || char == '“' || char == '«') open = index
                continue
            }
            val closes = char == '"' || char == '”' || char == '»'
            if (!closes) continue
            if (index > open + 1) {
                tracker.observeNarration(full, narrationFrom, open)
                quotes += Quote(open, index + 1, tracker.resolve(full, open, index + 1))
                narrationFrom = index + 1
            }
            open = -1
        }
        return quotes
    }

    // ------------------------------------------------------------ attribution

    /**
     * Tracks who is speaking: the cast, the character last mentioned by the
     * narration, and the most recent speakers for alternating untagged lines.
     */
    private class Tracker {
        private val counts = LinkedHashMap<String, Int>()
        private val recent = ArrayDeque<String>()
        private var subject: String? = null
        private var last: String? = null

        /** Remembers the last known character named by the narration in a gap. */
        fun observeNarration(full: String, from: Int, to: Int) {
            if (to <= from) return
            val text = full.substring(from, to)
            var best: String? = null
            var bestAt = -1
            for (name in counts.keys) {
                val at = text.lastIndexOf(name)
                if (at > bestAt) {
                    bestAt = at
                    best = name
                }
            }
            if (best != null) subject = best
        }

        fun resolve(full: String, open: Int, close: Int): String? {
            val prefix = full.substring(maxOf(0, open - WINDOW), open)
            val suffix = full.substring(close, minOf(full.length, close + WINDOW)).beforeQuote()
            // Only the narration since the previous quote says who this line
            // belongs to; text inside earlier quotes is someone else talking.
            val narration = prefix.substringAfterLast('”')
                .substringAfterLast('"')
                .substringAfterLast('»')

            nameAtStart(suffix)?.let { return record(it) }
            if (prefix.none { it == '"' || it == '”' || it == '“' || it == '»' || it == '«' }) {
                nameAtEnd(prefix)?.let { return record(it) }
            }

            if (PRONOUN_BEFORE.containsMatchIn(narration.takeLast(48)) ||
                PRONOUN_AFTER.containsMatchIn(suffix.take(48))
            ) {
                val who = subject ?: lastNameIn(narration) ?: alternate()
                if (who != null) return record(who)
            }

            val other = alternate() ?: last
            if (other != null) return record(other)
            return null
        }

        fun cast(): List<DialogueSpeaker> =
            counts.map { (name, lines) -> DialogueSpeaker(name, lines) }

        private fun alternate(): String? =
            recent.firstOrNull { it != last } ?: recent.firstOrNull()

        private fun record(name: String): String {
            counts[name] = (counts[name] ?: 0) + 1
            last = name
            subject = name
            recent.remove(name)
            recent.addFirst(name)
            return name
        }

        private fun lastNameIn(text: String): String? =
            NAME_TOKEN.findAll(text)
                .map { it.groupValues[1] }
                .lastOrNull { isName(it) }
    }

    private fun nameAtEnd(prefix: String): String? =
        TAG_NAME_FIRST_END.find(prefix)?.groupValues?.get(1)?.takeIf(::isName)
            ?: TAG_VERB_FIRST_END.find(prefix)?.groupValues?.get(1)?.takeIf(::isName)
            ?: TAG_NAME_COLON_END.find(prefix)?.groupValues?.get(1)?.takeIf(::isName)

    private fun nameAtStart(suffix: String): String? =
        TAG_NAME_FIRST_START.find(suffix)?.groupValues?.get(1)?.takeIf(::isName)
            ?: TAG_VERB_FIRST_START.find(suffix)?.groupValues?.get(1)?.takeIf(::isName)

    // ---------------------------------------------------------------- layout

    private fun buildParts(
        sentences: List<String>,
        sentenceStarts: IntArray,
        startsParagraph: BooleanArray?,
        quotes: List<Quote>,
    ): List<SpeechPart> {
        val parts = mutableListOf<SpeechPart>()
        sentences.forEachIndexed { index, sentence ->
            val start = sentenceStarts[index]
            val end = start + sentence.length
            var cursor = 0
            var first = true

            fun add(text: String, speaker: String?, dialogue: Boolean) {
                val clean = text.trim().trim('"', '“', '”', '«', '»').trim()
                    .replace(WHITESPACE, " ")
                if (clean.isEmpty()) return
                parts += SpeechPart(
                    sentence = index,
                    speaker = speaker,
                    text = clean,
                    startsParagraph = first && startsParagraph?.getOrNull(index) == true,
                    isDialogue = dialogue,
                )
                first = false
            }

            for (quote in quotes) {
                if (quote.start >= end || quote.end <= start) continue
                val from = (maxOf(quote.start, start) - start).coerceIn(0, sentence.length)
                val to = (minOf(quote.end, end) - start).coerceIn(0, sentence.length)
                if (from > cursor) add(sentence.substring(cursor, from), null, dialogue = false)
                add(sentence.substring(from, to), quote.speaker, dialogue = true)
                cursor = maxOf(cursor, to)
            }
            if (cursor < sentence.length) add(sentence.substring(cursor), null, dialogue = false)
            if (first) add(sentence, null, dialogue = false)
        }
        return parts
    }

    private fun String.beforeQuote(): String =
        substringBefore('"').substringBefore('”').substringBefore('“')
            .substringBefore('»').substringBefore('«')

    // ---------------------------------------------------------------- patterns

    private const val TITLE =
        """(?:(?:Mr|Mrs|Ms|Miss|Dr|Prof|Sir|Lady|Lord|Captain|Colonel|Major|Aunt|Uncle|Father|Mother|Brother|Sister)\.?\s+)?"""

    private const val NAME = """[A-Z][a-z’'\-]{1,24}"""

    private const val VERB =
        """(?i:said|asked|replied|answered|whispered|shouted|murmured|cried|called|added|continued|exclaimed|muttered|sighed|laughed|snapped|growled|repeated|explained|insisted|observed|remarked|agreed|admitted|demanded|wondered|yelled|protested|suggested|announced|declared|hissed|stammered|interrupted|thought|began|ventured|retorted|mumbled)"""

    private val TAG_NAME_FIRST_END = Regex("""$TITLE($NAME)\s+$VERB\s*[.,;:—–\-]?\s*$""")
    private val TAG_VERB_FIRST_END = Regex("""\b$VERB\s+$TITLE($NAME)\s*[.,;:—–\-]?\s*$""")
    private val TAG_NAME_FIRST_START = Regex("""^[\s,.;:—–\-]*$TITLE($NAME)\s+$VERB\b""")
    private val TAG_VERB_FIRST_START = Regex("""^[\s,.;:—–\-]*\b$VERB\s+$TITLE($NAME)\b""")
    private val TAG_NAME_COLON_END = Regex("""$TITLE($NAME)\s*:\s*$""")

    private val PRONOUN_BEFORE = Regex(
        """(?i)(?:\b(?:he|she|they)\s+$VERB|\b$VERB\s+(?:he|she|they))\s*[.,;:—–\-]?\s*$""",
    )
    private val PRONOUN_AFTER = Regex(
        """^[\s,.;:—–\-]*(?:\b(?:he|she|they)\s+$VERB|\b$VERB\s+(?:he|she|they))\b""",
    )

    private val NAME_TOKEN = Regex(TITLE + "(" + NAME + ")")

    /**
     * Words that look like names but are pronouns, determiners or turns of
     * phrase; treating one as a character would put a voice on the wrong lines.
     */
    private val NOT_NAMES = setOf(
        "A", "An", "All", "And", "As", "At", "Before", "Both", "But", "Chapter",
        "Even", "For", "God", "He", "Her", "Here", "His", "How", "However", "I",
        "If", "In", "It", "Its", "Just", "Lord", "Monday", "Tuesday", "Wednesday",
        "Thursday", "Friday", "Saturday", "Sunday", "January", "February", "March",
        "April", "May", "June", "July", "August", "September", "October",
        "November", "December", "No", "Not", "Now", "Of", "Off", "Oh", "On", "One",
        "Or", "Part", "Perhaps", "She", "So", "Some", "Still", "That", "The",
        "Their", "Then", "There", "These", "They", "This", "Those", "Three", "Two",
        "Well", "We", "What", "When", "While", "Who", "Why", "With", "Without",
        "Yes", "You",
    )

    private fun isName(candidate: String): Boolean =
        candidate.length >= 2 && candidate !in NOT_NAMES
}
