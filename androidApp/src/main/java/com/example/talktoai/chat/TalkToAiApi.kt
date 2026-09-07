package com.example.talktoai.chat

import com.example.talktoai.BuildConfig
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit

class TalkToAiApi(
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = BuildConfig.API_BASE_URL,
) {
    interface StreamListener {
        fun onEvent(event: StreamEvent)
        fun onFailure(code: String, message: String, retryable: Boolean)
    }

    interface JsonListener {
        fun onSuccess(json: JSONObject)
        fun onFailure(code: String, message: String, retryable: Boolean)
    }

    fun streamChat(
        installationId: String,
        conversationId: String,
        messages: List<ChatMessage>,
        attachments: List<ChatAttachment> = emptyList(),
        listener: StreamListener,
    ): Call {
        val payload = JSONObject().apply {
            put("installationId", installationId)
            put("conversationId", conversationId)
            put("stream", true)
            put("messages", JSONArray().apply {
                messages.filter(::isSendableMessage).forEach { message ->
                    put(JSONObject().apply {
                        put("role", message.role.wireName)
                        put("content", message.content)
                    })
                }
            })
            put("attachments", JSONArray().apply {
                attachments.forEach { attachment ->
                    put(JSONObject().apply {
                        put("id", attachment.id)
                        put("name", attachment.name)
                        put("mimeType", attachment.mimeType)
                        put("sizeBytes", attachment.sizeBytes)
                        put("objectRef", attachment.objectRef)
                    })
                }
            })
        }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/chat/completions")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!call.isCanceled()) listener.onFailure("NETWORK_ERROR", "网络连接失败", true)
                }

                override fun onResponse(call: Call, response: Response) {
                    var terminalReceived = false
                    try {
                    response.use {
                        if (!response.isSuccessful) {
                            val error = runCatching { JSONObject(response.body?.string().orEmpty()).getJSONObject("error") }.getOrNull()
                            listener.onFailure(
                                error?.optString("code").orEmpty().ifEmpty { "HTTP_${response.code}" },
                                error?.optString("message").orEmpty().ifEmpty { "服务请求失败" },
                                error?.optBoolean("retryable") ?: (response.code >= 500),
                            )
                            return
                        }
                        val parser = SseParser()
                        val source = response.body?.source() ?: run {
                            listener.onFailure("EMPTY_RESPONSE", "服务返回为空", true)
                            return
                        }
                        while (!source.exhausted() && !call.isCanceled()) {
                            parser.accept(source.readUtf8Line().orEmpty())?.let { event ->
                                terminalReceived = event.type == "done" || event.type == "error"
                                listener.onEvent(event)
                            }
                            if (terminalReceived) break
                        }
                        if (!terminalReceived && !call.isCanceled()) {
                            parser.finish()?.let { event ->
                                terminalReceived = event.type == "done" || event.type == "error"
                                listener.onEvent(event)
                            }
                        }
                        if (!terminalReceived && !call.isCanceled()) {
                            listener.onFailure("STREAM_INTERRUPTED", "连接已中断，请重新加载", true)
                        }
                    }
                    } catch (error: IOException) {
                        // OkHttp does not call onFailure again for exceptions while reading
                        // an onResponse body. Always close the stream and terminate UI state.
                        if (!terminalReceived && !call.isCanceled()) {
                            listener.onFailure("NETWORK_ERROR", "网络连接中断，请重新加载", true)
                        }
                    }
                }
            })
        }
    }

    fun marketUrl(symbol: String, period: String, from: String? = null, to: String? = null): String =
        "${baseUrl.trimEnd('/')}/v1/market/bars".toHttpUrl().newBuilder()
            .addQueryParameter("symbol", symbol)
            .addQueryParameter("period", period)
            .apply {
                from?.takeIf { it.isNotBlank() }?.let { addQueryParameter("from", it) }
                to?.takeIf { it.isNotBlank() }?.let { addQueryParameter("to", it) }
            }
            .build()
            .toString()

    fun getMarketBars(symbol: String, period: String, from: String? = null, to: String? = null, listener: JsonListener): Call {
        val request = Request.Builder().url(marketUrl(symbol, period, from, to)).get().build()
        return client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!call.isCanceled()) listener.onFailure("NETWORK_ERROR", "行情网络连接失败", true)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            val error = runCatching { JSONObject(raw).getJSONObject("error") }.getOrNull()
                            listener.onFailure(
                                error?.optString("code").orEmpty().ifEmpty { "HTTP_${response.code}" },
                                error?.optString("message").orEmpty().ifEmpty { "行情服务请求失败" },
                                error?.optBoolean("retryable") ?: (response.code >= 500),
                            )
                            return
                        }
                        runCatching { JSONObject(raw) }
                            .onSuccess(listener::onSuccess)
                            .onFailure { listener.onFailure("INVALID_RESPONSE", "行情响应格式错误", true) }
                    }
                }
            })
        }
    }

    fun getHealth(listener: JsonListener): Call = getJson("${baseUrl.trimEnd('/')}/health", "服务状态读取失败", listener)

    fun uploadAttachment(installationId: String, attachment: ChatAttachment, listener: JsonListener): Call {
        val file = File(attachment.localPath)
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/attachments/${attachment.id}")
            .header("X-Installation-Id", installationId)
            .header("X-File-Name", java.net.URLEncoder.encode(attachment.name, Charsets.UTF_8.name()))
            .put(file.asRequestBody(attachment.mimeType.toMediaType()))
            .build()
        return client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!call.isCanceled()) listener.onFailure("NETWORK_ERROR", "附件上传失败", true)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = response.body?.string().orEmpty()
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        if (response.isSuccessful && json != null) listener.onSuccess(json) else {
                            val error = json?.optJSONObject("error")
                            listener.onFailure(
                                error?.optString("code").orEmpty().ifEmpty { "HTTP_${response.code}" },
                                error?.optString("message").orEmpty().ifEmpty { "附件上传失败" },
                                error?.optBoolean("retryable") ?: (response.code >= 500),
                            )
                        }
                    }
                }
            })
        }
    }

    private fun getJson(url: String, failureMessage: String, listener: JsonListener): Call {
        val request = Request.Builder().url(url).get().build()
        return client.newCall(request).also { call ->
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (!call.isCanceled()) listener.onFailure("NETWORK_ERROR", failureMessage, true)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        val raw = response.body?.string().orEmpty()
                        if (!response.isSuccessful) {
                            listener.onFailure("HTTP_${response.code}", failureMessage, response.code >= 500)
                            return
                        }
                        runCatching { JSONObject(raw) }
                            .onSuccess(listener::onSuccess)
                            .onFailure { listener.onFailure("INVALID_RESPONSE", "服务状态格式错误", true) }
                    }
                }
            })
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        internal fun isSendableMessage(message: ChatMessage): Boolean =
            message.content.isNotBlank() && message.status != MessageStatus.FAILED

        private fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
