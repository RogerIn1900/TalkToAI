package com.example.talktoai.dsh.observability

import com.example.talktoai.dsh.contract.AttachmentRef
import com.example.talktoai.dsh.contract.ImageAttachmentPayload
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SanitizedLoggerTest {

    private val ctx = TraceContext.initial(1L)

    @Test
    fun scrub_text_redacts_long_base64_but_keeps_surrounding_text() {
        val b64 = "A".repeat(200) + "===="
        val s = "hello $b64 world"
        val out = LogScrubber.scrubText(s)
        assertFalse(b64 in out)
        assertTrue(out.contains("hello"))
        assertTrue(out.contains("world"))
        assertTrue(out.contains("<redacted-base64>"))
    }

    @Test
    fun scrub_text_redacts_token_like_fields() {
        val s = "token=abc.def.ghi password=hunter2 user=jerry"
        val out = LogScrubber.scrubText(s)
        assertTrue("abc.def.ghi" !in out)
        assertTrue("hunter2" !in out)
        assertTrue("jerry" in out) // user= 不在白名单中，保留
    }

    @Test
    fun scrub_text_truncates_long_strings() {
        // 用 "!" 字符（不在 base64 字符类中）做长度测试
        val s = "!".repeat(1000)
        val out = LogScrubber.scrubText(s, maxLen = 50)
        assertEquals(51, out.length) // 50 chars + ellipsis
        assertTrue(out.endsWith("…"))
    }

    @Test
    fun scrub_prompt_text_returns_size_only() {
        val s = "secret recipe with saffron"
        val out = LogScrubber.scrubPromptText(s)
        assertTrue("secret" !in out)
        assertTrue("bytes" in out)
    }

    @Test
    fun scrub_attachment_returns_metadata_only() {
        val p = ImageAttachmentPayload(
            ref = AttachmentRef("img1", "image/png", 1234L),
            base64Encoded = "BASE64DATA",
            widthPx = 100,
            heightPx = 200,
        )
        val out = LogScrubber.scrubAttachment(p)
        assertTrue("BASE64DATA" !in out)
        assertTrue("img1" in out)
        assertTrue("image/png" in out)
    }

    @Test
    fun sanitized_logger_passes_context_unchanged() {
        val captured = mutableListOf<Pair<String, TraceContext>>()
        val delegate = object : DshLogger {
            override fun debug(tag: String, ctx: TraceContext, message: String) {
                captured.add("debug" to ctx)
            }
            override fun info(tag: String, ctx: TraceContext, message: String) {
                captured.add("info" to ctx)
            }
            override fun warn(tag: String, ctx: TraceContext, message: String, error: Throwable?) {
                captured.add("warn" to ctx)
            }
            override fun error(tag: String, ctx: TraceContext, message: String, error: Throwable?) {
                captured.add("error" to ctx)
            }
        }
        val l = SanitizedLogger(delegate)
        val c = ctx.withSession("s1").withRequest("r1").withSeq(7L)
        l.info("T", c, "msg")
        l.error("T", c, "msg")
        assertEquals(2, captured.size)
        assertEquals("s1", captured[0].second.sessionId)
        assertEquals("r1", captured[0].second.requestId)
        assertEquals(7L, captured[0].second.seq)
    }
}
