package com.example.talktoai.talk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ChatAnswerBlocksTest {
    private fun message(
        content: String = "分析正文",
        status: String = "streaming",
        market: String = "",
        citations: List<String> = emptyList(),
    ) = ChatMessageUi("answer-1", "assistant", content, status, emptyList(), citations, marketDataJson = market)

    @Test fun structuredBlocksKeepMarketBeforeMarkdownAndSources() {
        val raw = """{"symbol":"600519.SH","source":"测试固定数据","marketTime":"2026-09-01","fetchedAt":"2026-09-01","freshness":"STALE","data":[]}"""
        val blocks = ChatAnswerBlockPolicy.blocks(message(market = raw, citations = listOf("https://example.com")))
        assertIs<ChatAnswerBlockUi.Market>(blocks[0])
        assertIs<ChatAnswerBlockUi.Markdown>(blocks[1])
        assertIs<ChatAnswerBlockUi.Citations>(blocks[2])
    }

    @Test fun streamDeltaReplacesCursorAndAccumulatesText() {
        val first = ChatStreamPatchPolicy.delta(message(content = "▋"), "贵")
        val second = ChatStreamPatchPolicy.delta(first, "州茅台")
        assertEquals("贵州茅台", second.content)
        assertEquals("streaming", second.status)
    }

    @Test fun citationPatchAcceptsOnlyDistinctHttpsUrls() {
        val original = message(citations = listOf("https://example.com/a"))
        assertEquals(original, ChatStreamPatchPolicy.citation(original, "http://unsafe.example"))
        assertEquals(original, ChatStreamPatchPolicy.citation(original, "https://example.com/a"))
        assertEquals(2, ChatStreamPatchPolicy.citation(original, "https://example.com/b").citations.size)
    }
}
