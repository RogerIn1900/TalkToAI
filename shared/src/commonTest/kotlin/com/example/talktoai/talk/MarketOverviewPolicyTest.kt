package com.example.talktoai.talk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarketOverviewPolicyTest {
    private val envelope = """{"symbol":"000001.SH","source":"测试数据","freshness":"STALE","marketTime":"2026-09-01","data":[{"time":"2026-09-01","open":10,"high":12,"low":9,"close":11,"volume":100},{"time":"2026-09-02","open":11,"high":13,"low":10,"close":12,"volume":120}]}"""

    @Test fun structuredDataCreatesChartWithoutAnyMarkdown() {
        val card = MarketOverviewPolicy.parse(envelope).single()
        assertEquals("上证指数", card.name)
        assertEquals(listOf(11f, 12f), card.chart.series.single().values)
        assertEquals("STALE", card.snapshot.freshness)
        assertEquals("测试数据", card.snapshot.source)
        assertTrue(MarketOverviewPolicy.sampleSummary(listOf(card)).contains("上涨 1"))
    }

    @Test fun multipleIndicesAreKeptAndInvalidRowsAreNotInvented() {
        val second = envelope.replace("000001.SH", "399001.SZ")
        val result = MarketOverviewPolicy.parse("""{"overview":[$envelope,$second,{"symbol":"bad","data":[]}]}""")
        assertEquals(listOf("上证指数", "深证成指"), result.map { it.name })
        assertTrue(MarketOverviewPolicy.parse("bad").isEmpty())
        assertTrue(MarketOverviewPolicy.parse(envelope.replace("\"volume\":100", "\"volume\":-1")).isEmpty())
    }

    @Test fun fixedBackendFixtureIsNotRenderedAsProductionOverview() {
        assertTrue(MarketOverviewPolicy.parse(envelope.replace("测试数据", "TalkToAI 测试固定数据（非实时行情）")).isEmpty())
    }

    @Test fun selectionToolbarIsBelowSelectionIncludingBubbleOffset() {
        val bottom = 22f + 100f + 48f
        assertTrue(TalkUiPolicy.selectionToolbarBelow(100f, 48f, 22f) > bottom)
        assertEquals(182f, TalkUiPolicy.selectionToolbarBelow(100f, 48f, 22f))
    }
}
