package com.example.talktoai.chart

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.components.YAxis
import com.github.mikephil.charting.data.BarData
import com.github.mikephil.charting.data.BarDataSet
import com.github.mikephil.charting.data.BarEntry
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.data.PieEntry
import com.github.mikephil.charting.formatter.ValueFormatter
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import org.json.JSONObject

/** Native Android chart renderer exposed to Kuikly through one replaceable view boundary. */
class TalkDataChartView(context: Context) : FrameLayout(context), IKuiklyRenderViewExport {
    private var chartType = TYPE_LINE
    private var chartData = ""
    private var darkMode = false
    private var renderedConfiguration: Triple<String, String, Boolean>? = null
    private val renderTask = Runnable { renderChart() }
    private val lineChart by lazy { LineChart(context) }
    private val barChart by lazy { BarChart(context) }
    private val pieChart by lazy { PieChart(context) }
    private var interactionHost: ChartInteractionHost? = null

    private fun scheduleRender() {
        // Kuikly delivers data, type and theme separately in the same UI batch.
        removeCallbacks(renderTask)
        post(renderTask)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(renderTask)
        interactionHost?.closeFullscreen()
        super.onDetachedFromWindow()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleRender()
    }

    override fun setProp(propKey: String, propValue: Any): Boolean = when (propKey) {
        PROP_CHART_TYPE -> {
            chartType = propValue.toString().takeIf(ALLOWED_TYPES::contains) ?: TYPE_LINE
            scheduleRender()
            true
        }
        PROP_CHART_DATA -> {
            chartData = propValue.toString()
            scheduleRender()
            true
        }
        PROP_DARK_MODE -> {
            darkMode = propValue as? Boolean ?: false
            scheduleRender()
            true
        }
        else -> super.setProp(propKey, propValue)
    }

    private fun renderChart() {
        val configuration = Triple(chartType, chartData, darkMode)
        if (configuration == renderedConfiguration) return
        if (chartData.isBlank()) return
        val model = runCatching { ChartPayload.parse(JSONObject(chartData)) }.getOrNull() ?: return
        interactionHost?.closeFullscreen()
        removeAllViews()
        val chart = when (chartType) {
            TYPE_BAR -> updateBarChart(model)
            TYPE_PIE -> updatePieChart(model)
            else -> updateLineChart(model)
        }
        // Chart instances survive type switches; only their validated data/configuration changes.
        (chart.parent as? android.view.ViewGroup)?.removeView(chart)
        interactionHost = ChartInteractionHost(context, chart, listOf(chart), darkMode)
        addView(interactionHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderedConfiguration = configuration
    }

    private fun updateLineChart(model: ChartPayload): LineChart = lineChart.apply {
        val secondary = ChartAxisPolicy.secondarySeriesIndices(model.series.map { it.values })
        configureCartesian(this, model, secondary.isNotEmpty())
        data = LineData(model.series.mapIndexed { index, series ->
            LineDataSet(series.values.mapIndexed { x, value -> Entry(x.toFloat(), value) }, series.name).apply {
                axisDependency = if (index in secondary) YAxis.AxisDependency.RIGHT else YAxis.AxisDependency.LEFT
                color = SERIES_COLORS[index % SERIES_COLORS.size]
                setCircleColor(color)
                lineWidth = 2f
                circleRadius = 2.5f
                setDrawValues(false)
            }
        })
        invalidate()
    }

    private fun updateBarChart(model: ChartPayload): BarChart = barChart.apply {
        setFitBars(true)
        val secondary = ChartAxisPolicy.secondarySeriesIndices(model.series.map { it.values })
        configureCartesian(this, model, secondary.isNotEmpty())
        val sets = model.series.mapIndexed { index, series ->
            BarDataSet(series.values.mapIndexed { x, value -> BarEntry(BarGroupPolicy.entryX(x, index, model.series.size), value) }, series.name).apply {
                axisDependency = if (index in secondary) YAxis.AxisDependency.RIGHT else YAxis.AxisDependency.LEFT
                color = SERIES_COLORS[index % SERIES_COLORS.size]
                setDrawValues(false)
            }
        }
        data = BarData(sets).apply {
            barWidth = BarGroupPolicy.barWidth(sets.size)
        }
        invalidate()
    }

    private fun updatePieChart(model: ChartPayload): PieChart = pieChart.apply {
        description.isEnabled = false
        setUsePercentValues(false)
        // Category labels stay in the legend; drawing them on narrow slices makes both
        // labels and values unreadable on a phone-sized chart.
        setDrawEntryLabels(false)
        setEntryLabelColor(if (darkMode) Color.WHITE else Color.DKGRAY)
        setHoleColor(Color.TRANSPARENT)
        legend.textColor = foregroundColor()
        val first = model.series.first()
        data = PieData(PieDataSet(first.values.mapIndexed { index, value ->
            PieEntry(value, model.labels.getOrElse(index) { (index + 1).toString() })
        }, first.name).apply {
            colors = SERIES_COLORS.toList()
            valueTextColor = foregroundColor()
            valueTextSize = 10f
        })
        invalidate()
    }

    private fun configureCartesian(
        chart: com.github.mikephil.charting.charts.BarLineChartBase<*>,
        model: ChartPayload,
        rightAxisEnabled: Boolean,
    ) {
        // Axis meanings are already shown below the chart by Kuikly. An in-plot
        // description overlaps the date ticks on narrow screens.
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = rightAxisEnabled
        chart.axisRight.textColor = foregroundColor()
        chart.axisRight.gridColor = gridColor()
        chart.axisLeft.textColor = foregroundColor()
        chart.axisLeft.gridColor = gridColor()
        chart.legend.textColor = foregroundColor()
        chart.xAxis.apply {
            position = XAxis.XAxisPosition.BOTTOM
            textColor = foregroundColor()
            gridColor = gridColor()
            granularity = 1f
            valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String =
                    model.labels.getOrElse(value.toInt()) { "" }
            }
        }
        chart.setNoDataText("暂无可绘制数据")
        chart.setNoDataTextColor(foregroundColor())
    }

    private fun foregroundColor(): Int = if (darkMode) Color.rgb(226, 232, 240) else Color.rgb(51, 65, 85)
    private fun gridColor(): Int = if (darkMode) Color.rgb(71, 85, 105) else Color.rgb(226, 232, 240)

    private data class ChartPayload(
        val labels: List<String>,
        val xLabel: String,
        val yLabel: String,
        val series: List<Series>,
    ) {
        data class Series(val name: String, val values: List<Float>)

        companion object {
            fun parse(root: JSONObject): ChartPayload {
                val labelsJson = root.getJSONArray("labels")
                val labels = List(labelsJson.length()) { labelsJson.getString(it) }
                val seriesJson = root.getJSONArray("series")
                val series = List(seriesJson.length()) { index ->
                    val item = seriesJson.getJSONObject(index)
                    val valuesJson = item.getJSONArray("values")
                    Series(item.optString("name", "数值"), List(valuesJson.length()) { valuesJson.getDouble(it).toFloat() })
                }.filter { it.values.isNotEmpty() }
                require(labels.isNotEmpty() && series.isNotEmpty())
                return ChartPayload(labels, root.optString("xLabel", "类别"), root.optString("yLabel", "数值"), series)
            }
        }
    }

    companion object {
        private const val PROP_CHART_TYPE = "chartType"
        private const val PROP_CHART_DATA = "chartData"
        private const val PROP_DARK_MODE = "darkMode"
        private const val TYPE_LINE = "line"
        private const val TYPE_BAR = "bar"
        private const val TYPE_PIE = "pie"
        private val ALLOWED_TYPES = setOf(TYPE_LINE, TYPE_BAR, TYPE_PIE)
        private val SERIES_COLORS = intArrayOf(
            Color.rgb(37, 99, 235),
            Color.rgb(239, 68, 68),
            Color.rgb(22, 163, 74),
            Color.rgb(245, 158, 11),
            Color.rgb(147, 51, 234),
        )
    }
}

internal object BarGroupPolicy {
    private const val GROUP_WIDTH = 0.7f // Fraction of one category's X-axis interval.
    private const val SINGLE_BAR_WIDTH = 0.55f
    fun barWidth(seriesCount: Int): Float {
        require(seriesCount > 0)
        return if (seriesCount == 1) SINGLE_BAR_WIDTH else GROUP_WIDTH / seriesCount
    }
    fun entryX(category: Int, seriesIndex: Int, seriesCount: Int): Float {
        require(seriesCount > 0 && seriesIndex in 0 until seriesCount)
        return category + (seriesIndex - (seriesCount - 1) / 2f) * barWidth(seriesCount)
    }
}

internal object ChartAxisPolicy {
    private const val SECONDARY_AXIS_RATIO = 1_000f

    fun secondarySeriesIndices(series: List<List<Float>>): Set<Int> {
        val maxima = series.map { values -> values.maxOfOrNull { kotlin.math.abs(it) } ?: 0f }
        val baseline = maxima.filter { it > 0f }.minOrNull() ?: return emptySet()
        return maxima.mapIndexedNotNull { index, maximum ->
            index.takeIf { maximum / baseline >= SECONDARY_AXIS_RATIO }
        }.toSet()
    }
}
