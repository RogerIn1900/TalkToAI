package com.example.talktoai.dsh.contract

/**
 * Session 主模型。直接映射 DSH Session/Turn/Step/ContentBlock/Tool 语义（设计 §11）。
 * 注意：设计明确要求"不复制一套 Run/Step 模型"。
 */
data class SessionId(val value: String) {
    init { require(value.isNotEmpty()) { "SessionId must not be empty" } }
    override fun toString(): String = value
}

data class SessionCursor(
    val openingCursor: Long,
    val liveTailSeq: Long,
    val loadedRanges: List<SeqRange>,
    val hasMore: Boolean,
    val oldestLoadedSeq: Long?,
) {
    companion object {
        val EMPTY = SessionCursor(
            openingCursor = 0L,
            liveTailSeq = 0L,
            loadedRanges = emptyList(),
            hasMore = false,
            oldestLoadedSeq = null,
        )
    }
}

data class SeqRange(val start: Long, val endInclusive: Long) {
    init { require(start <= endInclusive) { "SeqRange start($start) must <= end($endInclusive)" } }
    val size: Long get() = endInclusive - start + 1
    fun contains(seq: Long): Boolean = seq in start..endInclusive
}

sealed class ContentBlock {
    data class Text(val markdown: String) : ContentBlock()
    data class Code(val language: String?, val source: String) : ContentBlock()
    data class Latex(val source: String) : ContentBlock()
    data class ImageRef(val ref: AttachmentRef) : ContentBlock()
    data class ToolCard(
        val toolName: String,
        val status: ToolStatus,
        val summary: String? = null,
        val result: String? = null,
    ) : ContentBlock()
    data class Unknown(val type: String, val raw: String) : ContentBlock()
}

enum class ToolStatus { PENDING, RUNNING, SUCCESS, FAILED, CANCELLED }

sealed class SessionEvent {
    abstract val sessionId: SessionId
    abstract val seq: Long

    data class UserMessage(
        override val sessionId: SessionId,
        override val seq: Long,
        val messageId: String,
        val requestId: String,
        val content: List<ContentBlock>,
        val sentAtMs: Long,
    ) : SessionEvent()

    data class AssistantMessage(
        override val sessionId: SessionId,
        override val seq: Long,
        val messageId: String,
        val turnId: String,
        val content: List<ContentBlock>,
        val isFinal: Boolean,
        val createdAtMs: Long,
    ) : SessionEvent()

    data class Turn(
        override val sessionId: SessionId,
        override val seq: Long,
        val turnId: String,
        val kind: TurnKind,
    ) : SessionEvent()

    data class Step(
        override val sessionId: SessionId,
        override val seq: Long,
        val stepId: String,
        val turnId: String,
        val status: ToolStatus,
    ) : SessionEvent()

    data class Tool(
        override val sessionId: SessionId,
        override val seq: Long,
        val toolId: String,
        val toolName: String,
        val status: ToolStatus,
        val summary: String? = null,
    ) : SessionEvent()

    data class Interaction(
        override val sessionId: SessionId,
        override val seq: Long,
        val interactionId: String,
        val kind: InteractionKind,
        val payload: String,
    ) : SessionEvent()
}

enum class TurnKind { USER, ASSISTANT }
enum class InteractionKind { APPROVAL, QUESTION, PLAN_REVIEW }

sealed class HostSnapshot {
    abstract val generation: Long

    data class Queue(override val generation: Long, val pending: List<QueueItem>) : HostSnapshot()
    data class Jobs(override val generation: Long, val running: List<JobItem>) : HostSnapshot()
    data class Projection(override val generation: Long, val items: List<ProjectionItem>) : HostSnapshot()
    data class SessionList(override val generation: Long, val sessions: List<SessionSummary>) : HostSnapshot()
    data class WorkspaceList(override val generation: Long, val workspaces: List<WorkspaceSummary>) : HostSnapshot()
}

data class QueueItem(val requestId: String, val kind: QueueKind, val status: QueueStatus, val sessionId: SessionId, val summary: String?)
enum class QueueKind { PROMPT, STEER, FOLLOWUP, CANCEL }
enum class QueueStatus { PENDING, RUNNING, DONE, FAILED, CANCELLED }

data class JobItem(val jobId: String, val kind: String, val status: ToolStatus)
data class ProjectionItem(val sessionId: SessionId, val title: String, val archived: Boolean, val updatedAtMs: Long)
data class SessionSummary(val sessionId: SessionId, val title: String, val updatedAtMs: Long, val archived: Boolean)
data class WorkspaceSummary(val workspaceId: String, val name: String, val sessionCount: Int)
