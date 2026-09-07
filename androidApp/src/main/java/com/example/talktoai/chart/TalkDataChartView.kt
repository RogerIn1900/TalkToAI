package com.example.talktoai.chart

import android.content.Context
import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import com.github.mikephil.charting.charts.BarChart
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.components.XAxis
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

    private fun scheduleRender() {
        // Kuikly delivers data, type and theme separately in the same UI batch.
        removeCallbacks(renderTask)
        post(renderTask)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(renderTask)
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
        removeAllViews()
        val chart = when (chartType) {
            TYPE_BAR -> createBarChart(model)
            TYPE_PIE -> createPieChart(model)
            else -> createLineChart(model)
        }
        addView(chart, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        renderedConfiguration = configuration
    }

    private fun createLineChart(model: ChartPayload): LineChart = LineChart(context).apply {
        configureCartesian(this, model)
        data = LineData(model.series.mapIndexed { index, series ->
            LineDataSet(series.values.mapIndexed { x, value -> Entry(x.toFloat(), value) }, series.name).apply {
                color = SERIES_COLORS[index % SERIES_COLORS.size]
                setCircleColor(color)
                lineWidth = 2f
                circleRadius = 2.5f
                setDrawValues(false)
            }
        })
        invalidate()
    }

    private fun createBarChart(model: ChartPayload): BarChart = BarChart(context).apply {
        configureCartesian(this, model)
        val sets = model.series.mapIndexed { index, series ->
            BarDataSet(series.values.mapIndexed { x, value -> BarEntry(x.toFloat(), value) }, series.name).apply {
                color = SERIES_COLORS[index % SERIES_COLORS.size]
                setDrawValues(false)
            }
        }
        data = BarData(sets).apply {
            barWidth = if (sets.size > 1) 0.7f / sets.size else 0.55f
        }
        invalidate()
    }

    private fun createPieChart(model: ChartPayload): PieChart = PieChart(context).apply {
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

    private fun configureCartesian(chart: com.github.mikephil.charting.charts.BarLineChartBase<*>, model: ChartPayload) {
        // Axis meanings are already shown below the chart by Kuikly. An in-plot
        // description overlaps the date ticks on narrow screens.
        chart.description.isEnabled = false
        chart.axisRight.isEnabled = false
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
