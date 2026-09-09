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
    fun updatingSessionDuringStreamingPreservesEarlierMessages() {
        val session = ChatSessionEntity("stream-session", "original", false, null, 1L)
        dao.upsertSession(session)
        val user = ChatMessageEntity("user", session.id, 0, "user", "today market", "complete", 1L, "[]", "[]")
        val assistant = ChatMessageEntity("assistant", session.id, 1, "assistant", "first", "streaming", 2L, "[]", "[]", "{\"freshness\":\"STALE\"}")
        dao.upsertMessages(listOf(user, assistant))

        database.runInTransaction {
            dao.upsertSession(session.copy(title = "updated", updatedAtMs = 3L))
            dao.upsertMessages(listOf(assistant.copy(content = "first second")))
        }

        assertEquals("updated", dao.visibleSession(session.id)?.title)
        assertEquals(listOf(user, assistant.copy(content = "first second")), dao.messages(session.id))
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
            marketDataJson = "{\"symbol\":\"000001.SH\",\"freshness\":\"STALE\"}",
        )))

        dao.markInterruptedMessagesStopped()

        assertEquals("stopped", dao.messages("session").single().status)
        assertEquals("{\"symbol\":\"000001.SH\",\"freshness\":\"STALE\"}", dao.messages("session").single().marketDataJson)
    }

    @Test fun migrationAddsMarketPayloadWithoutDeletingOldMessages() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val factory = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        val helper = factory.create(androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL("CREATE TABLE chat_messages (id TEXT PRIMARY KEY, content TEXT NOT NULL)")
                    db.execSQL("INSERT INTO chat_messages VALUES ('old', 'preserved')")
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            }).build())
        try {
            val db = helper.writableDatabase
            ChatDatabase.MIGRATION_1_2.migrate(db)
            db.query("SELECT content, marketDataJson FROM chat_messages WHERE id='old'").use {
                org.junit.Assert.assertTrue(it.moveToFirst())
                assertEquals("preserved", it.getString(0))
                assertEquals("", it.getString(1))
            }
        } finally { helper.close() }
    }

    private companion object {
        const val MESSAGE_COUNT = 5_000
    }
}
