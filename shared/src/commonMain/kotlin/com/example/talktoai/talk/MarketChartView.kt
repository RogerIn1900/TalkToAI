package com.example.talktoai.talk

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event

internal class MarketChartView : DeclarativeBaseView<MarketChartAttr, Event>() {
    override fun createAttr() = MarketChartAttr()
    override fun createEvent() = Event()
    override fun viewName() = VIEW_NAME

    companion object { const val VIEW_NAME = "TalkMarketChart" }
}

internal class MarketChartAttr : Attr() {
    fun barsJson(value: String) { BARS_JSON with value }
    fun darkMode(value: Boolean) { DARK_MODE with value }

    companion object {
        const val BARS_JSON = "barsJson"
        const val DARK_MODE = "darkMode"
    }
}

internal fun ViewContainer<*, *>.MarketChart(init: MarketChartView.() -> Unit) {
    addChild(MarketChartView(), init)
}
