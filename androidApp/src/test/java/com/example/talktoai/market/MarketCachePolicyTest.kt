package com.example.talktoai.market

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCachePolicyTest {
    @Test
    fun intradayCacheExpiresAfterOneMinute() {
        assertTrue(MarketCachePolicy.isReusable("intraday", false, 1_000L, 61_000L))
        assertFalse(MarketCachePolicy.isReusable("intraday", false, 1_000L, 61_001L))
    }

    @Test
    fun historicalExactRangeIsReusableForSevenDays() {
        val sevenDaysMs = 7L * 24L * 60L * 60_000L
        assertTrue(MarketCachePolicy.isReusable("day", true, 0L, sevenDaysMs))
        assertFalse(MarketCachePolicy.isReusable("day", true, 0L, sevenDaysMs + 1L))
    }

    @Test
    fun futureAndMissingTimestampsAreRejected() {
        assertFalse(MarketCachePolicy.isReusable("day", false, -1L, 1_000L))
        assertFalse(MarketCachePolicy.isReusable("day", false, 2_000L, 1_000L))
    }
}
