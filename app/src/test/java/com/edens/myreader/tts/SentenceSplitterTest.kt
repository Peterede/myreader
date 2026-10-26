package com.edens.myreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSplitterTest {

    @Test
    fun `abbreviations and decimals do not end a sentence`() {
        assertEquals(
            listOf("Dr. Smith paid 3.50.", "He left."),
            SentenceSplitter.split("Dr. Smith paid 3.50. He left."),
        )
    }

    @Test
    fun `initials stay in the same sentence`() {
        assertEquals(
            listOf("J. R. R. Tolkien wrote it."),
            SentenceSplitter.split("J. R. R. Tolkien wrote it."),
        )
    }

    @Test
    fun `soft-wrapped source lines are merged`() {
        assertEquals(
            listOf("A line that is wrapped."),
            SentenceSplitter.split("A line that is\nwrapped."),
        )
    }

    @Test
    fun `a line ending in terminal punctuation is a sentence end`() {
        assertEquals(
            listOf("First line.", "Second line."),
            SentenceSplitter.split("First line.\nSecond line."),
        )
    }

    @Test
    fun `closing quotes stay with the sentence`() {
        assertEquals(
            listOf("\"Stop.\"", "Then he left."),
            SentenceSplitter.split("\"Stop.\" Then he left."),
        )
    }

    @Test
    fun `dialogue attributions stay with the quoted sentence`() {
        assertEquals(
            listOf("“Why?” she asked.", "He shrugged."),
            SentenceSplitter.split("“Why?” she asked. He shrugged."),
        )
        assertEquals(
            listOf("“Stop!” he shouted, and the room fell silent."),
            SentenceSplitter.split("“Stop!” he shouted, and the room fell silent."),
        )
    }

    @Test
    fun `an ellipsis followed by a lowercase word does not split`() {
        assertEquals(
            listOf("There was a pause… and then he nodded."),
            SentenceSplitter.split("There was a pause… and then he nodded."),
        )
    }

    @Test
    fun `titles and abbreviations survive inside a sentence`() {
        assertEquals(
            listOf("Mr. Sophistication ordered for Dr. Smith."),
            SentenceSplitter.split("Mr. Sophistication ordered for Dr. Smith."),
        )
    }

    @Test
    fun `question and exclamation marks end sentences`() {
        assertEquals(
            listOf("Why?", "Because!", "Fine."),
            SentenceSplitter.split("Why? Because! Fine."),
        )
    }

    @Test
    fun `sentences never exceed the maximum length`() {
        val text = "word, ".repeat(200) + "the end."

        val sentences = SentenceSplitter.split(text)

        assertTrue(sentences.size > 1)
        assertTrue(sentences.all { it.length <= 380 })
    }

    @Test
    fun `blank text produces no sentences`() {
        assertEquals(emptyList<String>(), SentenceSplitter.split("  \n\n  "))
    }
}
