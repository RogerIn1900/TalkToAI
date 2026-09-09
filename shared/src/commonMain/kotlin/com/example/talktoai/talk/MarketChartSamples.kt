package com.example.talktoai.talk

/** UI-only candles. Never assign these to provider results, snapshots, cache or AI context. */
internal object MarketChartSamples {
    const val DISCLAIMER = "模拟数据 · 固定样例，非所选日期行情"
    fun bars(): List<MarketBarUi> = listOf(
        MarketBarUi("样例1", 10f, 11f, 9.5f, 10.5f, 12000f),
        MarketBarUi("样例2", 10.5f, 11.5f, 10f, 11f, 18000f),
        MarketBarUi("样例3", 11f, 11.2f, 10.2f, 10.4f, 15000f),
        MarketBarUi("样例4", 10.4f, 11.8f, 10.3f, 11.6f, 24000f),
    )
}
