package com.example.talktoai.dsh.session

import com.example.talktoai.dsh.contract.ContentBlock
import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.SessionEvent
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.contract.ToolStatus
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.FollowFrame
import com.example.talktoai.dsh.transport.SessionHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SessionWindowStateMachineTest {

    private val sid = SessionId("s1")
    private val ctx = TraceContext.initial(1L).withSession(sid.value)

    private fun turn(seq: Long, kind: com.example.talktoai.dsh.contract.TurnKind, turnId: String = "t$seq"): SessionEvent.Turn =
        SessionEvent.Turn(sid, seq, turnId, kind)

    private fun assistant(seq: Long, msgId: String = "m$seq", text: String = "hi", final: Boolean = true): SessionEvent.AssistantMessage =
        SessionEvent.AssistantMessage(sid, seq, msgId, "t$seq", listOf(ContentBlock.Text(text)), isFinal = final, createdAtMs = 0)

    private fun header() = SessionHeader(sid.value, "title", 0L)

    @Test
    fun opening_snapshot_transitions_to_live_and_sets_cursor() {
        val sm = SessionWindowStateMachine(sid)
        val records = listOf(turn(1, com.example.talktoai.dsh.contract.TurnKind.USER), assistant(2))
        val r = sm.applyOpening(generation = 1L, header = header(), openingCursor = 1L, records = records, hasMore = false, ctx = ctx)
        assertIs<DshResult.Ok<Unit>>(r)
        assertEquals(SessionWindowStateMachine.Phase.LIVE, sm.phase)
        assertEquals(2L, sm.cursor.liveTailSeq)
        assertEquals(1L, sm.cursor.openingCursor)
        assertEquals(listOf(com.example.talktoai.dsh.contract.SeqRange(1, 2)), sm.cursor.loadedRanges)
        assertTrue(sm.isLive())
        assertEquals(2, sm.events.size)
    }

    @Test
    fun duplicate_old_event_is_dropped_idempotently() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(1L, header(), 1L, listOf(turn(1, com.example.talktoai.dsh.contract.TurnKind.USER)), false, ctx)
        val r = sm.applyEvent(turn(1, com.example.talktoai.dsh.contract.TurnKind.USER), 1L, ctx)
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Protocol.DroppedDuplicate>(r.error)
        assertEquals(1L, sm.cursor.liveTailSeq)
    }

    @Test
    fun event_seq_greater_than_expected_triggers_gap_error() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(1L, header(), 1L, listOf(turn(1, com.example.talktoai.dsh.contract.TurnKind.USER)), false, ctx)
        val r = sm.applyEvent(turn(3, com.example.talktoai.dsh.contract.TurnKind.USER), 1L, ctx)
        assertIs<DshResult.Err>(r)
        val err = r.error
        assertIs<DshError.Protocol.Gap>(err)
        assertEquals(2L, err.expectedSeq)
        assertEquals(3L, err.receivedSeq)
        assertEquals(SessionWindowStateMachine.Phase.FAILED, sm.phase)
    }

    @Test
    fun monotonic_event_extends_loaded_ranges() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(1L, header(), 1L, listOf(turn(1, com.example.talktoai.dsh.contract.TurnKind.USER)), false, ctx)
        sm.applyEvent(turn(2, com.example.talktoai.dsh.contract.TurnKind.USER), 1L, ctx)
        sm.applyEvent(turn(3, com.example.talktoai.dsh.contract.TurnKind.USER), 1L, ctx)
        assertEquals(3L, sm.cursor.liveTailSeq)
        assertEquals(listOf(com.example.talktoai.dsh.contract.SeqRange(1, 3)), sm.cursor.loadedRanges)
    }

    @Test
    fun rebuild_resets_then_re_opens() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(1L, header(), 1L, listOf(turn(1, com.example.talktoai.dsh.contract.TurnKind.USER)), false, ctx)
        sm.applyEvent(turn(5, com.example.talktoai.dsh.contract.TurnKind.USER), 1L, ctx)
        assertEquals(SessionWindowStateMachine.Phase.FAILED, sm.phase)
        assertEquals(DshResult.success(Unit), sm.beginRebuild())
        assertEquals(SessionWindowStateMachine.Phase.OPENING, sm.phase)
        // opening with new baseline
        sm.applyOpening(2L, header(), 5L, listOf(turn(5, com.example.talktoai.dsh.contract.TurnKind.ASSISTANT)), false, ctx)
        assertEquals(SessionWindowStateMachine.Phase.LIVE, sm.phase)
        assertEquals(5L, sm.cursor.liveTailSeq)
    }

    @Test
    fun page_prepend_does_not_change_tail() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(1L, header(), 11L, listOf(turn(11, com.example.talktoai.dsh.contract.TurnKind.USER)), false, ctx)
        sm.applyEvent(turn(12, com.example.talktoai.dsh.contract.TurnKind.ASSISTANT), 1L, ctx)
        val chunk = com.example.talktoai.dsh.transport.PageChunk(
            records = listOf(turn(5, com.example.talktoai.dsh.contract.TurnKind.USER), turn(6, com.example.talktoai.dsh.contract.TurnKind.ASSISTANT)),
            hasMore = false,
            oldestLoadedSeq = 5L,
        )
        val r = sm.applyPage(chunk, ctx)
        assertIs<DshResult.Ok<Unit>>(r)
        assertEquals(12L, sm.cursor.liveTailSeq)
        assertEquals(5L, sm.cursor.oldestLoadedSeq)
        assertEquals(
            listOf(com.example.talktoai.dsh.contract.SeqRange(5, 6), com.example.talktoai.dsh.contract.SeqRange(11, 12)),
            sm.cursor.loadedRanges
        )
    }

    @Test
    fun non_monotonic_opening_returns_error() {
        val sm = SessionWindowStateMachine(sid)
        val records = listOf(turn(1, com.example.talktoai.dsh.contract.TurnKind.USER), turn(3, com.example.talktoai.dsh.contract.TurnKind.ASSISTANT))
        val r = sm.applyOpening(1L, header(), 1L, records, false, ctx)
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Protocol.NonMonotonic>(r.error)
    }

    @Test
    fun tool_event_applies_with_status() {
        val sm = SessionWindowStateMachine(sid)
        sm.applyOpening(1L, header(), 1L, listOf(turn(1, com.example.talktoai.dsh.contract.TurnKind.USER)), false, ctx)
        val ev = SessionEvent.Tool(sid, 2L, "tool1", "search", ToolStatus.SUCCESS, "found 5")
        val r = sm.applyEvent(ev, 1L, ctx)
        assertIs<DshResult.Ok<Unit>>(r)
        assertEquals(2L, sm.cursor.liveTailSeq)
    }
}
