package com.edens.myreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechChunkerTest {

    private val sentences = listOf("One.", "Two.", "Three.", "Four.")

    private fun paragraphStarts(vararg indexes: Int): BooleanArray =
        BooleanArray(sentences.size).also { starts -> indexes.forEach { starts[it] = true } }

    @Test
    fun `a chunk never crosses a paragraph break`() {
        val chunk = SpeechChunker.plan(sentences, paragraphStarts(0, 2), from = 0)

        assertEquals(2, chunk.sentenceCount)
        assertEquals("One. Two.", chunk.text)
    }

    @Test
    fun `a chunk starts at the requested sentence`() {
        val chunk = SpeechChunker.plan(sentences, paragraphStarts(0), from = 1)

        assertEquals(1, chunk.start)
        assertEquals(3, chunk.sentenceCount)
        assertEquals("Two. Three. Four.", chunk.text)
    }

    @Test
    fun `offsets point at each covered sentence`() {
        val chunk = SpeechChunker.plan(sentences, null, from = 1)

        assertEquals(listOf(0, 5, 12), chunk.sentenceOffsets.toList())
        chunk.sentenceOffsets.forEachIndexed { i, offset ->
            assertTrue(chunk.text.startsWith(sentences[1 + i], offset))
        }
    }

    @Test
    fun `offsets are measured in the spoken text, not the book text`() {
        val text = listOf("He paused ; then left.", "Fine.")
        val chunk = SpeechChunker.plan(text, null, from = 0)

        assertEquals("He paused, then left. Fine.", chunk.text)
        assertEquals(22, chunk.sentenceOffsets[1])
        assertTrue(chunk.text.startsWith("Fine.", 22))
    }

    @Test
    fun `a long paragraph is cut at the size cap`() {
        val long = List(20) { "Sentence number $it with enough words to matter." }
        val starts = BooleanArray(long.size).also { it[0] = true }

        val chunk = SpeechChunker.plan(long, starts, from = 0)

        assertTrue(chunk.sentenceCount in 1 until long.size)
        assertTrue(chunk.text.length <= SpeechChunker.MAX_CHUNK_CHARS)
        assertTrue(chunk.sentenceCount <= SpeechChunker.MAX_SENTENCES)
    }

    @Test
    fun `a single overlong sentence is still spoken`() {
        val huge = "word ".repeat(400).trim() + "."

        val chunk = SpeechChunker.plan(listOf(huge), null, from = 0)

        assertEquals(1, chunk.sentenceCount)
        assertEquals(huge, chunk.text)
    }

    @Test
    fun `chunks cover the chapter without gaps`() {
        val paragraphStarts = booleanArrayOf(true, false, true, false)
        var index = 0
        var chunks = 0
        while (index < sentences.size) {
            val chunk = SpeechChunker.plan(sentences, paragraphStarts, from = index)
            assertTrue(chunk.sentenceCount > 0)
            index += chunk.sentenceCount
            chunks++
        }

        assertEquals(2, chunks)
        assertEquals(sentences.size, index)
    }
}
