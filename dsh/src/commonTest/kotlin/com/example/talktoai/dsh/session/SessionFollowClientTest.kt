package com.example.talktoai.dsh.session

import com.example.talktoai.dsh.client.InMemoryFakeRemote
import com.example.talktoai.dsh.contract.ContentBlock
import com.example.talktoai.dsh.contract.SessionEvent
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.contract.TurnKind
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.FollowFrame
import com.example.talktoai.dsh.transport.FollowStreamEvent
import com.example.talktoai.dsh.transport.SessionHeader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * SessionFollowClient 集成测试。
 * 使用可编程的 [ScriptedStream] 直接喂入 follow 帧序列，避免与 InMemoryFakeRemote 的
 * MutableSharedFlow 时序耦合。
 */
class SessionFollowClientTest {

    private val sid = SessionId("sess-A")
    private val ctx = TraceContext.initial(7L).withSession(sid.value)

    private fun turn(seq: Long, kind: TurnKind, id: String = "t$seq") =
        SessionEvent.Turn(sid, seq, id, kind)

    private fun assistant(seq: Long, text: String, final: Boolean = true): SessionEvent.AssistantMessage =
        SessionEvent.AssistantMessage(sid, seq, "m$seq", "t$seq", listOf(ContentBlock.Text(text)), isFinal = final, createdAtMs = 0L)

    private fun opening(gen: Long, records: List<SessionEvent>) = FollowStreamEvent.Frame(
        FollowFrame.OpeningSnapshot(
            generation = gen,
            header = SessionHeader(sid.value, "title", 0L),
            cursor = records.firstOrNull()?.seq ?: 1L,
            records = records,
            hasMore = false,
            projectionBaseline = emptyList(),
            queueBaseline = emptyList(),
        )
    )

    private fun eventFrame(gen: Long, event: SessionEvent) =
        FollowStreamEvent.Frame(FollowFrame.Event(gen, event))

    @Test
    fun applies_opening_and_continuous_events() = runBlocking<Unit> {
        val state = SessionWindowStateMachine(sid)
        val received = mutableListOf<SessionEvent>()
        val client = SessionFollowClient(sid, state)
        val frames = listOf(
            opening(1L, listOf(turn(1, TurnKind.USER), assistant(2, "hello"))),
            eventFrame(1L, turn(3, TurnKind.ASSISTANT)),
            eventFrame(1L, turn(4, TurnKind.USER)),
        )
        client.run(
            ctx,
            openStream = { ScriptedStream(frames, terminal = FollowStreamEvent.Completed) },
            onUpdate = { received.add(it) },
        )
        assertEquals(SessionWindowStateMachine.Phase.LIVE, state.phase)
        // opening records + 2 events = 4 callbacks
        assertEquals(4, received.size)
        assertEquals(4L, state.cursor.liveTailSeq)
    }

    @Test
    fun gap_is_detected_and_triggers_rebuild_with_new_opening() = runBlocking<Unit> {
        val state = SessionWindowStateMachine(sid)
        val client = SessionFollowClient(sid, state)
        var openCalls = 0
        client.run(
            ctx,
            openStream = { _ ->
                openCalls++
                when (openCalls) {
                    1 -> ScriptedStream(
                        frames = listOf(
                            opening(1L, listOf(turn(1, TurnKind.USER))),
                            eventFrame(1L, turn(99, TurnKind.ASSISTANT)),  // 缺号
                        ),
                        terminal = FollowStreamEvent.Completed,
                    )
                    else -> ScriptedStream(
                        frames = listOf(
                            opening(2L, listOf(turn(99, TurnKind.ASSISTANT))),
                            FollowStreamEvent.Completed,
                        ),
                        terminal = FollowStreamEvent.Completed,
                    )
                }
            },
            maxRebuilds = 3,
        )
        assertTrue(openCalls >= 2, "rebuild factory should be invoked at least twice")
        assertEquals(SessionWindowStateMachine.Phase.LIVE, state.phase)
        assertEquals(99L, state.cursor.liveTailSeq)
    }

    @Test
    fun duplicate_event_is_dropped_without_callback() = runBlocking<Unit> {
        val state = SessionWindowStateMachine(sid)
        val received = mutableListOf<SessionEvent>()
        val client = SessionFollowClient(sid, state)
        val frames = listOf(
            opening(1L, listOf(turn(1, TurnKind.USER))),
            eventFrame(1L, turn(1, TurnKind.USER)),  // 重复旧事件
            eventFrame(1L, turn(2, TurnKind.ASSISTANT)),
        )
        client.run(
            ctx,
            openStream = { ScriptedStream(frames, terminal = FollowStreamEvent.Completed) },
            onUpdate = { received.add(it) },
        )
        assertEquals(SessionWindowStateMachine.Phase.LIVE, state.phase)
        // opening 中 turn(1) 已上推；重复事件被丢弃；只有 turn(2) 通过 onUpdate 上推
        // 期望：opening records (1) + turn(2) = 2
        assertEquals(2, received.size)
        assertEquals(2L, received.last().seq)
    }
}
