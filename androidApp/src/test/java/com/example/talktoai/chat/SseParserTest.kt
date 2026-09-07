package com.example.talktoai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SseParserTest {
    @Test
    fun `parses named event with multiple data lines`() {
        val parser = SseParser()
        assertNull(parser.accept("event: delta"))
        assertNull(parser.accept("data: {\"text\":\"first\"}"))
        assertNull(parser.accept("data: second"))

        val event = parser.accept("")

        assertEquals("delta", event?.type)
        assertEquals("{\"text\":\"first\"}\nsecond", event?.data)
    }

    @Test
    fun `ignores comments and flushes trailing event`() {
        val parser = SseParser()
        assertNull(parser.accept(": keep-alive"))
        assertNull(parser.accept("data: done"))

        assertEquals(StreamEvent("message", "done"), parser.finish())
    }
}
