package com.example.talktoai.talk

import com.talktoai.marketui.MarketContent
import com.talktoai.marketui.MetricPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class MarketComponentAdapterTest {
    private val snapshot = MarketSnapshotUi(
        symbol = "600000.SH",
        freshness = "STALE",
        marketTime = "2026-09-12T15:00:00+08:00",
        fetchedAt = "2026-09-12T15:00:05+08:00",
        source = "provider fixture",
        clientCacheHit = true,
        open = 10f,
        high = 12f,
        low = 9f,
        close = 11f,
        volume = 2_439_100f,
        change = 1f,
        changePercent = 10f,
    )

    @Test
    fun readyProviderSnapshotMapsValuesAndFullProvenanceWithoutDemo() {
        val state = MarketRequestUiState.Ready(snapshot, emptyList())
        val prices = assertIs<MarketContent.Ready<MetricPoint>>(MarketComponentAdapter.prices(state)).block
        assertEquals(listOf("开盘", "最高", "最低", "收盘"), prices.items.map { it.label })
        assertEquals(listOf(10f, 12f, 9f, 11f), prices.items.map { it.value })
        assertEquals("provider fixture", prices.origin.source)
        assertTrue(prices.origin.asOf.contains("市场 2026-09-12T15:00:00+08:00"))
        assertTrue(prices.origin.asOf.contains("获取 2026-09-12T15:00:05+08:00"))
        assertTrue(prices.origin.status.contains("已过期"))
        assertTrue(prices.origin.status.contains("本地缓存"))
        assertFalse(prices.origin.simulated)

        val volume = assertIs<MarketContent.Ready<MetricPoint>>(MarketComponentAdapter.volume(state)).block
        assertEquals(listOf(MetricPoint("成交量", 2_439_100f)), volume.items)
    }

    @Test
    fun requestStatesStayPartitionedAndRetryPolicyIsPreserved() {
        assertIs<MarketContent.Loading>(MarketComponentAdapter.prices(MarketRequestUiState.Loading))

        val empty = assertIs<MarketContent.Empty>(
            MarketComponentAdapter.prices(
                MarketRequestUiState.Empty(MarketOriginUi("provider", "15:00", "15:00:05", "FRESH", false)),
            ),
        )
        assertEquals("provider", empty.origin?.source)

        val error = assertIs<MarketContent.Error>(
            MarketComponentAdapter.prices(MarketRequestUiState.Error("INVALID_SYMBOL", "代码错误", retryable = false)),
        )
        assertEquals("INVALID_SYMBOL", error.code)
        assertFalse(error.retryable)
    }

    @Test
    fun fixedFixtureOriginRemainsExplicitlySimulated() {
        val empty = assertIs<MarketContent.Empty>(
            MarketComponentAdapter.prices(
                MarketRequestUiState.Empty(
                    MarketOriginUi("TalkToAI 测试固定数据（非实时行情）", "15:00", "15:00:05", "STALE", false, simulated = true),
                ),
            ),
        )
        assertEquals(true, empty.origin?.simulated)
        assertTrue(TalkUiPolicy.isFixtureMarketSource(empty.origin?.source.orEmpty()))
    }
}
