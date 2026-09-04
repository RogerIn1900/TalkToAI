package com.example.talktoai.dsh.diagnostics

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.observability.LogScrubber
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.prompt.OptimisticState
import com.example.talktoai.dsh.prompt.PromptTracker

/**
 * 诊断导出器（设计 §8 / §9）。
 *
 * 用途：构造问题反馈包；用于支持/调试场景。
 *
 * 默认脱敏（设计 §9）：
 *   - trace/session/requestId/seq 保留
 *   - 正文 / base64 / token 替换
 *   - error message 仅保留错误码短名，不含堆栈
 *
 * 输出格式为可读 JSON，字段顺序稳定，便于 diff/审阅。
 */
class DiagnosticsExporter(
    private val deviceId: String,
    private val appVersion: String,
    private val expectedHostVersion: String,
) {
    data class Report(
        val header: Map<String, String>,
        val prompts: List<Map<String, String>>,
        val generatedAtMs: Long,
    )

    fun export(
        tracker: PromptTracker,
        errors: List<Pair<TraceContext, DshError>>,
        clock: () -> Long = { System.currentTimeMillis() },
    ): Report {
        val prompts = tracker.snapshot().map { e ->
            buildMap {
                put("requestId", LogScrubber.scrubText(e.requestId.value, 64))
                put("sessionId", LogScrubber.scrubText(e.sessionId.value, 64))
                put("state", stateName(e.state))
                put("text", LogScrubber.scrubPromptText(e.text))
                put("attachments", e.attachmentCount.toString())
                if (e.state is OptimisticState.Failed) {
                    put("error", e.state.error.toShort())
                }
                if (e.state is OptimisticState.Confirmed) {
                    put("messageId", LogScrubber.scrubText(e.state.messageId, 64))
                }
            }
        }

        val header = linkedMapOf(
            "deviceId" to LogScrubber.scrubText(deviceId, 64),
            "appVersion" to LogScrubber.scrubText(appVersion, 32),
            "expectedHostVersion" to LogScrubber.scrubText(expectedHostVersion, 32),
            "promptCount" to prompts.size.toString(),
            "errorCount" to errors.size.toString(),
        )

        return Report(header = header, prompts = prompts, generatedAtMs = clock())
    }

    fun toJson(report: Report): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"header\": {")
        report.header.entries.forEachIndexed { idx, (k, v) ->
            if (idx > 0) sb.append(",")
            sb.append("\n    \"").append(LogScrubber.scrubText(k, 32)).append("\": \"")
                .append(LogScrubber.scrubText(v, 200)).append("\"")
        }
        sb.append("\n  },\n  \"prompts\": [\n")
        report.prompts.forEachIndexed { idx, p ->
            if (idx > 0) sb.append(",\n")
            sb.append("    {")
            p.entries.forEachIndexed { i, (k, v) ->
                if (i > 0) sb.append(", ")
                sb.append("\"").append(LogScrubber.scrubText(k, 32)).append("\": \"")
                    .append(LogScrubber.scrubText(v, 200)).append("\"")
            }
            sb.append("}")
        }
        sb.append("\n  ],\n  \"generatedAtMs\": \"").append(report.generatedAtMs).append("\"\n}\n")
        return sb.toString()
    }

    private fun stateName(s: OptimisticState): String = when (s) {
        is OptimisticState.Sending -> "sending"
        is OptimisticState.Acked -> "acked"
        is OptimisticState.PendingConfirmation -> "pending-confirmation"
        is OptimisticState.Confirmed -> "confirmed"
        is OptimisticState.Failed -> "failed"
    }

    private fun DshError.toShort(): String = when (this) {
        is DshError.Business.IdempotencyConflict -> "idempotency-conflict"
        is DshError.Business.RateLimited -> "rate-limited"
        is DshError.Business.AttachmentTooLarge -> "attachment-too-large"
        is DshError.Business.Rejected -> "rejected"
        is DshError.Transport.Network -> "network"
        is DshError.Transport.Timeout -> "timeout"
        is DshError.Transport.Tls -> "tls"
        is DshError.Transport.AuthRequired -> "auth-required"
        is DshError.Transport.Unpaired -> "unpaired"
        is DshError.Transport.Disconnected -> "disconnected"
        is DshError.Protocol.Gap -> "gap"
        is DshError.Protocol.DroppedDuplicate -> "dup"
        is DshError.Protocol.NotAnOpeningSnapshot -> "bad-opening"
        is DshError.Protocol.NonMonotonic -> "non-monotonic"
        is DshError.Protocol.UnknownStream -> "unknown-stream"
        is DshError.Protocol.UnknownContentBlock -> "unknown-block"
        is DshError.Protocol.UnknownInteraction -> "unknown-interaction"
        is DshError.IncompatibleHost -> "incompatible-host"
        is DshError.Client.ImageBudgetExceeded -> "image-budget"
        is DshError.Client.ConcurrentImageUpload -> "concurrent-image"
        is DshError.Client.InvalidArgument -> "invalid-arg"
    }
}
