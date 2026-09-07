package com.example.talktoai.talk

import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewContainer
import com.tencent.kuikly.core.base.event.Event

/**
 * Kuikly boundary for the Android chart engine. Keeping the chart payload as a
 * small JSON contract isolates the shared UI from the replaceable native library.
 */
internal class DataChartView : DeclarativeBaseView<DataChartAttr, Event>() {
    override fun createAttr(): DataChartAttr = DataChartAttr()
    override fun createEvent(): Event = Event()
    override fun viewName(): String = VIEW_NAME

    companion object {
        const val VIEW_NAME = "TalkDataChart"
    }
}

internal class DataChartAttr : Attr() {
    fun chartType(value: String) {
        CHART_TYPE with value
    }

    fun chartData(value: String) {
        CHART_DATA with value
    }

    fun darkMode(value: Boolean) {
        DARK_MODE with value
    }

    companion object {
        const val CHART_TYPE = "chartType"
        const val CHART_DATA = "chartData"
        const val DARK_MODE = "darkMode"
    }
}

internal fun ViewContainer<*, *>.DataChart(init: DataChartView.() -> Unit) {
    addChild(DataChartView(), init)
}
