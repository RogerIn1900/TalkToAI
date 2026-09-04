package com.example.talktoai.dsh.diagnostics

import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.prompt.PromptTracker
import com.example.talktoai.dsh.contract.PromptRequest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticsExporterTest {

    private val exporter = DiagnosticsExporter(deviceId = "dev-1", appVersion = "1.0.0", expectedHostVersion = "0.1.2-alpha.2")
    private val tracker = PromptTracker(NoopLogger)
    private val sid = SessionId("s1")
    private val ctx = TraceContext.initial(1L).withSession(sid.value)

    @Test
    fun report_redacts_prompt_text_and_keeps_ids() {
        tracker.beginSend(PromptRequest(sid, "r1", "secret recipe"), ctx)
        val report = exporter.export(tracker, errors = emptyList(), clock = { 42L })
        assertTrue(report.header["deviceId"] == "dev-1")
        assertTrue(report.prompts.isNotEmpty())
        val p = report.prompts.first()
        assertTrue(p["requestId"] == "r1")
        assertTrue(p["sessionId"] == "s1")
        val text = p["text"]!!
        assertFalse("secret" in text)
        assertFalse("recipe" in text)
        assertTrue("bytes" in text)
    }

    @Test
    fun json_includes_header_prompts_and_timestamp() {
        tracker.beginSend(PromptRequest(sid, "r1", "secret recipe"), ctx)
        val report = exporter.export(tracker, errors = emptyList(), clock = { 1234L })
        val json = exporter.toJson(report)
        assertTrue("\"deviceId\": \"dev-1\"" in json)
        assertTrue("\"appVersion\": \"1.0.0\"" in json)
        assertTrue("\"expectedHostVersion\": \"0.1.2-alpha.2\"" in json)
        assertTrue("\"promptCount\"" in json)
        assertTrue("\"generatedAtMs\": \"1234\"" in json)
        assertTrue("\"requestId\": \"r1\"" in json)
    }

    @Test
    fun errors_are_included_via_short_codes() {
        tracker.beginSend(PromptRequest(sid, "r1", "x"), ctx)
        val errors = listOf(ctx to DshError.Transport.Disconnected())
        val report = exporter.export(tracker, errors = errors)
        assertTrue(report.header["errorCount"] == "1")
    }
}
