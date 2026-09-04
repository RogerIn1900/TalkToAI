package com.example.talktoai.dsh.client

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.SessionEvent
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.CommandAck
import com.example.talktoai.dsh.transport.CommandKind
import com.example.talktoai.dsh.transport.CommandRequest
import com.example.talktoai.dsh.transport.ControlFrame
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.transport.FollowFrame
import com.example.talktoai.dsh.transport.FollowStream
import com.example.talktoai.dsh.transport.FollowStreamEvent
import com.example.talktoai.dsh.transport.HandshakeInfo
import com.example.talktoai.dsh.transport.PageChunk
import com.example.talktoai.dsh.transport.PluginEntry
import com.example.talktoai.dsh.transport.PromptLookupResult
import com.example.talktoai.dsh.transport.SessionEntry
import com.example.talktoai.dsh.transport.SessionHeader
import com.example.talktoai.dsh.transport.WorkspaceEntry
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile

/**
 * InMemoryFakeRemote：单元测试和 dev harness 使用。
 * 提供完整的可重放 follow stream、page、control、command、lookup 等行为。
 */
class InMemoryFakeRemote(
    private val expectedVersion: String = "0.1.2-alpha.2",
    private val remoteVersion: String = "0.1.2-alpha.2",
    private val generation: Long = 1L,
) : DshRemote {

    data class CommittedPrompt(
        val sessionId: String,
        val requestId: String,
        val messageId: String,
        val committedAtMs: Long,
    )

    private val committedPrompts: MutableMap<Pair<String, String>, CommittedPrompt> = LinkedHashMap()

    private val followFlows: MutableMap<String, MutableSharedFlow<FollowStreamEvent>> = HashMap()
    private val histories: MutableMap<String, MutableList<SessionEvent>> = HashMap()

    var controlFrame: ControlFrame = ControlFrame.Queue(generation, emptyList())
    var lookupAvailable: Boolean = true
    var acceptCommands: Boolean = true

    override suspend fun handshake(): DshResult<HandshakeInfo> = DshResult.success(
        HandshakeInfo(
            remoteVersion = remoteVersion,
            expectedVersion = expectedVersion,
            connectionGeneration = generation,
            capabilities = setOf("session.follow", "session.control", "command", "lookup"),
        )
    )

    override suspend fun sessionFollow(sessionId: SessionId, ctx: TraceContext): FollowStream {
        val flow = followFlows.getOrPut(sessionId.value) {
            MutableSharedFlow(replay = 64, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
        }
        val header = SessionHeader(
            sessionId = sessionId.value,
            title = "Test ${sessionId.value}",
            createdAtMs = 0L,
        )
        val openingRecords = histories[sessionId.value]?.toList() ?: emptyList()
        val cursor = openingRecords.firstOrNull()?.seq ?: 1L
        val opening = FollowFrame.OpeningSnapshot(
            generation = generation,
            header = header,
            cursor = cursor,
            records = openingRecords,
            hasMore = false,
            projectionBaseline = emptyList(),
            queueBaseline = emptyList(),
        )
        flow.tryEmit(FollowStreamEvent.Frame(opening))
        return object : FollowStream {
            override suspend fun collect(handler: suspend (FollowStreamEvent) -> Boolean) {
                flow.takeWhile { ev -> handler(ev) }.collect {}
            }
            override suspend fun cancel() {}
        }
    }

    fun injectEvent(sessionId: SessionId, event: SessionEvent) {
        val flow = followFlows.getOrPut(sessionId.value) {
            MutableSharedFlow(replay = 64, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)
        }
        histories.getOrPut(sessionId.value) { mutableListOf() }.add(event)
        flow.tryEmit(FollowStreamEvent.Frame(FollowFrame.Event(generation, event)))
    }

    fun injectStaleEvent(sessionId: SessionId, event: SessionEvent) {
        val flow = followFlows.getOrPut(sessionId.value) { MutableSharedFlow() }
        flow.tryEmit(FollowStreamEvent.Frame(FollowFrame.Event(generation, event)))
    }

    fun injectClosed(sessionId: SessionId, reason: String = "test-close") {
        val flow = followFlows.getOrPut(sessionId.value) { MutableSharedFlow() }
        flow.tryEmit(FollowStreamEvent.Frame(FollowFrame.Closed(generation, reason)))
    }

    override suspend fun sessionPage(
        sessionId: SessionId,
        beforeSeq: Long?,
        limit: Int,
        ctx: TraceContext,
    ): DshResult<PageChunk> {
        val all = histories[sessionId.value]
            ?: return DshResult.success(PageChunk(emptyList(), hasMore = false, oldestLoadedSeq = null))
        val filtered = if (beforeSeq != null) all.filter { it.seq < beforeSeq } else all
        val sorted = filtered.sortedBy { it.seq }.takeLast(limit)
        return DshResult.success(
            PageChunk(records = sorted, hasMore = false, oldestLoadedSeq = sorted.firstOrNull()?.seq)
        )
    }

    override suspend fun sessionControl(
        sessionId: SessionId,
        generation: Long?,
        ctx: TraceContext,
    ): DshResult<ControlFrame> = DshResult.success(controlFrame)

    override suspend fun command(req: CommandRequest, ctx: TraceContext): DshResult<CommandAck> {
        if (!acceptCommands) {
            return DshResult.failure(DshError.Transport.Disconnected())
        }
        when (req.kind) {
            CommandKind.PROMPT, CommandKind.STEER -> {
                val msgId = "msg_${req.requestId}"
                val ts = System.currentTimeMillis()
                committedPrompts[req.sessionId.value to req.requestId] =
                    CommittedPrompt(req.sessionId.value, req.requestId, msgId, ts)
                return DshResult.success(CommandAck(req.requestId, accepted = true))
            }
            CommandKind.CANCEL -> {
                return DshResult.success(CommandAck(req.requestId, accepted = true))
            }
        }
    }

    override suspend fun promptLookup(
        sessionId: SessionId,
        requestId: String,
        ctx: TraceContext,
    ): DshResult<PromptLookupResult> {
        if (!lookupAvailable) return DshResult.success(PromptLookupResult.Unavailable)
        val cp = committedPrompts[sessionId.value to requestId]
            ?: return DshResult.success(PromptLookupResult.NotFound)
        return DshResult.success(PromptLookupResult.Committed(cp.messageId, cp.committedAtMs))
    }

    override suspend fun workspaceList(ctx: TraceContext): DshResult<List<WorkspaceEntry>> =
        DshResult.success(listOf(WorkspaceEntry("ws1", "Default")))

    override suspend fun sessionList(workspaceId: String?, ctx: TraceContext): DshResult<List<SessionEntry>> =
        DshResult.success(emptyList())

    override suspend fun pluginInventory(ctx: TraceContext): DshResult<List<PluginEntry>> =
        DshResult.success(emptyList())

    override suspend fun close() {
        followFlows.clear()
    }
}
