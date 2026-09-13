package com.example.talktoai.talk

import com.talktoai.marketui.DataOrigin
import com.talktoai.marketui.IndexTile
import com.talktoai.marketui.MarketBlock
import com.talktoai.marketui.MarketContent
import com.talktoai.marketui.MarketDashboardContent
import com.talktoai.marketui.Trend

/** Maps structured market data attached to an AI response into the UI-only SDK contract. */
internal object AiMarketDashboardAdapter {
    fun fromMessages(messages: List<ChatMessageUi>): MarketDashboardContent {
        val items = messages.asReversed().asSequence()
            .filter { it.role != "user" && it.marketDataJson.isNotBlank() }
            .map { MarketOverviewPolicy.parse(it.marketDataJson) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (items.isEmpty()) return MarketDashboardContent.empty()

        val latest = items.first().snapshot
        val sources = items.map { it.snapshot.source.ifBlank { "AI 行情数据" } }.distinct()
        val origin = DataOrigin(
            source = sources.joinToString(" / "),
            asOf = latest.marketTime.ifBlank { latest.fetchedAt.ifBlank { "时间未知" } },
            simulated = false,
            status = TalkUiPolicy.freshnessLabel(latest.freshness) +
                if (latest.clientCacheHit) " · 本地缓存" else "",
        )
        val indices = items.map { item ->
            IndexTile(
                symbol = item.snapshot.symbol,
                name = item.name,
                price = item.snapshot.close,
                changePercent = item.snapshot.changePercent ?: 0f,
                trend = Trend(item.chart.labels, item.chart.series.firstOrNull()?.values.orEmpty()),
            )
        }
        return MarketDashboardContent.empty().copy(
            indices = MarketContent.Ready(MarketBlock(indices, origin)),
        )
    }
}
