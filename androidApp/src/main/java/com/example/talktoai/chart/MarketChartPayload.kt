package com.example.talktoai.chart

import org.json.JSONArray

internal data class MarketChartPayload(val bars: List<Bar>) {
    data class Bar(
        val time: String,
        val open: Float,
        val high: Float,
        val low: Float,
        val close: Float,
        val volume: Float,
    )

    companion object {
        fun parse(raw: String): MarketChartPayload {
            val array = JSONArray(raw)
            val bars = List(array.length()) { index ->
                val item = array.getJSONObject(index)
                Bar(
                    time = item.getString("time"),
                    open = item.getDouble("open").toFloat(),
                    high = item.getDouble("high").toFloat(),
                    low = item.getDouble("low").toFloat(),
                    close = item.getDouble("close").toFloat(),
                    volume = item.getDouble("volume").toFloat(),
                ).also { bar ->
                    require(bar.low <= minOf(bar.open, bar.close) && bar.high >= maxOf(bar.open, bar.close))
                    require(bar.volume >= 0f)
                }
            }
            require(bars.isNotEmpty())
            return MarketChartPayload(bars)
        }
    }
}
