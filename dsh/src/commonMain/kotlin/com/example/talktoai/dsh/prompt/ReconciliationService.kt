package com.example.talktoai.dsh.prompt

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.PromptQueryResult
import com.example.talktoai.dsh.contract.PromptRequest
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.transport.PromptLookupResult

/**
 * Prompt 幂等闭环 / 对账服务（设计 §5 + §5.0 V1 降级）。
 *
 * 行为：
 *   - ACK 超时：保留乐观消息 → PendingConfirmation（不自动重试）
 *   - Lookup 已提交 → Confirmed(messageId)
 *   - Lookup NotFound → 保持 PendingConfirmation
 *   - Lookup Conflict → Failed(IdempotencyConflict)
 *   - Lookup Unavailable → 仅写日志，依靠 Queue / Follow 对账
 *
 * 关键约束：禁止对结果不确定的 Prompt 自动重试（避免主动制造重复）。
 */
class ReconciliationService(
    private val remote: DshRemote,
    private val tracker: PromptTracker,
    private val logger: DshLogger = NoopLogger,
) {
    suspend fun reconcile(req: PromptRequest, ctx: TraceContext): DshResult<PromptQueryResult> {
        val r = remote.promptLookup(req.sessionId, req.requestId, ctx)
        return when (r) {
            is DshResult.Err -> {
                logger.warn(TAG, ctx.withRequest(req.requestId),
                    "Lookup failed: ${r.error.message}")
                r
            }
            is DshResult.Ok -> {
                tracker.applyLookup(req, r.value, ctx)
                when (r.value) {
                    is PromptLookupResult.Committed -> {
                        val c = r.value
                        DshResult.success(PromptQueryResult.Committed(c.messageId, c.committedAtMs))
                    }
                    PromptLookupResult.NotFound -> {
                        logger.info(TAG, ctx.withRequest(req.requestId),
                            "Lookup NotFound → keep PendingConfirmation; no auto-retry")
                        DshResult.success(PromptQueryResult.NotFound)
                    }
                    is PromptLookupResult.Conflict -> {
                        val c = r.value
                        logger.warn(TAG, ctx.withRequest(req.requestId),
                            "Idempotency conflict: ${c.reason}")
                        DshResult.success(PromptQueryResult.Conflict(c.reason))
                    }
                    PromptLookupResult.Unavailable -> {
                        // V1 降级：标记不可用，依赖 Queue/Follow 对账
                        DshResult.success(PromptQueryResult.NotFound)
                    }
                }
            }
        }
    }

    suspend fun reconcileAll(ctx: TraceContext): List<Pair<SessionId, PromptRequestId>> {
        val pending = tracker.snapshot()
            .filter { it.state is OptimisticState.PendingConfirmation || it.state is OptimisticState.Sending }
        val reconciled = mutableListOf<Pair<SessionId, PromptRequestId>>()
        for (e in pending) {
            val req = PromptRequest(
                sessionId = e.sessionId,
                requestId = e.requestId.value,
                text = e.text,
                attachments = emptyList(),
            )
            val r = reconcile(req, ctx)
            if (r is DshResult.Ok) {
                reconciled.add(e.sessionId to e.requestId)
            }
        }
        return reconciled
    }

    companion object {
        private const val TAG = "ReconciliationSvc"
    }
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
