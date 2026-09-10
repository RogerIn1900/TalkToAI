package com.example.talktoai.chat

object AiModels {
    const val HY3 = "hy3"
    const val DEEPSEEK_V4_FLASH = "deepseek-v4-flash"
    const val DEFAULT = HY3
    val allowed = setOf(HY3, DEEPSEEK_V4_FLASH)
}

enum class MessageRole(val wireName: String) {
    USER("user"),
    ASSISTANT("assistant"),
}

enum class MessageStatus(val wireName: String) {
    COMPLETE("complete"),
    STREAMING("streaming"),
    STOPPED("stopped"),
    FAILED("failed"),
}

data class ChatMessage(
    val id: String,
    val role: MessageRole,
    val content: String,
    val status: MessageStatus,
    val createdAtMs: Long,
    val attachments: List<ChatAttachment> = emptyList(),
    val citations: List<String> = emptyList(),
    val marketDataJson: String = "",
)

data class ChatAttachment(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String,
    val objectRef: String = "",
)

data class ChatSession(
    val id: String,
    val title: String,
    val archived: Boolean,
    val deletedAtMs: Long?,
    val updatedAtMs: Long,
    val messages: List<ChatMessage>,
)

data class StreamEvent(
    val type: String,
    val data: String,
)
