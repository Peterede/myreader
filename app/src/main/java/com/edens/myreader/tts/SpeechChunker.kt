package com.edens.myreader.tts

/** One utterance: the spoken text and where each covered sentence starts in it. */
internal data class SpeechChunk(
    /** Index of the first sentence covered. */
    val start: Int,
    /** Number of sentences in [text]. */
    val sentenceCount: Int,
    /** The normalized text handed to the engine. */
    val text: String,
    /** Character offset of each sentence inside [text]. */
    val sentenceOffsets: IntArray,
    /**
     * True when the chunk ends on a paragraph boundary (or the end of the
     * chapter), so the next chunk opens a new paragraph. The player leaves a
     * short beat of silence there, the way a narrator does.
     */
    val endsParagraph: Boolean,
)

/**
 * Groups consecutive sentences into one utterance.
 *
 * Speaking a whole paragraph per utterance gives the engine continuous
 * prosody: a full stop in the middle of a paragraph is read as a sentence
 * boundary, not as the end of the utterance on which engines apply their
 * emphatic final contour. One utterance per sentence, by contrast, restarts
 * that contour at every sentence boundary — the exact artefact this avoids.
 * Chunks therefore stop at paragraph breaks, and only fall back to the size
 * caps for unusually long paragraphs.
 */
internal object SpeechChunker {

    /** Keeps an utterance comfortably below the engines' input limits. */
    const val MAX_CHUNK_CHARS = 800

    /** A chunk is only cut for length once it holds at least this much text. */
    const val MIN_CHUNK_CHARS = 80

    const val MAX_SENTENCES = 12

    /**
     * Plans the utterance that starts at [from].
     *
     * @param startsParagraph per sentence: true when that sentence opens a new
     *   paragraph (null when the structure is unknown).
     */
    fun plan(
        sentences: List<String>,
        startsParagraph: BooleanArray?,
        from: Int,
    ): SpeechChunk {
        require(from in sentences.indices) { "start sentence $from out of range" }

        val text = StringBuilder()
        val offsets = ArrayList<Int>()
        var count = 0
        var idx = from
        while (idx < sentences.size && count < MAX_SENTENCES) {
            if (count > 0 && startsParagraph?.getOrNull(idx) == true) break

            val spoken = SpeechText.forSpeech(sentences[idx])
            val added = if (count > 0) 1 + spoken.length else spoken.length
            if (count > 0 && text.length + added > MAX_CHUNK_CHARS &&
                text.length >= MIN_CHUNK_CHARS
            ) {
                break
            }

            if (count > 0) text.append(' ')
            offsets += text.length
            text.append(spoken)
            count++
            idx++
        }

        // Ending where the next chunk would open a paragraph is what earns the
        // pause; a chunk cut short by a size cap runs straight on.
        val next = from + count
        val endsParagraph = next >= sentences.size || startsParagraph?.getOrNull(next) == true

        return SpeechChunk(
            start = from,
            sentenceCount = count,
            text = text.toString(),
            sentenceOffsets = offsets.toIntArray(),
            endsParagraph = endsParagraph,
        )
    }
}
