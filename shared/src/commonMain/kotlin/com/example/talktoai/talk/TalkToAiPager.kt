package com.example.talktoai.talk

import com.example.talktoai.base.BasePager
import com.example.talktoai.base.BridgeModule
import com.example.talktoai.base.theme.ThemeColors
import com.tencent.kuikly.core.annotations.Page
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.Color
import com.tencent.kuikly.core.base.ViewBuilder
import com.tencent.kuikly.core.base.ViewRef
import com.tencent.kuikly.core.base.pagerId
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.views.Canvas
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.SelectableOption
import com.tencent.kuikly.core.views.SelectionType
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import kotlin.math.max

@Page("talk_to_ai", supportInLocal = true)
internal class TalkToAiPager : BasePager() {
    private lateinit var inputRef: ViewRef<InputView>
    private var transcriptSelectable: ViewRef<DivView>? = null
    internal val viewModel: TalkToAiViewModel by pagerId {
        TalkToAiViewModel(
            acquireModule(BridgeModule.MODULE_NAME),
            acquireModule(NotifyModule.MODULE_NAME),
        )
    }

    override fun created() {
        super.created()
        viewModel.start()
        viewModel.loadMarket("day")
    }

    override fun pageWillDestroy() {
        viewModel.destroy()
        super.pageWillDestroy()
    }

    override fun body(): ViewBuilder {
        val ctx = this
        val safeTop = pagerData.safeAreaInsets.top
        val safeBottom = pagerData.safeAreaInsets.bottom
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(ThemeColors.background)
                    padding(top = safeTop, bottom = safeBottom)
                }

                View {
                    attr {
                        height(54f)
                        padding(left = 18f, right = 18f)
                        flexDirectionRow()
                        alignItemsCenter()
                        justifyContentSpaceBetween()
                        backgroundColor(ThemeColors.surface)
                        borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                    }
                    Text {
                        attr {
                            text("TalkToAI")
                            fontSize(19f)
                            fontWeightSemiBold()
                            color(ThemeColors.onSurface)
                        }
                    }
                    View {
                        attr { flexDirectionRow(); alignItemsCenter() }
                        Text {
                            attr {
                                text("A股 · 只读咨询")
                                fontSize(12f)
                                color(ThemeColors.onSurfaceVariant)
                                marginRight(10f)
                            }
                        }
                    }
                }

                View {
                    attr {
                        margin(left = 12f, right = 12f, top = 10f)
                        padding(12f)
                        borderRadius(14f)
                        backgroundColor(ThemeColors.surface)
                        border(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                    }
                    Text {
                        attr {
                            text(ctx.viewModel.marketSummary)
                            fontSize(12f)
                            lineHeight(18f)
                            color(ThemeColors.onSurfaceVariant)
                        }
                    }
                    View {
                        attr {
                            flexDirectionRow(); alignItemsCenter(); marginTop(8f); height(34f)
                            padding(left = 8f); borderRadius(8f); backgroundColor(ThemeColors.background)
                        }
                        Input {
                            attr {
                                flex(1f); height(32f); color(ThemeColors.onSurface)
                                placeholder("股票代码，如 600000.SH"); text(ctx.viewModel.marketSymbol); fontSize(12f)
                            }
                            event { textDidChange { ctx.viewModel.marketSymbol = it.text } }
                        }
                        actionButton(ctx, "查询", "market-query", width = 54f)
                    }
                    View {
                        attr { flexDirectionRow(); marginTop(8f) }
                        periodButton(ctx, "分时", "intraday")
                        periodButton(ctx, "日", "day")
                        periodButton(ctx, "周", "week")
                        periodButton(ctx, "月", "month")
                    }
                    View {
                        attr { flexDirectionRow(); marginTop(6f) }
                        actionButton(ctx, "会话管理", "sessions", width = 82f)
                        actionButton(ctx, "插件与诊断", "tools", width = 92f)
                    }
                    View {
                        attr { flexDirectionRow(); alignItemsCenter(); marginTop(6f) }
                        Text { attr { text("起始"); fontSize(11f); color(ThemeColors.onSurfaceVariant); marginRight(4f) } }
                        Input {
                            attr { width(82f); height(30f); fontSize(11f); color(ThemeColors.onSurface); placeholder("YYYY-MM-DD") }
                            event { textDidChange { ctx.viewModel.marketFrom = it.text } }
                        }
                        Text { attr { text(" 结束 "); fontSize(11f); color(ThemeColors.onSurfaceVariant) } }
                        Input {
                            attr { width(82f); height(30f); fontSize(11f); color(ThemeColors.onSurface); placeholder("YYYY-MM-DD") }
                            event { textDidChange { ctx.viewModel.marketTo = it.text } }
                        }
                        actionButton(ctx, "自定义", "market-custom", width = 62f)
                    }
                    Canvas({
                        attr {
                            height(116f)
                            marginTop(8f)
                            backgroundColor(ThemeColors.background)
                            borderRadius(8f)
                        }
                    }) { canvas, width, height ->
                        val bars = ctx.viewModel.marketBars
                        if (bars.isNotEmpty()) {
                            val priceHeight = height * 0.68f
                            val volumeTop = height * 0.75f
                            val high = bars.maxOf { it.high }
                            val low = bars.minOf { it.low }
                            val range = max(high - low, 0.01f)
                            val maxVolume = max(bars.maxOf { it.volume }, 1f)
                            val step = width / bars.size
                            val candleWidth = max(step * 0.48f, 2f)
                            bars.forEachIndexed { index, bar ->
                                val x = step * index + step / 2f
                                val highY = 5f + (high - bar.high) / range * (priceHeight - 10f)
                                val lowY = 5f + (high - bar.low) / range * (priceHeight - 10f)
                                val openY = 5f + (high - bar.open) / range * (priceHeight - 10f)
                                val closeY = 5f + (high - bar.close) / range * (priceHeight - 10f)
                                val rising = bar.close >= bar.open
                                val color = if (rising) Color(0xFFE5484D) else Color(0xFF16A34A)
                                canvas.strokeStyle(color)
                                canvas.fillStyle(color)
                                canvas.lineWidth(1f)
                                canvas.beginPath()
                                canvas.moveTo(x, highY)
                                canvas.lineTo(x, lowY)
                                canvas.stroke()
                                val top = minOf(openY, closeY)
                                val bodyHeight = max(kotlin.math.abs(openY - closeY), 1.5f)
                                drawRectangle(canvas, x - candleWidth / 2f, top, candleWidth, bodyHeight)
                                val volumeHeight = (bar.volume / maxVolume) * (height - volumeTop - 4f)
                                drawRectangle(canvas, x - candleWidth / 2f, height - volumeHeight, candleWidth, volumeHeight)
                            }
                        }
                    }
                }

                Scroller {
                    attr {
                        flex(1f)
                        margin(left = 12f, right = 12f, top = 10f)
                        padding(14f)
                        borderRadius(14f)
                        backgroundColor(ThemeColors.surface)
                    }
                    View {
                        ref { ctx.transcriptSelectable = it }
                        attr {
                            selectable(SelectableOption.ENABLE)
                            selectionColor(ThemeColors.accent)
                        }
                        event {
                            longPress {
                                if (it.state == "start") {
                                    ctx.transcriptSelectable?.view?.createSelection(it.x, it.y, SelectionType.WORD)
                                }
                            }
                        }
                        Text {
                            attr {
                                text(ctx.viewModel.transcript)
                                fontSize(15f)
                                lineHeight(23f)
                                color(ThemeColors.onSurface)
                            }
                        }
                        Text {
                            attr {
                                text("\n\n${ctx.viewModel.status}")
                                fontSize(12f)
                                color(if (ctx.viewModel.online) ThemeColors.onSurfaceVariant else ThemeColors.error)
                            }
                        }
                        Text {
                            attr {
                                text("\n${ctx.viewModel.sourceSummary}")
                                fontSize(11f)
                                lineHeight(17f)
                                color(ThemeColors.onSurfaceVariant)
                            }
                        }
                    }
                }

                View {
                    attr {
                        padding(left = 12f, right = 12f, top = 8f)
                        flexDirectionRow()
                    }
                    actionButton(ctx, "复制", "copy")
                    actionButton(ctx, "重试", "retry")
                    actionButton(
                        ctx,
                        title = "新会话",
                        action = "generation",
                        dynamicTitle = { if (ctx.viewModel.isGenerating) "停止" else "新会话" },
                    )
                    actionButton(ctx, ctx.viewModel.themeLabel(), "theme", width = 86f)
                }

                vif({ ctx.viewModel.pendingAttachments.isNotEmpty() }) {
                    Scroller {
                        attr { height(34f); margin(left = 12f, right = 12f, top = 6f); flexDirectionRow() }
                        ctx.viewModel.pendingAttachments.forEach { attachment ->
                            val state = when (attachment.uploadState) { "ready" -> "✓"; "failed" -> "!"; else -> "…" }
                            actionButton(ctx, "📎 ${attachment.name.take(7)} $state ×", "attachment-remove", width = 112f, payload = attachment.id)
                        }
                    }
                }

                View {
                    attr {
                        margin(12f)
                        marginTop(8f)
                        height(48f)
                        flexDirectionRow()
                        alignItemsCenter()
                        padding(left = 12f, right = 6f)
                        borderRadius(16f)
                        backgroundColor(ThemeColors.surface)
                        border(Border(0.8f, BorderStyle.SOLID, ThemeColors.divider))
                    }
                    Button {
                        attr {
                            size(38f, 38f)
                            marginRight(4f)
                            borderRadius(12f)
                            backgroundColor(ThemeColors.surfaceVariant)
                            titleAttr { text("＋"); color(ThemeColors.onSurface); fontSize(20f) }
                        }
                        event { click { ctx.viewModel.pickAttachment() } }
                    }
                    Input {
                        ref { ctx.inputRef = it }
                        attr {
                            flex(1f)
                            height(38f)
                            placeholder("问行情、财报或股票基础知识")
                            fontSize(15f)
                            color(ThemeColors.onSurface)
                            if (ctx.viewModel.input.isNotEmpty()) text(ctx.viewModel.input)
                        }
                        event { textDidChange { ctx.viewModel.updateInput(it.text) } }
                    }
                    Button {
                        attr {
                            size(68f, 38f)
                            borderRadius(12f)
                            backgroundColor(ThemeColors.accent)
                            titleAttr {
                                text(if (ctx.viewModel.isGenerating) "停止" else "发送")
                                color(ThemeColors.onAccent)
                                fontSize(14f)
                            }
                        }
                        event {
                            click {
                                if (ctx.viewModel.isGenerating) {
                                    ctx.viewModel.stop()
                                } else if (ctx.viewModel.send()) {
                                    ctx.inputRef.view?.setText("")
                                }
                            }
                        }
                    }
                }

                vif({ ctx.viewModel.showSessionPanel }) {
                    sessionPanel(ctx, safeTop)
                }
                vif({ ctx.viewModel.showToolsPanel }) {
                    toolsPanel(ctx, safeTop)
                }
            }
        }
    }

}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.toolsPanel(ctx: TalkToAiPager, safeTop: Float) {
    View {
        attr {
            positionAbsolute(); left(0f); right(0f); top(safeTop + 54f); bottom(0f)
            zIndex(45, false); padding(16f); backgroundColor(ThemeColors.background); flexDirectionColumn()
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween(); marginBottom(14f) }
            Text { attr { text("插件与诊断"); fontSize(20f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
            actionButton(ctx, "关闭", "tools", width = 56f)
        }
        View {
            attr { padding(14f); borderRadius(12f); backgroundColor(ThemeColors.surface); marginBottom(12f) }
            Text { attr { text("插件状态（只读）\n\n${ctx.viewModel.pluginSummary}"); fontSize(14f); lineHeight(21f); color(ThemeColors.onSurface) } }
        }
        View {
            attr { padding(14f); borderRadius(12f); backgroundColor(ThemeColors.surface); marginBottom(12f) }
            Text { attr { text("日志中心\n\n${ctx.viewModel.logSummary}"); fontSize(14f); lineHeight(21f); color(ThemeColors.onSurface) } }
        }
        Text {
            attr {
                text("反馈包不包含聊天正文、附件内容、API 密钥或原始设备标识。加密标识仅能在本机双向解码。")
                fontSize(12f); lineHeight(18f); color(ThemeColors.onSurfaceVariant); marginBottom(14f)
            }
        }
        actionButton(ctx, "导出反馈包", "feedback-export", width = 110f)
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.sessionPanel(ctx: TalkToAiPager, safeTop: Float) {
    View {
        attr {
            positionAbsolute()
            left(0f); right(0f); top(safeTop + 54f); bottom(0f)
            zIndex(40, false)
            padding(16f)
            backgroundColor(ThemeColors.background)
            flexDirectionColumn()
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween(); marginBottom(12f) }
            Text { attr { text(if (ctx.viewModel.showArchivedSessions) "已归档会话" else "会话管理"); fontSize(20f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
            actionButton(ctx, "关闭", "sessions", width = 56f)
        }
        View {
            attr { flexDirectionRow(); marginBottom(12f) }
            actionButton(ctx, "进行中", "sessions-active", width = 72f)
            actionButton(ctx, "已归档", "sessions-archived", width = 72f)
            actionButton(ctx, "新会话", "sessions-new", width = 72f)
        }
        if (ctx.viewModel.currentSessionId.isNotEmpty()) {
            View {
                attr {
                    flexDirectionRow(); alignItemsCenter(); padding(8f); marginBottom(10f)
                    backgroundColor(ThemeColors.surface); borderRadius(10f)
                }
                Input {
                    attr {
                        flex(1f); height(36f); fontSize(14f); color(ThemeColors.onSurface)
                        placeholder("当前会话名称")
                        text(ctx.viewModel.renameDraft)
                    }
                    event { textDidChange { ctx.viewModel.renameDraft = it.text } }
                }
                actionButton(ctx, "改名", "session-rename", width = 54f)
                actionButton(ctx, "导出", "session-export", width = 54f)
                actionButton(ctx, "归档", "session-archive", width = 54f)
                actionButton(ctx, "删除", "session-delete", width = 54f)
            }
        }
        Scroller {
            attr { flex(1f) }
            val rows = ctx.viewModel.visibleSessions()
            if (rows.isEmpty()) {
                Text { attr { text(if (ctx.viewModel.showArchivedSessions) "暂无归档会话" else "暂无会话，发送第一条消息即可创建"); fontSize(14f); color(ThemeColors.onSurfaceVariant) } }
            }
            rows.forEach { row ->
                View {
                    attr {
                        padding(12f); marginBottom(8f); borderRadius(10f); backgroundColor(ThemeColors.surface)
                        flexDirectionRow(); alignItemsCenter()
                    }
                    Text {
                        attr { flex(1f); text(row.title); fontSize(15f); color(ThemeColors.onSurface) }
                        event { click { ctx.viewModel.openSession(row.id) } }
                    }
                    if (row.archived) actionButton(ctx, "恢复", "session-restore", width = 54f, payload = row.id)
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.periodButton(
    ctx: TalkToAiPager,
    title: String,
    period: String,
) {
    Button {
        attr {
            size(58f, 30f)
            marginRight(6f)
            borderRadius(9f)
            backgroundColor(if (ctx.viewModel.marketPeriod == period) ThemeColors.accent else ThemeColors.surfaceVariant)
            titleAttr {
                text(title)
                fontSize(12f)
                color(if (ctx.viewModel.marketPeriod == period) ThemeColors.onAccent else ThemeColors.onSurfaceVariant)
            }
        }
        event { click { ctx.viewModel.loadMarket(period) } }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.actionButton(
    ctx: TalkToAiPager,
    title: String,
    action: String,
    width: Float = 72f,
    payload: String = "",
    dynamicTitle: (() -> String)? = null,
) {
    Button {
        attr {
            height(30f)
            width(width)
            marginRight(8f)
            borderRadius(9f)
            backgroundColor(ThemeColors.surfaceVariant)
            titleAttr { text(dynamicTitle?.invoke() ?: title); fontSize(12f); color(ThemeColors.onSurface) }
        }
        event {
            click {
                if (action == "market-query") ctx.viewModel.loadMarket(ctx.viewModel.marketPeriod)
                else if (action == "market-custom") ctx.viewModel.loadCustomMarket()
                else if (action == "copy") ctx.viewModel.copyTranscript()
                else if (action == "retry") ctx.viewModel.retry()
                else if (action == "stop") ctx.viewModel.stop()
                else if (action == "new") ctx.viewModel.newSession()
                else if (action == "generation") {
                    if (ctx.viewModel.isGenerating) ctx.viewModel.stop() else ctx.viewModel.newSession()
                }
                else if (action == "theme") ctx.viewModel.cycleTheme()
                else if (action == "sessions") {
                    ctx.viewModel.status = "正在打开会话管理…"
                    ctx.viewModel.toggleSessionPanel()
                }
                else if (action == "tools") {
                    ctx.viewModel.status = "正在打开插件与诊断…"
                    ctx.viewModel.toggleToolsPanel()
                }
                else if (action == "feedback-export") ctx.viewModel.exportFeedback()
                else if (action == "attachment-remove") ctx.viewModel.removeAttachment(payload)
                else if (action == "sessions-active") ctx.viewModel.showArchivedSessions = false
                else if (action == "sessions-archived") ctx.viewModel.showArchivedSessions = true
                else if (action == "sessions-new") { ctx.viewModel.newSession(); ctx.viewModel.showSessionPanel = false }
                else if (action == "session-rename") ctx.viewModel.renameCurrent()
                else if (action == "session-export") ctx.viewModel.exportCurrent()
                else if (action == "session-archive") ctx.viewModel.archiveCurrent()
                else if (action == "session-delete") ctx.viewModel.deleteCurrent()
                else if (action == "session-restore") ctx.viewModel.restoreArchived(payload)
            }
        }
    }
}

private fun drawRectangle(
    canvas: com.tencent.kuikly.core.views.CanvasContext,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
) {
    canvas.beginPath()
    canvas.moveTo(left, top)
    canvas.lineTo(left + width, top)
    canvas.lineTo(left + width, top + height)
    canvas.lineTo(left, top + height)
    canvas.closePath()
    canvas.fill()
}
