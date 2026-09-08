package com.example.talktoai.chat.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatDatabaseTest {
    private lateinit var database: ChatDatabase
    private lateinit var dao: ChatDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            ChatDatabase::class.java,
        ).build()
        dao = database.chatDao()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun fiveThousandMessagesRemainOrderedAndADeletedSessionCascades() {
        val session = ChatSessionEntity("session", "压力会话", false, null, 1L)
        dao.upsertSession(session)
        val messages = List(MESSAGE_COUNT) { index ->
            ChatMessageEntity(
                id = "message-$index",
                sessionId = session.id,
                position = index,
                role = if (index % 2 == 0) "user" else "assistant",
                content = "content-$index",
                status = "complete",
                createdAtMs = index.toLong(),
                attachmentsJson = "[]",
                citationsJson = "[]",
            )
        }
        database.runInTransaction { dao.upsertMessages(messages) }

        val loaded = dao.messages(session.id)
        assertEquals(MESSAGE_COUNT, loaded.size)
        assertEquals("message-0", loaded.first().id)
        assertEquals("message-4999", loaded.last().id)
        val recent = dao.recentMessages(session.id, 100).asReversed()
        assertEquals(100, recent.size)
        assertEquals("message-4900", recent.first().id)
        assertEquals("message-4999", recent.last().id)
        assertEquals(MESSAGE_COUNT, dao.messageCount(session.id))
        assertEquals(listOf(session.id), dao.searchVisibleSessions("content-4242").map { it.id })

        dao.softDelete(session.id, nowMs = 10L)
        dao.deleteExpiredSessions(cutoffMs = 11L)
        assertNull(dao.visibleSession(session.id))
        assertEquals(0, dao.messages(session.id).size)
    }

    @Test
    fun interruptedStreamingMessagesBecomeStopped() {
        dao.upsertSession(ChatSessionEntity("session", "恢复", false, null, 1L))
        dao.upsertMessages(listOf(ChatMessageEntity(
            id = "streaming",
            sessionId = "session",
            position = 0,
            role = "assistant",
            content = "partial",
            status = "streaming",
            createdAtMs = 1L,
            attachmentsJson = "[]",
            citationsJson = "[]",
        )))

        dao.markInterruptedMessagesStopped()

        assertEquals("stopped", dao.messages("session").single().status)
    }

    private companion object {
        const val MESSAGE_COUNT = 5_000
    }
}
