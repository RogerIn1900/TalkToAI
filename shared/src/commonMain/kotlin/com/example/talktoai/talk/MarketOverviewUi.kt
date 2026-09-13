package com.example.talktoai.talk

import com.tencent.kuikly.core.nvi.serialization.json.JSONObject

internal data class MarketOverviewItem(val name: String, val snapshot: MarketSnapshotUi, val chart: ChartDataUi)

internal object MarketOverviewPolicy {
    private val indexNames = mapOf(
        "000001.SH" to "上证指数",
        "399001.SZ" to "深证成指",
        "399006.SZ" to "创业板指",
        "600000.SH" to "浦发银行",
        "600519.SH" to "贵州茅台",
        "000001.SZ" to "平安银行",
        "300750.SZ" to "宁德时代",
        "002594.SZ" to "比亚迪",
        "600036.SH" to "招商银行",
        "601318.SH" to "中国平安",
        "688981.SH" to "中芯国际",
        "300059.SZ" to "东方财富",
    )

    fun parse(raw: String): List<MarketOverviewItem> = runCatching {
        val root = JSONObject(raw)
        val overview = root.optJSONArray("overview")
        val envelopes = if (overview == null) listOf(root) else
            (0 until overview.length()).mapNotNull { overview.optJSONObject(it) }
        envelopes.mapNotNull { envelope ->
            runCatching {
                if (TalkUiPolicy.isFixtureMarketSource(envelope.optString("source"))) return@mapNotNull null
                val rows = requireNotNull(envelope.optJSONArray("data"))
                val bars = (0 until rows.length()).map { index ->
                    val bar = requireNotNull(rows.optJSONObject(index))
                    MarketBarUi(bar.optString("time"), bar.optDouble("open", Double.NaN).toFloat(), bar.optDouble("high", Double.NaN).toFloat(),
                        bar.optDouble("low", Double.NaN).toFloat(), bar.optDouble("close", Double.NaN).toFloat(), bar.optDouble("volume", Double.NaN).toFloat()).also {
                        require(listOf(it.open, it.high, it.low, it.close, it.volume).all { value -> value.isFinite() })
                        require(it.volume >= 0 && it.low <= minOf(it.open, it.close) && it.high >= maxOf(it.open, it.close))
                    }
                }
                val snapshot = TalkUiPolicy.marketSnapshot(envelope, bars) ?: return@mapNotNull null
                val name = indexNames[snapshot.symbol] ?: snapshot.symbol
                MarketOverviewItem(name, snapshot, ChartDataUi(name, bars.map { TalkUiPolicy.axisTimeLabel(it.time) },
                    "交易日期", "收盘点位 / 价格", listOf(ChartSeriesUi(name, bars.map { it.close }))))
            }.getOrNull()
        }.distinctBy { it.snapshot.symbol }
    }.getOrDefault(emptyList())

    fun sampleSummary(items: List<MarketOverviewItem>): String {
        val comparable = items.filter { it.snapshot.changePercent != null }
        if (comparable.isEmpty()) return "数据不足，无法统计涨跌"
        return "指数样本上涨 ${comparable.count { it.snapshot.change > 0 }} · 下跌 ${comparable.count { it.snapshot.change < 0 }} · 持平 ${comparable.count { it.snapshot.change == 0f }}"
    }

    fun provenanceSummary(items: List<MarketOverviewItem>): String {
        if (items.isEmpty()) return "来源和时效尚未确认"
        val sources = items.map { it.snapshot.source.ifBlank { "未提供来源" } }.distinct()
        val freshness = items.map { TalkUiPolicy.freshnessLabel(it.snapshot.freshness) }.distinct()
        val cache = if (items.any { it.snapshot.clientCacheHit }) " · 含本地缓存" else ""
        return "${sources.joinToString(" / ")} · ${freshness.joinToString(" / ")}$cache"
    }
}
