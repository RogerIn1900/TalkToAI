package com.example.talktoai.talk

import kotlin.test.Test
import kotlin.test.assertEquals

class MarketMetricRowsTest {
    private fun snapshot(volume: Float) = MarketSnapshotUi("600000.SH", "STALE", "2026-09-01", "2026-09-01",
        "fixture", false, 10f, 12f, 9f, 11f, volume, 1f, 10f)

    @Test fun priceLabelsRetainCorrectValuesAndVolumeUsesOwnRow() {
        val rows = marketMetricRows(snapshot(2439100f))
        assertEquals(listOf(2, 2, 1), rows.map { it.size })
        assertEquals(listOf("开盘", "收盘", "最高", "最低", "成交量"), rows.flatten().map { it.first })
        assertEquals(listOf("10.0", "11.0", "12.0", "9.0"), rows.take(2).flatten().map { it.second })
        assertEquals(TalkUiPolicy.formatVolume(2439100f), rows.last().single().second)
    }

    @Test fun zeroAndLargeVolumeKeepExistingFormattingWithoutDroppingMetric() {
        listOf(0f, 1_200_000_000f).forEach { volume ->
            assertEquals(listOf("成交量" to TalkUiPolicy.formatVolume(volume)), marketMetricRows(snapshot(volume)).last())
        }
    }
}
