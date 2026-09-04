package com.example.talktoai.dsh.transport

import com.example.talktoai.dsh.contract.SessionEvent

sealed class FollowFrame {
    abstract val generation: Long

    data class OpeningSnapshot(
        override val generation: Long,
        val header: SessionHeader,
        val cursor: Long,
        val records: List<SessionEvent>,
        val hasMore: Boolean,
        val projectionBaseline: List<ProjectionItemDto>,
        val queueBaseline: List<QueueItemDto>,
    ) : FollowFrame()

    data class Event(
        override val generation: Long,
        val event: SessionEvent,
    ) : FollowFrame()

    data class Closed(
        override val generation: Long,
        val reason: String,
    ) : FollowFrame()
}

data class SessionHeader(
    val sessionId: String,
    val title: String,
    val createdAtMs: Long,
)

interface FollowStream {
    suspend fun collect(handler: suspend (FollowStreamEvent) -> Boolean)
    suspend fun cancel()
}

sealed class FollowStreamEvent {
    data class Frame(val frame: FollowFrame) : FollowStreamEvent()
    data class Error(val error: com.example.talktoai.dsh.contract.DshError) : FollowStreamEvent()
    data object Completed : FollowStreamEvent()
}
