package com.example.talktoai.chart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MarketChartPayloadTest {
    @Test
    fun parsesValidOhlcvBars() {
        val payload = MarketChartPayload.parse(
            """[{"time":"2026-09-08","open":10,"high":11,"low":9,"close":10.5,"volume":1234}]""",
        )

        assertEquals(1, payload.bars.size)
        assertEquals(10.5f, payload.bars.single().close)
    }

    @Test
    fun rejectsImpossiblePriceRangeAndNegativeVolume() {
        assertThrows(IllegalArgumentException::class.java) {
            MarketChartPayload.parse(
                """[{"time":"2026-09-08","open":10,"high":9,"low":8,"close":10.5,"volume":1234}]""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            MarketChartPayload.parse(
                """[{"time":"2026-09-08","open":10,"high":11,"low":9,"close":10.5,"volume":-1}]""",
            )
        }
    }

    @Test
    fun axisLabelsUseTimeForIntradayAndDateForMultipleDays() {
        assertEquals("09:35", MarketChartLabelPolicy.format("2026-09-08T09:35:00+08:00", distinctDates = 1))
        assertEquals("09-08", MarketChartLabelPolicy.format("2026-09-08T15:00:00+08:00", distinctDates = 4))
    }
}
