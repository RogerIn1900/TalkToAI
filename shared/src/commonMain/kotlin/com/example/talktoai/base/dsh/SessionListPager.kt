package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.BridgeModule
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.module.RouterModule
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * Session 列表页面（设计 §8 P1 / §7 SessionList baseline+increment）。
 *
 * 通过 [DshBridgeModule.listSessions] 拿到由 Native 端 [com.example.talktoai.DshClientHolder]
 * 投影出的 SessionList snapshot；点击项跳转到 [ChatPager]。
 *
 * 注：使用 Scroller + 简单 View 行渲染，便于快速搭建；后续可替换为虚拟列表。
 */
@Page("session_list")
internal class SessionListPager : BasePager() {

    private val dsh: DshBridgeModule by pagerId { DshBridgeModule(acquireModule(BridgeModule.MODULE_NAME)) }

    private var sessions: List<DshSessionRow> by observable(emptyList())
    private var error: String by observable("")

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
                        fontSize(16f)
                        margin(12f)
                        text("DSH Sessions — ${ctx.sessions.size}")
                    }
                }
                Text {
                    attr {
                        fontSize(12f)
                        marginLeft(12f)
                        color(Color(0xFFAA3333))
                        text(ctx.error)
                    }
                }
                Scroller {
                    attr {
                        flex(1f)
                    }
                    ctx.sessions.forEach { row ->
                        View {
                            attr {
                                height(64f)
                                backgroundColor(Color.WHITE)
                                padding(8f)
                            }
                            event {
                                click {
                                    getPager().acquireModule<com.tencent.kuikly.core.module.RouterModule>(
                                        com.tencent.kuikly.core.module.RouterModule.MODULE_NAME
                                    ).openPage("chat", JSONObject().apply { put("sessionId", row.sessionId) })
                                }
                            }
                            Text {
                                attr {
                                    fontSize(15f)
                                    text(row.title)
                                }
                            }
                            Text {
                                attr {
                                    fontSize(12f)
                                    color(Color(0xFF666666))
                                    text(row.sessionId)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun created() {
        super.created()
        dsh.listSessions { resp ->
            if (!resp.optBoolean("ok", true)) {
                error = resp.optString("reason", "failed")
                return@listSessions
            }
            val arr = JSONArray(resp.optString("sessions", "[]"))
            val rows = mutableListOf<DshSessionRow>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                rows.add(
                    DshSessionRow(
                        sessionId = o.optString("sessionId"),
                        title = o.optString("title"),
                        archived = o.optBoolean("archived"),
                    )
                )
            }
            sessions = rows
        }
    }

    private data class DshSessionRow(val sessionId: String, val title: String, val archived: Boolean)
}
