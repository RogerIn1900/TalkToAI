package com.example.talktoai.dsh.contract

data class PromptRequest(
    val sessionId: SessionId,
    val requestId: String,
    val text: String,
    val attachments: List<ImageAttachmentPayload> = emptyList(),
    val steer: Boolean = false,
) {
    init {
        require(requestId.isNotEmpty()) { "requestId must not be empty" }
        require(text.isNotEmpty() || attachments.isNotEmpty()) { "Prompt must have text or attachment" }
    }
}

data class PromptAck(
    val requestId: String,
    val accepted: Boolean,
    val messageId: String? = null,
    val serverReceivedAtMs: Long? = null,
)

data class PromptQuery(
    val sessionId: SessionId,
    val requestId: String,
)

sealed class PromptQueryResult {
    data class Committed(val messageId: String, val committedAtMs: Long) : PromptQueryResult()
    data object NotFound : PromptQueryResult()
    data class Conflict(val reason: String) : PromptQueryResult()
}

sealed class Command {
    abstract val sessionId: SessionId

    data class Prompt(val request: PromptRequest) : Command() {
        override val sessionId: SessionId get() = request.sessionId
    }
    data class Cancel(val requestId: String, override val sessionId: SessionId) : Command()
    data class Steer(val requestId: String, val text: String, override val sessionId: SessionId) : Command()
}
