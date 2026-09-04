package com.example.talktoai.dsh.prompt

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.PromptRequest
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.CommandAck
import com.example.talktoai.dsh.transport.CommandRequest
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.transport.HandshakeInfo
import com.example.talktoai.dsh.transport.PromptLookupResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PromptTrackerTest {

    private val sid = SessionId("s1")
    private val ctx = TraceContext.initial(1L).withSession(sid.value)
    private val tracker = PromptTracker(NoopLogger)

    private fun req(id: String, text: String = "hi") = PromptRequest(sid, id, text)

    @Test
    fun begin_send_records_optimistic_sending_state() {
        val r = tracker.beginSend(req("a1"), ctx)
        val e = (r as DshResult.Ok).value
        assertIs<OptimisticState.Sending>(e.state)
        assertEquals("a1", e.requestId.value)
        assertEquals(sid, e.sessionId)
        assertTrue(!e.retryEligible)
    }

    @Test
    fun begin_send_same_request_id_same_payload_is_idempotent() {
        tracker.beginSend(req("a1", text = "hello"), ctx)
        val r = tracker.beginSend(req("a1", text = "hello"), ctx)
        val e = (r as DshResult.Ok).value
        assertIs<OptimisticState.Sending>(e.state)
        // 二次调用不创建新 Entry，state 保持一致
        assertEquals(1, tracker.snapshot().size)
    }

    @Test
    fun begin_send_same_request_id_different_payload_returns_idempotency_conflict() {
        tracker.beginSend(req("a1", text = "hello"), ctx)
        val r = tracker.beginSend(req("a1", text = "world"), ctx)
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Business.IdempotencyConflict>(r.error)
        // 原有 entry 不被覆盖
        val e = tracker.get(req("a1"))!!
        assertEquals("hello", e.text)
    }

    @Test
    fun begin_send_same_request_id_different_attachment_count_is_conflict() {
        // 设计 §5：A + 不同内容重试 → idempotency-conflict
        val plainReq = req("a1")
        val withAtt = PromptRequest(sid, "a1", "hi", attachments = listOf(
            com.example.talktoai.dsh.contract.ImageAttachmentPayload(
                ref = com.example.talktoai.dsh.contract.AttachmentRef(
                    id = "att-1", mime = "image/png", sizeBytes = 1024L
                ),
                base64Encoded = "AAA",
                widthPx = 10,
                heightPx = 10,
            )
        ))
        tracker.beginSend(plainReq, ctx)
        val r = tracker.beginSend(withAtt, ctx)
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Business.IdempotencyConflict>(r.error)
    }

    @Test
    fun submitter_idempotency_conflict_does_not_invoke_remote() {
        kotlinx.coroutines.runBlocking {
            val remote = TestRemote()
            val submitter = PromptSubmitter(remote, tracker)
            submitter.submit(PromptRequest(sid, "a1", "hello"), ctx)
            assertEquals(1, remote.commandsReceived)

            // 第二次用相同 requestId 不同 text → 应在客户端立即拒绝
            val r2 = submitter.submit(PromptRequest(sid, "a1", "different"), ctx)
            assertIs<DshResult.Err>(r2)
            assertIs<DshError.Business.IdempotencyConflict>(r2.error)
            assertEquals(1, remote.commandsReceived) // 未发起第二次 command
        }
    }

    @Test
    fun submitter_idempotency_hit_with_same_payload_returns_existing_state() {
        kotlinx.coroutines.runBlocking {
            val remote = TestRemote()
            val submitter = PromptSubmitter(remote, tracker)
            val r1 = submitter.submit(PromptRequest(sid, "a1", "hello"), ctx)
            assertIs<DshResult.Ok<*>>(r1)
            // 第二次用相同 requestId 相同 payload → 应跳过 remote.command
            val r2 = submitter.submit(PromptRequest(sid, "a1", "hello"), ctx)
            assertIs<DshResult.Ok<*>>(r2)
            assertEquals(1, remote.commandsReceived)
        }
    }

    @Test
    fun mark_acked_updates_state() {
        tracker.beginSend(req("a1"), ctx)
        tracker.markAcked(req("a1"), CommandAck("a1", true), ctx)
        val e = tracker.get(req("a1"))!!
        assertIs<OptimisticState.Acked>(e.state)
        assertTrue(e.state.accepted)
    }

    @Test
    fun mark_acked_rejected_records_failed() {
        tracker.beginSend(req("a1"), ctx)
        tracker.markAcked(req("a1"), CommandAck("a1", false, "validation"), ctx)
        val e = tracker.get(req("a1"))!!
        assertIs<OptimisticState.Failed>(e.state)
        val err = e.state.error
        assertIs<DshError.Business.Rejected>(err)
    }

    @Test
    fun ack_timeout_promotes_to_pending_confirmation_no_retry() {
        tracker.beginSend(req("a1"), ctx)
        tracker.markAckTimeout(req("a1"), ctx)
        val e = tracker.get(req("a1"))!!
        assertIs<OptimisticState.PendingConfirmation>(e.state)
        assertTrue(!e.retryEligible)
    }

    @Test
    fun lookup_committed_marks_confirmed() {
        tracker.beginSend(req("a1"), ctx)
        tracker.applyLookup(req("a1"), PromptLookupResult.Committed("msg1", 1234L), ctx)
        val e = tracker.get(req("a1"))!!
        assertIs<OptimisticState.Confirmed>(e.state)
        assertEquals("msg1", e.state.messageId)
    }

    @Test
    fun lookup_conflict_marks_failed() {
        tracker.beginSend(req("a1"), ctx)
        tracker.applyLookup(req("a1"), PromptLookupResult.Conflict("dup"), ctx)
        val e = tracker.get(req("a1"))!!
        assertIs<OptimisticState.Failed>(e.state)
        assertIs<DshError.Business.IdempotencyConflict>(e.state.error)
    }

    @Test
    fun lookup_not_found_remains_pending() {
        tracker.beginSend(req("a1"), ctx)
        tracker.applyLookup(req("a1"), PromptLookupResult.NotFound, ctx)
        val e = tracker.get(req("a1"))!!
        assertIs<OptimisticState.PendingConfirmation>(e.state)
    }

    @Test
    fun lookup_unavailable_keeps_state_for_queue_follow_reconcile() {
        tracker.beginSend(req("a1"), ctx)
        tracker.applyLookup(req("a1"), PromptLookupResult.Unavailable, ctx)
        val e = tracker.get(req("a1"))!!
        // 设计 §5 V1 降级：保留乐观消息，依靠 Queue/Follow 对账 → 保持 Sending 不变
        assertIs<OptimisticState.Sending>(e.state)
    }

    @Test
    fun user_message_event_with_rpc_id_confirms_prompt() {
        tracker.beginSend(req("a1"), ctx)
        tracker.markConfirmedByEvent(sid, "msg_abc", rpcId = "a1", ctx)
        val e = tracker.get(req("a1"))!!
        assertIs<OptimisticState.Confirmed>(e.state)
        assertEquals("msg_abc", e.state.messageId)
    }

    @Test
    fun request_ids_are_unique_per_call() {
        val gen = PromptIdGenerator(deviceId = "dev1")
        val a = gen.next(sid)
        val b = gen.next(sid)
        assertTrue(a.value != b.value)
        assertTrue(a.value.startsWith("p_dev1_"))
    }

    @Test
    fun submitter_acks_returns_request_id_and_marks_tracker() {
        kotlinx.coroutines.runBlocking {
            val remote = TestRemote()
            val submitter = PromptSubmitter(remote, tracker)
            val req = PromptRequest(sid, "a1", "hello")
            val r = submitter.submit(req, ctx)
            assertIs<DshResult.Ok<*>>(r)
            val e = tracker.get(req)!!
            assertIs<OptimisticState.Acked>(e.state)
            assertTrue(remote.commandsReceived == 1)
        }
    }

    @Test
    fun submitter_remote_error_marks_rejected() {
        kotlinx.coroutines.runBlocking {
            val remote = TestRemote(failNext = true)
            val submitter = PromptSubmitter(remote, tracker)
            val req = PromptRequest(sid, "a1", "hello")
            val r = submitter.submit(req, ctx)
            assertIs<DshResult.Err>(r)
            val e = tracker.get(req)!!
            assertIs<OptimisticState.Failed>(e.state)
        }
    }
}

private class TestRemote(
    var failNext: Boolean = false,
) : DshRemote {
    var commandsReceived: Int = 0

    override suspend fun handshake() = DshResult.success(
        HandshakeInfo("0.1.2-alpha.2", "0.1.2-alpha.2", 1L, emptySet())
    )

    override suspend fun sessionFollow(sessionId: SessionId, ctx: TraceContext) =
        throw UnsupportedOperationException()

    override suspend fun sessionPage(sessionId: SessionId, beforeSeq: Long?, limit: Int, ctx: TraceContext) =
        DshResult.success(com.example.talktoai.dsh.transport.PageChunk(emptyList(), false, null))

    override suspend fun sessionControl(sessionId: SessionId, generation: Long?, ctx: TraceContext) =
        DshResult.success(com.example.talktoai.dsh.transport.ControlFrame.Queue(1L, emptyList()))

    override suspend fun command(req: CommandRequest, ctx: TraceContext): DshResult<CommandAck> {
        commandsReceived++
        return if (failNext) DshResult.failure(DshError.Transport.Disconnected())
        else DshResult.success(CommandAck(req.requestId, true))
    }

    override suspend fun promptLookup(sessionId: SessionId, requestId: String, ctx: TraceContext) =
        DshResult.success(PromptLookupResult.NotFound)

    override suspend fun workspaceList(ctx: TraceContext) = DshResult.success(emptyList<com.example.talktoai.dsh.transport.WorkspaceEntry>())
    override suspend fun sessionList(workspaceId: String?, ctx: TraceContext) = DshResult.success(emptyList<com.example.talktoai.dsh.transport.SessionEntry>())
    override suspend fun pluginInventory(ctx: TraceContext) = DshResult.success(emptyList<com.example.talktoai.dsh.transport.PluginEntry>())
    override suspend fun close() {}
}
