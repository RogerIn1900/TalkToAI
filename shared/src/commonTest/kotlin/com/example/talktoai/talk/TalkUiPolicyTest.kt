package com.example.talktoai.talk

import com.tencent.kuikly.core.views.TextInputState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TalkUiPolicyTest {
    @Test
    fun displayContentUsesExplicitStateFallbacks() {
        assertEquals("▋", TalkUiPolicy.displayContent("", "streaming"))
        assertEquals("生成失败，可点击重新生成", TalkUiPolicy.displayContent("", "failed"))
        assertEquals("生成已停止", TalkUiPolicy.displayContent("", "stopped"))
        assertEquals("已完成", TalkUiPolicy.displayContent("已完成", "done"))
    }

    @Test
    fun bubbleAndAvatarStylesCycleDeterministically() {
        assertEquals(TalkUiPolicy.BUBBLE_OUTLINE, TalkUiPolicy.nextBubbleStyle(TalkUiPolicy.BUBBLE_SOFT))
        assertEquals(TalkUiPolicy.BUBBLE_COMPACT, TalkUiPolicy.nextBubbleStyle(TalkUiPolicy.BUBBLE_OUTLINE))
        assertEquals(TalkUiPolicy.BUBBLE_SOFT, TalkUiPolicy.nextBubbleStyle(TalkUiPolicy.BUBBLE_COMPACT))
        assertEquals(TalkUiPolicy.AVATAR_ROUND, TalkUiPolicy.nextAvatarStyle(TalkUiPolicy.AVATAR_TEXT))
        assertEquals(TalkUiPolicy.AVATAR_MINIMAL, TalkUiPolicy.nextAvatarStyle(TalkUiPolicy.AVATAR_ROUND))
        assertEquals(TalkUiPolicy.AVATAR_TEXT, TalkUiPolicy.nextAvatarStyle(TalkUiPolicy.AVATAR_MINIMAL))
    }

    @Test
    fun regenerateOnlyAcceptsLatestAssistantMessage() {
        val user = message("user", "user")
        val assistant = message("assistant", "assistant")
        assertFalse(TalkUiPolicy.canRegenerate(listOf(user, assistant), user.id))
        assertTrue(TalkUiPolicy.canRegenerate(listOf(user, assistant), assistant.id))
        assertFalse(TalkUiPolicy.canRegenerate(listOf(assistant, user), assistant.id))
    }

    @Test
    fun marketIntentRecognizesTodayAndChartQuestionsWithoutMatchingUnrelatedText() {
        assertTrue(TalkUiPolicy.isMarketIntent("今日大盘数据怎么样"))
        assertTrue(TalkUiPolicy.isMarketIntent("600000.SH 的 K 线和成交量"))
        assertTrue(TalkUiPolicy.isMarketIntent("today market data"))
        assertFalse(TalkUiPolicy.isMarketIntent("今天学习 Kotlin 数据类"))
    }

    @Test
    fun markdownBlocksRenderHeadingsListsParagraphsAndCodeWithoutMarkers() {
        val blocks = TalkUiPolicy.markdownBlocks("# 结论\n\n**风险**可控\n- 来源可靠\n```json\n{\"ok\":true}\n```")
        assertEquals(
            listOf(
                MarkdownBlockUi(TalkUiPolicy.MARKDOWN_HEADING, "结论"),
                MarkdownBlockUi(TalkUiPolicy.MARKDOWN_PARAGRAPH, "风险可控"),
                MarkdownBlockUi(TalkUiPolicy.MARKDOWN_BULLET, "• 来源可靠"),
                MarkdownBlockUi(TalkUiPolicy.MARKDOWN_CODE, "{\"ok\":true}"),
            ),
            blocks,
        )
    }

    @Test
    fun markdownTableIsRemovedFromTextAndConvertedToReusableChartData() {
        val content = """
            ## 指数表现
            | 日期 | 上证 | 深证 |
            | --- | ---: | ---: |
            | 09-05 | 3100.5 | 9800 |
            | 09-06 | 3112.0 | 9850 |

            数据仅供参考。
        """.trimIndent()

        val blocks = TalkUiPolicy.markdownBlocks(content)
        assertFalse(blocks.any { it.text.contains("|") || it.text.contains("---") })
        val richText = TalkUiPolicy.contentWithoutChartTables(content)
        assertFalse(richText.contains("| 日期"))
        assertTrue(richText.contains("数据仅供参考"))
        val chart = TalkUiPolicy.chartData(content).single()
        assertEquals("指数表现", chart.title)
        assertEquals(listOf("09-05", "09-06"), chart.labels)
        assertEquals(listOf("上证", "深证"), chart.series.map { it.name })
        assertEquals(listOf(3100.5f, 3112.0f), chart.series.first().values)
        assertEquals(TalkUiPolicy.CHART_LINE, TalkUiPolicy.normalizeChartType(null))
        assertEquals(TalkUiPolicy.CHART_PIE, TalkUiPolicy.normalizeChartType(TalkUiPolicy.CHART_PIE))
    }

    @Test
    fun malformedOrNonnumericMarkdownTablesDoNotBecomeCharts() {
        val content = "| 名称 | 说明 |\n| --- | --- |\n| A | 上涨 |\n| B | 下跌 |"
        assertTrue(TalkUiPolicy.chartData(content).isEmpty())
        assertEquals(content, TalkUiPolicy.contentWithoutChartTables(content))
    }

    @Test
    fun pieChartIsOnlyOfferedForOneNonNegativePartToWholeSeries() {
        fun chart(vararg series: ChartSeriesUi) = ChartDataUi("t", listOf("A", "B"), "x", "y", series.toList())
        assertTrue(TalkUiPolicy.canUsePie(chart(ChartSeriesUi("占比", listOf(1f, 2f)))))
        assertFalse(TalkUiPolicy.canUsePie(chart(ChartSeriesUi("涨跌", listOf(-1f, 2f)))))
        assertFalse(TalkUiPolicy.canUsePie(chart(ChartSeriesUi("A", listOf(1f)), ChartSeriesUi("B", listOf(2f)))))
    }

    @Test
    fun appearanceValuesAreNormalized() {
        assertEquals(TalkUiPolicy.BUBBLE_SOFT, TalkUiPolicy.normalizeBubbleStyle("unknown"))
        assertEquals(TalkUiPolicy.BUBBLE_OUTLINE, TalkUiPolicy.normalizeBubbleStyle(TalkUiPolicy.BUBBLE_OUTLINE))
        assertEquals(TalkUiPolicy.AVATAR_MINIMAL, TalkUiPolicy.normalizeAvatarStyle(TalkUiPolicy.AVATAR_MINIMAL))
        assertEquals("system", TalkUiPolicy.normalizeTheme("unknown"))
    }

    @Test
    fun inputStatePreservesValidSelectionAndCompositionAndClampsStaleOffsets() {
        val composing = TextInputState("行情", selectionStart = 2, selectionEnd = 2, compositionStart = 0, compositionEnd = 2)
        assertEquals(composing, TalkUiPolicy.normalizeInputState(composing))

        assertEquals(
            TextInputState("A", selectionStart = 1, selectionEnd = 1, compositionStart = -1, compositionEnd = -1),
            TalkUiPolicy.normalizeInputState(
                TextInputState("A", selectionStart = 9, selectionEnd = 5, compositionStart = -1, compositionEnd = 4),
            ),
        )
    }

    @Test
    fun marketDatesValidateCalendarDaysAndAxisLabels() {
        assertTrue(TalkUiPolicy.isIsoDate("2024-02-29"))
        assertFalse(TalkUiPolicy.isIsoDate("2025-02-29"))
        assertFalse(TalkUiPolicy.isIsoDate("2025-13-01"))
        assertEquals("2026-09-07", TalkUiPolicy.formatIsoDate(2026, 9, 7))
        assertEquals("09-04", TalkUiPolicy.axisTimeLabel("2026-09-04T15:00:00+08:00"))
        assertEquals("09:30", TalkUiPolicy.axisTimeLabel("2026-09-04 09:30:00", preferTime = true))
    }

    private fun message(id: String, role: String) = ChatMessageUi(
        id = id,
        role = role,
        content = "content",
        status = "done",
        attachmentNames = emptyList(),
        citations = emptyList(),
    )
}
