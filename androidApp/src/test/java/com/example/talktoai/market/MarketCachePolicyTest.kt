package com.example.talktoai.market

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketCachePolicyTest {
    @Test
    fun onlyCompletedHistoricalRangeUsesLongCacheLifetime() {
        val now = java.time.Instant.parse("2026-09-07T02:00:00Z").toEpochMilli()
        assertTrue(MarketCachePolicy.isHistoricalRange("2026-09-01", "2026-09-06", now))
        assertFalse(MarketCachePolicy.isHistoricalRange("2026-09-01", "2026-09-07", now))
        assertFalse(MarketCachePolicy.isHistoricalRange("2026-09-01", "2026-09-08", now))
        assertFalse(MarketCachePolicy.isHistoricalRange("2026-09-06", "2026-09-01", now))
        assertFalse(MarketCachePolicy.isHistoricalRange(null, "invalid", now))
    }

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
