package com.edens.myreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialogueAnalyzerTest {

    private fun analyze(vararg sentences: String): ChapterDialogue =
        DialogueAnalyzer.analyze(sentences.toList(), null)

    @Test
    fun `a quote tagged with a name belongs to that character`() {
        val chapter = analyze("“Hello,” said Maria.")

        val quote = chapter.parts.first { it.speaker != null }
        assertEquals("Maria", quote.speaker)
        assertEquals("Hello,", quote.text)
    }

    @Test
    fun `the tag around a quote stays with the narrator`() {
        val chapter = analyze("“Hello,” said Maria.")

        val tag = chapter.parts.last()
        assertNull(tag.speaker)
        assertEquals("said Maria.", tag.text)
    }

    @Test
    fun `a tag before the quote names the speaker`() {
        val chapter = analyze("Maria said, “Hello.”")

        assertEquals("Maria", chapter.parts.first { it.speaker != null }.speaker)
    }

    @Test
    fun `a verb-first tag names the speaker`() {
        val chapter = analyze("“Hello,” said Mr. Darcy.")

        assertEquals("Darcy", chapter.parts.first { it.speaker != null }.speaker)
    }

    @Test
    fun `a pronoun tag uses the character the narration named`() {
        val chapter = analyze("Maria looked up.", "“Hello,” she said.")

        assertEquals("Maria", chapter.parts.first { it.speaker != null }.speaker)
    }

    @Test
    fun `untagged quotes alternate between two characters`() {
        val chapter = analyze(
            "“Where to?” asked Maria.",
            "“Home,” said John.",
            "“Now?”",
        )

        val last = chapter.parts.filter { it.speaker != null }.last()
        assertEquals("Maria", last.speaker)
        assertEquals("Now?", last.text)
    }

    @Test
    fun `narration without dialogue is all narrator`() {
        val chapter = analyze("The road was empty.", "Nobody spoke.")

        assertEquals(2, chapter.parts.size)
        chapter.parts.forEach { assertNull(it.speaker) }
        assertEquals(emptyList<DialogueSpeaker>(), chapter.speakers)
    }

    @Test
    fun `a quote running over a sentence break keeps its speaker`() {
        val chapter = analyze("“Wait.", "Don't go,” said Maria.")

        val quotes = chapter.parts.filter { it.speaker != null }
        assertEquals(listOf("Wait.", "Don't go,"), quotes.map { it.text })
        assertEquals(listOf("Maria", "Maria"), quotes.map { it.speaker })
    }

    @Test
    fun `parts keep the sentence they came from`() {
        val chapter = analyze("“Hi,” said Maria.", "John waved.")

        assertEquals(listOf(0, 0, 1), chapter.parts.map { it.sentence })
    }

    @Test
    fun `the first part of a paragraph is flagged`() {
        val sentences = listOf("“Hi,” said Maria.", "John waved.")
        val starts = booleanArrayOf(true, true)

        val chapter = DialogueAnalyzer.analyze(sentences, starts)

        assertEquals(listOf(true, false, true), chapter.parts.map { it.startsParagraph })
    }

    @Test
    fun `quoted parts are marked as dialogue`() {
        val chapter = analyze("“Hello,” said Maria.")

        assertEquals(listOf(true, false), chapter.parts.map { it.isDialogue })
    }

    @Test
    fun `an unattributed quote is still dialogue`() {
        val chapter = analyze("“Hello.”")

        val line = chapter.parts.single()
        assertTrue(line.isDialogue)
        assertNull(line.speaker)
    }

    @Test
    fun `dialogue lines are counted per character`() {
        val chapter = analyze(
            "“One,” said Maria.",
            "“Two,” said John.",
            "“Three,” said Maria.",
        )

        assertEquals(
            listOf(DialogueSpeaker("Maria", 2), DialogueSpeaker("John", 1)),
            chapter.speakers,
        )
    }
}
