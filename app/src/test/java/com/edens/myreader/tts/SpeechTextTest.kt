package com.edens.myreader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechTextTest {

    @Test
    fun `a semicolon is spoken as a comma`() {
        assertEquals("He paused, then left.", SpeechText.forSpeech("He paused; then left."))
    }

    @Test
    fun `space before punctuation is dropped`() {
        assertEquals("Wait.", SpeechText.forSpeech("Wait ."))
        assertEquals("Stop!", SpeechText.forSpeech("Stop !"))
        assertEquals("Really?", SpeechText.forSpeech("Really ?"))
    }

    @Test
    fun `dashes get spaces around them`() {
        assertEquals("Well — you know.", SpeechText.forSpeech("Well—you know."))
        assertEquals("Well — you know.", SpeechText.forSpeech("Well – you know."))
        assertEquals("Well — you know.", SpeechText.forSpeech("Well--you know."))
        assertEquals("Well — you know.", SpeechText.forSpeech("Well -- you know."))
    }

    @Test
    fun `hyphenated words are left alone`() {
        assertEquals("A well-known rule.", SpeechText.forSpeech("A well-known rule."))
    }

    @Test
    fun `runs of dots become a single ellipsis`() {
        assertEquals("I don't know…", SpeechText.forSpeech("I don't know..."))
        assertEquals("Wait… what?", SpeechText.forSpeech("Wait ... what?"))
    }

    @Test
    fun `repeated marks collapse to one`() {
        assertEquals("Stop!", SpeechText.forSpeech("Stop!!!"))
        assertEquals("Really?", SpeechText.forSpeech("Really?!?"))
    }

    @Test
    fun `abbreviation dots are dropped before a name`() {
        assertEquals("Mr Winston looked up.", SpeechText.forSpeech("Mr. Winston looked up."))
        assertEquals("Ask Dr Smith or Prof Jones.", SpeechText.forSpeech("Ask Dr. Smith or Prof. Jones."))
    }

    @Test
    fun `an abbreviation followed by a name loses only its own dot`() {
        assertEquals(
            "That was Mr Sophistication.",
            SpeechText.forSpeech("That was Mr. Sophistication."),
        )
    }

    @Test
    fun `an abbreviation ending the sentence keeps its full stop`() {
        assertEquals("He never spoke to Mr.", SpeechText.forSpeech("He never spoke to Mr."))
    }

    @Test
    fun `numbers and decimals are untouched`() {
        assertEquals("It cost 3.50, not 4.", SpeechText.forSpeech("It cost 3.50; not 4."))
    }

    @Test
    fun `dialogue keeps its punctuation intact`() {
        assertEquals("“Why?” she asked.", SpeechText.forSpeech("“Why?” she asked."))
    }

    @Test
    fun `latin abbreviations are read as words`() {
        assertEquals(
            "Use a spoon, for example, this one, or a fork, that is, whatever.",
            SpeechText.forSpeech("Use a spoon, e.g. this one, or a fork, i.e. whatever."),
        )
        assertEquals("Apples, oranges, et cetera, are fine.", SpeechText.forSpeech("Apples, oranges, etc., are fine."))
        assertEquals("It was him versus her.", SpeechText.forSpeech("It was him vs. her."))
    }

    @Test
    fun `a sentence-final latin abbreviation keeps its full stop`() {
        assertEquals("Bring pens, et cetera.", SpeechText.forSpeech("Bring pens, etc."))
    }

    @Test
    fun `an ampersand is read as and`() {
        assertEquals("Tom and Jerry.", SpeechText.forSpeech("Tom & Jerry."))
        assertEquals("AT&T is a company.", SpeechText.forSpeech("AT&T is a company."))
    }

    @Test
    fun `a numbered reference is read as number`() {
        assertEquals("See chapter number 5.", SpeechText.forSpeech("See chapter No. 5."))
    }

    @Test
    fun `footnote markers are not read out`() {
        assertEquals(
            "The remark was never explained.",
            SpeechText.forSpeech("The remark was never explained.[12]"),
        )
        assertEquals("He left then.", SpeechText.forSpeech("He left¹⁴ then."))
    }

    @Test
    fun `emphasis asterisks are dropped`() {
        assertEquals("He was very angry.", SpeechText.forSpeech("He was *very* angry."))
    }

    @Test
    fun `a spaced scene break is left alone`() {
        assertEquals("* * *", SpeechText.forSpeech("* * *"))
    }

    @Test
    fun `surrounding whitespace is trimmed and collapsed`() {
        assertEquals("A calm sentence.", SpeechText.forSpeech("  A   calm\tsentence.  "))
    }
}
