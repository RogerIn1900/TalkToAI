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
import com.tencent.kuikly.core.directives.vfor
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
        val pageWidth = pagerData.pageViewWidth
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
                        padding(left = 12f, right = 14f)
                        flexDirectionRow()
                        alignItemsCenter()
                        justifyContentSpaceBetween()
                        backgroundColor(ThemeColors.surface)
                        borderBottom(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
                    }
                    View {
                        attr { flexDirectionRow(); alignItemsCenter() }
                        Button {
                            attr {
                                size(38f, 38f); marginRight(8f); borderRadius(12f)
                                backgroundColor(ThemeColors.surfaceVariant)
                                titleAttr { text("☰"); fontSize(20f); color(ThemeColors.onSurface) }
                            }
                            event { click { ctx.viewModel.toggleSidebar() } }
                        }
                        Text {
                            attr {
                                text("TalkToAI")
                                fontSize(19f)
                                fontWeightSemiBold()
                                color(ThemeColors.onSurface)
                            }
                        }
                    }
                    Text {
                        attr {
                            text("A股 · hy3 · 只读")
                            fontSize(12f)
                            color(ThemeColors.onSurfaceVariant)
                        }
                    }
                }

                View {
                    attr {
                        height(46f); padding(left = 12f, right = 12f, top = 8f, bottom = 4f)
                        flexDirectionRow(); backgroundColor(ThemeColors.background)
                    }
                    tabButton(ctx, "对话", TalkUiPolicy.TAB_CHAT)
                    tabButton(ctx, "行情", TalkUiPolicy.TAB_MARKET)
                }

                vif({ ctx.viewModel.activeTab == TalkUiPolicy.TAB_CHAT }) {
                    chatTabContent(ctx, pageWidth)
                }
                vif({ ctx.viewModel.activeTab == TalkUiPolicy.TAB_MARKET }) {
                    marketTabContent(ctx)
                }

                vif({ ctx.viewModel.activeTab == TalkUiPolicy.TAB_CHAT && ctx.viewModel.pendingAttachments.isNotEmpty() }) {
                    Scroller {
                        attr { height(34f); margin(left = 12f, right = 12f, top = 6f); flexDirectionRow() }
                        ctx.viewModel.pendingAttachments.forEach { attachment ->
                            val state = when (attachment.uploadState) { "ready" -> "✓"; "failed" -> "!"; else -> "…" }
                            actionButton(ctx, "📎 ${attachment.name.take(7)} $state ×", "attachment-remove", width = 112f, payload = attachment.id)
                        }
                    }
                }

                vif({ ctx.viewModel.activeTab == TalkUiPolicy.TAB_CHAT }) {
                    View {
                        attr {
                            margin(left = 12f, right = 12f, top = 8f, bottom = 10f)
                            height(50f); flexDirectionRow(); alignItemsCenter()
                            padding(left = 6f, right = 6f); borderRadius(17f)
                            backgroundColor(ThemeColors.surface)
                            border(Border(0.8f, BorderStyle.SOLID, ThemeColors.divider))
                        }
                        Button {
                            attr {
                                size(38f, 38f); marginRight(4f); borderRadius(12f)
                                backgroundColor(ThemeColors.surfaceVariant)
                                titleAttr { text("＋"); color(ThemeColors.onSurface); fontSize(20f) }
                            }
                            event { click { ctx.viewModel.pickAttachment() } }
                        }
                        Input {
                            ref { ctx.inputRef = it }
                            attr {
                                flex(1f); height(40f); placeholder("问行情、财报或股票基础知识")
                                fontSize(15f); color(ThemeColors.onSurface)
                                if (ctx.viewModel.input.isNotEmpty()) text(ctx.viewModel.input)
                            }
                            event { textDidChange { ctx.viewModel.updateInput(it.text) } }
                        }
                        Button {
                            attr {
                                size(68f, 38f); borderRadius(12f); backgroundColor(ThemeColors.accent)
                                titleAttr {
                                    text(if (ctx.viewModel.isGenerating) "停止" else "发送")
                                    color(ThemeColors.onAccent); fontSize(14f)
                                }
                            }
                            event {
                                click {
                                    if (ctx.viewModel.isGenerating) ctx.viewModel.stop()
                                    else if (ctx.viewModel.send()) ctx.inputRef.view?.setText("")
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
                vif({ ctx.viewModel.showSidebar }) {
                    sidebarPanel(ctx, safeTop, safeBottom, pageWidth)
                }
            }
        }
    }

}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.tabButton(
    ctx: TalkToAiPager,
    title: String,
    tab: String,
) {
    Button {
        attr {
            flex(1f); height(34f); margin(left = 3f, right = 3f); borderRadius(11f)
            backgroundColor(if (ctx.viewModel.activeTab == tab) ThemeColors.accent else ThemeColors.surface)
            titleAttr {
                text(title); fontSize(14f); fontWeightSemiBold()
                color(if (ctx.viewModel.activeTab == tab) ThemeColors.onAccent else ThemeColors.onSurfaceVariant)
            }
        }
        event { click { ctx.viewModel.selectTab(tab) } }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.chatTabContent(
    ctx: TalkToAiPager,
    pageWidth: Float,
) {
    Scroller {
        attr {
            flex(1f); padding(left = 12f, right = 12f, top = 8f, bottom = 8f)
            backgroundColor(ThemeColors.background)
        }
        vif({ ctx.viewModel.messages.isEmpty() }) {
            View {
                attr {
                    padding(18f); marginTop(20f); borderRadius(16f)
                    backgroundColor(ThemeColors.surface); alignItemsCenter()
                }
                Text {
                    attr {
                        text("你好，我是 TalkToAI\n可以问我 A 股行情、财报和基础概念")
                        fontSize(15f); lineHeight(23f); color(ThemeColors.onSurface); textAlignCenter()
                    }
                }
                Text {
                    attr {
                        text("仅供信息参考，不构成投资建议 · 禁止下单交易")
                        marginTop(8f); fontSize(11f); color(ThemeColors.onSurfaceVariant)
                    }
                }
            }
        }
        vfor({ ctx.viewModel.messages }) { message ->
            messageRow(ctx, message, pageWidth)
        }
        View {
            attr {
                margin(top = 6f, bottom = 4f); padding(left = 8f, right = 8f, top = 7f, bottom = 7f)
                borderRadius(9f); backgroundColor(ThemeColors.surface)
            }
            Text {
                attr {
                    text(ctx.viewModel.status); fontSize(11f)
                    color(if (ctx.viewModel.online) ThemeColors.onSurfaceVariant else ThemeColors.error)
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.messageRow(
    ctx: TalkToAiPager,
    message: ChatMessageUi,
    pageWidth: Float,
) {
    val isUser = message.role == "user"
    var selectableBubble: ViewRef<DivView>? = null
    View {
        attr {
            width(pageWidth - 24f); marginBottom(12f); flexDirectionRow()
            alignItemsFlexStart()
            if (isUser) justifyContentFlexEnd()
        }
        if (!isUser) messageAvatar(ctx, ai = true)
        View {
            ref { selectableBubble = it }
            attr {
                if (isUser) maxWidth(pageWidth * 0.72f) else width(pageWidth * 0.72f)
                if (ctx.viewModel.bubbleStyle == TalkUiPolicy.BUBBLE_COMPACT) {
                    padding(9f); borderRadius(8f)
                } else {
                    padding(12f); borderRadius(15f)
                }
                backgroundColor(if (isUser) ThemeColors.accent else ThemeColors.surface)
                if (ctx.viewModel.bubbleStyle == TalkUiPolicy.BUBBLE_OUTLINE && !isUser) {
                    border(Border(1f, BorderStyle.SOLID, ThemeColors.accent))
                }
                selectable(SelectableOption.ENABLE)
                selectionColor(ThemeColors.accent)
            }
            event {
                longPress {
                    if (it.state == "start") {
                        selectableBubble?.view?.createSelection(it.x, it.y, SelectionType.WORD)
                    }
                }
            }
            Text {
                attr {
                    if (isUser) maxWidth(pageWidth * 0.72f - 24f) else width(pageWidth * 0.72f - 24f)
                    text(message.content); fontSize(15f); lineHeight(22f)
                    color(if (isUser) ThemeColors.onAccent else ThemeColors.onSurface)
                }
            }
            if (message.attachmentNames.isNotEmpty()) {
                Text {
                    attr {
                        text(message.attachmentNames.joinToString(separator = "\n") { "📎 $it" })
                        marginTop(8f); fontSize(12f)
                        color(if (isUser) ThemeColors.onAccent else ThemeColors.onSurfaceVariant)
                    }
                }
            }
            if (!isUser && message.citations.isNotEmpty()) {
                Text {
                    attr {
                        text("来源\n${message.citations.joinToString("\n")}")
                        marginTop(8f); fontSize(11f); lineHeight(16f); color(ThemeColors.onSurfaceVariant)
                    }
                }
            }
            View {
                attr { flexDirectionRow(); marginTop(8f); alignItemsCenter() }
                messageActionButton(ctx, "复制", "message-copy", message.id)
                if (!isUser) {
                    messageActionButton(
                        ctx,
                        if (message.liked) "已赞" else "点赞",
                        "message-like",
                        message.id,
                    )
                    if (TalkUiPolicy.canRegenerate(ctx.viewModel.messages, message.id)) {
                        messageActionButton(ctx, "重新生成", "message-regenerate", message.id, width = 72f)
                    }
                }
            }
        }
        if (isUser) messageAvatar(ctx, ai = false)
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.messageAvatar(ctx: TalkToAiPager, ai: Boolean) {
    if (ctx.viewModel.avatarStyle == TalkUiPolicy.AVATAR_MINIMAL) return
    View {
        attr {
            size(32f, 32f); margin(left = 7f, right = 7f); alignItemsCenter(); justifyContentCenter()
            borderRadius(if (ctx.viewModel.avatarStyle == TalkUiPolicy.AVATAR_ROUND) 16f else 9f)
            backgroundColor(if (ai) ThemeColors.surfaceVariant else ThemeColors.accent)
        }
        Text {
            attr {
                text(if (ai) "AI" else "我"); fontSize(11f); fontWeightSemiBold()
                color(if (ai) ThemeColors.onSurface else ThemeColors.onAccent)
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.messageActionButton(
    ctx: TalkToAiPager,
    title: String,
    action: String,
    payload: String,
    width: Float = 50f,
) {
    Button {
        attr {
            width(width); height(26f); marginRight(5f); borderRadius(8f)
            backgroundColor(ThemeColors.surfaceVariant)
            titleAttr { text(title); fontSize(10f); color(ThemeColors.onSurfaceVariant) }
        }
        event {
            click {
                when (action) {
                    "message-copy" -> ctx.viewModel.copyMessage(payload)
                    "message-like" -> ctx.viewModel.toggleLike(payload)
                    "message-regenerate" -> ctx.viewModel.regenerate(payload)
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.marketTabContent(ctx: TalkToAiPager) {
    View {
        attr {
            flex(1f); margin(left = 12f, right = 12f, top = 8f, bottom = 12f); padding(14f)
            borderRadius(14f); backgroundColor(ThemeColors.surface)
            border(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
        }
        Text {
            attr { text(ctx.viewModel.marketSummary); fontSize(13f); lineHeight(19f); color(ThemeColors.onSurfaceVariant) }
        }
        View {
            attr {
                flexDirectionRow(); alignItemsCenter(); marginTop(12f); height(38f)
                padding(left = 10f); borderRadius(10f); backgroundColor(ThemeColors.background)
            }
            Input {
                attr {
                    flex(1f); height(36f); color(ThemeColors.onSurface)
                    placeholder("股票代码，如 600000.SH"); text(ctx.viewModel.marketSymbol); fontSize(13f)
                }
                event { textDidChange { ctx.viewModel.marketSymbol = it.text } }
            }
            actionButton(ctx, "查询", "market-query", width = 58f)
        }
        View {
            attr { flexDirectionRow(); marginTop(10f) }
            periodButton(ctx, "分时", "intraday")
            periodButton(ctx, "日", "day")
            periodButton(ctx, "周", "week")
            periodButton(ctx, "月", "month")
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); marginTop(10f) }
            Input {
                attr { width(96f); height(32f); fontSize(11f); color(ThemeColors.onSurface); placeholder("起始 YYYY-MM-DD") }
                event { textDidChange { ctx.viewModel.marketFrom = it.text } }
            }
            Text { attr { text(" 至 "); fontSize(11f); color(ThemeColors.onSurfaceVariant) } }
            Input {
                attr { width(96f); height(32f); fontSize(11f); color(ThemeColors.onSurface); placeholder("结束 YYYY-MM-DD") }
                event { textDidChange { ctx.viewModel.marketTo = it.text } }
            }
            actionButton(ctx, "自定义", "market-custom", width = 64f)
        }
        Text {
            attr { text("K 线与成交量"); marginTop(14f); fontSize(15f); fontWeightSemiBold(); color(ThemeColors.onSurface) }
        }
        Canvas({
            attr { flex(1f); marginTop(8f); backgroundColor(ThemeColors.background); borderRadius(10f) }
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
                    canvas.strokeStyle(color); canvas.fillStyle(color); canvas.lineWidth(1f)
                    canvas.beginPath(); canvas.moveTo(x, highY); canvas.lineTo(x, lowY); canvas.stroke()
                    val top = minOf(openY, closeY)
                    val bodyHeight = max(kotlin.math.abs(openY - closeY), 1.5f)
                    drawRectangle(canvas, x - candleWidth / 2f, top, candleWidth, bodyHeight)
                    val volumeHeight = (bar.volume / maxVolume) * (height - volumeTop - 4f)
                    drawRectangle(canvas, x - candleWidth / 2f, height - volumeHeight, candleWidth, volumeHeight)
                }
            }
        }
        Text {
            attr {
                text("行情会显示数据时间与新鲜度；过期数据不会标记为实时。")
                marginTop(8f); fontSize(11f); color(ThemeColors.onSurfaceVariant)
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.sidebarPanel(
    ctx: TalkToAiPager,
    safeTop: Float,
    safeBottom: Float,
    pageWidth: Float,
) {
    View {
        attr {
            positionAbsolute(); left(0f); right(0f); top(0f); bottom(0f)
            zIndex(60, false); backgroundColor(ThemeColors.overlay)
        }
        event { click { ctx.viewModel.toggleSidebar() } }
    }
    View {
        attr {
            positionAbsolute(); left(0f); top(0f); bottom(0f); width(pageWidth * 0.84f)
            zIndex(61, false); padding(top = safeTop + 14f, bottom = safeBottom + 14f)
            backgroundColor(ThemeColors.surface); flexDirectionColumn()
        }
        View {
            attr { padding(left = 16f, right = 12f, bottom = 14f); flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween() }
            View {
                Text { attr { text("TalkToAI"); fontSize(21f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
                Text { attr { text("A 股智能咨询 · 禁止交易"); marginTop(3f); fontSize(11f); color(ThemeColors.onSurfaceVariant) } }
            }
            actionButton(ctx, "关闭", "sidebar", width = 54f)
        }
        Scroller {
            attr { flex(1f); padding(left = 12f, right = 12f) }
            sidebarEntry(ctx, "💬", "会话选择", "管理、改名、归档与删除", "sessions")
            sidebarEntry(ctx, "🧠", "模型选择", "腾讯混元 hy3（首版唯一）", "model")
            sidebarEntry(ctx, "🛠", "工具选择", "A 股行情查询（只读）", "market-tab")
            sidebarEntry(ctx, "📈", "股市选择", "A 股（首版唯一）", "market")
            sidebarEntry(ctx, "🔌", "插件和诊断", "插件状态、日志与反馈包", "tools")
            sidebarEntry(
                ctx,
                "🙂",
                "自定义图标",
                TalkUiPolicy.avatarStyleLabel(ctx.viewModel.avatarStyle),
                "avatar-style",
            )
            sidebarEntry(ctx, "🔎", "查询", "打开行情查询中心", "market-tab")
            sidebarEntry(ctx, "◐", "主题选择", ctx.viewModel.themeLabel(), "theme")
            sidebarEntry(
                ctx,
                "◻",
                "气泡样式",
                TalkUiPolicy.bubbleStyleLabel(ctx.viewModel.bubbleStyle),
                "bubble-style",
            )
        }
        View {
            attr { padding(left = 12f, right = 12f, top = 10f) }
            Button {
                attr {
                    height(42f); borderRadius(12f); backgroundColor(ThemeColors.accent)
                    titleAttr { text("＋ 新会话"); fontSize(14f); color(ThemeColors.onAccent) }
                }
                event { click { ctx.viewModel.newSession(); ctx.viewModel.showSidebar = false } }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.sidebarEntry(
    ctx: TalkToAiPager,
    icon: String,
    title: String,
    subtitle: String,
    action: String,
) {
    View {
        attr {
            minHeight(58f); padding(left = 10f, right = 10f, top = 9f, bottom = 9f)
            marginBottom(6f); borderRadius(12f); backgroundColor(ThemeColors.background)
            flexDirectionRow(); alignItemsCenter()
        }
        event {
            click {
                when (action) {
                    "sessions" -> { ctx.viewModel.showSidebar = false; ctx.viewModel.toggleSessionPanel() }
                    "model" -> ctx.viewModel.modelNotice()
                    "market" -> ctx.viewModel.marketNotice()
                    "market-tab" -> ctx.viewModel.selectTab(TalkUiPolicy.TAB_MARKET)
                    "tools" -> { ctx.viewModel.showSidebar = false; ctx.viewModel.toggleToolsPanel() }
                    "theme" -> ctx.viewModel.cycleTheme()
                    "bubble-style" -> ctx.viewModel.cycleBubbleStyle()
                    "avatar-style" -> ctx.viewModel.cycleAvatarStyle()
                }
            }
        }
        Text { attr { text(icon); fontSize(21f); marginRight(12f) } }
        View {
            attr { flex(1f) }
            Text { attr { text(title); fontSize(14f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
            Text { attr { text(subtitle); marginTop(2f); fontSize(11f); color(ThemeColors.onSurfaceVariant) } }
        }
        Text { attr { text("›"); fontSize(20f); color(ThemeColors.onSurfaceVariant) } }
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
                else if (action == "sidebar") ctx.viewModel.toggleSidebar()
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
