package com.example.talktoai.chat

import android.content.Context
import okhttp3.Call
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ChatCoordinator(
    context: Context,
    private val api: TalkToAiApi = TalkToAiApi(),
    private val store: SessionStore = SessionStore(context),
    identity: InstallationIdentity = InstallationIdentity(context),
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val emit: (JSONObject) -> Unit,
) {
    private val installationId = identity.get()
    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun start(sessionId: String?, text: String, attachments: List<ChatAttachment> = emptyList()): String {
        val trimmed = text.trim()
        require(trimmed.isNotEmpty()) { "消息不能为空" }
        val resolvedSessionId = sessionId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val now = nowMs()
        val existing = store.get(resolvedSessionId)
        require(attachments.size <= AttachmentStore.MAX_ATTACHMENTS) { "附件最多${AttachmentStore.MAX_ATTACHMENTS}个" }
        val userMessage = ChatMessage(
            UUID.randomUUID().toString(), MessageRole.USER, trimmed, MessageStatus.COMPLETE, now, attachments,
        )
        val session = existing?.copy(
            updatedAtMs = now,
            messages = existing.messages + userMessage,
        ) ?: ChatSession(
            id = resolvedSessionId,
            title = trimmed.take(DEFAULT_TITLE_CHARS),
            archived = false,
            deletedAtMs = null,
            updatedAtMs = now,
            messages = listOf(userMessage),
        )
        return generate(session)
    }

    fun retry(sessionId: String): String {
        val existing = store.get(sessionId) ?: throw IllegalArgumentException("session-not-found")
        val lastUser = existing.messages.indexOfLast { it.role == MessageRole.USER }
        require(lastUser >= 0) { "user-message-not-found" }
        val trimmed = existing.copy(
            updatedAtMs = nowMs(),
            messages = existing.messages.take(lastUser + 1),
        )
        return generate(trimmed)
    }

    private fun generate(baseSession: ChatSession): String {
        val requestId = UUID.randomUUID().toString()
        val now = nowMs()
        val assistantMessage = ChatMessage(requestId, MessageRole.ASSISTANT, "", MessageStatus.STREAMING, now)
        var session = baseSession.copy(updatedAtMs = now, messages = baseSession.messages + assistantMessage)
        store.upsert(session)
        emit(snapshotEvent("session", session, requestId))
        val currentAttachments = baseSession.messages.lastOrNull { it.role == MessageRole.USER }?.attachments.orEmpty()

        val call = api.streamChat(installationId, session.id, session.messages, currentAttachments, object : TalkToAiApi.StreamListener {
            override fun onEvent(event: StreamEvent) {
                val data = runCatching { JSONObject(event.data) }.getOrElse { JSONObject() }
                when (event.type) {
                    "delta" -> {
                        val delta = data.optString("text")
                        session = updateAssistant(session, requestId) { message ->
                            message.copy(content = message.content + delta, status = MessageStatus.STREAMING)
                        }
                        store.upsert(session)
                        emit(snapshotEvent("delta", session, requestId))
                    }
                    "done" -> {
                        session = updateAssistant(session, requestId) { it.copy(status = MessageStatus.COMPLETE) }
                        store.upsert(session)
                        activeCalls.remove(requestId)
                        emit(snapshotEvent("done", session, requestId).put("quota", data.optJSONObject("quota")))
                    }
                    "error" -> fail(session, requestId, data.optString("code"), data.optString("message"), data.optBoolean("retryable", true))
                    "meta" -> emit(JSONObject().put("type", "meta").put("requestId", requestId).put("data", data))
                    "citation" -> {
                        val url = data.optString("url")
                        if (url.startsWith("https://")) {
                            session = updateAssistant(session, requestId) { message ->
                                message.copy(citations = (message.citations + url).distinct())
                            }
                            store.upsert(session)
                        }
                        emit(snapshotEvent("citation", session, requestId).put("citation", data))
                    }
                }
            }

            override fun onFailure(code: String, message: String, retryable: Boolean) {
                fail(session, requestId, code, message, retryable)
            }
        })
        activeCalls[requestId] = call
        return requestId
    }

    fun stop(requestId: String) {
        activeCalls.remove(requestId)?.cancel()
        val session = store.loadVisible().firstOrNull { candidate -> candidate.messages.any { it.id == requestId } } ?: return
        val stopped = updateAssistant(session, requestId) { it.copy(status = MessageStatus.STOPPED) }
        store.upsert(stopped)
        emit(snapshotEvent("stopped", stopped, requestId))
    }

    fun sessionsJson(): JSONObject = JSONObject().put("sessions", JSONArray().apply {
        store.loadVisible().sortedByDescending { it.updatedAtMs }.forEach { put(sessionJson(it)) }
    })

    fun rename(sessionId: String, title: String) = store.rename(sessionId, title, nowMs())
    fun archive(sessionId: String, archived: Boolean) = store.archive(sessionId, archived, nowMs())
    fun delete(sessionId: String) = store.softDelete(sessionId, nowMs())
    fun exportMarkdown(sessionId: String): String {
        val session = store.get(sessionId) ?: throw IllegalArgumentException("session-not-found")
        return buildString {
            append("# ").append(session.title).append("\n\n")
            append("> TalkToAI A股只读咨询；仅供信息参考，不构成投资建议。\n\n")
            session.messages.forEach { message ->
                append("## ").append(if (message.role == MessageRole.USER) "用户" else "TalkToAI").append("\n\n")
                append(message.content.ifBlank { "（无正文，状态：${message.status.wireName}）" }).append("\n\n")
                message.attachments.forEach { attachment ->
                    append("- 附件：").append(attachment.name).append(" (").append(attachment.mimeType).append(")\n")
                }
                if (message.attachments.isNotEmpty()) append("\n")
                message.citations.forEach { citation -> append("- 来源：").append(citation).append("\n") }
                if (message.citations.isNotEmpty()) append("\n")
            }
        }
    }
    fun cancelAll() = activeCalls.values.forEach(Call::cancel).also { activeCalls.clear() }

    private fun fail(session: ChatSession, requestId: String, code: String, message: String, retryable: Boolean) {
        activeCalls.remove(requestId)
        val failed = updateAssistant(session, requestId) { it.copy(status = MessageStatus.FAILED) }
        store.upsert(failed)
        emit(snapshotEvent("error", failed, requestId)
            .put("error", JSONObject().put("code", code).put("message", message).put("retryable", retryable)))
    }

    private fun updateAssistant(session: ChatSession, requestId: String, transform: (ChatMessage) -> ChatMessage): ChatSession =
        session.copy(updatedAtMs = nowMs(), messages = session.messages.map { if (it.id == requestId) transform(it) else it })

    private fun snapshotEvent(type: String, session: ChatSession, requestId: String): JSONObject = JSONObject()
        .put("type", type)
        .put("requestId", requestId)
        .put("session", sessionJson(session))

    private fun sessionJson(session: ChatSession): JSONObject = JSONObject().apply {
        put("id", session.id)
        put("title", session.title)
        put("archived", session.archived)
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
                                put("objectRef", attachment.objectRef)
                            })
                        }
                    })
                    put("citations", JSONArray(message.citations))
                })
            }
        })
    }

    companion object {
        private const val DEFAULT_TITLE_CHARS = 20
    }
}
