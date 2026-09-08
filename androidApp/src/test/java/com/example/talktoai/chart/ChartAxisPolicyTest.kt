package com.example.talktoai.chart

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAxisPolicyTest {
    @Test
    fun volumeUsesSecondaryAxisWhenItWouldFlattenPriceSeries() {
        val secondary = ChartAxisPolicy.secondarySeriesIndices(listOf(
            listOf(10f, 11f),
            listOf(12f, 13f),
            listOf(1_800_000f, 2_100_000f),
        ))

        assertEquals(setOf(2), secondary)
    }

    @Test
    fun comparableSeriesShareThePrimaryAxis() {
        assertEquals(
            emptySet<Int>(),
            ChartAxisPolicy.secondarySeriesIndices(listOf(listOf(10f, 11f), listOf(20f, 22f))),
        )
    }
}
