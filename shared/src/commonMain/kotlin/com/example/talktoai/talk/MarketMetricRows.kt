package com.example.talktoai.talk

/** Two price columns and a full-width volume row avoid five squeezed columns on phones. */
internal fun marketMetricRows(snapshot: MarketSnapshotUi): List<List<Pair<String, String>>> = listOf(
    listOf("开盘" to TalkUiPolicy.formatQuote(snapshot.open), "收盘" to TalkUiPolicy.formatQuote(snapshot.close)),
    listOf("最高" to TalkUiPolicy.formatQuote(snapshot.high), "最低" to TalkUiPolicy.formatQuote(snapshot.low)),
    listOf("成交量" to TalkUiPolicy.formatVolume(snapshot.volume)),
)
