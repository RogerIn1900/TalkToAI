package com.example.talktoai.dsh.transport

import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.SessionEvent
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.TraceContext

/**
 * Remote 抽象（设计 §2.1 "Web/App Remote: HTTP unary + logical stream"）。
 * 客户端只依赖 Remote/Stream 契约，不依赖 Host 内部 Service 实现。
 */
interface DshRemote {
    suspend fun handshake(): DshResult<HandshakeInfo>

    /** 设计 §4：follow 必须先返回 opening snapshot，再连续 cursor+1..N。 */
    suspend fun sessionFollow(sessionId: SessionId, ctx: TraceContext): FollowStream

    /** 历史向前分页（设计 §4）。 */
    suspend fun sessionPage(
        sessionId: SessionId,
        beforeSeq: Long?,
        limit: Int,
        ctx: TraceContext,
    ): DshResult<PageChunk>

    suspend fun sessionControl(
        sessionId: SessionId,
        generation: Long?,
        ctx: TraceContext,
    ): DshResult<ControlFrame>

    suspend fun command(req: CommandRequest, ctx: TraceContext): DshResult<CommandAck>

    suspend fun promptLookup(
        sessionId: SessionId,
        requestId: String,
        ctx: TraceContext,
    ): DshResult<PromptLookupResult>

    suspend fun workspaceList(ctx: TraceContext): DshResult<List<WorkspaceEntry>>

    suspend fun sessionList(workspaceId: String?, ctx: TraceContext): DshResult<List<SessionEntry>>

    suspend fun pluginInventory(ctx: TraceContext): DshResult<List<PluginEntry>>

    suspend fun close()
}

data class HandshakeInfo(
    val remoteVersion: String,
    val expectedVersion: String,
    val connectionGeneration: Long,
    val capabilities: Set<String>,
)

data class PageChunk(
    val records: List<SessionEvent>,
    val hasMore: Boolean,
    val oldestLoadedSeq: Long?,
)

sealed class ControlFrame {
    data class Queue(val generation: Long, val items: List<QueueItemDto>) : ControlFrame()
    data class Jobs(val generation: Long, val items: List<JobItemDto>) : ControlFrame()
    data class Projection(val generation: Long, val items: List<ProjectionItemDto>) : ControlFrame()
    data class SessionList(val generation: Long, val sessions: List<SessionSummaryDto>) : ControlFrame()
    data class WorkspaceList(val generation: Long, val workspaces: List<WorkspaceSummaryDto>) : ControlFrame()
}

data class QueueItemDto(
    val requestId: String,
    val kind: String,
    val status: String,
    val sessionId: String,
    val summary: String? = null,
)
data class JobItemDto(val jobId: String, val kind: String, val status: String)
data class ProjectionItemDto(val sessionId: String, val title: String, val archived: Boolean, val updatedAtMs: Long)
data class SessionSummaryDto(val sessionId: String, val title: String, val updatedAtMs: Long, val archived: Boolean)
data class WorkspaceSummaryDto(val workspaceId: String, val name: String, val sessionCount: Int)
data class WorkspaceEntry(val id: String, val name: String)
data class SessionEntry(val id: String, val workspaceId: String, val title: String, val updatedAtMs: Long)
data class PluginEntry(val id: String, val name: String, val version: String, val capabilities: Set<String>)

data class CommandRequest(
    val sessionId: SessionId,
    val kind: CommandKind,
    val requestId: String,
    val payload: Map<String, String>,
)

enum class CommandKind { PROMPT, CANCEL, STEER }

data class CommandAck(
    val requestId: String,
    val accepted: Boolean,
    val reason: String? = null,
)

sealed class PromptLookupResult {
    data class Committed(val messageId: String, val committedAtMs: Long) : PromptLookupResult()
    data object NotFound : PromptLookupResult()
    data class Conflict(val reason: String) : PromptLookupResult()
    data object Unavailable : PromptLookupResult()
}
