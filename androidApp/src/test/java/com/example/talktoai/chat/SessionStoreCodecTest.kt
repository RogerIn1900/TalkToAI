package com.example.talktoai.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStoreCodecTest {
    @Test
    fun `session codec preserves attachment references and message status`() {
        val source = ChatSession(
            id = "session-1",
            title = "测试会话",
            archived = false,
            deletedAtMs = null,
            updatedAtMs = 20,
            messages = listOf(
                ChatMessage(
                    id = "message-1",
                    role = MessageRole.USER,
                    content = "分析附件",
                    status = MessageStatus.COMPLETE,
                    createdAtMs = 10,
                    attachments = listOf(
                        ChatAttachment("abcdef0123456789", "data.csv", "text/csv", 12, "/private/data.csv", "cloud://fixture/data.csv"),
                    ),
                    citations = listOf("https://example.com/source"),
                ),
            ),
        )

        val decoded = SessionStore.decode(SessionStore.encode(listOf(source)).toString())

        assertEquals(listOf(source), decoded)
    }

    @Test
    fun `invalid persisted JSON recovers as empty session list`() {
        assertTrue(SessionStore.decode("not-json").isEmpty())
    }

    @Test
    fun `interrupted streaming response recovers as stopped`() {
        val session = ChatSession(
            "s", "t", false, null, 1,
            listOf(ChatMessage("m", MessageRole.ASSISTANT, "partial", MessageStatus.STREAMING, 1)),
        )

        val recovered = SessionStore.recoverInterrupted(listOf(session))

        assertEquals(MessageStatus.STOPPED, recovered.single().messages.single().status)
    }
}
