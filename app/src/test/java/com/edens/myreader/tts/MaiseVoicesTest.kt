package com.edens.myreader.tts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MaiseVoicesTest {

    @Test
    fun `language and name are split apart`() {
        assertEquals(MaiseVoices.Parsed("en-US", "Heart"), MaiseVoices.parse("en-US-heart-kokoro"))
        assertEquals(MaiseVoices.Parsed("en-GB", "Alice"), MaiseVoices.parse("en-GB-alice-kokoro"))
        assertEquals(
            MaiseVoices.Parsed("zh-CN", "Xiaobei"),
            MaiseVoices.parse("zh-CN-xiaobei-kokoro"),
        )
    }

    @Test
    fun `a name with several parts is title cased`() {
        assertEquals(
            MaiseVoices.Parsed("el-GR", "Alpha F"),
            MaiseVoices.parse("el-GR-alpha-f-kokoro"),
        )
    }

    @Test
    fun `other engines' voice ids do not match`() {
        assertNull(MaiseVoices.parse("en-us-x-sfg#female_1-local"))
        assertNull(MaiseVoices.parse("en-supertonic-F3"))
        assertNull(MaiseVoices.parse("en-US-heart"))
    }
}
