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
import com.tencent.kuikly.core.base.event.layoutFrameDidChange
import com.tencent.kuikly.core.directives.vif
import com.tencent.kuikly.core.directives.vfor
import com.tencent.kuikly.core.module.NotifyModule
import com.tencent.kuikly.core.module.CalendarModule
import com.tencent.kuikly.core.module.ICalendar
import com.tencent.kuiklybase.KuiklyMarkdown
import com.tencent.kuiklybase.config.MarkdownColors
import com.tencent.kuiklybase.config.MarkdownConfig
import com.tencent.kuikly.core.views.DatePicker
import com.tencent.kuikly.core.views.DivView
import com.tencent.kuikly.core.views.Input
import com.tencent.kuikly.core.views.InputView
import com.tencent.kuikly.core.views.Scroller
import com.tencent.kuikly.core.views.List as LazyColumn
import com.tencent.kuikly.core.views.Refresh
import com.tencent.kuikly.core.views.RefreshViewState
import com.tencent.kuikly.core.views.ScrollerView
import com.tencent.kuikly.core.views.SelectableOption
import com.tencent.kuikly.core.views.SelectionType
import com.tencent.kuikly.core.views.Text
import com.tencent.kuikly.core.views.TextInputState
import com.tencent.kuikly.core.views.View
import com.tencent.kuikly.core.views.compose.Button
import com.tencent.kuikly.core.timer.setTimeout
import com.tencent.kuikly.core.timer.clearTimeout
import com.tencent.kuikly.core.nvi.serialization.json.JSONArray
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

@Page("talk_to_ai", supportInLocal = true)
internal class TalkToAiPager : BasePager() {
    private lateinit var inputRef: ViewRef<InputView>
    internal lateinit var chatScrollerRef: ViewRef<ScrollerView<*, *>>
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
        val pageWidth = pagerData.pageViewWidth
        return {
            View {
                attr {
                    flex(1f)
                    flexDirectionColumn()
                    backgroundColor(ThemeColors.background)
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
                            event { click { ctx.openSidebar() } }
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
                            text("A股 · ${TalkUiPolicy.modelLabel(ctx.viewModel.selectedModel)} · 只读")
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

                View {
                    attr { flex(1f) }
                    // Keep both pages mounted: tab navigation must not reset scroll or native chart state.
                    View {
                        attr { positionAbsolute(); left(0f); right(0f); top(0f); bottom(0f); visibility(ctx.viewModel.activeTab == TalkUiPolicy.TAB_CHAT) }
                        chatTabContent(ctx, pageWidth)
                    }
                    View {
                        attr { positionAbsolute(); left(0f); right(0f); top(0f); bottom(0f); visibility(ctx.viewModel.activeTab == TalkUiPolicy.TAB_MARKET) }
                        marketTabContent(ctx)
                    }
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
                                textInputState { ctx.viewModel.inputState }
                            }
                            event { textInputStateChange(isSyncEdit = true) { ctx.viewModel.updateInputState(it) } }
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
                                    else if (ctx.viewModel.send()) ctx.inputRef.view?.setTextInputState(TextInputState(""))
                                }
                            }
                        }
                    }
                }

                vif({ ctx.viewModel.activeTab == TalkUiPolicy.TAB_CHAT }) {
                    View {
                        attr { height(30f); alignItemsCenter(); justifyContentCenter() }
                        Text { attr { text("当前模型：${TalkUiPolicy.modelLabel(ctx.viewModel.selectedModel)} ▾"); fontSize(12f); color(ThemeColors.onSurfaceVariant) } }
                        event { click { ctx.inputRef.view?.blur(); ctx.viewModel.selectModel() } }
                    }
                }

                vif({ ctx.viewModel.showSessionPanel }) {
                    sessionPanel(ctx)
                }
                vif({ ctx.viewModel.showToolsPanel }) {
                    toolsPanel(ctx)
                }
                vif({ ctx.viewModel.showSettingsPanel }) {
                    settingsPanel(ctx)
                }
                vif({ ctx.viewModel.showSidebar }) {
                    sidebarPanel(ctx, pageWidth)
                }
                vif({ ctx.viewModel.showDatePicker }) {
                    marketDatePicker(ctx, pageWidth)
                }
            }
        }
    }

    private fun openSidebar() {
        if (::inputRef.isInitialized) inputRef.view?.blur()
        viewModel.openSidebar()
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
    var scrollTimeoutRef = ""
    var viewportHeight = 0f
    var measuredContentHeight = 0f
    var followLatest = true
    var readingOffset = 0f
    fun scheduleScroll() {
        if (ctx.viewModel.activeTab != TalkUiPolicy.TAB_CHAT) return
        if (measuredContentHeight <= 0f) return
        if (scrollTimeoutRef.isNotEmpty()) ctx.clearTimeout(scrollTimeoutRef)
        scrollTimeoutRef = ctx.setTimeout(SCROLL_TO_LATEST_DELAY_MS) {
            if (ctx.viewModel.activeTab != TalkUiPolicy.TAB_CHAT) return@setTimeout
            val latestRequest = ctx.viewModel.pendingScrollToLatestRequest()
            viewportHeight = ctx.chatScrollerRef.view?.flexNode?.layoutFrame?.height ?: viewportHeight
            if (viewportHeight <= 0f) return@setTimeout
            val bottomOffset = TalkUiPolicy.latestMessageOffset(measuredContentHeight, viewportHeight)
            val targetOffset = if (latestRequest != null || followLatest) bottomOffset else readingOffset.coerceIn(0f, bottomOffset)
            ctx.chatScrollerRef.view?.setContentOffset(0f, targetOffset, false)
            if (latestRequest != null) ctx.viewModel.consumeScrollToLatestRequest(latestRequest)
            if (latestRequest != null) followLatest = true
            scrollTimeoutRef = ""
        }
    }
    Scroller {
        ref { ctx.chatScrollerRef = it }
        attr {
            flex(1f); padding(left = 12f, right = 12f, top = 8f, bottom = 8f)
            backgroundColor(ThemeColors.background)
        }
        event {
            scroll { position ->
                if (ctx.viewModel.activeTab == TalkUiPolicy.TAB_CHAT) readingOffset = position.offsetY
                if (position.isDragging) {
                    followLatest = measuredContentHeight - viewportHeight - position.offsetY <= SCROLL_FOLLOW_THRESHOLD
                }
            }
            contentSizeChanged { _, contentHeight ->
                measuredContentHeight = contentHeight
                scheduleScroll()
            }
            layoutFrameDidChange { frame ->
                viewportHeight = frame.height
                scheduleScroll()
            }
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
        vif({ ctx.viewModel.hasEarlierMessages() }) {
            Button {
                attr {
                    height(36f); margin(left = 42f, right = 42f, bottom = 10f); borderRadius(12f)
                    backgroundColor(ThemeColors.surface)
                    titleAttr { text("加载更早的消息"); fontSize(13f); color(ThemeColors.accent) }
                }
                event { click { ctx.viewModel.loadEarlierMessages() } }
            }
        }
        vfor({ ctx.viewModel.renderedMessages }) { message ->
            View {
                messageRow(ctx, message, pageWidth)
                vif({
                    ctx.viewModel.showInlineMarketCard &&
                        ctx.viewModel.inlineMarketAnchorMessageId == message.id
                        && ctx.viewModel.messages.none { it.marketDataJson.isNotEmpty() }
                }) {
                    inlineMarketCard(ctx)
                }
            }
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

private const val SCROLL_TO_LATEST_DELAY_MS = 250
private const val SCROLL_FOLLOW_THRESHOLD = 32f

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.messageRow(
    ctx: TalkToAiPager,
    message: ChatMessageUi,
    pageWidth: Float,
) {
    val isUser = message.role == "user"
    val markdownBlocks = TalkUiPolicy.markdownBlocks(message.content)
    // A partial Markdown table changes on every SSE delta and would rebuild the native chart.
    // Render it once the answer is stable; the separately delivered market snapshot stays visible.
    val charts = if (TalkUiPolicy.shouldRenderDerivedCharts(message.role, message.status)) {
        TalkUiPolicy.chartData(message.content)
    } else emptyList()
    var selectableBubble: ViewRef<DivView>? = null
    fun selectionChanged(top: Float, height: Float) {
        ctx.viewModel.openSelection(message.id, top, height, selectableBubble?.view?.flexNode?.layoutFrame?.y ?: 0f)
    }
    View {
        attr {
            width(pageWidth - 24f); marginBottom(12f); flexDirectionRow()
            alignItemsFlexStart()
            if (isUser) justifyContentFlexEnd()
        }
        if (!isUser) messageAvatar(ctx, ai = true)
        View {
            attr {
                if (isUser) maxWidth(pageWidth * 0.72f) else width(pageWidth * 0.82f)
                if (isUser) alignItemsFlexEnd()
            }
            if (!isUser) {
                Text {
                    attr {
                        text("TalkToAI · ${TalkUiPolicy.modelLabel(ctx.viewModel.selectedModel)}")
                        margin(left = 4f, bottom = 5f); fontSize(10f); color(ThemeColors.onSurfaceVariant)
                    }
                }
            }
            if (!isUser && message.marketDataJson.isNotEmpty()) {
                marketOverviewCard(ctx, message, pageWidth * 0.82f)
            }
            View {
                ref { selectableBubble = it }
                attr {
                    if (isUser) maxWidth(pageWidth * 0.72f) else width(pageWidth * 0.82f)
                    if (ctx.viewModel.bubbleStyle == TalkUiPolicy.BUBBLE_COMPACT) {
                        padding(9f); borderRadius(7f)
                    } else if (ctx.viewModel.bubbleStyle == TalkUiPolicy.BUBBLE_OUTLINE) {
                        padding(12f); borderRadius(5f)
                    } else {
                        padding(12f); borderRadius(16f)
                    }
                    backgroundColor(
                        if (isUser && ctx.viewModel.bubbleStyle != TalkUiPolicy.BUBBLE_OUTLINE) {
                            ThemeColors.accent
                        } else {
                            ThemeColors.surface
                        },
                    )
                    if (ctx.viewModel.bubbleStyle == TalkUiPolicy.BUBBLE_OUTLINE) {
                        border(Border(1f, BorderStyle.SOLID, ThemeColors.accent))
                    } else {
                        border(Border(0f, BorderStyle.SOLID, ThemeColors.surface))
                    }
                    selectable(SelectableOption.ENABLE)
                    selectionColor(ThemeColors.accent)
                }
                event {
                    selectStart { frame -> selectionChanged(frame.y, frame.height) }
                    selectChange { frame -> selectionChanged(frame.y, frame.height) }
                    selectEnd { frame -> selectionChanged(frame.y, frame.height) }
                    selectCancel {
                        if (ctx.viewModel.selectedMessageId == message.id) ctx.viewModel.selectedMessageId = ""
                    }
                    longPress {
                        if (it.state == "start") {
                            selectableBubble?.view?.createSelection(it.x, it.y, SelectionType.WORD)
                        }
                    }
                }
                if (!isUser && message.status != "streaming" && message.content.isNotBlank()) {
                    // The maintained Kuikly renderer handles GFM tables, nested lists, links and code
                    // without exposing transport Markdown syntax to the user. Streaming keeps the
                    // lightweight fallback to avoid reparsing the complete answer for every delta.
                    KuiklyMarkdown(
                        content = TalkUiPolicy.contentWithoutChartTables(message.content),
                        config = talkMarkdownConfig(),
                    )
                } else {
                    markdownBlocks.forEachIndexed { index, block ->
                        Text {
                            attr {
                                if (isUser) maxWidth(pageWidth * 0.72f - 24f) else width(pageWidth * 0.82f - 24f)
                                text(block.text)
                                fontSize(if (block.kind == TalkUiPolicy.MARKDOWN_HEADING) 17f else if (block.kind == TalkUiPolicy.MARKDOWN_CODE) 13f else 15f)
                                lineHeight(if (block.kind == TalkUiPolicy.MARKDOWN_HEADING) 24f else 22f)
                                if (block.kind == TalkUiPolicy.MARKDOWN_HEADING) fontWeightSemiBold()
                                if (index > 0) marginTop(6f)
                                color(
                                    if (isUser && ctx.viewModel.bubbleStyle != TalkUiPolicy.BUBBLE_OUTLINE) {
                                        ThemeColors.onAccent
                                    } else {
                                        ThemeColors.onSurface
                                    },
                                )
                            }
                        }
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
            }
            charts.forEachIndexed { chartIndex, chart ->
                messageChartCard(ctx, message.id, chartIndex, chart, pageWidth * 0.82f)
            }
            if (!isUser && message.citations.isNotEmpty()) {
                sourceDisclosure(ctx, message)
            }
            vif({ ctx.viewModel.selectedMessageId == message.id }) {
                View {
                    attr {
                        positionAbsolute(); left(0f); top(ctx.viewModel.selectionToolbarTop); zIndex(25, false)
                        flexDirectionRow(); alignItemsCenter(); padding(4f); borderRadius(9f)
                        backgroundColor(ThemeColors.accent)
                    }
                    Text { attr { text("已选择"); margin(left = 5f, right = 5f); fontSize(10f); color(ThemeColors.onAccent) } }
                    selectionButton("复制选中") {
                        selectableBubble?.view?.getSelection { result ->
                            ctx.viewModel.copySelectedText(message.id, result.content)
                            selectableBubble?.view?.clearSelection()
                        }
                    }
                    selectionButton("全选") { selectableBubble?.view?.createSelectionAll() }
                    selectionButton("取消") {
                        selectableBubble?.view?.clearSelection()
                        ctx.viewModel.selectedMessageId = ""
                    }
                }
            }
            vif({ ctx.viewModel.selectedMessageId != message.id }) {
                View {
                    attr {
                        flexDirectionRow(); marginTop(5f); alignItemsCenter()
                        if (isUser) justifyContentFlexEnd()
                    }
                    messageActionButton(ctx, "复制", "message-copy", message.id)
                    messageActionButton(ctx, "分享", "message-share", message.id)
                    if (!isUser) {
                        vif({ message.status == "complete" }) {
                            messageActionButton(
                                ctx,
                                if (message.liked) "已赞" else "点赞",
                                "message-like",
                                message.id,
                            )
                            messageActionButton(
                                ctx,
                                if (message.disliked) "已踩" else "踩",
                                "message-dislike",
                                message.id,
                            )
                        }
                        vif({ TalkUiPolicy.canRegenerate(ctx.viewModel.messages, message.id) }) {
                            messageActionButton(
                                ctx,
                                if (message.status == "failed") "重新加载" else "重新生成",
                                "message-regenerate",
                                message.id,
                                width = 72f,
                            )
                        }
                    }
                }
            }
        }
        if (isUser) messageAvatar(ctx, ai = false)
    }
}

private fun talkMarkdownConfig(): MarkdownConfig {
    val colors = if (ThemeColors.isNightMode) {
        MarkdownColors(
            text = 0xFFE2E8F0,
            codeBackground = 0xFF0F172A,
            inlineCodeBackground = 0xFF1E293B,
            dividerColor = 0xFF475569,
            tableBackground = 0xFF1E293B,
            blockQuoteBar = 0xFF60A5FA,
            blockQuoteBackground = 0xFF172033,
            linkColor = 0xFF60A5FA,
            codeText = 0xFFF1F5F9,
        )
    } else {
        MarkdownColors(
            text = 0xFF1E293B,
            codeBackground = 0xFFF1F5F9,
            inlineCodeBackground = 0xFFE2E8F0,
            dividerColor = 0xFFCBD5E1,
            tableBackground = 0xFFF8FAFC,
            blockQuoteBar = 0xFF3B82F6,
            blockQuoteBackground = 0xFFEFF6FF,
            linkColor = 0xFF2563EB,
            codeText = 0xFF1E293B,
        )
    }
    return MarkdownConfig(
        colors = colors,
        codeHighlightDarkTheme = ThemeColors.isNightMode,
    )
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.messageChartCard(
    ctx: TalkToAiPager,
    messageId: String,
    chartIndex: Int,
    chart: ChartDataUi,
    width: Float,
) {
    val chartKey = "$messageId-$chartIndex"
    View {
        attr {
            width(width); height(if (ctx.viewModel.chartTableVisible(chartKey)) 500f else 330f)
            marginTop(8f); padding(10f); borderRadius(12f)
            backgroundColor(ThemeColors.surface)
            border(Border(0.8f, BorderStyle.SOLID, ThemeColors.divider))
        }
        Text {
            attr { text(chart.title); fontSize(13f); fontWeightSemiBold(); color(ThemeColors.onSurface) }
        }
        View {
            attr { height(32f); marginTop(5f); flexDirectionRow() }
            chartTypeButton(ctx, chartKey, "折线", TalkUiPolicy.CHART_LINE)
            chartTypeButton(ctx, chartKey, "柱状", TalkUiPolicy.CHART_BAR)
            vif({ TalkUiPolicy.canUsePie(chart) }) {
                chartTypeButton(ctx, chartKey, "饼状", TalkUiPolicy.CHART_PIE)
            }
            chartDataButton(ctx, chartKey)
        }
        DataChart {
            attr {
                flex(1f); marginTop(4f)
                chartType(ctx.viewModel.messageChartType(chartKey))
                chartData(chart.toJson())
                darkMode(ThemeColors.isNightMode)
            }
        }
        Text {
            attr {
                text("横轴：${chart.xLabel} · 纵轴：${chart.yLabel}")
                marginTop(3f); fontSize(9f); color(ThemeColors.onSurfaceVariant)
            }
        }
        vif({ ctx.viewModel.chartTableVisible(chartKey) }) {
            chartDataTable(chart)
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.chartDataButton(
    ctx: TalkToAiPager,
    chartKey: String,
) {
    Button {
        attr {
            val selected = ctx.viewModel.chartTableVisible(chartKey)
            width(58f); height(28f); marginRight(6f); borderRadius(8f)
            backgroundColor(if (selected) ThemeColors.accent else ThemeColors.surfaceVariant)
            titleAttr {
                text(if (ctx.viewModel.chartTableVisible(chartKey)) "收起" else "数据")
                fontSize(10f); color(if (ctx.viewModel.chartTableVisible(chartKey)) ThemeColors.onAccent else ThemeColors.onSurfaceVariant)
            }
        }
        event { click { ctx.viewModel.toggleChartTable(chartKey) } }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.chartDataTable(chart: ChartDataUi) {
    val headers = listOf(chart.xLabel) + chart.series.map { it.name }
    View {
        attr {
            marginTop(7f); borderRadius(8f)
            border(Border(0.7f, BorderStyle.SOLID, ThemeColors.divider))
        }
        compactTableRow(headers, header = true)
        TalkUiPolicy.chartTableRows(chart, limit = 5).forEach { row ->
            compactTableRow(row, header = false)
        }
        if (chart.labels.size > 5) {
            Text {
                attr {
                    text("仅显示前 5 行，共 ${chart.labels.size} 行")
                    margin(5f); fontSize(9f); color(ThemeColors.onSurfaceVariant)
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.compactTableRow(
    values: List<String>,
    header: Boolean,
) {
    View {
        attr {
            height(24f); flexDirectionRow()
            backgroundColor(if (header) ThemeColors.surfaceVariant else ThemeColors.surface)
            if (!header) borderTop(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
        }
        values.forEach { value ->
            Text {
                attr {
                    flex(1f); margin(left = 4f, right = 4f); text(value.take(14)); fontSize(8.5f)
                    if (header) fontWeightSemiBold()
                    color(ThemeColors.onSurface); textAlignCenter()
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.sourceDisclosure(
    ctx: TalkToAiPager,
    message: ChatMessageUi,
) {
    Button {
        attr {
            height(30f); marginTop(6f); borderRadius(9f); backgroundColor(ThemeColors.surfaceVariant)
            titleAttr {
                text("来源 ${message.citations.size} 条 ${if (ctx.viewModel.sourcesExpanded(message.id)) "⌃" else "⌄"}")
                fontSize(10f); color(ThemeColors.onSurfaceVariant)
            }
        }
        event { click { ctx.viewModel.toggleSources(message.id) } }
    }
    vif({ ctx.viewModel.sourcesExpanded(message.id) }) {
        View {
            attr {
                marginTop(5f); padding(9f); borderRadius(9f); backgroundColor(ThemeColors.surface)
                border(Border(0.7f, BorderStyle.SOLID, ThemeColors.divider))
            }
            message.citations.forEachIndexed { index, citation ->
                Text {
                    attr {
                        text("${index + 1}. $citation"); fontSize(10f); lineHeight(15f)
                        if (index > 0) marginTop(5f)
                        color(ThemeColors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.chartTypeButton(
    ctx: TalkToAiPager,
    chartKey: String,
    title: String,
    chartType: String,
) {
    View {
        attr {
            val selected = ctx.viewModel.messageChartType(chartKey) == chartType
            width(58f); height(32f); marginRight(6f); borderRadius(16f)
            alignItemsCenter(); justifyContentCenter()
            backgroundColor(if (selected) ThemeColors.accent else ThemeColors.surfaceVariant)
        }
        Text { attr {
            text(title); fontSize(11f)
            color(if (ctx.viewModel.messageChartType(chartKey) == chartType) ThemeColors.onAccent else ThemeColors.onSurfaceVariant)
        } }
        event { click { ctx.viewModel.selectMessageChartType(chartKey, chartType) } }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.selectionButton(
    title: String,
    onClick: () -> Unit,
) {
    Button {
        attr {
            width(62f); height(28f); marginRight(3f); borderRadius(7f); backgroundColor(ThemeColors.onSurface)
            titleAttr { text(title); fontSize(10f); color(ThemeColors.surface) }
        }
        event { click { onClick() } }
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
    View {
        attr {
            width(width); height(26f); marginRight(5f); borderRadius(8f)
            alignItemsCenter(); justifyContentCenter()
            backgroundColor(if (ctx.viewModel.messageActionSelected(action, payload)) ThemeColors.accent else ThemeColors.surfaceVariant)
        }
        Text { attr {
            text(ctx.viewModel.messageActionTitle(action, payload, title)); fontSize(10f)
            color(if (ctx.viewModel.messageActionSelected(action, payload)) ThemeColors.onAccent else ThemeColors.onSurfaceVariant)
        } }
        event {
            click {
                when (action) {
                    "message-copy" -> ctx.viewModel.copyMessage(payload)
                    "message-share" -> ctx.viewModel.shareMessage(payload)
                    "message-like" -> ctx.viewModel.toggleLike(payload)
                    "message-dislike" -> ctx.viewModel.toggleDislike(payload)
                    "message-regenerate" -> ctx.viewModel.regenerate(payload)
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.marketOverviewCard(ctx: TalkToAiPager, message: ChatMessageUi, cardWidth: Float) {
    val items = MarketOverviewPolicy.parse(message.marketDataJson)
    View {
        attr { width(cardWidth); padding(10f); marginTop(10f); borderRadius(14f); backgroundColor(ThemeColors.surface) }
        Text { attr { text("市场概览"); fontSize(19f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
        Text { attr { text("回答时行情快照 · 时间与来源以各卡片标注为准"); marginTop(5f); fontSize(10f); color(ThemeColors.onSurfaceVariant) } }
        View {
            attr { marginTop(10f); padding(10f); borderRadius(12f); backgroundColor(ThemeColors.surfaceVariant) }
            Text { attr { text("市场温度 · 指数样本"); fontSize(13f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
            Text { attr { text(MarketOverviewPolicy.sampleSummary(items)); marginTop(6f); fontSize(11f); color(ThemeColors.onSurfaceVariant) } }
            Text { attr { text("仅描述返回样本，不代表全市场；过期或测试数据不反映今日盘面。"); marginTop(5f); fontSize(10f); color(ThemeColors.onSurfaceVariant) } }
        }
        if (items.isEmpty()) Text { attr { text("没有可绘制的行情数据，请重新查询；不会用模型文本补造曲线。"); marginTop(10f); fontSize(12f); color(ThemeColors.onSurfaceVariant) } }
        items.forEach { item ->
            View {
                attr { marginTop(12f); padding(8f); borderRadius(12f); border(Border(0.7f, BorderStyle.SOLID, ThemeColors.divider)) }
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween() }
                    Text { attr { text(item.name); fontSize(15f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
                    Text {
                        attr { text("查看详情 ›"); fontSize(11f); color(ThemeColors.accent) }
                        event { click { ctx.viewModel.marketDetailVisible = true; ctx.viewModel.marketSymbol = item.snapshot.symbol; ctx.viewModel.loadMarket("day"); ctx.viewModel.selectTab(TalkUiPolicy.TAB_MARKET) } }
                    }
                }
                marketSnapshotHeader(item.snapshot, compact = true)
                DataChart { attr { height(200f); marginTop(6f); chartType("line"); chartData(item.chart.toJson()); darkMode(ThemeColors.isNightMode) } }
                Text { attr { text("横轴：交易日期 · 纵轴：收盘点位 / 价格"); fontSize(10f); color(ThemeColors.onSurfaceVariant) } }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.inlineMarketCard(ctx: TalkToAiPager) {
    View {
        attr {
            marginBottom(12f); padding(12f); borderRadius(14f); backgroundColor(ThemeColors.surface)
            border(Border(0.8f, BorderStyle.SOLID, ThemeColors.divider))
        }
        Text {
            attr {
                text("行情工具 · AI 解读依据"); fontSize(14f); fontWeightSemiBold(); color(ThemeColors.onSurface)
            }
        }
        vif({ ctx.viewModel.inlineMarketSnapshot != null }) {
            ctx.viewModel.inlineMarketSnapshot?.let { snapshot -> marketSnapshotHeader(snapshot, compact = true) }
        }
        vif({ ctx.viewModel.inlineMarketSnapshot == null }) {
            Text {
            attr {
                text(ctx.viewModel.inlineMarketSummary); marginTop(5f); fontSize(11f); lineHeight(17f)
                color(ThemeColors.onSurfaceVariant)
            }
            }
        }
        chartLegend()
        vif({ ctx.viewModel.inlineMarketBars.isNotEmpty() }) {
            MarketChart {
                attr {
                    height(270f); marginTop(8f); borderRadius(9f); backgroundColor(ThemeColors.background)
                    barsJson(ctx.viewModel.inlineMarketBars.toChartJson())
                    darkMode(ThemeColors.isNightMode)
                }
            }
        }
        Text {
            attr {
                text("图表先于模型回答返回；过期或测试数据不会标记为实时。")
                marginTop(6f); fontSize(10f); color(ThemeColors.onSurfaceVariant)
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.marketTabContent(ctx: TalkToAiPager) {
    View {
        attr { flex(1f) }
        View {
            attr { flexDirectionRow(); padding(10f) }
            listOf(false to "行情看板", true to "个股查询").forEach { (detail, title) ->
                View {
                    attr {
                        padding(10f); marginRight(8f); borderRadius(16f)
                        backgroundColor(if (ctx.viewModel.marketDetailVisible == detail) ThemeColors.accent else ThemeColors.surface)
                    }
                    Text { attr { text(title); fontSize(13f); color(if (ctx.viewModel.marketDetailVisible == detail) ThemeColors.onAccent else ThemeColors.onSurface) } }
                    event { click { ctx.viewModel.marketDetailVisible = detail } }
                }
            }
        }
        vif({ !ctx.viewModel.marketDetailVisible }) {
            EditableDashboard { attr { flex(1f); darkMode(ThemeColors.isNightMode) } }
        }
        vif({ ctx.viewModel.marketDetailVisible }) { marketDetailContent(ctx) }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.marketDetailContent(ctx: TalkToAiPager) {
    // Virtualize the detail page so the chart and metrics do not collapse into one viewport.
    LazyColumn {
        attr {
            flex(1f); margin(left = 12f, right = 12f, top = 8f, bottom = 12f); padding(14f)
            borderRadius(14f); backgroundColor(ThemeColors.surface)
            border(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider))
        }
        vif({ ctx.viewModel.marketSnapshot != null }) {
            ctx.viewModel.marketSnapshot?.let { snapshot -> marketSnapshotHeader(snapshot, compact = false) }
        }
        vif({ ctx.viewModel.marketSnapshot == null }) {
            Text {
                attr { text(ctx.viewModel.marketSummary); fontSize(13f); lineHeight(19f); color(ThemeColors.onSurfaceVariant) }
            }
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
            dateChoiceButton({ ctx.viewModel.marketFrom.ifEmpty { "起始日期" } }) {
                ctx.viewModel.openDatePicker(
                    TalkToAiViewModel.DATE_TARGET_FROM,
                    defaultMarketDate(ctx, dayOffset = -30),
                )
            }
            Text { attr { text(" 至 "); fontSize(11f); color(ThemeColors.onSurfaceVariant) } }
            dateChoiceButton({ ctx.viewModel.marketTo.ifEmpty { "结束日期" } }) {
                ctx.viewModel.openDatePicker(
                    TalkToAiViewModel.DATE_TARGET_TO,
                    defaultMarketDate(ctx),
                )
            }
            actionButton(ctx, "自定义", "market-custom", width = 64f)
        }
        Text {
            attr { text("K 线与成交量"); marginTop(14f); fontSize(15f); fontWeightSemiBold(); color(ThemeColors.onSurface) }
        }
        Text {
            attr {
                text("横轴：交易日期 / 时间 · 左纵轴：价格 · 下方纵轴：成交量")
                marginTop(4f); fontSize(10f); color(ThemeColors.onSurfaceVariant)
            }
        }
        chartLegend()
        vif({ ctx.viewModel.marketBars.isNotEmpty() }) {
            MarketChart {
                attr {
                    height(MARKET_DETAIL_CHART_HEIGHT_DP); marginTop(8f); backgroundColor(ThemeColors.background); borderRadius(10f)
                    barsJson(ctx.viewModel.marketBars.toChartJson())
                    darkMode(ThemeColors.isNightMode)
                }
            }
            vif({ ctx.viewModel.marketSnapshot != null }) {
                ctx.viewModel.marketSnapshot?.let { snapshot -> marketMetricsTable(snapshot) }
            }
        }
        vif({ ctx.viewModel.marketBars.isEmpty() }) {
            View {
                attr {
                    marginTop(8f); borderRadius(10f); backgroundColor(ThemeColors.background)
                }
                Text { attr { text(MarketChartSamples.DISCLAIMER); fontSize(12f); color(ThemeColors.accent) } }
                MarketChart { attr {
                    height(MARKET_DETAIL_CHART_HEIGHT_DP); barsJson(MarketChartSamples.bars().toChartJson()); darkMode(ThemeColors.isNightMode)
                } }
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

// dp: room for K-line, volume, axes, legend and fullscreen/reset controls, independent of keyboard height.
private const val MARKET_DETAIL_CHART_HEIGHT_DP = 420f

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.marketSnapshotHeader(
    snapshot: MarketSnapshotUi,
    compact: Boolean,
) {
    val moveColor = if (snapshot.change >= 0f) Color(0xFFE5484D) else Color(0xFF16A34A)
    View {
        attr {
            marginTop(if (compact) 6f else 0f); padding(if (compact) 9f else 11f)
            borderRadius(11f); backgroundColor(ThemeColors.background)
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween() }
            View {
                Text {
                    attr {
                        text(snapshot.symbol); fontSize(if (compact) 12f else 14f)
                        fontWeightSemiBold(); color(ThemeColors.onSurface)
                    }
                }
                View {
                    attr { flexDirectionRow(); alignItemsCenter(); marginTop(4f) }
                    Text {
                        attr {
                            text(TalkUiPolicy.formatQuote(snapshot.close)); fontSize(if (compact) 21f else 25f)
                            fontWeightSemiBold(); color(moveColor)
                        }
                    }
                    Text {
                        attr {
                            text("${TalkUiPolicy.formatSigned(snapshot.change)}  ${TalkUiPolicy.formatPercent(snapshot.changePercent)}")
                            marginLeft(8f); fontSize(11f); color(moveColor)
                        }
                    }
                }
            }
            View {
                attr {
                    padding(left = 8f, right = 8f, top = 5f, bottom = 5f); borderRadius(8f)
                    backgroundColor(if (snapshot.freshness.uppercase() == "FRESH") Color(0xFFE8F7EF) else Color(0xFFFFF3D6))
                }
                Text {
                    attr {
                        text(TalkUiPolicy.freshnessLabel(snapshot.freshness) + if (snapshot.clientCacheHit) " · 缓存" else "")
                        fontSize(9f); fontWeightSemiBold()
                        color(if (snapshot.freshness.uppercase() == "FRESH") Color(0xFF147D4D) else Color(0xFF9A6700))
                    }
                }
            }
        }
        Text {
            attr {
                text("市场时间 ${TalkUiPolicy.formatTimestamp(snapshot.marketTime).ifEmpty { "未知" }}")
                marginTop(6f); fontSize(9.5f); color(ThemeColors.onSurfaceVariant)
            }
        }
        if (snapshot.fetchedAt.isNotEmpty()) {
            Text {
                attr {
                    text("抓取时间 ${TalkUiPolicy.formatTimestamp(snapshot.fetchedAt)}")
                    marginTop(3f); fontSize(9.5f); color(ThemeColors.onSurfaceVariant)
                }
            }
        }
        Text {
            attr {
                text("来源 ${snapshot.source.ifEmpty { "未提供" }}")
                marginTop(3f); fontSize(9.5f); color(ThemeColors.onSurfaceVariant)
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.marketMetricsTable(snapshot: MarketSnapshotUi) {
    View {
        attr {
            marginTop(8f); borderRadius(9f); border(Border(0.7f, BorderStyle.SOLID, ThemeColors.divider))
        }
        compactTableRow(listOf("开", "高", "低", "收", "成交量"), header = true)
        compactTableRow(
            listOf(
                TalkUiPolicy.formatQuote(snapshot.open),
                TalkUiPolicy.formatQuote(snapshot.high),
                TalkUiPolicy.formatQuote(snapshot.low),
                TalkUiPolicy.formatQuote(snapshot.close),
                TalkUiPolicy.formatVolume(snapshot.volume),
            ),
            header = false,
        )
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.dateChoiceButton(
    title: () -> String,
    onClick: () -> Unit,
) {
    Button {
        attr {
            width(102f); height(34f); borderRadius(9f); backgroundColor(ThemeColors.background)
            border(Border(0.7f, BorderStyle.SOLID, ThemeColors.divider))
            titleAttr { text("📅 ${title()}"); fontSize(10f); color(ThemeColors.onSurface) }
        }
        event { click { onClick() } }
    }
}

private fun defaultMarketDate(ctx: TalkToAiPager, dayOffset: Int = 0): String {
    val calendar = ctx.acquireModule<CalendarModule>(CalendarModule.MODULE_NAME).newCalendarInstance()
    if (dayOffset != 0) calendar.add(ICalendar.Field.DAY_OF_YEAR, dayOffset)
    return TalkUiPolicy.formatIsoDate(
        calendar.get(ICalendar.Field.YEAR),
        calendar.get(ICalendar.Field.MONTH) + 1,
        calendar.get(ICalendar.Field.DAY_OF_MONTH),
    )
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.marketDatePicker(
    ctx: TalkToAiPager,
    pageWidth: Float,
) {
    View {
        attr {
            positionAbsolute(); left(0f); right(0f); top(0f); bottom(0f)
            zIndex(80, false); backgroundColor(ThemeColors.overlay)
        }
        event { click { ctx.viewModel.closeDatePicker() } }
    }
    View {
        attr {
            positionAbsolute(); left(18f); right(18f); top(120f); zIndex(81, false)
            padding(16f); borderRadius(16f); backgroundColor(ThemeColors.surface)
        }
        Text {
            attr {
                text(if (ctx.viewModel.datePickerTarget == TalkToAiViewModel.DATE_TARGET_FROM) "选择起始日期" else "选择结束日期")
                fontSize(17f); fontWeightSemiBold(); color(ThemeColors.onSurface)
            }
        }
        val parts = ctx.viewModel.pendingMarketDate.split("-").mapNotNull(String::toIntOrNull)
        DatePicker {
            attr {
                width(pageWidth - 68f); height(225f); marginTop(8f)
                if (parts.size == 3) initialDate(parts[0], parts[1], parts[2])
            }
            event {
                chooseEvent { picked ->
                    picked.date?.let { ctx.viewModel.updatePendingMarketDate(it.year, it.month, it.day) }
                }
            }
        }
        Text {
            attr {
                text("已选 ${ctx.viewModel.pendingMarketDate}"); fontSize(12f); color(ThemeColors.onSurfaceVariant)
            }
        }
        View {
            attr { height(38f); marginTop(10f); flexDirectionRow(); justifyContentFlexEnd() }
            Button {
                attr {
                    width(66f); height(36f); marginRight(8f); borderRadius(10f); backgroundColor(ThemeColors.surfaceVariant)
                    titleAttr { text("取消"); fontSize(13f); color(ThemeColors.onSurface) }
                }
                event { click { ctx.viewModel.closeDatePicker() } }
            }
            Button {
                attr {
                    width(66f); height(36f); borderRadius(10f); backgroundColor(ThemeColors.accent)
                    titleAttr { text("确定"); fontSize(13f); color(ThemeColors.onAccent) }
                }
                event { click { ctx.viewModel.confirmMarketDate() } }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.chartLegend() {
    View {
        attr { height(24f); marginTop(5f); flexDirectionRow(); alignItemsCenter() }
        legendItem("涨 / 阳线", Color(0xFFE5484D))
        legendItem("跌 / 阴线", Color(0xFF16A34A))
        legendItem("成交量", Color(0xFF64748B))
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.legendItem(title: String, color: Color) {
    View {
        attr { flexDirectionRow(); alignItemsCenter(); marginRight(12f) }
        View { attr { size(8f, 8f); marginRight(4f); borderRadius(2f); backgroundColor(color) } }
        Text { attr { text(title); fontSize(9f); color(ThemeColors.onSurfaceVariant) } }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.sidebarPanel(
    ctx: TalkToAiPager,
    pageWidth: Float,
) {
    View {
        attr {
            positionAbsolute(); left(0f); right(0f); top(0f); bottom(0f)
            zIndex(60, false); backgroundColor(ThemeColors.overlay)
        }
        event { click { ctx.viewModel.closeSidebar() } }
    }
    View {
        attr {
            positionAbsolute(); left(0f); top(0f); bottom(0f); width(pageWidth * 0.84f)
            zIndex(61, false); padding(top = 14f, bottom = 14f)
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
            Refresh {
                val refresh = this
                attr { height(52f); alignItemsCenter(); justifyContentCenter() }
                Text { attr { text("下拉刷新会话与插件状态"); fontSize(12f); color(ThemeColors.onSurfaceVariant) } }
                event { refreshStateDidChange { state ->
                    if (state == RefreshViewState.REFRESHING) ctx.viewModel.refreshSidebar { refresh.endRefresh() }
                } }
            }
            sidebarEntry(ctx, "🧠", "AI 与工具", "模型、只读工具和回答格式", "ai-settings")
            sidebarEntry(ctx, "📈", "行情中心", "A 股查询、周期和图表", "market-tab")
            sidebarEntry(ctx, "🔌", "插件和诊断", "插件状态、日志与反馈包", "tools")
            sidebarEntry(
                ctx,
                "◐",
                "外观设置",
                "${ctx.viewModel.themeLabel()} · ${TalkUiPolicy.bubbleStyleLabel(ctx.viewModel.bubbleStyle)}气泡",
                "appearance-settings",
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
        View {
            attr { height(330f); marginTop(12f); padding(12f); borderTop(Border(0.5f, BorderStyle.SOLID, ThemeColors.divider)) }
            Text { attr { text("历史会话"); fontSize(15f); fontWeightSemiBold(); color(ThemeColors.onSurface); marginBottom(8f) } }
            View {
                attr { flexDirectionRow(); marginBottom(8f) }
                settingChoiceButton("进行中", { !ctx.viewModel.showArchivedSessions }) { ctx.viewModel.showArchivedSessions = false }
                settingChoiceButton("已归档", { ctx.viewModel.showArchivedSessions }) { ctx.viewModel.showArchivedSessions = true }
            }
            Scroller {
                attr { flex(1f) }
                vif({ ctx.viewModel.visibleSessions().isEmpty() }) {
                    Text { attr { text("暂无会话"); fontSize(12f); color(ThemeColors.onSurfaceVariant) } }
                }
                vfor({ ctx.viewModel.sidebarSessions }) { row ->
                    View {
                    vif({ row.archived == ctx.viewModel.showArchivedSessions }) {
                    View {
                        attr { padding(10f); marginBottom(6f); borderRadius(10f); backgroundColor(if (row.id == ctx.viewModel.currentSessionId) ThemeColors.surfaceVariant else ThemeColors.surface) }
                        Text { attr { text(row.title); fontSize(13f); color(ThemeColors.onSurface); lineHeight(19f) } }
                        event { click { ctx.viewModel.openSession(row.id) } }
                        vif({ row.archived }) { actionButton(ctx, "恢复", "session-restore", width = 54f, payload = row.id) }
                    }
                    }
                    }
                }
            }
            vif({ ctx.viewModel.currentSessionId.isNotEmpty() }) {
                View {
                    attr { flexDirectionRow(); marginTop(6f) }
                    Input {
                        attr { flex(1f); height(32f); fontSize(12f); color(ThemeColors.onSurface); text(ctx.viewModel.renameDraft); placeholder("当前会话名称") }
                        event { textDidChange { ctx.viewModel.renameDraft = it.text } }
                    }
                    actionButton(ctx, "改名", "session-rename", width = 50f)
                }
                View {
                    attr { flexDirectionRow(); marginTop(4f) }
                    actionButton(ctx, "导出", "session-export", width = 54f)
                    actionButton(ctx, "归档", "session-archive", width = 54f)
                    actionButton(ctx, "删除", "session-delete", width = 54f)
                }
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
                    "ai-settings" -> ctx.viewModel.openSettings(TalkUiPolicy.SETTINGS_AI)
                    "appearance-settings" -> ctx.viewModel.openSettings(TalkUiPolicy.SETTINGS_APPEARANCE)
                    "market-tab" -> ctx.viewModel.selectTab(TalkUiPolicy.TAB_MARKET)
                    "tools" -> { ctx.viewModel.showSidebar = false; ctx.viewModel.toggleToolsPanel() }
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

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.settingsPanel(ctx: TalkToAiPager) {
    View {
        attr {
            positionAbsolute(); left(0f); right(0f); top(54f); bottom(0f)
            zIndex(46, false); padding(16f); backgroundColor(ThemeColors.background); flexDirectionColumn()
        }
        View {
            attr { flexDirectionRow(); alignItemsCenter(); justifyContentSpaceBetween(); marginBottom(14f) }
            Text {
                attr {
                    text(if (ctx.viewModel.settingsSection == TalkUiPolicy.SETTINGS_APPEARANCE) "外观设置" else "AI 与工具")
                    fontSize(20f); fontWeightSemiBold(); color(ThemeColors.onSurface)
                }
            }
            Button {
                attr {
                    width(56f); height(30f); borderRadius(9f); backgroundColor(ThemeColors.surfaceVariant)
                    titleAttr { text("关闭"); fontSize(12f); color(ThemeColors.onSurface) }
                }
                event { click { ctx.viewModel.closeSettings() } }
            }
        }
        if (ctx.viewModel.settingsSection == TalkUiPolicy.SETTINGS_AI) {
            settingsCard("模型", "${TalkUiPolicy.modelLabel(ctx.viewModel.selectedModel)}\n腾讯混元与 DeepSeek 均由测试后端代理，密钥不进入 APK。")
            settingsCard("工具", "A 股行情查询（只读）\n行情问题会先调用 MarketDataProvider，再把带来源、时间和新鲜度的数据交给 AI。")
            settingsCard("回答格式", "客户端隐藏 Markdown 标记；数值表格自动转换为图表，默认折线并可切换柱状或饼状。")
            Button {
                attr {
                    height(42f); marginTop(4f); borderRadius(12f); backgroundColor(ThemeColors.accent)
                    titleAttr { text("进入行情中心"); fontSize(14f); color(ThemeColors.onAccent) }
                }
                event { click { ctx.viewModel.selectTab(TalkUiPolicy.TAB_MARKET) } }
            }
        } else {
            settingsLabel("主题")
            View {
                attr { flexDirectionRow(); marginBottom(16f) }
                settingChoiceButton("跟随系统", { ctx.viewModel.themeMode == "system" }) { ctx.viewModel.selectTheme("system") }
                settingChoiceButton("浅色", { ctx.viewModel.themeMode == "light" }) { ctx.viewModel.selectTheme("light") }
                settingChoiceButton("深色", { ctx.viewModel.themeMode == "dark" }) { ctx.viewModel.selectTheme("dark") }
            }
            settingsLabel("气泡样式")
            View {
                attr { flexDirectionRow(); marginBottom(16f) }
                settingChoiceButton("柔和", { ctx.viewModel.bubbleStyle == TalkUiPolicy.BUBBLE_SOFT }) { ctx.viewModel.selectBubbleStyle(TalkUiPolicy.BUBBLE_SOFT) }
                settingChoiceButton("描边", { ctx.viewModel.bubbleStyle == TalkUiPolicy.BUBBLE_OUTLINE }) { ctx.viewModel.selectBubbleStyle(TalkUiPolicy.BUBBLE_OUTLINE) }
                settingChoiceButton("紧凑", { ctx.viewModel.bubbleStyle == TalkUiPolicy.BUBBLE_COMPACT }) { ctx.viewModel.selectBubbleStyle(TalkUiPolicy.BUBBLE_COMPACT) }
            }
            settingsLabel("头像样式")
            View {
                attr { flexDirectionRow(); marginBottom(16f) }
                settingChoiceButton("文字", { ctx.viewModel.avatarStyle == TalkUiPolicy.AVATAR_TEXT }) { ctx.viewModel.selectAvatarStyle(TalkUiPolicy.AVATAR_TEXT) }
                settingChoiceButton("圆形", { ctx.viewModel.avatarStyle == TalkUiPolicy.AVATAR_ROUND }) { ctx.viewModel.selectAvatarStyle(TalkUiPolicy.AVATAR_ROUND) }
                settingChoiceButton("极简", { ctx.viewModel.avatarStyle == TalkUiPolicy.AVATAR_MINIMAL }) { ctx.viewModel.selectAvatarStyle(TalkUiPolicy.AVATAR_MINIMAL) }
            }
            Text {
                attr {
                    text("选中项会立即高亮；关闭页面后可在对话中看到样式变化。")
                    fontSize(12f); lineHeight(18f); color(ThemeColors.onSurfaceVariant)
                }
            }
        }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.settingsCard(title: String, body: String) {
    View {
        attr { padding(14f); borderRadius(12f); backgroundColor(ThemeColors.surface); marginBottom(10f) }
        Text { attr { text(title); fontSize(15f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
        Text { attr { text(body); marginTop(6f); fontSize(13f); lineHeight(20f); color(ThemeColors.onSurfaceVariant) } }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.settingsLabel(title: String) {
    Text { attr { text(title); marginBottom(8f); fontSize(14f); fontWeightSemiBold(); color(ThemeColors.onSurface) } }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.settingChoiceButton(
    title: String,
    isSelected: () -> Boolean,
    onClick: () -> Unit,
) {
    View {
        attr {
            val selected = isSelected()
            flex(1f); height(38f); marginRight(7f); borderRadius(10f)
            alignItemsCenter(); justifyContentCenter()
            backgroundColor(if (selected) ThemeColors.accent else ThemeColors.surface)
            border(Border(if (selected) 1.5f else 0.7f, BorderStyle.SOLID, if (selected) ThemeColors.accent else ThemeColors.divider))
        }
        Text { attr { text(if (isSelected()) "✓ $title" else title); fontSize(12f); color(if (isSelected()) ThemeColors.onAccent else ThemeColors.onSurface) } }
        event { click { onClick() } }
    }
}

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.toolsPanel(ctx: TalkToAiPager) {
    View {
        attr {
            positionAbsolute(); left(0f); right(0f); top(54f); bottom(0f)
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

private fun com.tencent.kuikly.core.base.ViewContainer<*, *>.sessionPanel(ctx: TalkToAiPager) {
    View {
        attr {
            positionAbsolute()
            left(0f); right(0f); top(54f); bottom(0f)
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
        Input {
            attr {
                height(40f); marginBottom(10f)
                borderRadius(10f); backgroundColor(ThemeColors.surface)
                fontSize(14f); color(ThemeColors.onSurface)
                placeholder("搜索会话名称或内容")
                text(ctx.viewModel.sessionQuery)
            }
            event { textDidChange { ctx.viewModel.searchSessions(it.text) } }
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
                else if (action == "sidebar") ctx.viewModel.closeSidebar()
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

private fun List<MarketBarUi>.toChartJson(): String = JSONArray().apply {
    this@toChartJson.forEach { bar ->
        put(JSONObject().apply {
            put("time", bar.time)
            put("open", bar.open)
            put("high", bar.high)
            put("low", bar.low)
            put("close", bar.close)
            put("volume", bar.volume)
        })
    }
}.toString()
