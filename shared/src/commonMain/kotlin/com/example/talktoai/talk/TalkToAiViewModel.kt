package com.example.talktoai.talk

import com.example.talktoai.base.BridgeModule
import com.tencent.kuikly.core.module.CallbackRef
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.collection.ObservableList
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.reactive.handler.observableList
import com.tencent.kuikly.core.views.TextInputState
import com.talktoai.marketui.MarketBlockId
import com.talktoai.marketui.MarketDashboardAction
import com.talktoai.marketui.MarketDashboardContent
import com.talktoai.marketui.MarketDashboardPolicy
import com.talktoai.marketui.MarketDashboardState

internal class TalkToAiViewModel(
    private val bridge: BridgeModule,
    private val notify: NotifyModule,
) {
    var input: String by observable("")
    var inputState: TextInputState by observable(TextInputState(""))
    var transcript: String by observable("你好，我是 TalkToAI。可以询问 A 股行情与基础概念。")
    var messages: ObservableList<ChatMessageUi> by observableList()
    var renderedMessages: ObservableList<ChatMessageUi> by observableList()
    var status: String by observable("正在恢复会话…")
    var currentSessionId: String by observable("")
    var activeRequestId: String by observable("")
    var isGenerating: Boolean by observable(false)
    var online: Boolean by observable(true)
    var marketSummary: String by observable("点击下方周期加载 600000.SH 测试行情")
    var marketBars: List<MarketBarUi> by observable(emptyList())
    var marketDetailVisible: Boolean by observable(false)
    var marketSnapshot: MarketSnapshotUi? by observable(null)
    var marketLoading: Boolean by observable(false)
    var marketRequestState: MarketRequestUiState by observable(MarketRequestUiState.Loading)
    var marketDashboardState: MarketDashboardState by observable(MarketDashboardState())
    var marketPeriod: String by observable("day")
    var marketSymbol: String by observable("600000.SH")
    var marketFrom: String by observable("")
    var marketTo: String by observable("")
    var showDatePicker: Boolean by observable(false)
    var datePickerTarget: String by observable(DATE_TARGET_FROM)
    var pendingMarketDate: String by observable("")
    var themeMode: String by observable("system")
    var sessions: List<SessionRowUi> by observable(emptyList())
    var sidebarSessions: ObservableList<SessionRowUi> by observableList()
    var showSessionPanel: Boolean by observable(false)
    var showArchivedSessions: Boolean by observable(false)
    var sessionQuery: String by observable("")
    var renameDraft: String by observable("")
    var pendingAttachments: List<AttachmentUi> by observable(emptyList())
    var showToolsPanel: Boolean by observable(false)
    var pluginSummary: String by observable("正在读取插件状态…")
    var logSummary: String by observable("正在读取日志状态…")
    var sourceSummary: String by observable("来源：当前回答尚未提供外部引用")
    var activeTab: String by observable(TalkUiPolicy.TAB_CHAT)
    var showSidebar: Boolean by observable(false)
    var bubbleStyle: String by observable(TalkUiPolicy.BUBBLE_SOFT)
    var avatarStyle: String by observable(TalkUiPolicy.AVATAR_TEXT)
    var likedMessageIds: List<String> by observable(emptyList())
    var dislikedMessageIds: List<String> by observable(emptyList())
    var expandedSourceMessageIds: List<String> by observable(emptyList())
    var visibleChartTableKeys: List<String> by observable(emptyList())
    var selectedMessageId: String by observable("")
    var selectionToolbarTop: Float by observable(0f)
    var messageChartTypes: Map<String, String> by observable(emptyMap())
    var showSettingsPanel: Boolean by observable(false)
    var settingsSection: String by observable(TalkUiPolicy.SETTINGS_AI)
    var showInlineMarketCard: Boolean by observable(false)
    var inlineMarketSummary: String by observable("")
    var inlineMarketBars: List<MarketBarUi> by observable(emptyList())
    var inlineMarketSnapshot: MarketSnapshotUi? by observable(null)
    var inlineMarketAnchorMessageId: String by observable("")
    var messageWindowSize: Int by observable(DEFAULT_MESSAGE_WINDOW)
    var totalMessageCount: Int by observable(0)
    private var scrollRequestVersion: Int = 1
    private var consumedScrollRequestVersion: Int = 0
    private var marketRequestVersion: Int = 0
    private var lastMarketCustomRange: Boolean = false
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
            if (response?.optBoolean("ok", false) == true) {
                themeMode = TalkUiPolicy.normalizeTheme(response.optString("mode", "system"))
                bubbleStyle = TalkUiPolicy.normalizeBubbleStyle(response.optString("bubbleStyle"))
                avatarStyle = TalkUiPolicy.normalizeAvatarStyle(response.optString("avatarStyle"))
                if (response.optString("resumeDestination") == TalkUiPolicy.SETTINGS_APPEARANCE) {
                    settingsSection = TalkUiPolicy.SETTINGS_APPEARANCE
                    showSidebar = false
                    showSettingsPanel = true
                }
            }
        }
        bridge.callJsonRpc("talk.draft.get", null) { response ->
            if (response?.optBoolean("ok", false) == true) {
                input = response.optString("text")
                inputState = TextInputState(input)
            }
        }
    }

    fun destroy() {
        marketRequestVersion++
        notificationRef?.let { notify.removeNotify(CHAT_EVENT, it) }
        networkNotificationRef?.let { notify.removeNotify(NETWORK_EVENT, it) }
        notificationRef = null
        networkNotificationRef = null
    }

    fun marketDashboardContent(): MarketDashboardContent =
        AiMarketDashboardAdapter.fromMessages(messages.toList())

    fun dispatchMarketDashboardAction(action: MarketDashboardAction) {
        marketDashboardState = MarketDashboardPolicy.reduce(marketDashboardState, action)
        if (action is MarketDashboardAction.OpenIndex) {
            marketSymbol = action.symbol
            marketDetailVisible = true
            loadMarket("day")
        }
    }

    fun updateInputState(state: TextInputState) {
        val normalized = TalkUiPolicy.normalizeInputState(state)
        inputState = normalized
        input = normalized.text
        bridge.callJsonRpc("talk.draft.set", JSONObject().put("text", normalized.text), null)
    }

    fun send(): Boolean {
        val text = input.trim()
        if (text.isEmpty() || isGenerating) return false
        if (pendingAttachments.any { it.objectRef.isEmpty() }) {
            status = "请等待附件上传完成，或移除上传失败的附件"
            return false
        }
        input = ""
        inputState = TextInputState("")
        bridge.callJsonRpc("talk.draft.set", JSONObject().put("text", ""), null)
        if (TalkUiPolicy.isMarketIntent(text)) {
            showInlineMarketCard = true
            inlineMarketSummary = "正在通过只读行情工具获取数据…"
            inlineMarketBars = emptyList()
            inlineMarketSnapshot = null
            inlineMarketAnchorMessageId = ""
        }
        isGenerating = true
        status = if (online) "正在生成…" else "消息已保存，正在等待网络响应…"
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
        if (currentSessionId.isEmpty() || isGenerating) return
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

    fun copyMessage(messageId: String) {
        val message = messages.firstOrNull { it.id == messageId } ?: return
        bridge.copyToPasteboard(message.content)
        bridge.toast("已复制本条消息")
    }

    fun copySelectedText(messageId: String, parts: List<String>) {
        if (messageId != selectedMessageId) return
        val content = parts.joinToString(separator = "\n").trim()
        if (content.isEmpty()) {
            bridge.toast("请先选择文本")
            return
        }
        bridge.copyToPasteboard(content)
        selectedMessageId = ""
        bridge.toast("已复制选中文本")
    }

    fun toggleLike(messageId: String) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val liked = !messages[index].liked
        messages[index] = messages[index].copy(liked = liked, disliked = false)
        likedMessageIds = if (!liked) {
            likedMessageIds.filterNot { it == messageId }
        } else {
            likedMessageIds + messageId
        }
        dislikedMessageIds = dislikedMessageIds.filterNot { it == messageId }
        bridge.toast(if (liked) "已点赞" else "已取消点赞")
    }

    fun messageActionSelected(action: String, messageId: String): Boolean = when (action) {
        "message-like" -> messageId in likedMessageIds
        "message-dislike" -> messageId in dislikedMessageIds
        else -> false
    }

    fun messageActionTitle(action: String, messageId: String, fallback: String): String = when (action) {
        "message-like" -> if (messageActionSelected(action, messageId)) "已赞" else "点赞"
        "message-dislike" -> if (messageActionSelected(action, messageId)) "已踩" else "踩"
        else -> fallback
    }

    fun toggleDislike(messageId: String) {
        val index = messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val disliked = !messages[index].disliked
        messages[index] = messages[index].copy(disliked = disliked, liked = false)
        dislikedMessageIds = if (!disliked) {
            dislikedMessageIds.filterNot { it == messageId }
        } else {
            dislikedMessageIds + messageId
        }
        likedMessageIds = likedMessageIds.filterNot { it == messageId }
        bridge.toast(if (disliked) "已记录不满意" else "已取消反馈")
    }

    fun shareMessage(messageId: String) {
        val message = messages.firstOrNull { it.id == messageId } ?: return
        bridge.callJsonRpc(
            "talk.message.share",
            JSONObject().put("content", TalkUiPolicy.shareableContent(message.content, message.citations)),
        ) { response ->
            if (response?.optBoolean("ok", false) != true) status = "分享失败，请稍后重试"
        }
    }

    fun toggleSources(messageId: String) {
        expandedSourceMessageIds = if (messageId in expandedSourceMessageIds) {
            expandedSourceMessageIds.filterNot { it == messageId }
        } else {
            expandedSourceMessageIds + messageId
        }
    }

    fun sourcesExpanded(messageId: String): Boolean = messageId in expandedSourceMessageIds

    fun toggleChartTable(chartKey: String) {
        visibleChartTableKeys = if (chartKey in visibleChartTableKeys) {
            visibleChartTableKeys.filterNot { it == chartKey }
        } else {
            visibleChartTableKeys + chartKey
        }
    }

    fun chartTableVisible(chartKey: String): Boolean = chartKey in visibleChartTableKeys

    fun openSelection(messageId: String, selectionTop: Float, selectionHeight: Float, bubbleTop: Float = 0f) {
        selectedMessageId = messageId
        selectionToolbarTop = TalkUiPolicy.selectionToolbarBelow(selectionTop, selectionHeight, bubbleTop)
    }

    fun selectMessageChartType(messageId: String, chartType: String) {
        val normalized = TalkUiPolicy.normalizeChartType(chartType)
        messageChartTypes = messageChartTypes + (messageId to normalized)
    }

    fun messageChartType(messageId: String): String =
        TalkUiPolicy.normalizeChartType(messageChartTypes[messageId])

    fun consumeScrollToLatestRequest(version: Int): Boolean {
        if (version != scrollRequestVersion || version <= consumedScrollRequestVersion) return false
        consumedScrollRequestVersion = version
        return true
    }

    fun pendingScrollToLatestRequest(): Int? =
        scrollRequestVersion.takeIf { it > consumedScrollRequestVersion }

    private fun requestScrollToLatest() {
        scrollRequestVersion += 1
    }

    fun regenerate(messageId: String) {
        if (!TalkUiPolicy.canRegenerate(messages, messageId)) {
            status = "首版仅支持重新生成最新一条 AI 回答"
            return
        }
        retry()
    }

    fun selectTab(tab: String) {
        activeTab = if (tab == TalkUiPolicy.TAB_MARKET) TalkUiPolicy.TAB_MARKET else TalkUiPolicy.TAB_CHAT
        showSidebar = false
        showSettingsPanel = false
    }

    fun openSidebar() {
        refreshSessions()
        bridge.callJsonRpc("talk.draft.flush", null, null)
        selectedMessageId = ""
        showSessionPanel = false
        showToolsPanel = false
        showSettingsPanel = false
        showSidebar = true
    }

    fun closeSidebar() {
        showSidebar = false
    }

    fun selectBubbleStyle(style: String) {
        bubbleStyle = TalkUiPolicy.normalizeBubbleStyle(style)
        bridge.callJsonRpc("talk.appearance.set", JSONObject().put("bubbleStyle", bubbleStyle), null)
    }

    fun selectAvatarStyle(style: String) {
        avatarStyle = TalkUiPolicy.normalizeAvatarStyle(style)
        bridge.callJsonRpc("talk.appearance.set", JSONObject().put("avatarStyle", avatarStyle), null)
    }

    fun modelNotice() {
        status = "首版测试环境仅启用腾讯混元 hy3"
        showSidebar = false
    }

    fun selectModel() {
        bridge.callJsonRpc("talk.models.select", null, null)
    }

    fun marketNotice() {
        status = "首版仅支持 A 股，港股和美股将在 MarketDataProvider 扩展后接入"
        showSidebar = false
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

    fun selectTheme(mode: String) {
        themeMode = TalkUiPolicy.normalizeTheme(mode)
        bridge.callJsonRpc(
            "talk.theme.set",
            JSONObject().put("mode", themeMode).put("resumeDestination", TalkUiPolicy.SETTINGS_APPEARANCE),
            null,
        )
    }

    fun openSettings(section: String) {
        settingsSection = if (section == TalkUiPolicy.SETTINGS_APPEARANCE) {
            TalkUiPolicy.SETTINGS_APPEARANCE
        } else {
            TalkUiPolicy.SETTINGS_AI
        }
        showSidebar = false
        showSettingsPanel = true
    }

    fun closeSettings() {
        showSettingsPanel = false
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
        showSidebar = false
        activeTab = TalkUiPolicy.TAB_CHAT
        openSession(sessionId, DEFAULT_MESSAGE_WINDOW, closePanel = true)
    }

    private fun openSession(sessionId: String, limit: Int, closePanel: Boolean) {
        bridge.callJsonRpc(
            "talk.sessions.open",
            JSONObject().put("sessionId", sessionId).put("limit", limit),
        ) { response ->
            val session = parseWrappedJson(response)?.optJSONObject("session") ?: return@callJsonRpc
            applySession(session)
            renameDraft = session.optString("title")
            if (closePanel) showSessionPanel = false
        }
    }

    fun searchSessions(query: String) {
        sessionQuery = query
        val normalized = query.trim()
        bridge.callJsonRpc("talk.sessions.search", JSONObject().put("query", normalized)) { response ->
            if (sessionQuery.trim() == normalized) {
                parseWrappedJson(response)?.optJSONArray("sessions")?.let(::updateSessionRows)
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
        activeTab = TalkUiPolicy.TAB_CHAT
        requestScrollToLatest()
        currentSessionId = ""
        renameDraft = ""
        transcript = "新会话已建立。"
        messages.clear()
        renderedMessages.clear()
        messageWindowSize = DEFAULT_MESSAGE_WINDOW
        totalMessageCount = 0
        status = "就绪"
        isGenerating = false
        activeRequestId = ""
        pendingAttachments = emptyList()
        selectedMessageId = ""
        showInlineMarketCard = false
        inlineMarketSummary = ""
        inlineMarketBars = emptyList()
        inlineMarketSnapshot = null
        inlineMarketAnchorMessageId = ""
    }

    fun hasEarlierMessages(): Boolean = totalMessageCount > messages.size

    fun loadEarlierMessages() {
        if (currentSessionId.isEmpty()) return
        val nextLimit = (messages.size + MESSAGE_WINDOW_STEP).coerceAtMost(totalMessageCount)
        messageWindowSize = nextLimit
        openSession(currentSessionId, nextLimit, closePanel = false)
    }

    fun loadMarket(period: String, customRange: Boolean = false) {
        val symbol = marketSymbol.trim().uppercase()
        if (!Regex("^(?:[036]\\d{5})\\.(?:SH|SZ)$").matches(symbol)) {
            marketRequestVersion++
            marketLoading = false
            marketSummary = "请输入形如 600000.SH 或 000001.SZ 的 A 股代码"
            marketBars = emptyList()
            marketSnapshot = null
            marketRequestState = MarketRequestUiState.Error("INVALID_SYMBOL", marketSummary, retryable = false)
            return
        }
        if (customRange && (!TalkUiPolicy.isIsoDate(marketFrom) || !TalkUiPolicy.isIsoDate(marketTo) || marketFrom > marketTo)) {
            marketRequestVersion++
            marketLoading = false
            marketSummary = "自定义日期须为 YYYY-MM-DD，且起始日期不晚于结束日期"
            marketBars = emptyList()
            marketSnapshot = null
            marketRequestState = MarketRequestUiState.Error("INVALID_RANGE", marketSummary, retryable = false)
            return
        }
        marketSymbol = symbol
        marketPeriod = period
        lastMarketCustomRange = customRange
        marketSummary = "行情加载中…"
        marketLoading = true
        marketRequestState = MarketRequestUiState.Loading
        val requestVersion = ++marketRequestVersion
        bridge.callJsonRpc("talk.market.bars", JSONObject().apply {
            put("symbol", symbol)
            put("period", period)
            if (customRange) {
                put("from", marketFrom)
                put("to", marketTo)
            }
        }) { response ->
            if (requestVersion != marketRequestVersion) return@callJsonRpc
            val root = parseWrappedJson(response)
            if (root == null) {
                marketLoading = false
                marketSummary = response?.optString("message").orEmpty().ifEmpty { "行情加载失败" }
                marketBars = emptyList()
                marketSnapshot = null
                marketRequestState = MarketRequestUiState.Error(
                    code = response?.optString("error").orEmpty().ifEmpty { "MARKET_REQUEST_FAILED" },
                    message = marketSummary,
                    retryable = true,
                )
                return@callJsonRpc
            }
            val data = root.optJSONArray("data") ?: JSONArray()
            val parsedBars = runCatching {
                buildList {
                    for (index in 0 until data.length()) {
                        val bar = requireNotNull(data.optJSONObject(index))
                        add(MarketBarUi(
                            time = bar.optString("time"),
                            open = bar.optDouble("open", Double.NaN).toFloat(),
                            high = bar.optDouble("high", Double.NaN).toFloat(),
                            low = bar.optDouble("low", Double.NaN).toFloat(),
                            close = bar.optDouble("close", Double.NaN).toFloat(),
                            volume = bar.optDouble("volume", Double.NaN).toFloat(),
                        ).also {
                            require(it.time.isNotBlank())
                            require(listOf(it.open, it.high, it.low, it.close, it.volume).all(Float::isFinite))
                            require(it.volume >= 0f && it.low <= minOf(it.open, it.close) && it.high >= maxOf(it.open, it.close))
                        })
                    }
                }
            }.getOrElse {
                marketLoading = false
                marketSummary = "行情数据格式无效，请重试"
                marketBars = emptyList()
                marketSnapshot = null
                marketRequestState = MarketRequestUiState.Error("INVALID_MARKET_DATA", marketSummary, retryable = true)
                return@callJsonRpc
            }
            val cacheLabel = if (root.optBoolean("clientCacheHit")) " · 本地缓存" else ""
            marketLoading = false
            val source = root.optString("source")
            val origin = MarketOriginUi(
                source = source,
                marketTime = root.optString("marketTime"),
                fetchedAt = root.optString("fetchedAt"),
                freshness = root.optString("freshness"),
                clientCacheHit = root.optBoolean("clientCacheHit"),
                simulated = TalkUiPolicy.isFixtureMarketSource(source),
            )
            if (origin.simulated) {
                marketBars = emptyList()
                marketSnapshot = null
                marketSummary = "固定测试行情不在生产页面展示，请配置真实或延时行情源\n来源：$source"
                marketRequestState = MarketRequestUiState.Empty(origin)
                return@callJsonRpc
            }
            marketBars = parsedBars
            marketSnapshot = TalkUiPolicy.marketSnapshot(root, marketBars)
            marketSummary = "${root.optString("symbol")} · ${root.optString("freshness")}$cacheLabel\n" +
                "数据：${root.optString("marketTime")}\n来源：$source"
            marketRequestState = marketSnapshot?.let { MarketRequestUiState.Ready(it, parsedBars) }
                ?: MarketRequestUiState.Empty(origin)
        }
    }

    fun dispatchMarketAction(action: MarketUiAction) {
        when (action) {
            is MarketUiAction.Retry -> if (action.blockId == MarketBlockId.Breadth || action.blockId == MarketBlockId.Turnover) {
                loadMarket(marketPeriod, lastMarketCustomRange)
            }
        }
    }

    fun loadCustomMarket() = loadMarket("day", customRange = true)

    fun openDatePicker(target: String, fallbackDate: String) {
        datePickerTarget = if (target == DATE_TARGET_TO) DATE_TARGET_TO else DATE_TARGET_FROM
        pendingMarketDate = when (datePickerTarget) {
            DATE_TARGET_TO -> marketTo.takeIf(TalkUiPolicy::isIsoDate)
            else -> marketFrom.takeIf(TalkUiPolicy::isIsoDate)
        } ?: fallbackDate
        showDatePicker = true
    }

    fun updatePendingMarketDate(year: Int, month: Int, day: Int) {
        pendingMarketDate = TalkUiPolicy.formatIsoDate(year, month, day)
    }

    fun confirmMarketDate() {
        if (!TalkUiPolicy.isIsoDate(pendingMarketDate)) return
        if (datePickerTarget == DATE_TARGET_TO) {
            marketTo = pendingMarketDate
            if (marketFrom.isEmpty()) marketFrom = pendingMarketDate
        } else {
            marketFrom = pendingMarketDate
            if (marketTo.isEmpty()) marketTo = pendingMarketDate
        }
        showDatePicker = false
        loadCustomMarket()
    }

    fun closeDatePicker() {
        showDatePicker = false
    }

    private fun handleChatEvent(event: JSONObject) {
        requestScrollToLatest()
        val type = event.optString("type")
        event.optJSONObject("session")?.let(::applySession)
        when (type) {
            "market" -> applyInlineMarket(event.optJSONObject("market"))
            "delta" -> status = "正在生成…"
            "done" -> {
                isGenerating = false
                activeRequestId = ""
                status = "回答完成 · 仅供信息参考，不构成投资建议"
                if (showInlineMarketCard && inlineMarketBars.isEmpty() && inlineMarketSummary.startsWith("正在")) {
                    inlineMarketSummary = "行情工具未返回可用数据；以下文本回答不得视为实时行情"
                }
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

    private fun applyInlineMarket(root: JSONObject?) {
        if (root == null) {
            inlineMarketSummary = "行情工具返回格式错误，AI 将明确说明数据不可用"
            inlineMarketBars = emptyList()
            inlineMarketSnapshot = null
            return
        }
        val source = root.optString("source")
        if (TalkUiPolicy.isFixtureMarketSource(source)) {
            inlineMarketSummary = "固定测试行情不作为 AI 解读依据\n来源：$source"
            inlineMarketBars = emptyList()
            inlineMarketSnapshot = null
            return
        }
        val data = root.optJSONArray("data") ?: JSONArray()
        inlineMarketBars = buildList {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.let { bar ->
                    add(MarketBarUi(
                        time = bar.optString("time"),
                        open = bar.optDouble("open").toFloat(),
                        high = bar.optDouble("high").toFloat(),
                        low = bar.optDouble("low").toFloat(),
                        close = bar.optDouble("close").toFloat(),
                        volume = bar.optDouble("volume").toFloat(),
                    ))
                }
            }
        }
        inlineMarketSnapshot = TalkUiPolicy.marketSnapshot(root, inlineMarketBars)
        inlineMarketSummary = "${root.optString("symbol")} · ${root.optString("freshness")}\n" +
            "数据：${root.optString("marketTime")}\n来源：$source"
    }

    private fun applySession(session: JSONObject) {
        requestScrollToLatest()
        val incomingSessionId = session.optString("id")
        if (incomingSessionId != currentSessionId) messageWindowSize = DEFAULT_MESSAGE_WINDOW
        currentSessionId = incomingSessionId
        totalMessageCount = session.optInt("totalMessages", session.optJSONArray("messages")?.length() ?: 0)
        renameDraft = session.optString("title")
        val sessionMessages = session.optJSONArray("messages") ?: JSONArray()
        val parsedMessages = buildList {
            for (index in 0 until sessionMessages.length()) {
                val message = sessionMessages.optJSONObject(index) ?: continue
                val status = message.optString("status")
                val attachments = message.optJSONArray("attachments") ?: JSONArray()
                val citations = message.optJSONArray("citations") ?: JSONArray()
                add(
                    ChatMessageUi(
                        id = message.optString("id").ifEmpty { "message-$index" },
                        role = message.optString("role"),
                        content = TalkUiPolicy.displayContent(message.optString("content"), status),
                        status = status,
                        attachmentNames = buildList {
                            for (attachmentIndex in 0 until attachments.length()) {
                                attachments.optJSONObject(attachmentIndex)?.optString("name")?.takeIf { it.isNotEmpty() }?.let(::add)
                            }
                        },
                        citations = buildList {
                            for (citationIndex in 0 until citations.length()) {
                                citations.optString(citationIndex)?.takeIf { it.isNotEmpty() }?.let(::add)
                            }
                        },
                        liked = message.optString("id") in likedMessageIds,
                        disliked = message.optString("id") in dislikedMessageIds,
                        marketDataJson = message.optString("marketDataJson"),
                    ),
                )
            }
        }
        // Keep unchanged rows and native charts alive while only the streaming
        // answer changes. Clearing the list rebuilt the entire conversation.
        while (messages.size > parsedMessages.size) messages.removeAt(messages.lastIndex)
        parsedMessages.forEachIndexed { index, message ->
            if (index >= messages.size) messages.add(message)
            else if (messages[index] != message) messages[index] = message
        }
        if (showInlineMarketCard && inlineMarketAnchorMessageId.isEmpty()) {
            inlineMarketAnchorMessageId = parsedMessages.lastOrNull { it.role == "user" }?.id.orEmpty()
        }
        syncRenderedMessages()
        transcript = buildString {
            for (index in 0 until sessionMessages.length()) {
                val message = sessionMessages.optJSONObject(index) ?: continue
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
        val last = sessionMessages.optJSONObject(sessionMessages.length() - 1)
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
            "complete" -> status = "会话已恢复 · 仅供信息参考，不构成投资建议"
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

    fun refreshSidebar(onComplete: () -> Unit) {
        loadToolStatus()
        bridge.callJsonRpc("talk.sessions.load", null) { response ->
            val rows = parseWrappedJson(response)?.optJSONArray("sessions")
            if (rows != null) updateSessionRows(rows)
            bridge.toast(if (rows != null) "会话已刷新" else "刷新失败，请重试")
            onComplete()
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
                    append("• ").append(item.optString("name")).append(" · ")
                        .append(TalkUiPolicy.pluginStatusLabel(item.optString("status")))
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
        sidebarSessions.clear()
        sidebarSessions.addAll(sessions)
    }

    private fun firstSession(array: JSONArray, archived: Boolean): JSONObject? {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            if (item.optBoolean("archived") == archived) return item
        }
        return null
    }

    private fun syncRenderedMessages() {
        val desired = messages.takeLast(messageWindowSize)
        while (renderedMessages.size > desired.size) renderedMessages.removeAt(renderedMessages.lastIndex)
        desired.forEachIndexed { index, message ->
            if (index >= renderedMessages.size) renderedMessages.add(message)
            else if (renderedMessages[index] != message) renderedMessages[index] = message
        }
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
        private const val DEFAULT_MESSAGE_WINDOW = 100
        private const val MESSAGE_WINDOW_STEP = 100
        private const val SELECTION_TOOLBAR_GAP = 6f
        const val DATE_TARGET_FROM = "from"
        const val DATE_TARGET_TO = "to"
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
    val time: String,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Float,
)

internal data class MarketSnapshotUi(
    val symbol: String,
    val freshness: String,
    val marketTime: String,
    val fetchedAt: String,
    val source: String,
    val clientCacheHit: Boolean,
    val open: Float,
    val high: Float,
    val low: Float,
    val close: Float,
    val volume: Float,
    val change: Float,
    val changePercent: Float?,
)

internal data class SessionRowUi(
    val id: String,
    val title: String,
    val archived: Boolean,
    val updatedAtMs: Long,
)

internal data class ChatMessageUi(
    val id: String,
    val role: String,
    val content: String,
    val status: String,
    val attachmentNames: List<String>,
    val citations: List<String>,
    val liked: Boolean = false,
    val disliked: Boolean = false,
    val marketDataJson: String = "",
)

internal data class MarkdownBlockUi(
    val kind: String,
    val text: String,
)

internal data class ChartSeriesUi(
    val name: String,
    val values: List<Float>,
)

internal data class ChartDataUi(
    val title: String,
    val labels: List<String>,
    val xLabel: String,
    val yLabel: String,
    val series: List<ChartSeriesUi>,
) {
    fun toJson(): String = JSONObject().apply {
        put("title", title)
        put("labels", JSONArray().apply { labels.forEach(::put) })
        put("xLabel", xLabel)
        put("yLabel", yLabel)
        put("series", JSONArray().apply {
            series.forEach { item ->
                put(JSONObject().apply {
                    put("name", item.name)
                    put("values", JSONArray().apply { item.values.forEach(::put) })
                })
            }
        })
    }.toString()
}

internal object TalkUiPolicy {
    fun isFixtureMarketSource(source: String): Boolean =
        source.contains("测试固定数据") || source.contains("测试夹具")

    fun shouldRenderDerivedCharts(role: String, status: String): Boolean =
        role != "user" && status != "streaming"

    private const val SELECTION_GAP_DP = 12f // Clear the native selection handles below the final line.
    fun selectionToolbarBelow(top: Float, height: Float, bubbleTop: Float): Float =
        (bubbleTop.coerceAtLeast(0f) + top.coerceAtLeast(0f) + height.coerceAtLeast(0f) + SELECTION_GAP_DP)

    // Kuikly defers out-of-range offsets. Leave 2 dp for native pixel rounding.
    private const val SCROLL_RANGE_ROUNDING_INSET_DP = 2f

    fun latestMessageOffset(contentHeight: Float, viewportHeight: Float): Float {
        if (!contentHeight.isFinite() || !viewportHeight.isFinite() || viewportHeight <= 0f) return 0f
        return (contentHeight - viewportHeight - SCROLL_RANGE_ROUNDING_INSET_DP).coerceAtLeast(0f)
    }

    const val TAB_CHAT = "chat"
    const val TAB_MARKET = "market"
    const val BUBBLE_SOFT = "soft"
    const val BUBBLE_OUTLINE = "outline"
    const val BUBBLE_COMPACT = "compact"
    const val AVATAR_TEXT = "text"
    const val AVATAR_ROUND = "round"
    const val AVATAR_MINIMAL = "minimal"
    const val SETTINGS_AI = "ai"
    const val SETTINGS_APPEARANCE = "appearance"
    const val MARKDOWN_HEADING = "heading"
    const val MARKDOWN_BULLET = "bullet"
    const val MARKDOWN_CODE = "code"
    const val MARKDOWN_PARAGRAPH = "paragraph"
    const val CHART_LINE = "line"
    const val CHART_BAR = "bar"
    const val CHART_PIE = "pie"

    fun displayContent(content: String, status: String): String = content.ifEmpty {
        when (status) {
            "streaming" -> "▋"
            "failed" -> "生成失败，可点击重新生成"
            "stopped" -> "生成已停止"
            else -> ""
        }
    }

    fun nextBubbleStyle(current: String): String = when (current) {
        BUBBLE_SOFT -> BUBBLE_OUTLINE
        BUBBLE_OUTLINE -> BUBBLE_COMPACT
        else -> BUBBLE_SOFT
    }

    fun normalizeBubbleStyle(style: String): String = when (style) {
        BUBBLE_OUTLINE, BUBBLE_COMPACT -> style
        else -> BUBBLE_SOFT
    }

    fun bubbleStyleLabel(style: String): String = when (style) {
        BUBBLE_OUTLINE -> "描边"
        BUBBLE_COMPACT -> "紧凑"
        else -> "柔和"
    }

    fun nextAvatarStyle(current: String): String = when (current) {
        AVATAR_TEXT -> AVATAR_ROUND
        AVATAR_ROUND -> AVATAR_MINIMAL
        else -> AVATAR_TEXT
    }

    fun normalizeAvatarStyle(style: String): String = when (style) {
        AVATAR_ROUND, AVATAR_MINIMAL -> style
        else -> AVATAR_TEXT
    }

    fun normalizeTheme(mode: String): String = when (mode) {
        "light", "dark" -> mode
        else -> "system"
    }

    fun pluginStatusLabel(status: String): String = when (status) {
        "active" -> "可用"
        "development_only" -> "开发数据"
        "test_fixture" -> "测试数据"
        "configuration_required" -> "未配置"
        "unavailable" -> "不可用"
        else -> "状态未知"
    }

    fun freshnessLabel(freshness: String): String = when (freshness.uppercase()) {
        "FRESH" -> "新鲜"
        "DELAYED" -> "延时"
        "STALE" -> "已过期"
        else -> "待确认"
    }

    fun marketSnapshot(root: JSONObject, bars: List<MarketBarUi>): MarketSnapshotUi? {
        val latest = bars.lastOrNull() ?: return null
        val previousClose = bars.dropLast(1).lastOrNull()?.close
        val change = previousClose?.let { latest.close - it } ?: (latest.close - latest.open)
        val percent = previousClose?.takeIf { it != 0f }?.let { change / it * 100f }
        return MarketSnapshotUi(
            symbol = root.optString("symbol"),
            freshness = root.optString("freshness"),
            marketTime = root.optString("marketTime"),
            fetchedAt = root.optString("fetchedAt"),
            source = root.optString("source"),
            clientCacheHit = root.optBoolean("clientCacheHit"),
            open = latest.open,
            high = latest.high,
            low = latest.low,
            close = latest.close,
            volume = latest.volume,
            change = change,
            changePercent = percent,
        )
    }

    fun shareableContent(content: String, citations: List<String>): String = buildString {
        append(content.trim())
        if (citations.isNotEmpty()) {
            append("\n\n来源：\n")
            append(citations.joinToString("\n"))
        }
        append("\n\n仅供信息参考，不构成投资建议。")
    }

    fun chartTableRows(chart: ChartDataUi, limit: Int = 8): List<List<String>> {
        if (limit <= 0) return emptyList()
        return chart.labels.take(limit).mapIndexed { rowIndex, label ->
            buildList {
                add(label)
                chart.series.forEach { series ->
                    add(formatChartValue(series.values.getOrNull(rowIndex)))
                }
            }
        }
    }

    fun formatQuote(value: Float): String = ((value * 100f).toInt() / 100f).toString()

    fun formatSigned(value: Float): String = (if (value >= 0f) "+" else "") + formatQuote(value)

    fun formatPercent(value: Float?): String = value?.let { formatSigned(it) + "%" } ?: "—"

    fun formatVolume(value: Float): String = when {
        value >= 100_000_000f -> formatQuote(value / 100_000_000f) + "亿"
        value >= 10_000f -> formatQuote(value / 10_000f) + "万"
        else -> value.toInt().toString()
    }

    fun formatTimestamp(value: String): String = value.trim()
        .replace("T", " ")
        .replace(Regex("\\.\\d{1,6}Z$"), " UTC")
        .replace(Regex("Z$"), " UTC")

    fun normalizeInputState(state: TextInputState): TextInputState = state.coerceToTextBounds()

    fun isIsoDate(value: String): Boolean {
        val match = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(value) ?: return false
        val year = match.groupValues[1].toInt()
        val month = match.groupValues[2].toInt()
        val day = match.groupValues[3].toInt()
        if (year !in 1970..9999 || month !in 1..12) return false
        val february = if (year % 400 == 0 || year % 4 == 0 && year % 100 != 0) 29 else 28
        val days = intArrayOf(31, february, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return day in 1..days[month - 1]
    }

    fun formatIsoDate(year: Int, month: Int, day: Int): String =
        year.toString().padStart(4, '0') + "-" +
            month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')

    fun axisTimeLabel(value: String, preferTime: Boolean = false): String {
        val trimmed = value.trim()
        val timeMatch = Regex("(?:T|\\s)(\\d{2}:\\d{2})").find(trimmed)
        if (preferTime && timeMatch != null) return timeMatch.groupValues[1]
        val dateMatch = Regex("\\d{4}-(\\d{2})-(\\d{2})").find(trimmed)
        return dateMatch?.let { "${it.groupValues[1]}-${it.groupValues[2]}" } ?: trimmed.take(8)
    }

    fun isMarketIntent(content: String): Boolean {
        val normalized = content.replace(" ", "")
        return Regex("(大盘|行情|走势|指数|K线|成交量|A股)", RegexOption.IGNORE_CASE).containsMatchIn(normalized) ||
            Regex("(今日数据|今天数据)").containsMatchIn(normalized) ||
            Regex("(今日|今天).*(市场|盘面|涨跌)").containsMatchIn(normalized) ||
            Regex("(市场|盘面|涨跌).*(今日|今天)").containsMatchIn(normalized) ||
            Regex("(todaymarket|markettoday|a-?share|stockindex|kline)", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
    }

    fun markdownBlocks(content: String): List<MarkdownBlockUi> {
        if (content.isBlank()) return emptyList()
        val blocks = mutableListOf<MarkdownBlockUi>()
        val paragraph = mutableListOf<String>()
        val code = mutableListOf<String>()
        var inCode = false
        fun flushParagraph() {
            if (paragraph.isNotEmpty()) {
                blocks += MarkdownBlockUi(MARKDOWN_PARAGRAPH, stripInlineMarkdown(paragraph.joinToString("\n")))
                paragraph.clear()
            }
        }
        fun flushCode() {
            if (code.isNotEmpty()) {
                blocks += MarkdownBlockUi(MARKDOWN_CODE, code.joinToString("\n"))
                code.clear()
            }
        }
        val lines = content.lines()
        val tableLines = markdownTableRanges(lines).flatMap { it.toList() }.toSet()
        lines.forEachIndexed { lineIndex, rawLine ->
            if (lineIndex in tableLines) {
                flushParagraph()
                return@forEachIndexed
            }
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) flushCode() else flushParagraph()
                inCode = !inCode
            } else if (inCode) {
                code += line
            } else if (line.isBlank()) {
                flushParagraph()
            } else {
                val heading = Regex("^#{1,3}\\s+(.+)$").find(line)
                val bullet = Regex("^(?:[-*]|\\d+[.)])\\s+(.+)$").find(line)
                when {
                    heading != null -> {
                        flushParagraph()
                        blocks += MarkdownBlockUi(MARKDOWN_HEADING, stripInlineMarkdown(heading.groupValues[1]))
                    }
                    bullet != null -> {
                        flushParagraph()
                        blocks += MarkdownBlockUi(MARKDOWN_BULLET, "• ${stripInlineMarkdown(bullet.groupValues[1])}")
                    }
                    else -> paragraph += line
                }
            }
        }
        if (inCode) flushCode() else flushParagraph()
        return blocks
    }

    fun chartData(content: String): List<ChartDataUi> {
        val lines = content.lines()
        return markdownTableRanges(lines).mapNotNull { range ->
            val header = tableCells(lines[range.first])
            val rows = (range.first + 2..range.last).map { tableCells(lines[it]) }.filter { it.size == header.size }
            if (header.size < 2 || rows.size < 2) return@mapNotNull null
            val numericColumns = header.indices.filter { column -> rows.all { parseChartNumber(it[column]) != null } }
            if (numericColumns.isEmpty()) return@mapNotNull null
            val labelColumn = header.indices.firstOrNull { it !in numericColumns } ?: 0
            val valueColumns = numericColumns.filterNot { it == labelColumn }
            if (valueColumns.isEmpty()) return@mapNotNull null
            val titleLine = (range.first - 1 downTo 0)
                .firstOrNull { lines[it].isNotBlank() && !lines[it].contains('|') }
                ?.let { stripInlineMarkdown(lines[it].replace(HEADING_PREFIX, "").trim()) }
                .orEmpty()
                .ifEmpty { "数据图表" }
            ChartDataUi(
                title = titleLine,
                labels = rows.map { stripInlineMarkdown(it[labelColumn]).take(MAX_CHART_LABEL_CHARS) },
                xLabel = header[labelColumn].ifBlank { "类别" },
                yLabel = valueColumns.joinToString(" / ") { header[it] }.ifBlank { "数值" },
                series = valueColumns.map { column ->
                    ChartSeriesUi(header[column].ifBlank { "数值" }, rows.map { parseChartNumber(it[column])!! })
                },
            )
        }
    }

    fun contentWithoutChartTables(content: String): String {
        val lines = content.lines()
        val chartTableLines = markdownTableRanges(lines).filter { range ->
            val header = tableCells(lines[range.first])
            val rows = (range.first + 2..range.last).map { tableCells(lines[it]) }.filter { it.size == header.size }
            if (header.size < 2 || rows.size < 2) return@filter false
            val numericColumns = header.indices.filter { column -> rows.all { parseChartNumber(it[column]) != null } }
            val labelColumn = header.indices.firstOrNull { it !in numericColumns } ?: 0
            numericColumns.any { it != labelColumn }
        }.flatMap { it.toList() }.toSet()
        return lines.filterIndexed { index, _ -> index !in chartTableLines }
            .joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    fun normalizeChartType(type: String?): String = when (type) {
        CHART_BAR, CHART_PIE -> type
        else -> CHART_LINE
    }

    fun canUsePie(chart: ChartDataUi): Boolean = chart.series.size == 1 &&
        chart.series.single().values.all { it >= 0f } &&
        chart.series.single().values.any { it > 0f }

    private fun markdownTableRanges(lines: List<String>): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        var index = 0
        while (index + 1 < lines.size) {
            if (lines[index].contains('|') && TABLE_SEPARATOR.matches(lines[index + 1].trim())) {
                var end = index + 2
                while (end < lines.size && lines[end].contains('|') && lines[end].isNotBlank()) end++
                if (end - index >= 4) ranges += index until end
                index = end
            } else {
                index++
            }
        }
        return ranges
    }

    private fun tableCells(line: String): List<String> = line.trim().trim('|').split('|').map(String::trim)

    private fun parseChartNumber(raw: String): Float? {
        val normalized = stripInlineMarkdown(raw)
            .replace(",", "")
            .replace("%", "")
            .replace("¥", "")
            .replace("￥", "")
            .trim()
        return normalized.toFloatOrNull()
    }

    private fun formatChartValue(value: Float?): String = when {
        value == null -> "—"
        value % 1f == 0f -> value.toInt().toString()
        else -> ((value * 100f).toInt() / 100f).toString()
    }

    private fun stripInlineMarkdown(content: String): String = content
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
        .replace(Regex("__(.+?)__"), "$1")
        .replace(Regex("`([^`]+)`"), "$1")

    fun avatarStyleLabel(style: String): String = when (style) {
        AVATAR_ROUND -> "圆形"
        AVATAR_MINIMAL -> "极简"
        else -> "文字"
    }

    fun canRegenerate(messages: List<ChatMessageUi>, messageId: String): Boolean =
        messages.lastOrNull()?.let { it.id == messageId && it.role == "assistant" } == true

    private val TABLE_SEPARATOR = Regex("^\\|?\\s*:?-{3,}:?\\s*(?:\\|\\s*:?-{3,}:?\\s*)+\\|?$")
    private val HEADING_PREFIX = Regex("^#{1,6}\\s*")
    private const val MAX_CHART_LABEL_CHARS = 16
}
