package com.example.talktoai.dsh.prompt

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.PromptAck
import com.example.talktoai.dsh.contract.PromptQueryResult
import com.example.talktoai.dsh.contract.PromptRequest
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.CommandAck
import com.example.talktoai.dsh.transport.CommandKind
import com.example.talktoai.dsh.transport.CommandRequest
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.transport.PromptLookupResult
import kotlin.random.Random

data class PromptRequestId(val value: String) {
    init { require(value.isNotEmpty()) }
    override fun toString(): String = value
}

class PromptIdGenerator(
    private val deviceId: String,
    private val random: Random = Random.Default,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    fun next(sessionId: SessionId): PromptRequestId {
        val ts = clock()
        val rand = random.nextLong().toString(Character.MAX_RADIX)
        return PromptRequestId("p_${deviceId}_${sessionId.value}_${ts}_$rand")
    }
}

sealed class OptimisticState {
    data class Sending(val createdAtMs: Long) : OptimisticState()
    data class Acked(val accepted: Boolean, val ackedAtMs: Long, val reason: String? = null) : OptimisticState()
    data class PendingConfirmation(val sinceMs: Long) : OptimisticState()
    data class Confirmed(val messageId: String, val confirmedAtMs: Long) : OptimisticState()
    data class Failed(val error: DshError, val failedAtMs: Long) : OptimisticState()
}

class PromptTracker(
    private val logger: DshLogger = NoopLogger,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    data class Entry(
        val requestId: PromptRequestId,
        val sessionId: SessionId,
        val payloadDigest: String,
        val text: String,
        val attachmentCount: Int,
        val state: OptimisticState,
        val retryEligible: Boolean,
    )

    private val entries: MutableMap<Pair<SessionId, PromptRequestId>, Entry> = LinkedHashMap()
    private val messageIndex: MutableMap<String, PromptRequestId> = HashMap()

    /**
     * 记录一次 Prompt 的乐观发送。
     *
     * 设计 §5 幂等矩阵：
     *   - 首次提交 requestId=A：返回新 [Entry] (Sending)
     *   - A + 相同内容重试：返回现有 [Entry] (沿用原状态，不重复入队)
     *   - A + 不同内容重试：返回 [DshResult.Err] ([DshError.Business.IdempotencyConflict])
     *
     * 注意：当前实现遵循设计 §5 V1 降级策略——同一 requestId 的"重试"只在调用方显式调用
     * 时才会进入本路径；ACK 超时后依赖 Lookup / Queue / Follow 对账，不会触发本冲突。
     */
    fun beginSend(req: PromptRequest, ctx: TraceContext): DshResult<Entry> {
        val rid = PromptRequestId(req.requestId)
        val key = req.sessionId to rid
        val incomingDigest = digest(req)
        val existing = entries[key]
        if (existing != null) {
            if (existing.payloadDigest != incomingDigest) {
                logger.warn(
                    TAG,
                    ctx.withRequest(req.requestId),
                    "Idempotency conflict: same requestId with different payload"
                )
                return DshResult.failure(
                    DshError.Business.IdempotencyConflict(req.requestId)
                )
            }
            // 相同 payload：返回现有 Entry（幂等命中），调用方应继续等待原状态收敛
            logger.debug(
                TAG,
                ctx.withRequest(req.requestId),
                "Idempotency hit: returning existing entry state=${existing.state::class.simpleName}"
            )
            return DshResult.success(existing)
        }
        val entry = Entry(
            requestId = rid,
            sessionId = req.sessionId,
            payloadDigest = incomingDigest,
            text = req.text,
            attachmentCount = req.attachments.size,
            state = OptimisticState.Sending(clock()),
            retryEligible = false,
        )
        entries[key] = entry
        logger.info(TAG, ctx.withRequest(req.requestId), "Prompt begin")
        return DshResult.success(entry)
    }

    fun markAcked(req: PromptRequest, ack: CommandAck, ctx: TraceContext) {
        val key = req.sessionId to PromptRequestId(req.requestId)
        val cur = entries[key] ?: return
        if (!ack.accepted) {
            entries[key] = cur.copy(
                state = OptimisticState.Failed(DshError.Business.Rejected(ack.reason ?: ""), clock())
            )
            logger.warn(TAG, ctx.withRequest(req.requestId), "Prompt rejected: ${ack.reason}")
            return
        }
        entries[key] = cur.copy(state = OptimisticState.Acked(true, clock()))
        logger.info(TAG, ctx.withRequest(req.requestId), "Prompt ack accepted; awaiting UserMessage event")
    }

    fun markAckTimeout(req: PromptRequest, ctx: TraceContext) {
        val key = req.sessionId to PromptRequestId(req.requestId)
        val cur = entries[key] ?: return
        entries[key] = cur.copy(
            state = OptimisticState.PendingConfirmation(clock()),
            retryEligible = false,
        )
        logger.warn(TAG, ctx.withRequest(req.requestId), "ACK timeout → PendingConfirmation")
    }

    fun markConfirmedByEvent(sessionId: SessionId, messageId: String, rpcId: String?, ctx: TraceContext) {
        val rid = rpcId?.let { PromptRequestId(it) }
            ?: messageIndex[messageId]
            ?: return
        val key = sessionId to rid
        val cur = entries[key] ?: return
        entries[key] = cur.copy(state = OptimisticState.Confirmed(messageId, clock()))
        messageIndex[messageId] = rid
        logger.info(TAG, ctx.withRequest(rid.value), "Prompt confirmed by event messageId=$messageId")
    }

    fun markIdempotencyConflict(req: PromptRequest, ctx: TraceContext) {
        val key = req.sessionId to PromptRequestId(req.requestId)
        val cur = entries[key] ?: return
        entries[key] = cur.copy(
            state = OptimisticState.Failed(DshError.Business.IdempotencyConflict(req.requestId), clock()),
            retryEligible = false,
        )
        logger.warn(TAG, ctx.withRequest(req.requestId), "Idempotency conflict")
    }

    fun applyLookup(
        req: PromptRequest,
        result: PromptLookupResult,
        ctx: TraceContext,
    ) {
        val key = req.sessionId to PromptRequestId(req.requestId)
        val cur = entries[key] ?: return
        when (result) {
            is PromptLookupResult.Committed -> {
                entries[key] = cur.copy(state = OptimisticState.Confirmed(result.messageId, clock()))
                messageIndex[result.messageId] = cur.requestId
                logger.info(TAG, ctx.withRequest(req.requestId), "Lookup committed messageId=${result.messageId}")
            }
            PromptLookupResult.NotFound -> {
                entries[key] = cur.copy(state = OptimisticState.PendingConfirmation(clock()))
            }
            is PromptLookupResult.Conflict -> markIdempotencyConflict(req, ctx)
            PromptLookupResult.Unavailable -> {
                logger.info(TAG, ctx.withRequest(req.requestId), "Lookup unavailable; relying on Queue/Follow")
            }
        }
    }

    fun get(req: PromptRequest): Entry? = entries[req.sessionId to PromptRequestId(req.requestId)]
    fun snapshot(): List<Entry> = entries.values.toList()

    private fun digest(req: PromptRequest): String {
        val parts = buildList {
            add(req.text)
            req.attachments.forEach { add("img:${it.ref.id}:${it.ref.sizeBytes}") }
        }
        return parts.joinToString("|")
    }

    companion object {
        private const val TAG = "PromptTracker"
    }
}

class PromptSubmitter(
    private val remote: DshRemote,
    private val tracker: PromptTracker,
    private val ackTimeoutMs: Long = 8_000L,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    /**
     * 提交 Prompt。
     *
     * 流程（设计 §5 + §5.0 V1 降级）：
     *   1. beginSend：检测 (sessionId, requestId) 幂等键；同 key 不同 payload → 立即返回
     *      [DshError.Business.IdempotencyConflict]，**禁止重试**
     *   2. 远程 command 调用
     *   3. ACK 接受 → [OptimisticState.Acked]
     *   4. ACK 接受但 wallclock 超时（不应发生但兜底）→ [OptimisticState.PendingConfirmation]
     *   5. ACK 拒绝或远程错误 → [OptimisticState.Failed]
     */
    suspend fun submit(
        req: PromptRequest,
        ctx: TraceContext,
    ): DshResult<PromptAck> {
        val begin = tracker.beginSend(req, ctx)
        when (begin) {
            is DshResult.Err -> return DshResult.failure(begin.error)
            is DshResult.Ok -> {
                val entry = begin.value
                if (!isFresh(entry)) {
                    // 幂等命中：直接沿用现有状态返回；不重复发送
                    return DshResult.success(
                        PromptAck(
                            requestId = req.requestId,
                            accepted = entry.state !is OptimisticState.Failed,
                            serverReceivedAtMs = clock(),
                        )
                    )
                }
            }
        }
        val cmd = CommandRequest(
            sessionId = req.sessionId,
            kind = if (req.steer) CommandKind.STEER else CommandKind.PROMPT,
            requestId = req.requestId,
            payload = buildPayload(req),
        )
        val started = clock()
        val result = remote.command(cmd, ctx)
        return when (result) {
            is DshResult.Err -> {
                tracker.markAcked(
                    req,
                    CommandAck(req.requestId, false, result.error.message),
                    ctx,
                )
                result
            }
            is DshResult.Ok -> {
                tracker.markAcked(req, result.value, ctx)
                if (result.value.accepted && clock() - started >= ackTimeoutMs) {
                    // ACK 接受但已超过 ackTimeoutMs：把后续对账交给 Queue/Follow
                    tracker.markAckTimeout(req, ctx)
                }
                DshResult.success(
                    PromptAck(
                        requestId = req.requestId,
                        accepted = result.value.accepted,
                        serverReceivedAtMs = clock(),
                    )
                )
            }
        }
    }

    /**
     * 判断 entry 是否仍是"新鲜"待发送状态。
     * Confirmed / PendingConfirmation / Acked 都视为幂等命中（不重发）。
     */
    private fun isFresh(entry: PromptTracker.Entry): Boolean =
        entry.state is OptimisticState.Sending || entry.state is OptimisticState.Failed

    private fun buildPayload(req: PromptRequest): Map<String, String> = buildMap {
        put("text", req.text)
        req.attachments.forEachIndexed { idx, a ->
            put("attachment.$idx.id", a.ref.id)
            put("attachment.$idx.b64", a.base64Encoded)
            put("attachment.$idx.size", a.ref.sizeBytes.toString())
        }
    }

    suspend fun reconcile(req: PromptRequest, ctx: TraceContext): DshResult<PromptQueryResult> {
        val r = remote.promptLookup(req.sessionId, req.requestId, ctx)
        return when (r) {
            is DshResult.Err -> r
            is DshResult.Ok -> {
                tracker.applyLookup(req, r.value, ctx)
                DshResult.success(
                    when (r.value) {
                        is PromptLookupResult.Committed -> PromptQueryResult.Committed(r.value.messageId, r.value.committedAtMs)
                        PromptLookupResult.NotFound -> PromptQueryResult.NotFound
                        is PromptLookupResult.Conflict -> PromptQueryResult.Conflict(r.value.reason)
                        PromptLookupResult.Unavailable -> PromptQueryResult.NotFound
                    }
                )
            }
        }
    }
}
