package com.example.talktoai.dsh.prompt

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.PromptQueryResult
import com.example.talktoai.dsh.contract.PromptRequest
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.CommandAck
import com.example.talktoai.dsh.transport.CommandRequest
import com.example.talktoai.dsh.transport.ControlFrame
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.transport.HandshakeInfo
import com.example.talktoai.dsh.transport.PageChunk
import com.example.talktoai.dsh.transport.PluginEntry
import com.example.talktoai.dsh.transport.PromptLookupResult
import com.example.talktoai.dsh.transport.SessionEntry
import com.example.talktoai.dsh.transport.WorkspaceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

class ReconciliationServiceTest {

    private val sid = SessionId("s1")
    private val ctx = TraceContext.initial(1L).withSession(sid.value)
    private val tracker = PromptTracker(NoopLogger)
    private val req = PromptRequest(sid, "a1", "hello")

    private class StubRemote(
        var lookup: PromptLookupResult = PromptLookupResult.NotFound,
    ) : DshRemote {
        override suspend fun handshake() = DshResult.success(HandshakeInfo("v", "v", 1L, emptySet()))
        override suspend fun sessionFollow(sessionId: SessionId, ctx: TraceContext) =
            throw UnsupportedOperationException()
        override suspend fun sessionPage(sessionId: SessionId, beforeSeq: Long?, limit: Int, ctx: TraceContext) =
            DshResult.success(PageChunk(emptyList(), false, null))
        override suspend fun sessionControl(sessionId: SessionId, generation: Long?, ctx: TraceContext) =
            DshResult.success(ControlFrame.Queue(1L, emptyList()))
        override suspend fun command(req: CommandRequest, ctx: TraceContext) = DshResult.success(CommandAck(req.requestId, true))
        override suspend fun promptLookup(sessionId: SessionId, requestId: String, ctx: TraceContext) =
            DshResult.success(lookup)
        override suspend fun workspaceList(ctx: TraceContext) = DshResult.success(emptyList<WorkspaceEntry>())
        override suspend fun sessionList(workspaceId: String?, ctx: TraceContext) = DshResult.success(emptyList<SessionEntry>())
        override suspend fun pluginInventory(ctx: TraceContext) = DshResult.success(emptyList<PluginEntry>())
        override suspend fun close() {}
    }

    @Test
    fun reconcile_committed_marks_confirmed() = runBlocking<Unit> {
        tracker.beginSend(req, ctx)
        val svc = ReconciliationService(StubRemote(PromptLookupResult.Committed("m1", 9L)), tracker)
        val r = svc.reconcile(req, ctx)
        assertIs<DshResult.Ok<PromptQueryResult>>(r)
        assertIs<PromptQueryResult.Committed>(r.value)
        val e = tracker.get(req)!!
        assertIs<OptimisticState.Confirmed>(e.state)
        assertEquals("m1", e.state.messageId)
    }

    @Test
    fun reconcile_not_found_keeps_pending() = runBlocking<Unit> {
        tracker.beginSend(req, ctx)
        // 模拟 ACK 超时场景
        tracker.markAckTimeout(req, ctx)
        val svc = ReconciliationService(StubRemote(PromptLookupResult.NotFound), tracker)
        val r = svc.reconcile(req, ctx)
        assertIs<DshResult.Ok<PromptQueryResult>>(r)
        assertIs<PromptQueryResult.NotFound>(r.value)
        val e = tracker.get(req)!!
        assertIs<OptimisticState.PendingConfirmation>(e.state)
        assertEquals(false, e.retryEligible) // 设计明确：ACK 超时不自动重试
    }

    @Test
    fun reconcile_conflict_marks_failed() = runBlocking<Unit> {
        tracker.beginSend(req, ctx)
        val svc = ReconciliationService(
            StubRemote(PromptLookupResult.Conflict("payload-mismatch")),
            tracker,
        )
        val r = svc.reconcile(req, ctx)
        assertIs<DshResult.Ok<PromptQueryResult>>(r)
        assertIs<PromptQueryResult.Conflict>(r.value)
        val e = tracker.get(req)!!
        assertIs<OptimisticState.Failed>(e.state)
        assertIs<DshError.Business.IdempotencyConflict>(e.state.error)
    }

    @Test
    fun reconcile_unavailable_returns_not_found_for_queue_follow_reconcile() = runBlocking<Unit> {
        tracker.beginSend(req, ctx)
        tracker.markAckTimeout(req, ctx)
        val svc = ReconciliationService(StubRemote(PromptLookupResult.Unavailable), tracker)
        val r = svc.reconcile(req, ctx)
        assertIs<DshResult.Ok<PromptQueryResult>>(r)
        assertIs<PromptQueryResult.NotFound>(r.value)
        val e = tracker.get(req)!!
        assertIs<OptimisticState.PendingConfirmation>(e.state)
    }

    @Test
    fun reconcile_all_processes_all_pending_entries() = runBlocking<Unit> {
        tracker.beginSend(PromptRequest(sid, "x1", "a"), ctx)
        tracker.markAckTimeout(PromptRequest(sid, "x1", "a"), ctx)
        tracker.beginSend(PromptRequest(sid, "x2", "b"), ctx)
        // 按 requestId 返回不同结果：x1 找不到，x2 已提交
        val svc = ReconciliationService(
            object : DshRemote {
                override suspend fun handshake() = DshResult.success(HandshakeInfo("v", "v", 1L, emptySet()))
                override suspend fun sessionFollow(sessionId: SessionId, ctx: TraceContext) = throw UnsupportedOperationException()
                override suspend fun sessionPage(sessionId: SessionId, beforeSeq: Long?, limit: Int, ctx: TraceContext) =
                    DshResult.success(PageChunk(emptyList(), false, null))
                override suspend fun sessionControl(sessionId: SessionId, generation: Long?, ctx: TraceContext) =
                    DshResult.success(ControlFrame.Queue(1L, emptyList()))
                override suspend fun command(req: CommandRequest, ctx: TraceContext) = DshResult.success(CommandAck(req.requestId, true))
                override suspend fun promptLookup(sessionId: SessionId, requestId: String, ctx: TraceContext): DshResult<PromptLookupResult> =
                    if (requestId == "x2") DshResult.success(PromptLookupResult.Committed("m_x2", 1L))
                    else DshResult.success(PromptLookupResult.NotFound)
                override suspend fun workspaceList(ctx: TraceContext) = DshResult.success(emptyList<WorkspaceEntry>())
                override suspend fun sessionList(workspaceId: String?, ctx: TraceContext) = DshResult.success(emptyList<SessionEntry>())
                override suspend fun pluginInventory(ctx: TraceContext) = DshResult.success(emptyList<PluginEntry>())
                override suspend fun close() {}
            },
            tracker,
        )
        val reconciled = svc.reconcileAll(ctx)
        assertEquals(2, reconciled.size)
        val e1 = tracker.get(PromptRequest(sid, "x1", "a"))!!
        val e2 = tracker.get(PromptRequest(sid, "x2", "b"))!!
        assertIs<OptimisticState.PendingConfirmation>(e1.state)
        assertIs<OptimisticState.Confirmed>(e2.state)
    }
}
