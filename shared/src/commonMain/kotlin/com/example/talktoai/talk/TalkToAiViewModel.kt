package com.example.talktoai.talk

import com.example.talktoai.base.BridgeModule
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable

internal class TalkToAiViewModel(
    private val bridge: BridgeModule,
    private val notify: NotifyModule,
) {
    var input: String by observable("")
    var transcript: String by observable("你好，我是 TalkToAI。可以询问 A 股行情与基础概念。")
    var status: String by observable("正在恢复会话…")
    var currentSessionId: String by observable("")
    var activeRequestId: String by observable("")
    var isGenerating: Boolean by observable(false)
    var online: Boolean by observable(true)
    var marketSummary: String by observable("点击下方周期加载 600000.SH 测试行情")
    var marketBars: List<MarketBarUi> by observable(emptyList())
    var marketPeriod: String by observable("day")
    var marketSymbol: String by observable("600000.SH")
    var marketFrom: String by observable("")
    var marketTo: String by observable("")
    var themeMode: String by observable("system")
    var sessions: List<SessionRowUi> by observable(emptyList())
    var showSessionPanel: Boolean by observable(false)
    var showArchivedSessions: Boolean by observable(false)
    var renameDraft: String by observable("")
    var pendingAttachments: List<AttachmentUi> by observable(emptyList())
    var showToolsPanel: Boolean by observable(false)
    var pluginSummary: String by observable("正在读取插件状态…")
    var logSummary: String by observable("正在读取日志状态…")
    var sourceSummary: String by observable("来源：当前回答尚未提供外部引用")
    private var notificationRef: CallbackRef? = null
    private var networkNotificationRef: CallbackRef? = null

    fun start() {
        notificationRef = notify.addNotify(CHAT_EVENT) { event ->
            event?.let(::handleChatEvent)
        }
        networkNotificationRef = notify.addNotify(NETWORK_EVENT) { event ->
            val wasOnline = online
            online = event?.optBoolean("online", false) == true
            if (!online) status = "无网络：历史会话仍可查看"
            else if (!wasOnline) status = "网络已恢复"
        }
        bridge.callJsonRpc("talk.network.status", null) { response ->
            online = response?.optBoolean("online", false) == true
            if (!online) status = "无网络：历史会话仍可查看"
        }
        bridge.callJsonRpc("talk.sessions.load", null) { response ->
            val root = parseWrappedJson(response)
            val sessions = root?.optJSONArray("sessions") ?: JSONArray()
            updateSessionRows(sessions)
            if (sessions.length() > 0) {
                val session = firstSession(sessions, archived = false) ?: sessions.optJSONObject(0)
                if (session != null) applySession(session)
            } else if (online) {
                status = "就绪"
            }
        }
        bridge.callJsonRpc("talk.theme.get", null) { response ->
            if (response?.optBoolean("ok", false) == true) themeMode = response.optString("mode", "system")
        }
        bridge.callJsonRpc("talk.draft.get", null) { response ->
            if (response?.optBoolean("ok", false) == true) input = response.optString("text")
        }
    }

    fun destroy() {
        notificationRef?.let { notify.removeNotify(CHAT_EVENT, it) }
        networkNotificationRef?.let { notify.removeNotify(NETWORK_EVENT, it) }
        notificationRef = null
        networkNotificationRef = null
    }

    fun updateInput(text: String) {
        input = text
        bridge.callJsonRpc("talk.draft.set", JSONObject().put("text", text), null)
    }

    fun send(): Boolean {
        val text = input.trim()
        if (text.isEmpty() || isGenerating) return false
        if (pendingAttachments.any { it.objectRef.isEmpty() }) {
            status = "请等待附件上传完成，或移除上传失败的附件"
            return false
        }
        if (!online) {
            status = "无网络，消息未发送"
            return false
        }
        input = ""
        bridge.callJsonRpc("talk.draft.set", JSONObject().put("text", ""), null)
        isGenerating = true
        status = "正在生成…"
        sourceSummary = "来源：等待回答引用…"
        bridge.callJsonRpc("talk.chat.start", JSONObject().apply {
            put("sessionId", currentSessionId)
            put("text", text)
            put("attachments", JSONArray().apply {
                pendingAttachments.forEach { attachment ->
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
        }) { response ->
            if (response?.optBoolean("ok", false) != true) {
                isGenerating = false
                status = response?.optString("message").orEmpty().ifEmpty { "发送失败，可重试" }
            } else {
                activeRequestId = response.optString("requestId")
                pendingAttachments = emptyList()
            }
        }
        return true
    }

    fun pickAttachment() {
        if (pendingAttachments.size >= MAX_ATTACHMENTS) {
            status = "每条消息最多 $MAX_ATTACHMENTS 个附件"
            return
        }
        bridge.callJsonRpc("talk.attachments.pick", null) { response ->
            if (response?.optBoolean("ok", false) != true) {
                if (response?.optString("error") != "ATTACHMENT_PICK_CANCELLED") {
                    status = attachmentError(response?.optString("error").orEmpty())
                }
                return@callJsonRpc
            }
            val attachment = AttachmentUi(
                id = response.optString("id"),
                name = response.optString("name"),
                mimeType = response.optString("mimeType"),
                sizeBytes = response.optLong("sizeBytes"),
                localPath = response.optString("localPath"),
                objectRef = response.optString("objectRef"),
                uploadState = "uploading",
            )
            pendingAttachments = (pendingAttachments.filterNot { it.id == attachment.id } + attachment).take(MAX_ATTACHMENTS)
            status = "附件上传中…"
            uploadAttachment(attachment)
        }
    }

    private fun uploadAttachment(attachment: AttachmentUi) {
        bridge.callJsonRpc("talk.attachments.upload", JSONObject().apply {
            put("id", attachment.id)
            put("name", attachment.name)
            put("mimeType", attachment.mimeType)
            put("sizeBytes", attachment.sizeBytes)
            put("localPath", attachment.localPath)
        }) { response ->
            val uploaded = parseWrappedJson(response)
            if (uploaded == null) {
                pendingAttachments = pendingAttachments.map {
                    if (it.id == attachment.id) it.copy(uploadState = "failed") else it
                }
                status = response?.optString("message").orEmpty().ifEmpty { "附件上传失败，可移除后继续" }
            } else {
                pendingAttachments = pendingAttachments.map {
                    if (it.id == attachment.id) it.copy(
                        objectRef = uploaded.optString("objectRef"),
                        uploadState = "ready",
                    ) else it
                }
                status = "附件已上传并就绪"
            }
        }
    }

    fun removeAttachment(id: String) {
        pendingAttachments = pendingAttachments.filterNot { it.id == id }
    }

    fun stop() {
        if (!isGenerating || activeRequestId.isEmpty()) return
        bridge.callJsonRpc("talk.chat.stop", JSONObject().put("requestId", activeRequestId), null)
        isGenerating = false
        status = "已停止生成"
    }

    fun retry() {
        if (currentSessionId.isEmpty() || isGenerating || !online) return
        isGenerating = true
        status = "正在重试…"
        bridge.callJsonRpc("talk.chat.retry", JSONObject().put("sessionId", currentSessionId)) { response ->
            if (response?.optBoolean("ok", false) == true) {
                activeRequestId = response.optString("requestId")
            } else {
                isGenerating = false
                status = response?.optString("message").orEmpty().ifEmpty { "当前会话无法重试" }
            }
        }
    }

    fun copyTranscript() {
        bridge.copyToPasteboard(transcript)
        bridge.toast("已复制对话")
    }

    fun cycleTheme() {
        val next = when (themeMode) {
            "system" -> "light"
            "light" -> "dark"
            else -> "system"
        }
        themeMode = next
        bridge.callJsonRpc("talk.theme.set", JSONObject().put("mode", next), null)
    }

    fun themeLabel(): String = when (themeMode) {
        "light" -> "主题·浅色"
        "dark" -> "主题·深色"
        else -> "主题·系统"
    }

    fun toggleSessionPanel() {
        showSessionPanel = !showSessionPanel
        if (showSessionPanel) refreshSessions()
    }

    fun toggleToolsPanel() {
        showToolsPanel = !showToolsPanel
        if (showToolsPanel) loadToolStatus()
    }

    fun exportFeedback() {
        bridge.callJsonRpc("talk.feedback.export", null) { response ->
            if (response?.optBoolean("ok", false) != true) status = "反馈包导出失败"
        }
    }

    fun visibleSessions(): List<SessionRowUi> = sessions.filter { it.archived == showArchivedSessions }

    fun openSession(sessionId: String) {
        bridge.callJsonRpc("talk.sessions.load", null) { response ->
            val array = parseWrappedJson(response)?.optJSONArray("sessions") ?: return@callJsonRpc
            updateSessionRows(array)
            for (index in 0 until array.length()) {
                val candidate = array.optJSONObject(index) ?: continue
                if (candidate.optString("id") == sessionId) {
                    applySession(candidate)
                    renameDraft = candidate.optString("title")
                    showSessionPanel = false
                    return@callJsonRpc
                }
            }
        }
    }

    fun renameCurrent() {
        val title = renameDraft.trim()
        if (currentSessionId.isEmpty() || title.isEmpty()) {
            status = "会话名称不能为空"
            return
        }
        mutateCurrentSession("talk.sessions.rename", JSONObject().put("sessionId", currentSessionId).put("title", title))
    }

    fun archiveCurrent() {
        if (currentSessionId.isEmpty()) return
        bridge.showAlert("归档会话", "归档会从主列表隐藏会话，但不会删除历史。", "取消", "归档") { result ->
            if (result?.optInt("index", 0) == 1) {
                mutateCurrentSession(
                    "talk.sessions.archive",
                    JSONObject().put("sessionId", currentSessionId).put("archived", true),
                )
                newSession()
            }
        }
    }

    fun deleteCurrent() {
        if (currentSessionId.isEmpty()) return
        bridge.showAlert("删除会话", "会话将保留在本机回收状态 7 天，之后自动清理。", "取消", "删除") { result ->
            if (result?.optInt("index", 0) == 1) {
                mutateCurrentSession("talk.sessions.delete", JSONObject().put("sessionId", currentSessionId))
                newSession()
            }
        }
    }

    fun exportCurrent() {
        if (currentSessionId.isEmpty()) {
            status = "当前没有可导出的会话"
            return
        }
        bridge.callJsonRpc("talk.sessions.export", JSONObject().put("sessionId", currentSessionId)) { response ->
            if (response?.optBoolean("ok", false) != true) status = "会话导出失败"
        }
    }

    fun restoreArchived(sessionId: String) {
        bridge.callJsonRpc(
            "talk.sessions.archive",
            JSONObject().put("sessionId", sessionId).put("archived", false),
        ) {
            refreshSessions()
        }
    }

    fun newSession() {
        currentSessionId = ""
        renameDraft = ""
        transcript = "新会话已建立。"
        status = "就绪"
        isGenerating = false
        activeRequestId = ""
        pendingAttachments = emptyList()
    }

    fun loadMarket(period: String, customRange: Boolean = false) {
        val symbol = marketSymbol.trim().uppercase()
        if (!Regex("^(?:[036]\\d{5})\\.(?:SH|SZ)$").matches(symbol)) {
            marketSummary = "请输入形如 600000.SH 或 000001.SZ 的 A 股代码"
            return
        }
        if (customRange && (!isIsoDate(marketFrom) || !isIsoDate(marketTo) || marketFrom > marketTo)) {
            marketSummary = "自定义日期须为 YYYY-MM-DD，且起始日期不晚于结束日期"
            return
        }
        marketSymbol = symbol
        marketPeriod = period
        marketSummary = "行情加载中…"
        bridge.callJsonRpc("talk.market.bars", JSONObject().apply {
            put("symbol", symbol)
            put("period", period)
            if (customRange) {
                put("from", marketFrom)
                put("to", marketTo)
            }
        }) { response ->
            val root = parseWrappedJson(response)
            if (root == null) {
                marketSummary = response?.optString("message").orEmpty().ifEmpty { "行情加载失败" }
                marketBars = emptyList()
                return@callJsonRpc
            }
            val data = root.optJSONArray("data") ?: JSONArray()
            marketBars = buildList {
                for (index in 0 until data.length()) {
                    data.optJSONObject(index)?.let { bar ->
                        add(MarketBarUi(
                            open = bar.optDouble("open").toFloat(),
                            high = bar.optDouble("high").toFloat(),
                            low = bar.optDouble("low").toFloat(),
                            close = bar.optDouble("close").toFloat(),
                            volume = bar.optDouble("volume").toFloat(),
                        ))
                    }
                }
            }
            marketSummary = "${root.optString("symbol")} · ${root.optString("freshness")}\n" +
                "数据：${root.optString("marketTime")}\n来源：${root.optString("source")}"
        }
    }

    fun loadCustomMarket() = loadMarket("day", customRange = true)

    private fun isIsoDate(value: String): Boolean = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value)

    private fun handleChatEvent(event: JSONObject) {
        val type = event.optString("type")
        event.optJSONObject("session")?.let(::applySession)
        when (type) {
            "delta" -> status = "正在生成…"
            "done" -> {
                isGenerating = false
                activeRequestId = ""
                status = "回答完成 · 仅供信息参考，不构成投资建议"
                if (sourceSummary == "来源：等待回答引用…") sourceSummary = "来源：模型未提供可核验的 HTTPS 引用"
            }
            "stopped" -> {
                isGenerating = false
                activeRequestId = ""
                status = "已停止生成"
            }
            "error" -> {
                isGenerating = false
                activeRequestId = ""
                val error = event.optJSONObject("error")
                status = error?.optString("message").orEmpty().ifEmpty { "生成失败，可重试" }
                sourceSummary = "来源：生成失败，未产生可核验引用"
            }
            "citation" -> {
                val citation = event.optJSONObject("citation")
                val url = citation?.optString("url").orEmpty()
                if (url.isNotEmpty()) {
                    sourceSummary = if (sourceSummary.startsWith("来源：等待")) "来源：$url" else "$sourceSummary\n$url"
                }
            }
        }
    }

    private fun applySession(session: JSONObject) {
        currentSessionId = session.optString("id")
        renameDraft = session.optString("title")
        val messages = session.optJSONArray("messages") ?: JSONArray()
        transcript = buildString {
            for (index in 0 until messages.length()) {
                val message = messages.optJSONObject(index) ?: continue
                if (isNotEmpty()) append("\n\n")
                append(if (message.optString("role") == "user") "我：" else "TalkToAI：")
                val messageStatus = message.optString("status")
                append(message.optString("content").ifEmpty {
                    when (messageStatus) {
                        "streaming" -> "▋"
                        "failed" -> "生成失败，可点击重试"
                        "stopped" -> "生成已停止"
                        else -> ""
                    }
                })
                val attachments = message.optJSONArray("attachments") ?: JSONArray()
                for (attachmentIndex in 0 until attachments.length()) {
                    val attachment = attachments.optJSONObject(attachmentIndex) ?: continue
                    append("\n  📎 ").append(attachment.optString("name"))
                }
            }
        }
        val last = messages.optJSONObject(messages.length() - 1)
        val lastStatus = last?.optString("status").orEmpty()
        val restoredCitations = last?.optJSONArray("citations")
        if (restoredCitations != null && restoredCitations.length() > 0) {
            sourceSummary = buildString {
                append("来源：")
                for (index in 0 until restoredCitations.length()) {
                    if (index > 0) append("\n")
                    append(restoredCitations.optString(index))
                }
            }
        }
        isGenerating = lastStatus == "streaming"
        if (isGenerating) activeRequestId = last?.optString("id").orEmpty()
        when (lastStatus) {
            "failed" -> {
                status = "上次生成失败，可重试"
                sourceSummary = "来源：生成失败，未产生可核验引用"
            }
            "stopped" -> {
                status = "上次生成已停止"
                sourceSummary = "来源：生成停止前未记录引用"
            }
        }
    }

    private fun parseWrappedJson(response: JSONObject?): JSONObject? {
        if (response?.optBoolean("ok", false) != true) return null
        return runCatching { JSONObject(response.optString("json")) }.getOrNull()
    }

    private fun refreshSessions() {
        bridge.callJsonRpc("talk.sessions.load", null) { response ->
            parseWrappedJson(response)?.optJSONArray("sessions")?.let(::updateSessionRows)
        }
    }

    private fun loadToolStatus() {
        bridge.callJsonRpc("talk.plugins.status", null) { response ->
            val plugins = parseWrappedJson(response)?.optJSONArray("plugins")
            pluginSummary = if (plugins == null) {
                "插件状态读取失败"
            } else buildString {
                for (index in 0 until plugins.length()) {
                    val item = plugins.optJSONObject(index) ?: continue
                    if (isNotEmpty()) append("\n")
                    append("• ").append(item.optString("name")).append(" · ").append(item.optString("status"))
                    append("\n  ").append(item.optString("detail"))
                }
            }
        }
        bridge.callJsonRpc("talk.logs.summary", null) { response ->
            val summary = parseWrappedJson(response)
            logSummary = if (summary == null) "日志状态读取失败" else
                "本机 ${summary.optInt("eventCount")} 条 · ${summary.optInt("retentionDays")} 天留存 · 每日上限 ${summary.optInt("dailyLimit")}\n" +
                    "标识符：${summary.optString("redaction")}"
        }
    }

    private fun mutateCurrentSession(method: String, payload: JSONObject) {
        bridge.callJsonRpc(method, payload) { response ->
            val root = parseWrappedJson(response)
            if (root == null) {
                status = response?.optString("message").orEmpty().ifEmpty { "会话操作失败" }
            } else {
                updateSessionRows(root.optJSONArray("sessions") ?: JSONArray())
                status = "会话已更新"
            }
        }
    }

    private fun updateSessionRows(array: JSONArray) {
        sessions = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                add(
                    SessionRowUi(
                        id = item.optString("id"),
                        title = item.optString("title").ifEmpty { "未命名会话" },
                        archived = item.optBoolean("archived"),
                        updatedAtMs = item.optLong("updatedAtMs"),
                    ),
                )
            }
        }
    }

    private fun firstSession(array: JSONArray, archived: Boolean): JSONObject? {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optBoolean("archived") == archived) return item
        }
        return null
    }

    private fun attachmentError(code: String): String = when (code) {
        "ATTACHMENT_TYPE_UNSUPPORTED" -> "仅支持图片、CSV 和 TXT"
        "ATTACHMENT_TOO_LARGE", "ATTACHMENT_SIZE_INVALID" -> "图片最大 10MB，CSV/TXT 最大 2MB"
        "ATTACHMENT_EMPTY" -> "附件内容为空"
        else -> "附件读取失败"
    }

    companion object {
        private const val CHAT_EVENT = "talk.chat.event"
        private const val NETWORK_EVENT = "talk.network.event"
        private const val MAX_ATTACHMENTS = 5
    }
}

internal data class AttachmentUi(
    val id: String,
    val name: String,
    val mimeType: String,
    val sizeBytes: Long,
    val localPath: String,
    val objectRef: String,
    val uploadState: String,
)

internal data class MarketBarUi(
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Float,
)

internal data class SessionRowUi(
    val id: String,
    val title: String,
    val archived: Boolean,
    val updatedAtMs: Long,
)
