package com.example.talktoai.chat

import android.content.Context
import com.example.talktoai.chat.db.ChatDatabase
import com.example.talktoai.chat.db.ChatMessageEntity
import com.example.talktoai.chat.db.ChatSessionEntity
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val database = ChatDatabase.get(context)
    private val dao = database.chatDao()
    private val lock = Any()

    fun loadVisible(): List<ChatSession> = synchronized(lock) {
        migrateLegacyLocked()
        dao.deleteExpiredSessions(System.currentTimeMillis() - DELETION_RETENTION_MS)
        dao.markInterruptedMessagesStopped()
        dao.visibleSessions().map(::hydrate)
    }

    fun loadVisibleSummaries(): List<ChatSession> = synchronized(lock) {
        migrateLegacyLocked()
        dao.deleteExpiredSessions(System.currentTimeMillis() - DELETION_RETENTION_MS)
        dao.markInterruptedMessagesStopped()
        dao.visibleSessions().map { entity ->
            ChatSession(entity.id, entity.title, entity.archived, entity.deletedAtMs, entity.updatedAtMs, emptyList())
        }
    }

    fun searchVisibleSummaries(query: String): List<ChatSession> = synchronized(lock) {
        migrateLegacyLocked()
        dao.searchVisibleSessions(query.trim()).map { entity ->
            ChatSession(entity.id, entity.title, entity.archived, entity.deletedAtMs, entity.updatedAtMs, emptyList())
        }
    }

    fun getRecent(sessionId: String, limit: Int): SessionSlice? = synchronized(lock) {
        migrateLegacyLocked()
        dao.markInterruptedMessagesStopped()
        val entity = dao.visibleSession(sessionId) ?: return@synchronized null
        val safeLimit = limit.coerceIn(1, MAX_PAGE_MESSAGES)
        val messages = dao.recentMessages(sessionId, safeLimit).asReversed().map { it.toModel() }
        SessionSlice(
            session = ChatSession(entity.id, entity.title, entity.archived, entity.deletedAtMs, entity.updatedAtMs, messages),
            totalMessages = dao.messageCount(sessionId),
        )
    }

    fun get(sessionId: String): ChatSession? = synchronized(lock) {
        migrateLegacyLocked()
        dao.markInterruptedMessagesStopped()
        dao.visibleSession(sessionId)?.let(::hydrate)
    }

    fun upsert(session: ChatSession, durable: Boolean = true) = synchronized(lock) {
        migrateLegacyLocked()
        upsertLocked(session, incremental = !durable)
    }

    fun softDelete(sessionId: String, nowMs: Long) = synchronized(lock) {
        migrateLegacyLocked()
        dao.softDelete(sessionId, nowMs)
    }

    fun rename(sessionId: String, title: String, nowMs: Long) = synchronized(lock) {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "title-invalid" }
        migrateLegacyLocked()
        dao.rename(sessionId, normalized.take(MAX_TITLE_CHARS), nowMs)
    }

    fun archive(sessionId: String, archived: Boolean, nowMs: Long) = synchronized(lock) {
        migrateLegacyLocked()
        dao.archive(sessionId, archived, nowMs)
    }

    private fun migrateLegacyLocked() {
        if (preferences.getBoolean(KEY_ROOM_MIGRATED, false)) return
        val legacy = recoverInterrupted(decode(preferences.getString(KEY_SESSIONS, null)))
        legacy.forEach { upsertLocked(it, incremental = false) }
        // Preserve the old JSON as a rollback source. Only the marker changes.
        check(preferences.edit().putBoolean(KEY_ROOM_MIGRATED, true).commit()) { "Failed to mark Room migration" }
    }

    private fun upsertLocked(session: ChatSession, incremental: Boolean) {
        database.runInTransaction {
            dao.upsertSession(session.toEntity())
            if (incremental) {
                // A streaming delta can only modify the tail assistant message. Avoid reading and
                // comparing the complete conversation up to 20 times per second.
                session.messages.lastOrNull()?.let { message ->
                    dao.upsertMessages(listOf(message.toEntity(session.id, session.messages.lastIndex)))
                }
                return@runInTransaction
            }
            val desired = session.messages.mapIndexed { position, message -> message.toEntity(session.id, position) }
            val existing = dao.messages(session.id).associateBy { it.id }
            val changed = desired.filter { existing[it.id] != it }
            if (changed.isNotEmpty()) dao.upsertMessages(changed)
            val removed = existing.keys - desired.mapTo(mutableSetOf()) { it.id }
            if (removed.isNotEmpty()) dao.deleteMessages(removed.toList())
        }
    }

    private fun hydrate(entity: ChatSessionEntity): ChatSession = ChatSession(
        id = entity.id,
        title = entity.title,
        archived = entity.archived,
        deletedAtMs = entity.deletedAtMs,
        updatedAtMs = entity.updatedAtMs,
        messages = dao.messages(entity.id).map { it.toModel() },
    )

    private fun ChatSession.toEntity() = ChatSessionEntity(id, title, archived, deletedAtMs, updatedAtMs)

    private fun ChatMessage.toEntity(sessionId: String, position: Int) = ChatMessageEntity(
        id = id,
        sessionId = sessionId,
        position = position,
        role = role.wireName,
        content = content,
        status = status.wireName,
        createdAtMs = createdAtMs,
        attachmentsJson = encodeAttachments(attachments).toString(),
        citationsJson = JSONArray(citations).toString(),
    )

    private fun ChatMessageEntity.toModel() = ChatMessage(
        id = id,
        role = MessageRole.entries.first { it.wireName == role },
        content = content,
        status = MessageStatus.entries.first { it.wireName == status },
        createdAtMs = createdAtMs,
        attachments = decodeAttachments(attachmentsJson),
        citations = decodeCitations(citationsJson),
    )

    private fun encodeAttachments(attachments: List<ChatAttachment>) = JSONArray().apply {
        attachments.forEach { attachment ->
            put(JSONObject().apply {
                put("id", attachment.id)
                put("name", attachment.name)
                put("mimeType", attachment.mimeType)
                put("sizeBytes", attachment.sizeBytes)
                put("localPath", attachment.localPath)
                put("objectRef", attachment.objectRef)
            })
        }
    }

    private fun decodeAttachments(raw: String): List<ChatAttachment> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { index ->
            val item = array.getJSONObject(index)
            ChatAttachment(
                id = item.getString("id"),
                name = item.getString("name"),
                mimeType = item.getString("mimeType"),
                sizeBytes = item.getLong("sizeBytes"),
                localPath = item.optString("localPath"),
                objectRef = item.optString("objectRef"),
            )
        }
    }.getOrDefault(emptyList())

    private fun decodeCitations(raw: String): List<String> = runCatching {
        val array = JSONArray(raw)
        List(array.length()) { array.optString(it) }.filter { it.startsWith("https://") }
    }.getOrDefault(emptyList())

    companion object {
        private const val PREFERENCES_NAME = "talktoai_sessions_v1"
        private const val KEY_SESSIONS = "sessions"
        private const val KEY_ROOM_MIGRATED = "room_migrated_v1"
        private const val MAX_TITLE_CHARS = 48
        private const val MAX_PAGE_MESSAGES = 1_000
        private const val DELETION_RETENTION_MS = 7L * 24 * 60 * 60 * 1000

        internal fun encode(sessions: List<ChatSession>): JSONArray = JSONArray().apply {
            sessions.forEach { session ->
                put(JSONObject().apply {
                    put("id", session.id)
                    put("title", session.title)
                    put("archived", session.archived)
                    put("deletedAtMs", session.deletedAtMs ?: JSONObject.NULL)
                    put("updatedAtMs", session.updatedAtMs)
                    put("messages", JSONArray().apply {
                        session.messages.forEach { message ->
                            put(JSONObject().apply {
                                put("id", message.id)
                                put("role", message.role.wireName)
                                put("content", message.content)
                                put("status", message.status.wireName)
                                put("createdAtMs", message.createdAtMs)
                                put("attachments", JSONArray().apply {
                                    message.attachments.forEach { attachment ->
                                        put(JSONObject().apply {
                                            put("id", attachment.id)
                                            put("name", attachment.name)
                                            put("mimeType", attachment.mimeType)
                                            put("sizeBytes", attachment.sizeBytes)
                                            put("localPath", attachment.localPath)
                                            put("objectRef", attachment.objectRef)
                                        })
                                    }
                                })
                                put("citations", JSONArray(message.citations))
                            })
                        }
                    })
                })
            }
        }

        internal fun decode(raw: String?): List<ChatSession> {
            if (raw.isNullOrBlank()) return emptyList()
            return runCatching {
                val array = JSONArray(raw)
                buildList {
                    for (index in 0 until array.length()) {
                        val item = array.getJSONObject(index)
                        val messagesJson = item.optJSONArray("messages") ?: JSONArray()
                        val messages = buildList {
                            for (messageIndex in 0 until messagesJson.length()) {
                                val message = messagesJson.getJSONObject(messageIndex)
                                val attachmentsJson = message.optJSONArray("attachments") ?: JSONArray()
                                val attachments = buildList {
                                    for (attachmentIndex in 0 until attachmentsJson.length()) {
                                        val attachment = attachmentsJson.getJSONObject(attachmentIndex)
                                        add(ChatAttachment(
                                            id = attachment.getString("id"),
                                            name = attachment.getString("name"),
                                            mimeType = attachment.getString("mimeType"),
                                            sizeBytes = attachment.getLong("sizeBytes"),
                                            localPath = attachment.optString("localPath"),
                                            objectRef = attachment.optString("objectRef"),
                                        ))
                                    }
                                }
                                val citationsJson = message.optJSONArray("citations") ?: JSONArray()
                                val citations = buildList {
                                    for (citationIndex in 0 until citationsJson.length()) {
                                        citationsJson.optString(citationIndex).takeIf { it.startsWith("https://") }?.let(::add)
                                    }
                                }
                                add(ChatMessage(
                                    id = message.getString("id"),
                                    role = MessageRole.entries.first { it.wireName == message.getString("role") },
                                    content = message.getString("content"),
                                    status = MessageStatus.entries.first { it.wireName == message.getString("status") },
                                    createdAtMs = message.getLong("createdAtMs"),
                                    attachments = attachments,
                                    citations = citations,
                                ))
                            }
                        }
                        add(ChatSession(
                            id = item.getString("id"),
                            title = item.getString("title"),
                            archived = item.optBoolean("archived"),
                            deletedAtMs = item.optLong("deletedAtMs").takeIf { !item.isNull("deletedAtMs") },
                            updatedAtMs = item.getLong("updatedAtMs"),
                            messages = messages,
                        ))
                    }
                }
            }.getOrDefault(emptyList())
        }

        internal fun recoverInterrupted(sessions: List<ChatSession>): List<ChatSession> = sessions.map { session ->
            session.copy(messages = session.messages.map { message ->
                if (message.status == MessageStatus.STREAMING) message.copy(status = MessageStatus.STOPPED) else message
            })
        }
    }
}

data class SessionSlice(val session: ChatSession, val totalMessages: Int)
