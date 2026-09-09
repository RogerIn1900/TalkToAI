package com.example.talktoai.module

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Network
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.content.Intent
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.example.talktoai.chat.ChatCoordinator
import com.example.talktoai.chat.TalkToAiApi
import com.example.talktoai.ThemeMode
import com.example.talktoai.ThemePreferences
import com.example.talktoai.KuiklyRenderActivity
import com.example.talktoai.chat.ChatAttachment
import com.example.talktoai.chat.InstallationIdentity
import com.tencent.kuikly.core.render.android.expand.module.sendKuiklyEvent
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import com.example.talktoai.diagnostics.DiagnosticLogStore
import com.example.talktoai.market.MarketBarsCache

class KRBridgeModule : KuiklyRenderBaseModule() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val storageExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "talktoai-storage").apply { isDaemon = true }
    }
    private val api = TalkToAiApi()
    private var coordinator: ChatCoordinator? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingDraft: String? = null
    private val draftWriteRunnable = Runnable { flushDraft() }
    private val diagnostics: DiagnosticLogStore by lazy {
        DiagnosticLogStore(requireNotNull(context?.applicationContext))
    }
    private val marketCache: MarketBarsCache by lazy {
        MarketBarsCache(requireNotNull(context?.applicationContext))
    }

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? = when (method) {
        "toast" -> toast(params)
        "copyToPasteboard" -> copyToPasteboard(params)
        "showAlert" -> showAlert(params, callback)
        "talk.sessions.load" -> runStorage(callback) { chatCoordinator().sessionsJson() }
        "talk.sessions.search" -> runStorage(callback) {
            chatCoordinator().sessionsJson(JSONObject(params ?: "{}").optString("query"))
        }
        "talk.sessions.open" -> runStorage(callback) {
            val json = JSONObject(params ?: "{}")
            chatCoordinator().openSessionJson(json.requireString("sessionId"), json.optInt("limit", 100))
        }
        "talk.sessions.rename" -> mutateSession(params, callback) { c, p -> c.rename(p.requireString("sessionId"), p.requireString("title")) }
        "talk.sessions.archive" -> mutateSession(params, callback) { c, p -> c.archive(p.requireString("sessionId"), p.optBoolean("archived", true)) }
        "talk.sessions.delete" -> mutateSession(params, callback) { c, p -> c.delete(p.requireString("sessionId")) }
        "talk.sessions.export" -> exportSession(params, callback)
        "talk.attachments.pick" -> pickAttachment(callback)
        "talk.models.select" -> showModelPicker(callback)
        "talk.attachments.upload" -> uploadAttachment(params, callback)
        "talk.chat.start" -> startChat(params, callback)
        "talk.chat.retry" -> retryChat(params, callback)
        "talk.chat.stop" -> stopChat(params, callback)
        "talk.market.bars" -> marketBars(params, callback)
        "talk.network.status" -> networkStatus(callback)
        "talk.draft.get" -> callback?.invoke(mapOf("ok" to true, "text" to draftPreferences().getString(DRAFT_KEY, "").orEmpty()))
        "talk.draft.set" -> saveDraft(params, callback)
        "talk.draft.flush" -> flushDraft().also { callback?.invoke(mapOf("ok" to true)) }
        "talk.theme.get" -> getAppearance(callback)
        "talk.theme.set" -> setTheme(params, callback)
        "talk.appearance.set" -> setAppearance(params, callback)
        "talk.plugins.status" -> pluginStatus(callback)
        "talk.logs.summary" -> callbackJson(callback, diagnostics.summary())
        "talk.feedback.export" -> exportFeedback(callback)
        "talk.message.share" -> shareMessage(params, callback)
        else -> callback?.invoke(mapOf("ok" to false, "error" to "METHOD_NOT_FOUND"))
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(draftWriteRunnable)
        flushDraft()
        coordinator?.cancelAll()
        storageExecutor.shutdown()
        networkCallback?.let { callback ->
            (context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                ?.runCatching { unregisterNetworkCallback(callback) }
        }
        networkCallback = null
        super.onDestroy()
    }

    private fun chatCoordinator(): ChatCoordinator {
        coordinator?.let { return it }
        val appContext = requireNotNull(context?.applicationContext) { "Kuikly module context is unavailable" }
        return ChatCoordinator(appContext) { event ->
            diagnostics.record(
                level = if (event.optString("type") == "error") "error" else "info",
                event = "chat_${event.optString("type", "event")}",
                identifiers = mapOf(
                    "requestId" to event.optString("requestId"),
                    "sessionId" to event.optJSONObject("session")?.optString("id").orEmpty(),
                ),
            )
            mainHandler.post { appContext.sendKuiklyEvent(CHAT_EVENT, event) }
        }.also { coordinator = it }
    }

    private fun startChat(params: String?, callback: KuiklyRenderCallback?) {
        storageExecutor.execute { runCatching {
            val json = JSONObject(params ?: "{}")
            val attachmentsJson = json.optJSONArray("attachments")
            val attachments = buildList {
                if (attachmentsJson != null) {
                    for (index in 0 until attachmentsJson.length()) {
                        val item = attachmentsJson.getJSONObject(index)
                        add(ChatAttachment(
                            id = item.requireString("id"),
                            name = item.requireString("name"),
                            mimeType = item.requireString("mimeType"),
                            sizeBytes = item.getLong("sizeBytes"),
                            localPath = item.optString("localPath"),
                            objectRef = item.requireString("objectRef"),
                        ))
                    }
                }
            }
            val requestId = chatCoordinator().start(
                json.optString("sessionId").takeIf(String::isNotBlank), json.requireString("text"), attachments,
            )
            mainHandler.post { callback?.invoke(mapOf("ok" to true, "requestId" to requestId)) }
        }.onFailure { error ->
            mainHandler.post { callback?.invoke(mapOf("ok" to false, "error" to "INVALID_ARGUMENT", "message" to (error.message ?: "参数错误"))) }
        } }
    }

    private fun pickAttachment(callback: KuiklyRenderCallback?) {
        val activity = context as? KuiklyRenderActivity ?: run {
            callback?.invoke(mapOf("ok" to false, "error" to "ATTACHMENT_PICK_UNAVAILABLE"))
            return
        }
        activity.pickAttachment { result ->
            result.onSuccess { attachment ->
                callback?.invoke(mapOf(
                    "ok" to true,
                    "id" to attachment.id,
                    "name" to attachment.name,
                    "mimeType" to attachment.mimeType,
                    "sizeBytes" to attachment.sizeBytes,
                    "localPath" to attachment.localPath,
                    "objectRef" to attachment.objectRef,
                ))
            }.onFailure { error ->
                val code = error.message.orEmpty().ifEmpty { "ATTACHMENT_IMPORT_FAILED" }
                callback?.invoke(mapOf("ok" to false, "error" to code))
            }
        }
    }

    private fun showModelPicker(callback: KuiklyRenderCallback?) {
        val activity = context as? KuiklyRenderActivity ?: return
        android.app.AlertDialog.Builder(activity)
            .setTitle("选择模型（当前环境开放 1 个）")
            .setSingleChoiceItems(arrayOf("腾讯混元 hy3"), 0) { dialog, _ ->
                callback?.invoke(mapOf("ok" to true, "model" to "hy3"))
                dialog.dismiss()
            }
            .setNegativeButton("返回") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun uploadAttachment(params: String?, callback: KuiklyRenderCallback?) {
        runCatching {
            val item = JSONObject(params ?: "{}")
            ChatAttachment(
                id = item.requireString("id"),
                name = item.requireString("name"),
                mimeType = item.requireString("mimeType"),
                sizeBytes = item.getLong("sizeBytes"),
                localPath = item.requireString("localPath"),
            )
        }.onSuccess { attachment ->
            val appContext = requireNotNull(context?.applicationContext)
            api.uploadAttachment(InstallationIdentity(appContext).get(), attachment, object : TalkToAiApi.JsonListener {
                override fun onSuccess(json: JSONObject) {
                    diagnostics.record(
                        "info", "attachment_upload_success",
                        identifiers = mapOf("attachmentId" to attachment.id),
                        attributes = mapOf("mimeType" to attachment.mimeType, "sizeBytes" to attachment.sizeBytes),
                    )
                    mainHandler.post { callbackJson(callback, json) }
                }

                override fun onFailure(code: String, message: String, retryable: Boolean) {
                    diagnostics.record(
                        "warn", "attachment_upload_failed",
                        identifiers = mapOf("attachmentId" to attachment.id),
                        attributes = mapOf("code" to code),
                    )
                    mainHandler.post {
                        callback?.invoke(mapOf("ok" to false, "error" to code, "message" to message, "retryable" to retryable))
                    }
                }
            })
        }.onFailure {
            callback?.invoke(mapOf("ok" to false, "error" to "INVALID_ARGUMENT", "message" to "附件参数无效"))
        }
    }

    private fun stopChat(params: String?, callback: KuiklyRenderCallback?) {
        val requestId = JSONObject(params ?: "{}").optString("requestId")
        storageExecutor.execute {
            chatCoordinator().stop(requestId)
            mainHandler.post { callback?.invoke(mapOf("ok" to true)) }
        }
    }

    private fun retryChat(params: String?, callback: KuiklyRenderCallback?) {
        storageExecutor.execute { runCatching {
            chatCoordinator().retry(JSONObject(params ?: "{}").requireString("sessionId"))
        }.onSuccess { requestId -> mainHandler.post { callback?.invoke(mapOf("ok" to true, "requestId" to requestId)) } }
            .onFailure { mainHandler.post { callback?.invoke(mapOf("ok" to false, "error" to "RETRY_UNAVAILABLE", "message" to "没有可重试的用户消息")) } }
        }
    }

    private fun mutateSession(
        params: String?,
        callback: KuiklyRenderCallback?,
        action: (ChatCoordinator, JSONObject) -> Unit,
    ) {
        storageExecutor.execute { runCatching { action(chatCoordinator(), JSONObject(params ?: "{}")) }
            .onSuccess {
                val sessions = chatCoordinator().sessionsJson()
                mainHandler.post { callbackJson(callback, sessions) }
            }
            .onFailure { mainHandler.post { callback?.invoke(mapOf("ok" to false, "error" to "INVALID_ARGUMENT")) } }
        }
    }

    private fun marketBars(params: String?, callback: KuiklyRenderCallback?) {
        val json = JSONObject(params ?: "{}")
        val symbol = json.optString("symbol", "600000.SH")
        val period = json.optString("period", "day")
        val from = json.optString("from").takeIf(String::isNotBlank)
        val to = json.optString("to").takeIf(String::isNotBlank)
        marketCache.get(symbol, period, from, to)?.let { cached ->
            diagnostics.record("info", "market_bars_cache_hit", attributes = mapOf("period" to period))
            callbackJson(callback, cached)
            return
        }
        api.getMarketBars(
            symbol,
            period,
            from,
            to,
            object : TalkToAiApi.JsonListener {
            override fun onSuccess(json: JSONObject) {
                marketCache.put(symbol, period, from, to, json)
                diagnostics.record("info", "market_bars_success", attributes = mapOf("period" to json.optString("period", "unknown")))
                mainHandler.post { callbackJson(callback, json) }
            }

            override fun onFailure(code: String, message: String, retryable: Boolean) {
                diagnostics.record("warn", "market_bars_failed", attributes = mapOf("code" to code, "retryable" to retryable))
                mainHandler.post { callback?.invoke(mapOf("ok" to false, "error" to code, "message" to message, "retryable" to retryable)) }
            }
            },
        )
    }

    private fun pluginStatus(callback: KuiklyRenderCallback?) {
        api.getHealth(object : TalkToAiApi.JsonListener {
            override fun onSuccess(json: JSONObject) {
                val capabilities = json.optJSONObject("capabilities") ?: JSONObject()
                val marketProvider = capabilities.optString("marketProvider", "unknown")
                val attachmentCapability = capabilities.optString("attachments", "configuration_required")
                val plugins = JSONObject().put("plugins", org.json.JSONArray().apply {
                    val marketProviders = capabilities.optJSONArray("marketProviders")
                    if (marketProviders != null && marketProviders.length() > 0) {
                        for (index in 0 until marketProviders.length()) {
                            val provider = marketProviders.optJSONObject(index) ?: continue
                            put(JSONObject()
                                .put("id", "market-data-${provider.optString("id", index.toString())}")
                                .put("name", provider.optString("name", "A股数据源"))
                                .put("status", provider.optString("status", "configuration_required"))
                                .put("detail", provider.optString("detail", "开发数据源 · 只读")))
                        }
                    } else {
                        val marketStatus = when {
                            !capabilities.optBoolean("marketReady") -> "unavailable"
                            marketProvider == "fixture" -> "test_fixture"
                            else -> "development_only"
                        }
                        put(JSONObject().put("id", "market-data").put("name", "A股行情")
                            .put("status", marketStatus)
                            .put("detail", "$marketProvider · 只读"))
                    }
                    put(JSONObject().put("id", "ai-chat").put("name", "腾讯云 AI")
                        .put("status", if (capabilities.optBoolean("aiReady")) "active" else "configuration_required")
                        .put("detail", if (capabilities.optBoolean("aiReady")) "流式服务可用" else "服务端凭证待配置"))
                    put(JSONObject().put("id", "attachments").put("name", "附件").put("status", attachmentCapability)
                        .put("detail", "图片/CSV/TXT 已本地预检；云端存储与内容解析状态见上方"))
                })
                mainHandler.post { callbackJson(callback, plugins) }
            }

            override fun onFailure(code: String, message: String, retryable: Boolean) {
                mainHandler.post { callback?.invoke(mapOf("ok" to false, "error" to code, "message" to message)) }
            }
        })
    }

    private fun exportFeedback(callback: KuiklyRenderCallback?) {
        runCatching {
            diagnostics.record("info", "feedback_exported")
            val target = diagnostics.createFeedbackPackage()
            val ctx = requireNotNull(context)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", target)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ctx.startActivity(Intent.createChooser(share, "导出问题反馈包"))
            callback?.invoke(mapOf("ok" to true))
        }.onFailure {
            callback?.invoke(mapOf("ok" to false, "error" to "FEEDBACK_EXPORT_FAILED"))
        }
    }

    private fun callbackJson(callback: KuiklyRenderCallback?, json: JSONObject) {
        callback?.invoke(mapOf("ok" to true, "json" to json.toString()))
    }

    private fun runStorage(callback: KuiklyRenderCallback?, block: () -> JSONObject) {
        storageExecutor.execute {
            runCatching(block)
                .onSuccess { json -> mainHandler.post { callbackJson(callback, json) } }
                .onFailure { mainHandler.post { callback?.invoke(mapOf("ok" to false, "error" to "STORAGE_FAILED")) } }
        }
    }

    private fun isOnline(): Boolean {
        val manager = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        val usable = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        val suspended = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P &&
            !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)
        return usable && !suspended
    }

    private fun networkStatus(callback: KuiklyRenderCallback?) {
        ensureNetworkMonitoring()
        callback?.invoke(mapOf("online" to isOnline()))
    }

    private fun ensureNetworkMonitoring() {
        if (networkCallback != null) return
        val appContext = requireNotNull(context?.applicationContext)
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = emitNetworkState(appContext)
            override fun onLost(network: Network) = emitNetworkState(appContext)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = emitNetworkState(appContext)
        }
        networkCallback = callback
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            manager.registerDefaultNetworkCallback(callback)
        } else {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
        }
    }

    private fun emitNetworkState(appContext: Context) {
        mainHandler.post {
            appContext.sendKuiklyEvent(NETWORK_EVENT, JSONObject().put("online", isOnline()))
        }
    }

    private fun draftPreferences() = requireNotNull(context?.applicationContext)
        .getSharedPreferences(DRAFT_PREFERENCES, Context.MODE_PRIVATE)

    private fun saveDraft(params: String?, callback: KuiklyRenderCallback?) {
        pendingDraft = JSONObject(params ?: "{}").optString("text").take(MAX_DRAFT_CHARS)
        mainHandler.removeCallbacks(draftWriteRunnable)
        mainHandler.postDelayed(draftWriteRunnable, DRAFT_WRITE_DEBOUNCE_MS)
        callback?.invoke(mapOf("ok" to true))
    }

    private fun flushDraft() {
        val text = pendingDraft ?: return
        pendingDraft = null
        // Synchronous commit on every key event caused visible stalls during long backspace.
        draftPreferences().edit().putString(DRAFT_KEY, text).apply()
    }

    private fun themePreferences(): ThemePreferences =
        ThemePreferences(requireNotNull(context?.applicationContext) { "Kuikly module context is unavailable" })

    private fun getAppearance(callback: KuiklyRenderCallback?) {
        val preferences = themePreferences()
        callback?.invoke(mapOf(
            "ok" to true,
            "mode" to preferences.get().wireName,
            "bubbleStyle" to preferences.getBubbleStyle(),
            "avatarStyle" to preferences.getAvatarStyle(),
            "resumeDestination" to preferences.consumeResumeDestination(),
        ))
    }

    private fun setTheme(params: String?, callback: KuiklyRenderCallback?) {
        val requested = JSONObject(params ?: "{}").optString("mode")
        val mode = ThemeMode.entries.firstOrNull { it.wireName == requested }
        if (mode == null) {
            callback?.invoke(mapOf("ok" to false, "error" to "INVALID_THEME_MODE"))
            return
        }
        callback?.invoke(mapOf("ok" to true, "mode" to mode.wireName))
        val resumeDestination = JSONObject(params ?: "{}").optString("resumeDestination")
        mainHandler.post { themePreferences().set(mode, resumeDestination) }
    }

    private fun setAppearance(params: String?, callback: KuiklyRenderCallback?) {
        val json = JSONObject(params ?: "{}")
        val preferences = themePreferences()
        preferences.setAppearance(
            bubbleStyle = json.optString("bubbleStyle").takeIf(String::isNotBlank),
            avatarStyle = json.optString("avatarStyle").takeIf(String::isNotBlank),
        )
        callback?.invoke(mapOf(
            "ok" to true,
            "bubbleStyle" to preferences.getBubbleStyle(),
            "avatarStyle" to preferences.getAvatarStyle(),
        ))
    }

    private fun toast(params: String?) {
        val content = JSONObject(params ?: "{}").optString("content")
        Toast.makeText(context, content, Toast.LENGTH_SHORT).show()
    }

    private fun copyToPasteboard(params: String?) {
        val value = JSONObject(params ?: "{}").optString("content")
        (context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)
            ?.setPrimaryClip(ClipData.newPlainText("TalkToAI", value))
    }

    private fun shareMessage(params: String?, callback: KuiklyRenderCallback?) {
        val content = JSONObject(params ?: "{}").optString("content").trim()
        if (content.isEmpty()) {
            callback?.invoke(mapOf("ok" to false, "error" to "EMPTY_CONTENT"))
            return
        }
        runCatching {
            val ctx = requireNotNull(context)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, content)
            }
            ctx.startActivity(Intent.createChooser(shareIntent, "分享回答"))
        }.onSuccess {
            callback?.invoke(mapOf("ok" to true))
        }.onFailure {
            callback?.invoke(mapOf("ok" to false, "error" to "SHARE_FAILED"))
        }
    }

    private fun showAlert(params: String?, callback: KuiklyRenderCallback?) {
        val activity = context as? android.app.Activity ?: run {
            callback?.invoke(mapOf("index" to -1))
            return
        }
        val json = JSONObject(params ?: "{}")
        val buttons = json.optJSONArray("buttons")
        val left = buttons?.optString(0).orEmpty().ifBlank { "取消" }
        val right = buttons?.optString(1).orEmpty().ifBlank { "确定" }
        AlertDialog.Builder(activity)
            .setTitle(json.optString("title"))
            .setMessage(json.optString("message"))
            .setNegativeButton(left) { _, _ -> callback?.invoke(mapOf("index" to 0)) }
            .setPositiveButton(right) { _, _ -> callback?.invoke(mapOf("index" to 1)) }
            .setOnCancelListener { callback?.invoke(mapOf("index" to 0)) }
            .show()
    }

    private fun exportSession(params: String?, callback: KuiklyRenderCallback?) {
        storageExecutor.execute { runCatching {
            val sessionId = JSONObject(params ?: "{}").requireString("sessionId")
            val ctx = requireNotNull(context)
            val exportsDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
            val target = File(exportsDir, "talktoai-session-${sessionId.take(8)}.md")
            target.writeText(chatCoordinator().exportMarkdown(sessionId), Charsets.UTF_8)
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", target)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            mainHandler.post {
                ctx.startActivity(Intent.createChooser(share, "导出会话"))
                callback?.invoke(mapOf("ok" to true))
            }
        }.onFailure {
            mainHandler.post { callback?.invoke(mapOf("ok" to false, "error" to "EXPORT_FAILED", "message" to "会话导出失败")) }
        } }
    }

    private fun JSONObject.requireString(key: String): String = getString(key).trim().also {
        require(it.isNotEmpty()) { "$key must not be empty" }
    }

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
        const val CHAT_EVENT = "talk.chat.event"
        const val NETWORK_EVENT = "talk.network.event"
        private const val DRAFT_PREFERENCES = "talktoai_draft_v1"
        private const val DRAFT_KEY = "text"
        private const val MAX_DRAFT_CHARS = 12_000
        private const val DRAFT_WRITE_DEBOUNCE_MS = 250L
    }
}
