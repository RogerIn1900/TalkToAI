package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.BridgeModule
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*

/**
 * Workspace 列表页面（设计 §7 WorkspaceList baseline+increment）。
 */
@Page("workspace_list")
internal class WorkspaceListPager : BasePager() {

    private val dsh: DshBridgeModule by pagerId { DshBridgeModule(acquireModule(BridgeModule.MODULE_NAME)) }

    private var workspaces: List<DshWorkspaceRow> by observable(emptyList())
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
                        text("Workspaces — ${ctx.workspaces.size}")
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
                    ctx.workspaces.forEach { row ->
                        View {
                            attr {
                                height(56f)
                                backgroundColor(Color.WHITE)
                                padding(8f)
                            }
                            Text {
                                attr {
                                    fontSize(15f)
                                    text(row.name)
                                }
                            }
                            Text {
                                attr {
                                    fontSize(12f)
                                    color(Color(0xFF666666))
                                    text("sessions: ${row.sessionCount}")
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
        dsh.listWorkspaces { resp ->
            if (!resp.optBoolean("ok", true)) {
                error = resp.optString("reason", "failed")
                return@listWorkspaces
            }
            val arr = JSONArray(resp.optString("workspaces", "[]"))
            val rows = mutableListOf<DshWorkspaceRow>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                rows.add(
                    DshWorkspaceRow(
                        id = o.optString("workspaceId"),
                        name = o.optString("name"),
                        sessionCount = o.optInt("sessionCount"),
                    )
                )
            }
            workspaces = rows
        }
    }

    private data class DshWorkspaceRow(val id: String, val name: String, val sessionCount: Int)
}
