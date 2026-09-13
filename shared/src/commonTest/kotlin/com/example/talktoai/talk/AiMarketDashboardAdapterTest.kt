package com.example.talktoai.talk

import com.talktoai.marketui.MarketContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class AiMarketDashboardAdapterTest {
    @Test fun noAiMarketDataLeavesBlocksEmptyForSdkMockFallback() {
        val content = AiMarketDashboardAdapter.fromMessages(emptyList())
        assertIs<MarketContent.Empty>(content.indices)
        assertIs<MarketContent.Empty>(content.sectors)
    }

    @Test fun latestAiMarketPayloadBecomesRealIndexBlock() {
        val payload = """{"symbol":"000001.SH","source":"行情服务","freshness":"FRESH","marketTime":"2026-09-13","data":[{"time":"2026-09-12","open":10,"high":12,"low":9,"close":11,"volume":100},{"time":"2026-09-13","open":11,"high":13,"low":10,"close":12,"volume":120}]}"""
        val message = ChatMessageUi("m1", "assistant", "", "complete", emptyList(), emptyList(), marketDataJson = payload)
        val block = assertIs<MarketContent.Ready<*>>(AiMarketDashboardAdapter.fromMessages(listOf(message)).indices).block
        assertEquals("行情服务", block.origin.source)
        assertEquals(false, block.origin.simulated)
        assertEquals("000001.SH", (block.items.single() as com.talktoai.marketui.IndexTile).symbol)
    }
}
