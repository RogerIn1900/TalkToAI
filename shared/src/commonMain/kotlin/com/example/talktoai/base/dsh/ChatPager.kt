package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.BridgeModule
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.*
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.compose.Button

/**
 * DSH Chat 页面（设计 §8 / P1 UI）。
 *
 * 边界：UI 只通过 [DshBridgeModule] RPC 与 Native DshClient 通信。
 * 设计 §2.1：不复制 DSH Host，不依赖 Host 内部 Service 实现。
 */
@Page("chat", supportInLocal = true)
internal class ChatPager : BasePager() {

    private val dsh: DshBridgeModule by pagerId {
        DshBridgeModule(acquireModule(BridgeModule.MODULE_NAME))
    }

    private lateinit var inputRef: ViewRef<InputView>

    private var inputText: String by observable("")
    private var statusText: String by observable("idle")
    private var eventsCount: Int by observable(0)

    override fun body(): ViewBuilder {
        val ctx = this
        return {
            View {
                attr {
                    flex(1f)
                    backgroundColor(Color(0xFFF5F7FA))
                }
                Text {
                    attr {
                        fontSize(14f)
                        margin(12f)
                        text("DSH Chat — ${ctx.sessionId()}")
                    }
                }
                Text {
                    attr {
                        fontSize(14f)
                        marginLeft(12f)
                        marginRight(12f)
                        color(Color(0xFF333333))
                        text("status: ${ctx.statusText}")
                    }
                }
                Text {
                    attr {
                        fontSize(13f)
                        marginLeft(12f)
                        marginRight(12f)
                        color(Color(0xFF666666))
                        text("events: ${ctx.eventsCount}")
                    }
                }
                View {
                    attr {
                        flexDirectionRow()
                        margin(12f)
                    }
                    Input {
                        ref { ctx.inputRef = it }
                        attr {
                            flex(1f)
                            placeholder("Type message...")
                            fontSize(15f)
                            color(Color(0xFF333333))
                        }
                    }
                    Button {
                        attr {
                            size(80f, 40f)
                            marginLeft(8f)
                            titleAttr {
                                text("Send")
                                fontSize(15f)
                                color(Color.WHITE)
                            }
                            backgroundColor(Color(0xFF3B82F6))
                        }
                        event {
                            click {
                                val txt = ctx.inputText
                                if (txt.isNotEmpty()) {
                                    ctx.send(txt)
                                    ctx.inputRef.view?.setText("")
                                    ctx.inputText = ""
                                }
                            }
                        }
                    }
                }
                Text {
                    attr {
                        fontSize(12f)
                        margin(12f)
                        color(Color(0xFF999999))
                        text("§2.1: UI → DshBridgeModule RPC → Native DshClient → Remote")
                    }
                }
            }
        }
    }

    override fun created() {
        super.created()
        statusText = "loading..."
        dsh.listRecentEvents(sessionId()) { evs ->
            eventsCount = evs.optInt("count", 0)
            statusText = "ready"
        }
    }

    private fun send(text: String) {
        statusText = "sending..."
        dsh.sendPrompt(sessionId(), text) { ack ->
            if (ack.optBoolean("accepted")) {
                statusText = "sent ${ack.optString("requestId")}"
                dsh.listRecentEvents(sessionId()) { evs ->
                    eventsCount = evs.optInt("count", 0)
                }
            } else {
                statusText = "rejected: ${ack.optString("reason")}"
            }
        }
    }

    private fun sessionId(): String =
        pageData.params.optString("sessionId").ifEmpty { "default" }

    companion object {
        const val ROUTE = "chat"
    }
}

/**
 * DSH RPC 桥接层（设计 §2.1 跨端一致性）。
 *
 * 约定 method name 与 Native 实现对应：
 *   dsh.sendPrompt   → DshClient.sendPrompt()
 *   dsh.listRecentEvents → Session events query
 *   dsh.reconcilePrompt → DshClient.reconcilePrompt()
 */
internal class DshBridgeModule(private val bridge: BridgeModule) {

    fun sendPrompt(sessionId: String, text: String, callback: (JSONObject) -> Unit) {
        val args = JSONObject().apply {
            put("sessionId", sessionId)
            put("text", text)
        }
        bridge.callJsonRpc(DSH_SEND_PROMPT, args) { resp ->
            callback(resp ?: JSONObject().apply { put("accepted", false); put("reason", "no-response") })
        }
    }

    fun listRecentEvents(sessionId: String, callback: (JSONObject) -> Unit) {
        val args = JSONObject().apply { put("sessionId", sessionId) }
        bridge.callJsonRpc(DSH_LIST_EVENTS, args) { resp ->
            callback(resp ?: JSONObject().apply { put("events", "[]"); put("count", 0) })
        }
    }

    fun reconcile(sessionId: String, requestId: String, callback: (JSONObject) -> Unit) {
        val args = JSONObject().apply {
            put("sessionId", sessionId)
            put("requestId", requestId)
        }
        bridge.callJsonRpc(DSH_RECONCILE, args) { resp ->
            callback(resp ?: JSONObject().apply { put("ok", false) })
        }
    }

    fun listSessions(callback: (JSONObject) -> Unit) {
        bridge.callJsonRpc(DSH_LIST_SESSIONS, null) { resp ->
            callback(resp ?: JSONObject().apply { put("ok", false); put("reason", "no-response") })
        }
    }

    fun listWorkspaces(callback: (JSONObject) -> Unit) {
        bridge.callJsonRpc(DSH_LIST_WORKSPACES, null) { resp ->
            callback(resp ?: JSONObject().apply { put("ok", false); put("reason", "no-response") })
        }
    }

    fun exportDiagnostics(callback: (JSONObject) -> Unit) {
        bridge.callJsonRpc(DSH_EXPORT_DIAGNOSTICS, null) { resp ->
            callback(resp ?: JSONObject().apply { put("ok", false); put("reason", "no-response") })
        }
    }

    companion object {
        const val DSH_SEND_PROMPT = "dsh.sendPrompt"
        const val DSH_LIST_EVENTS = "dsh.listRecentEvents"
        const val DSH_RECONCILE = "dsh.reconcilePrompt"
        const val DSH_LIST_SESSIONS = "dsh.listSessions"
        const val DSH_LIST_WORKSPACES = "dsh.listWorkspaces"
        const val DSH_EXPORT_DIAGNOSTICS = "dsh.exportDiagnostics"
    }
}
