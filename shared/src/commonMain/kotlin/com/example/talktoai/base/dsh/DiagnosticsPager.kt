package com.example.talktoai.base.dsh

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.BridgeModule
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.*
import com.tencent.kuikly.core.reactive.handler.observable
import com.tencent.kuikly.core.views.*
import com.tencent.kuikly.core.views.compose.Button

/**
 * Diagnostics 页面（设计 §8 / §9）。
 *
 * 通过 [DshBridgeModule.exportDiagnostics] 拿到 Native 端的
 * [com.example.talktoai.dsh.diagnostics.DiagnosticsExporter] 输出；
 * 默认已脱敏（trace/session/requestId 保留，正文 / base64 / token 替换）。
 */
@Page("diagnostics")
internal class DiagnosticsPager : BasePager() {

    private val dsh: DshBridgeModule by pagerId { DshBridgeModule(acquireModule(BridgeModule.MODULE_NAME)) }

    private var report: String by observable("(no report yet)")
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
                        text("Diagnostics")
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
                Button {
                    attr {
                        size(160f, 40f)
                        margin(12f)
                        titleAttr {
                            text("Refresh")
                            fontSize(15f)
                            color(Color.WHITE)
                        }
                        backgroundColor(Color(0xFF3B82F6))
                    }
                    event {
                        click {
                            ctx.refresh()
                        }
                    }
                }
                Scroller {
                    attr {
                        flex(1f)
                    }
                    Text {
                        attr {
                            fontSize(11f)
                            margin(12f)
                            color(Color(0xFF333333))
                            text(ctx.report)
                        }
                    }
                }
            }
        }
    }

    override fun created() {
        super.created()
        refresh()
    }

    private fun refresh() {
        error = ""
        dsh.exportDiagnostics { resp ->
            if (!resp.optBoolean("ok", true)) {
                error = resp.optString("reason", "failed")
                report = "(failed)"
            } else {
                report = resp.optString("report", "(empty)")
            }
        }
    }
}
