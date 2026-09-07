package com.example.talktoai.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class SessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun loadVisible(): List<ChatSession> = synchronized(lock) {
        purgeExpiredLocked(System.currentTimeMillis())
        val decoded = decode(preferences.getString(KEY_SESSIONS, null))
        val recovered = recoverInterrupted(decoded)
        if (recovered != decoded) persist(recovered)
        recovered.filter { it.deletedAtMs == null }
    }

    fun get(sessionId: String): ChatSession? = synchronized(lock) {
        decode(preferences.getString(KEY_SESSIONS, null)).firstOrNull { it.id == sessionId && it.deletedAtMs == null }
    }

    fun upsert(session: ChatSession, durable: Boolean = true) = synchronized(lock) {
        val sessions = decode(preferences.getString(KEY_SESSIONS, null)).toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session else sessions.add(session)
        persist(sessions, durable)
    }

    fun softDelete(sessionId: String, nowMs: Long) = updateSession(sessionId) { it.copy(deletedAtMs = nowMs, updatedAtMs = nowMs) }

    fun rename(sessionId: String, title: String, nowMs: Long) = updateSession(sessionId) {
        val normalized = title.trim()
        require(normalized.isNotEmpty()) { "title-invalid" }
        it.copy(title = normalized.take(MAX_TITLE_CHARS), updatedAtMs = nowMs)
    }

    fun archive(sessionId: String, archived: Boolean, nowMs: Long) = updateSession(sessionId) {
        it.copy(archived = archived, updatedAtMs = nowMs)
    }

    private fun updateSession(sessionId: String, transform: (ChatSession) -> ChatSession) = synchronized(lock) {
        val sessions = decode(preferences.getString(KEY_SESSIONS, null)).toMutableList()
        val index = sessions.indexOfFirst { it.id == sessionId }
        if (index >= 0) {
            sessions[index] = transform(sessions[index])
            persist(sessions)
        }
    }

    private fun purgeExpiredLocked(nowMs: Long) {
        val sessions = decode(preferences.getString(KEY_SESSIONS, null))
        val retained = sessions.filter { session ->
            session.deletedAtMs?.let { nowMs - it < DELETION_RETENTION_MS } ?: true
        }
        if (retained.size != sessions.size) persist(retained)
    }

    private fun persist(sessions: List<ChatSession>, durable: Boolean = true) {
        val editor = preferences.edit().putString(KEY_SESSIONS, encode(sessions).toString())
        if (durable) {
            check(editor.commit()) { "Failed to persist chat sessions" }
        } else {
            // Streaming updates are frequent; apply() updates the in-memory value immediately and lets
            // SharedPreferences coalesce disk writes. The terminal event always performs a durable commit.
            editor.apply()
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "talktoai_sessions_v1"
        private const val KEY_SESSIONS = "sessions"
        private const val MAX_TITLE_CHARS = 48
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
