package com.example.talktoai.talk

import kotlin.test.*

class MarketChartSamplesTest {
    @Test fun sampleCandlesAreDeterministicAndInternallyValid() {
        assertEquals(MarketChartSamples.bars(), MarketChartSamples.bars())
        assertTrue(MarketChartSamples.DISCLAIMER.contains("模拟"))
        assertTrue(MarketChartSamples.bars().all {
            it.high >= maxOf(it.open, it.close) && it.low <= minOf(it.open, it.close) && it.volume > 0
        })
    }
}
