package com.example.talktoai.talk

import com.talktoai.marketui.DataOrigin
import com.talktoai.marketui.MarketBlock
import com.talktoai.marketui.MarketBlockId
import com.talktoai.marketui.MarketContent
import com.talktoai.marketui.MarketEmptyReason
import com.talktoai.marketui.MetricPoint

/** Market provider state rendered by both the existing detail UI and the SDK panels. */
internal sealed class MarketRequestUiState {
    data object Loading : MarketRequestUiState()
    data class Empty(val origin: MarketOriginUi) : MarketRequestUiState()
    data class Error(val code: String, val message: String, val retryable: Boolean) : MarketRequestUiState()
    data class Ready(val snapshot: MarketSnapshotUi, val bars: List<MarketBarUi>) : MarketRequestUiState()
}

internal data class MarketOriginUi(
    val source: String,
    val marketTime: String,
    val fetchedAt: String,
    val freshness: String,
    val clientCacheHit: Boolean,
    val simulated: Boolean = false,
)

internal sealed class MarketUiAction {
    data class Retry(val blockId: MarketBlockId) : MarketUiAction()
}

internal object MarketComponentAdapter {
    fun prices(state: MarketRequestUiState): MarketContent<MetricPoint> = content(state) { snapshot ->
        listOf(
            MetricPoint("开盘", snapshot.open),
            MetricPoint("最高", snapshot.high),
            MetricPoint("最低", snapshot.low),
            MetricPoint("收盘", snapshot.close),
        )
    }

    fun volume(state: MarketRequestUiState): MarketContent<MetricPoint> = content(state) { snapshot ->
        listOf(MetricPoint("成交量", snapshot.volume))
    }

    private fun content(
        state: MarketRequestUiState,
        values: (MarketSnapshotUi) -> List<MetricPoint>,
    ): MarketContent<MetricPoint> = when (state) {
        MarketRequestUiState.Loading -> MarketContent.Loading
        is MarketRequestUiState.Empty -> MarketContent.Empty(state.origin.toSdkOrigin(), MarketEmptyReason.NoData)
        is MarketRequestUiState.Error -> MarketContent.Error(
            origin = null,
            code = state.code,
            message = state.message,
            retryable = state.retryable,
        )
        is MarketRequestUiState.Ready -> MarketContent.Ready(
            MarketBlock(values(state.snapshot), state.snapshot.origin().toSdkOrigin()),
        )
    }

    private fun MarketSnapshotUi.origin() = MarketOriginUi(
        source = source,
        marketTime = marketTime,
        fetchedAt = fetchedAt,
        freshness = freshness,
        clientCacheHit = clientCacheHit,
    )

    private fun MarketOriginUi.toSdkOrigin() = DataOrigin(
        source = source.ifBlank { "未提供来源" },
        asOf = buildList {
            add("市场 ${marketTime.ifBlank { "未知" }}")
            if (fetchedAt.isNotBlank()) add("获取 $fetchedAt")
        }.joinToString(" · "),
        simulated = simulated,
        status = TalkUiPolicy.freshnessLabel(freshness) + if (clientCacheHit) " · 本地缓存" else "",
    )
}
