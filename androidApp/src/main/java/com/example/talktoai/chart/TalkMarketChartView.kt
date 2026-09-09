package com.example.talktoai.chart

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.CandleStickChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.CandleData
import com.github.mikephil.charting.data.CandleDataSet
import com.github.mikephil.charting.data.CandleEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport

/** MPAndroidChart K-line and volume renderer. Both panes use the same X range and labels. */
class TalkMarketChartView(context: Context) : FrameLayout(context), IKuiklyRenderViewExport {
    private var barsJson = ""
    private var darkMode = false
    private var rendered: Pair<String, Boolean>? = null
    private val renderTask = Runnable(::render)
    private var interactionHost: ChartInteractionHost? = null

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
        PROP_BARS_JSON -> { barsJson = propValue.toString(); schedule(); true }
        PROP_DARK_MODE -> { darkMode = propValue as? Boolean ?: false; schedule(); true }
        else -> super.setProp(propKey, propValue)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); schedule() }
    override fun onDetachedFromWindow() { removeCallbacks(renderTask); interactionHost?.closeFullscreen(); super.onDetachedFromWindow() }

    private fun schedule() { removeCallbacks(renderTask); post(renderTask) }

    private fun render() {
        val configuration = barsJson to darkMode
        if (barsJson.isBlank() || rendered == configuration) return
        val model = runCatching { MarketChartPayload.parse(barsJson) }.getOrNull() ?: return
        interactionHost?.closeFullscreen()
        removeAllViews()
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val priceChart = candleChart(model)
        val volumeChart = volumeChart(model)
        container.addView(priceChart, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, PRICE_PANE_WEIGHT,
        ))
        container.addView(volumeChart, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, VOLUME_PANE_WEIGHT,
        ))
        interactionHost = ChartInteractionHost(context, container, listOf(priceChart, volumeChart), darkMode)
        addView(interactionHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        rendered = configuration
    }

    private fun candleChart(model: MarketChartPayload) = CandleStickChart(context).apply {
        configureBase(this, model, showXAxis = false)
        data = CandleData(CandleDataSet(model.bars.mapIndexed { index, bar ->
            CandleEntry(index.toFloat(), bar.high, bar.low, bar.open, bar.close)
        }, "K线").apply {
            axisDependency = com.github.mikephil.charting.components.YAxis.AxisDependency.LEFT
            decreasingColor = DOWN_COLOR
            decreasingPaintStyle = android.graphics.Paint.Style.FILL
            increasingColor = UP_COLOR
            increasingPaintStyle = android.graphics.Paint.Style.STROKE
            neutralColor = NEUTRAL_COLOR
            shadowColorSameAsCandle = true
            setDrawValues(false)
        })
        setVisibleXRangeMaximum(VISIBLE_BAR_COUNT)
        moveViewToX((model.bars.lastIndex - VISIBLE_BAR_COUNT).coerceAtLeast(0f))
        invalidate()
    }

    internal fun volumeChart(model: MarketChartPayload) = BarChart(context).apply {
        configureBase(this, model, showXAxis = true)
        // The compact volume pane cannot fit the library's default six labels.
        axisLeft.setLabelCount(VOLUME_AXIS_LABEL_COUNT, true)
        axisLeft.axisMinimum = 0f
        axisLeft.valueFormatter = object : ValueFormatter() {
            override fun getFormattedValue(value: Float): String = when {
                value >= 100_000_000f -> "${(value / 100_000_000f).toInt()}亿"
                value >= 10_000f -> "${(value / 10_000f).toInt()}万"
                else -> value.toInt().toString()
            }
        }
        data = BarData(BarDataSet(model.bars.mapIndexed { index, bar ->
            BarEntry(index.toFloat(), bar.volume)
        }, "成交量").apply {
            colors = model.bars.map { if (it.close >= it.open) UP_COLOR else DOWN_COLOR }
            setDrawValues(false)
        }).apply { barWidth = 0.7f }
        setVisibleXRangeMaximum(VISIBLE_BAR_COUNT)
        moveViewToX((model.bars.lastIndex - VISIBLE_BAR_COUNT).coerceAtLeast(0f))
        invalidate()
    }

    private fun configureBase(
        chart: com.github.mikephil.charting.charts.BarLineChartBase<*>,
        model: MarketChartPayload,
        showXAxis: Boolean,
    ) {
        val distinctDates = model.bars.map { it.time.take(10) }.distinct().size
        val foreground = if (darkMode) Color.rgb(226, 232, 240) else Color.rgb(71, 85, 105)
        val grid = if (darkMode) Color.rgb(71, 85, 105) else Color.rgb(226, 232, 240)
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
        chart.axisLeft.textColor = foreground
        chart.axisLeft.gridColor = grid
        chart.legend.textColor = foreground
        // The price and volume panes intentionally share a fixed window. Disabling independent
        // drag/zoom prevents the two X axes from drifting apart until linked gestures are added.
        chart.isDragEnabled = false
        chart.setScaleEnabled(false)
        chart.setScaleYEnabled(false)
        chart.xAxis.apply {
            isEnabled = showXAxis
            position = XAxis.XAxisPosition.BOTTOM
            textColor = foreground
            gridColor = grid
            granularity = 1f
            setLabelCount(4, false)
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String = model.bars.getOrNull(value.toInt())?.time
                    ?.let { MarketChartLabelPolicy.format(it, distinctDates) }.orEmpty()
            }
        }
        chart.setNoDataText("暂无可绘制数据")
        chart.setNoDataTextColor(foreground)
    }

    companion object {
        private const val PROP_BARS_JSON = "barsJson"
        private const val PROP_DARK_MODE = "darkMode"
        private const val PRICE_PANE_WEIGHT = 3f
        private const val VOLUME_PANE_WEIGHT = 1f
        private const val VOLUME_AXIS_LABEL_COUNT = 2
        private const val VISIBLE_BAR_COUNT = 40f
        private val UP_COLOR = Color.rgb(220, 38, 38)
        private val DOWN_COLOR = Color.rgb(22, 163, 74)
        private val NEUTRAL_COLOR = Color.rgb(100, 116, 139)
    }
}

internal object MarketChartLabelPolicy {
    fun format(timestamp: String, distinctDates: Int): String = when {
        timestamp.length >= 16 && timestamp[10] == 'T' && distinctDates == 1 -> timestamp.substring(11, 16)
        timestamp.length >= 10 -> timestamp.substring(5, 10)
        else -> timestamp
    }
}
