package com.example.talktoai.chat.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @androidx.room.PrimaryKey val id: String,
    val title: String,
    val archived: Boolean,
    val deletedAtMs: Long?,
    val updatedAtMs: Long,
)

@Entity(
    tableName = "chat_messages",
    foreignKeys = [ForeignKey(
        entity = ChatSessionEntity::class,
        parentColumns = ["id"],
        childColumns = ["sessionId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["sessionId", "position"], unique = true)],
)
data class ChatMessageEntity(
    @androidx.room.PrimaryKey val id: String,
    val sessionId: String,
    val position: Int,
    val role: String,
    val content: String,
    val status: String,
    val createdAtMs: Long,
    val attachmentsJson: String,
    val citationsJson: String,
)

@Dao
interface ChatDao {
    @Query("SELECT * FROM chat_sessions WHERE deletedAtMs IS NULL ORDER BY updatedAtMs DESC")
    fun visibleSessions(): List<ChatSessionEntity>

    @Query("""
        SELECT * FROM chat_sessions AS session
        WHERE session.deletedAtMs IS NULL AND (
            :query = '' OR instr(lower(session.title), lower(:query)) > 0 OR EXISTS (
                SELECT 1 FROM chat_messages AS message
                WHERE message.sessionId = session.id
                  AND instr(lower(message.content), lower(:query)) > 0
            )
        )
        ORDER BY session.updatedAtMs DESC
    """)
    fun searchVisibleSessions(query: String): List<ChatSessionEntity>

    @Query("SELECT * FROM chat_sessions WHERE id = :sessionId AND deletedAtMs IS NULL LIMIT 1")
    fun visibleSession(sessionId: String): ChatSessionEntity?

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY position")
    fun messages(sessionId: String): List<ChatMessageEntity>

    @Query("SELECT COUNT(*) FROM chat_messages WHERE sessionId = :sessionId")
    fun messageCount(sessionId: String): Int

    @Query("SELECT * FROM chat_messages WHERE sessionId = :sessionId ORDER BY position DESC LIMIT :limit")
    fun recentMessages(sessionId: String, limit: Int): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertSession(session: ChatSessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertMessages(messages: List<ChatMessageEntity>)

    @Query("DELETE FROM chat_messages WHERE id IN (:messageIds)")
    fun deleteMessages(messageIds: List<String>)

    @Query("DELETE FROM chat_sessions WHERE deletedAtMs IS NOT NULL AND deletedAtMs < :cutoffMs")
    fun deleteExpiredSessions(cutoffMs: Long)

    @Query("UPDATE chat_messages SET status = 'stopped' WHERE status = 'streaming'")
    fun markInterruptedMessagesStopped()

    @Query("UPDATE chat_sessions SET title = :title, updatedAtMs = :nowMs WHERE id = :sessionId")
    fun rename(sessionId: String, title: String, nowMs: Long): Int

    @Query("UPDATE chat_sessions SET archived = :archived, updatedAtMs = :nowMs WHERE id = :sessionId")
    fun archive(sessionId: String, archived: Boolean, nowMs: Long): Int

    @Query("UPDATE chat_sessions SET deletedAtMs = :nowMs, updatedAtMs = :nowMs WHERE id = :sessionId")
    fun softDelete(sessionId: String, nowMs: Long): Int
}

@Database(
    entities = [ChatSessionEntity::class, ChatMessageEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao

    companion object {
        @Volatile private var instance: ChatDatabase? = null

        fun get(context: Context): ChatDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                ChatDatabase::class.java,
                DATABASE_NAME,
            ).build().also { instance = it }
        }

        private const val DATABASE_NAME = "talktoai-chat.db"
    }
}
