package com.example.talktoai.chart

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartAxisPolicyTest {
    @Test fun groupedBarsAreOrderedNonOverlappingAndCenteredOnCategory() {
        for (count in 1..6) {
            val positions = (0 until count).map { BarGroupPolicy.entryX(2, it, count) }
            assertEquals(2f, positions.average().toFloat(), 0.0001f)
            positions.zipWithNext().forEach { (left, right) ->
                org.junit.Assert.assertTrue(right - left >= BarGroupPolicy.barWidth(count) - 0.0001f)
            }
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun emptyBarGroupIsRejected() { BarGroupPolicy.barWidth(0) }

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
