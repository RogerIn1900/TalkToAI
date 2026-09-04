package com.example.talktoai.module

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.tencent.kuikly.core.render.android.export.KuiklyRenderBaseModule
import com.tencent.kuikly.core.render.android.export.KuiklyRenderCallback
import com.example.talktoai.DshClientHolder
import com.example.talktoai.KRApplication
import com.example.talktoai.dsh.contract.AttachmentRef
import com.example.talktoai.dsh.contract.ImageAttachmentPayload
import com.example.talktoai.dsh.contract.SessionId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date

class KRBridgeModule : KuiklyRenderBaseModule() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun call(method: String, params: String?, callback: KuiklyRenderCallback?): Any? {
        return when (method) {
            "ssoRequest" -> ssoRequest(params, callback)
            "showAlert" -> showAlert(params, callback)
            "closePage" -> closePage(params)
            "openPage" -> openPage(params)
            "copyToPasteboard" -> copyToPasteboard(params)
            "toast" -> toast(params)
            "log" -> log(params)
            "reportDT" -> reportDT(params)
            "reportRealtime" -> reportRealtime(params)
            "qqLiveSSORequest" -> qqLiveSSORequest(params, callback)
            "localServeTime" -> localServeTime(params, callback)
            "currentTimestamp" -> currentTimestamp(params)
            "dateFormatter" -> dateFormatter(params)

            // ---------- DSH bridge (设计 §2.1 跨端一致性) ----------
            "dsh.connect" -> dshConnect(callback)
            "dsh.sendPrompt" -> dshSendPrompt(params, callback)
            "dsh.listRecentEvents" -> dshListRecentEvents(params, callback)
            "dsh.reconcilePrompt" -> dshReconcile(params, callback)
            "dsh.listSessions" -> dshListSessions(callback)
            "dsh.listWorkspaces" -> dshListWorkspaces(callback)
            "dsh.exportDiagnostics" -> dshExportDiagnostics(callback)

            else -> callback?.invoke(
                mapOf(
                    "code" to -1,
                    "message" to "方法不存在"
                )
            )
        }
    }

    private fun dshConnect(callback: KuiklyRenderCallback?) {
        scope.launch {
            val r = runCatching { DshClientHolder.ensureConnected() }
            callback?.invoke(
                mapOf(
                    "ok" to r.isSuccess,
                    "error" to r.exceptionOrNull()?.message,
                )
            )
        }
    }

    private fun dshSendPrompt(params: String?, callback: KuiklyRenderCallback?) {
        scope.launch {
            val p = JSONObject(params ?: "{}")
            val sid = SessionId(p.optString("sessionId").ifEmpty { "default" })
            val text = p.optString("text")
            val attsJson = p.optJSONArray("attachments")
            val attachments = mutableListOf<ImageAttachmentPayload>()
            if (attsJson != null) {
                for (i in 0 until attsJson.length()) {
                    val a = attsJson.optJSONObject(i) ?: continue
                    val ref = AttachmentRef(
                        id = a.optString("id"),
                        mime = a.optString("mime", "image/jpeg"),
                        sizeBytes = a.optLong("sizeBytes", 0L),
                    )
                    attachments.add(
                        ImageAttachmentPayload(
                            ref = ref,
                            base64Encoded = a.optString("base64"),
                            widthPx = a.optInt("widthPx", 0),
                            heightPx = a.optInt("heightPx", 0),
                        )
                    )
                }
            }
            val ctx = runCatching { DshClientHolder.ensureConnected() }.getOrNull()
            if (ctx == null) {
                callback?.invoke(mapOf("accepted" to false, "reason" to "client-not-ready"))
                return@launch
            }
            val r = ctx.client.sendPrompt(sid, text, attachments)
            when (r) {
                is com.example.talktoai.dsh.contract.DshResult.Ok -> callback?.invoke(
                    mapOf("accepted" to true, "requestId" to r.value)
                )
                is com.example.talktoai.dsh.contract.DshResult.Err -> callback?.invoke(
                    mapOf("accepted" to false, "reason" to r.error.message)
                )
            }
        }
    }

    private fun dshListRecentEvents(params: String?, callback: KuiklyRenderCallback?) {
        scope.launch {
            val ctx = runCatching { DshClientHolder.ensureConnected() }.getOrNull()
            if (ctx == null) {
                callback?.invoke(mapOf("count" to 0, "events" to "[]"))
                return@launch
            }
            val snapshot = ctx.tracker.snapshot()
            val arr = JSONArray()
            for (e in snapshot) {
                val o = JSONObject()
                o.put("requestId", e.requestId.value)
                o.put("sessionId", e.sessionId.value)
                o.put("state", e.state::class.simpleName ?: "unknown")
                arr.put(o)
            }
            callback?.invoke(mapOf("count" to snapshot.size, "events" to arr.toString()))
        }
    }

    private fun dshReconcile(params: String?, callback: KuiklyRenderCallback?) {
        scope.launch {
            val p = JSONObject(params ?: "{}")
            val sid = SessionId(p.optString("sessionId"))
            val requestId = p.optString("requestId")
            val ctx = runCatching { DshClientHolder.ensureConnected() }.getOrNull()
            if (ctx == null) {
                callback?.invoke(mapOf("ok" to false, "reason" to "client-not-ready"))
                return@launch
            }
            val r = ctx.reconciler.reconcile(
                com.example.talktoai.dsh.contract.PromptRequest(
                    sessionId = sid,
                    requestId = requestId,
                    text = "_lookup_",
                    attachments = emptyList(),
                ),
                ctx.client.trace(sid),
            )
            when (r) {
                is com.example.talktoai.dsh.contract.DshResult.Ok -> {
                    val v = r.value
                    val out = mutableMapOf<String, Any?>("ok" to true)
                    when (v) {
                        is com.example.talktoai.dsh.contract.PromptQueryResult.Committed -> {
                            out["committed"] = true
                            out["messageId"] = v.messageId
                        }
                        com.example.talktoai.dsh.contract.PromptQueryResult.NotFound -> out["committed"] = false
                        is com.example.talktoai.dsh.contract.PromptQueryResult.Conflict -> {
                            out["committed"] = false
                            out["reason"] = v.reason
                        }
                    }
                    callback?.invoke(out)
                }
                is com.example.talktoai.dsh.contract.DshResult.Err -> callback?.invoke(
                    mapOf("ok" to false, "reason" to r.error.message)
                )
            }
        }
    }

    private fun dshListSessions(callback: KuiklyRenderCallback?) {
        scope.launch {
            val ctx = runCatching { DshClientHolder.ensureConnected() }.getOrNull()
            if (ctx == null) {
                callback?.invoke(mapOf("count" to 0, "sessions" to "[]"))
                return@launch
            }
            val sl = ctx.sessionListSnapshot()
            val arr = JSONArray()
            sl?.sessions?.forEach { s ->
                arr.put(JSONObject().apply {
                    put("sessionId", s.sessionId.value)
                    put("title", s.title)
                    put("archived", s.archived)
                    put("updatedAtMs", s.updatedAtMs)
                })
            }
            callback?.invoke(mapOf("count" to arr.length(), "sessions" to arr.toString()))
        }
    }

    private fun dshListWorkspaces(callback: KuiklyRenderCallback?) {
        scope.launch {
            val ctx = runCatching { DshClientHolder.ensureConnected() }.getOrNull()
            if (ctx == null) {
                callback?.invoke(mapOf("count" to 0, "workspaces" to "[]"))
                return@launch
            }
            val wl = ctx.workspaceListSnapshot()
            val arr = JSONArray()
            wl?.workspaces?.forEach { w ->
                arr.put(JSONObject().apply {
                    put("workspaceId", w.workspaceId)
                    put("name", w.name)
                    put("sessionCount", w.sessionCount)
                })
            }
            callback?.invoke(mapOf("count" to arr.length(), "workspaces" to arr.toString()))
        }
    }

    private fun dshExportDiagnostics(callback: KuiklyRenderCallback?) {
        scope.launch {
            val ctx = runCatching { DshClientHolder.ensureConnected() }.getOrNull()
            if (ctx == null) {
                callback?.invoke(mapOf("ok" to false, "reason" to "client-not-ready"))
                return@launch
            }
            val report = ctx.diagnostics.export(ctx.tracker, errors = emptyList())
            callback?.invoke(mapOf("ok" to true, "report" to ctx.diagnostics.toJson(report)))
        }
    }

    private fun reportRealtime(params: String?) {
    }

    private fun reportDT(params: String?) {
    }

    private fun log(params: String?) {
        if (params == null) {
            return
        }

        val paramJSON = JSONObject(params)
        Log.i("KuiklyRender", paramJSON.optString("content"))
    }

    private fun toast(params: String?) {
        if (params == null) {
            return
        }
        val paramJSON = JSONObject(params)
        Toast.makeText(
            KRApplication.application,
            paramJSON.optString("content"),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun copyToPasteboard(params: String?) {
        if (params == null) {
            return
        }

        val paramJSON = JSONObject(params)
        (context?.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager)?.also {
            it.setPrimaryClip(ClipData.newPlainText(MODULE_NAME, paramJSON.optString("content")))
        }
    }

    private fun openPage(params: String?) {
        if (params == null) {
            return
        }
        val ctx = context ?: return
        val paramJSON = JSONObject(params)
        val url = paramJSON.optString("url")
    }

    private fun closePage(params: String?) {
        activity?.finish()
    }

    private fun showAlert(params: String?, callback: KuiklyRenderCallback?) {
        if (params == null) {
            return
        }
        val paramJSON = JSONObject(params)
        val titleText = paramJSON.optString("title")
        val message = paramJSON.optString("message")
        val buttons = paramJSON.optJSONArray("buttons") ?: JSONArray()
    }

    private fun ssoRequest(params: String?, callback: KuiklyRenderCallback?) {}

    private fun qqLiveSSORequest(params: String?, callback: KuiklyRenderCallback?) {
    }

    private fun localServeTime(params: String?, callback: KuiklyRenderCallback?) {
        val time = (System.currentTimeMillis() / 1000.0)
        callback?.invoke(
            mapOf(
                "time" to time
            )
        )
    }

    private fun currentTimestamp(params: String?): String {
        return (System.currentTimeMillis()).toString()
    }

    private fun dateFormatter(params: String?): String {
        val paramJSONObject = JSONObject(params ?: "{}")
        val data = Date(paramJSONObject.optLong("timeStamp"))
        val format = SimpleDateFormat(paramJSONObject.optString("format"))
        return format.format(data)
    }

    companion object {
        const val MODULE_NAME = "HRBridgeModule"
    }
}

private fun JSONObject.toMap(): Map<Any, Any> {
    val map = mutableMapOf<Any, Any>()
    val keys = keys()
    while (keys.hasNext()) {
        val key = keys.next()
        when (val v = opt(key)) {
            is JSONObject -> {
                map[key] = v.toMap()
            }

            else -> {
                v?.also {
                    map[key] = it
                }
            }
        }
    }
    return map
}
