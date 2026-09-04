package com.example.talktoai.dsh.session

import com.example.talktoai.dsh.contract.ContentBlock
import com.example.talktoai.dsh.contract.SessionEvent
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.contract.ToolStatus
import com.example.talktoai.dsh.contract.TurnKind
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.FollowFrame
import com.example.talktoai.dsh.transport.FollowStreamEvent
import com.example.talktoai.dsh.transport.PageChunk
import com.example.talktoai.dsh.transport.SessionHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking

/**
 * 长会话 / 大窗口性能基线（设计 §8.1 + §10 P0-4）。
 *
 * 验收阈值（首版，可在 P2 调优）：
 *   - 1000 events opening snapshot 应用 < 500ms
 *   - 5000 events 增量 append < 1000ms
 *   - 重复事件丢弃（100 条重复） < 200ms
 *   - 加载的 events 列表大小 == 历史总数（无丢失）
 */
class LongSessionPerfTest {

    private val sid = SessionId("perf-sess")
    private val ctx = TraceContext.initial(1L).withSession(sid.value)

    private fun userTurn(seq: Long) = SessionEvent.Turn(sid, seq, "t$seq", TurnKind.USER)
    private fun assistantTurn(seq: Long) = SessionEvent.Turn(sid, seq, "t$seq", TurnKind.ASSISTANT)
    private fun tool(seq: Long) = SessionEvent.Tool(sid, seq, "tool$seq", "search", ToolStatus.SUCCESS, "ok")
    private fun assistant(seq: Long, text: String) = SessionEvent.AssistantMessage(
        sid, seq, "m$seq", "t$seq",
        listOf(ContentBlock.Text(text)),
        isFinal = true, createdAtMs = 0L,
    )

    @Test
    fun opening_with_1000_events_completes_under_threshold() {
        val sm = SessionWindowStateMachine(sid)
        val records = (1L..1000L).map { seq ->
            when {
                seq % 3 == 0L -> assistantTurn(seq)
                seq % 3 == 1L -> userTurn(seq)
                else -> tool(seq)
            }
        }
        val elapsedMs = measureTimeMillis {
            val r = sm.applyOpening(
                generation = 1L,
                header = SessionHeader(sid.value, "perf", 0L),
                openingCursor = 1L,
                records = records,
                hasMore = false,
                ctx = ctx,
            )
            assertTrue(r is com.example.talktoai.dsh.contract.DshResult.Ok)
        }
        assertEquals(1000, sm.events.size)
        assertEquals(1000L, sm.cursor.liveTailSeq)
        // 验收阈值：500ms 宽松预算
        assertTrue(elapsedMs < 500L, "opening 1000 events took ${elapsedMs}ms > 500ms budget")
    }

    @Test
    fun incremental_5000_events_under_threshold() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(
            generation = 1L,
            header = SessionHeader(sid.value, "perf", 0L),
            openingCursor = 1L,
            records = listOf(userTurn(1L)),
            hasMore = false,
            ctx = ctx,
        )
        val elapsedMs = measureTimeMillis {
            for (seq in 2L..5000L) {
                val ev = if (seq % 2L == 0L) assistant(seq, "msg-$seq") else userTurn(seq)
                val r = sm.applyEvent(ev, 1L, ctx)
                assertTrue(r is com.example.talktoai.dsh.contract.DshResult.Ok, "seq=$seq failed: $r")
            }
        }
        assertEquals(5000, sm.events.size)
        assertEquals(5000L, sm.cursor.liveTailSeq)
        assertTrue(elapsedMs < 1000L, "5000 incremental events took ${elapsedMs}ms > 1000ms budget")
    }

    @Test
    fun duplicates_are_dropped_within_threshold() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(
            generation = 1L,
            header = SessionHeader(sid.value, "perf", 0L),
            openingCursor = 1L,
            records = (1L..500L).map { assistantTurn(it) },
            hasMore = false,
            ctx = ctx,
        )
        val elapsedMs = measureTimeMillis {
            for (seq in 1L..100L) {
                val r = sm.applyEvent(assistantTurn(seq), 1L, ctx)
                assertTrue(r is com.example.talktoai.dsh.contract.DshResult.Err, "seq=$seq should be dropped")
            }
        }
        assertEquals(500, sm.events.size)
        assertTrue(elapsedMs < 200L, "100 duplicates took ${elapsedMs}ms > 200ms budget")
    }

    @Test
    fun page_history_prepend_keeps_window_invariants() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(
            generation = 1L,
            header = SessionHeader(sid.value, "perf", 0L),
            openingCursor = 5001L,
            records = (5001L..6000L).map { assistant(it, "new-$it") },
            hasMore = true,
            ctx = ctx,
        )
        // 分两批 prepend 历史
        val older1 = PageChunk(
            records = (4001L..5000L).map { assistant(it, "hist-$it") },
            hasMore = true,
            oldestLoadedSeq = 4001L,
        )
        val older2 = PageChunk(
            records = (1L..4000L).map { assistant(it, "hist-$it") },
            hasMore = false,
            oldestLoadedSeq = 1L,
        )
        val elapsedMs = measureTimeMillis {
            assertTrue(sm.applyPage(older1, ctx) is com.example.talktoai.dsh.contract.DshResult.Ok)
            assertTrue(sm.applyPage(older2, ctx) is com.example.talktoai.dsh.contract.DshResult.Ok)
        }
        assertEquals(6000, sm.events.size)
        assertEquals(1L, sm.cursor.oldestLoadedSeq)
        assertEquals(6000L, sm.cursor.liveTailSeq)
        assertEquals(false, sm.cursor.hasMore)
        // 历史 prepend 不影响实时尾部
        assertTrue(elapsedMs < 1500L, "prepend 5000 history took ${elapsedMs}ms > 1500ms budget")
    }

    @Test
    fun follow_client_processes_1000_event_stream() = runBlocking<Unit> {
        val state = SessionWindowStateMachine(sid)
        val client = SessionFollowClient(sid, state)
        val received = mutableListOf<SessionEvent>()
        val frames = buildList<FollowStreamEvent> {
            add(FollowStreamEvent.Frame(FollowFrame.OpeningSnapshot(
                generation = 1L,
                header = SessionHeader(sid.value, "perf", 0L),
                cursor = 1L,
                records = (1L..10L).map { userTurn(it) },
                hasMore = false,
                projectionBaseline = emptyList(),
                queueBaseline = emptyList(),
            )))
            for (seq in 11L..1000L) {
                add(FollowStreamEvent.Frame(FollowFrame.Event(1L, assistant(seq, "delta-$seq"))))
            }
            add(FollowStreamEvent.Completed)
        }
        val elapsedMs = measureTimeMillis {
            client.run(
                ctx,
                openStream = { ScriptedStream(frames, FollowStreamEvent.Completed) },
                onUpdate = { received.add(it) },
                maxRebuilds = 0,
            )
        }
        // opening 10 条 + 增量 990 条
        assertEquals(1000, received.size)
        assertEquals(SessionWindowStateMachine.Phase.LIVE, state.phase)
        assertTrue(elapsedMs < 1500L, "follow stream 1000 events took ${elapsedMs}ms > 1500ms budget")
    }
}
