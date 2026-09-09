package com.example.talktoai.chart

import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.PieData
import com.github.mikephil.charting.data.PieDataSet
import com.github.mikephil.charting.charts.BarLineChartBase
import com.github.mikephil.charting.charts.Chart

/** Shared native controls keep fullscreen and legend behavior identical for both chart surfaces. */
internal class ChartInteractionHost(
    context: Context,
    content: View,
    private val charts: List<Chart<*>>,
    dark: Boolean,
) : LinearLayout(context) {
    private var fullscreen: Dialog? = null
    private val foreground = if (dark) Color.WHITE else Color.DKGRAY
    private val controlBackground = if (dark) Color.rgb(51, 65, 85) else Color.rgb(238, 242, 255)

    init {
        orientation = VERTICAL
        setBackgroundColor(if (dark) Color.rgb(30, 41, 59) else Color.WHITE)
        val toolbar = LinearLayout(context)
        toolbar.addView(control("全屏") { if (fullscreen != null) closeFullscreen() else openFullscreen() })
        toolbar.addView(control("归位") {
            charts.forEach { chart ->
                (chart as? BarLineChartBase<*>)?.let { cartesian ->
                    cartesian.viewPortHandler.setMinimumScaleX(1f)
                    cartesian.fitScreen()
                }
                chart.highlightValues(null)
                chart.invalidate()
            }
        })
        addView(toolbar, LayoutParams(LayoutParams.MATCH_PARENT, dp(CONTROL_HEIGHT_DP)))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        val legend = LinearLayout(context)
        charts.forEach { chart ->
            chart.legend.isEnabled = false
            if (chart is PieChart) {
                addPieLegend(chart, legend)
                return@forEach
            }
            chart.data?.dataSets?.forEach { dataSet ->
                val button = control("● ${dataSet.label}") {}
                button.setTextColor(dataSet.color)
                button.setOnClickListener {
                    dataSet.isVisible = !dataSet.isVisible
                    button.alpha = if (dataSet.isVisible) 1f else HIDDEN_ALPHA
                    button.text = "${if (dataSet.isVisible) "●" else "○"} ${dataSet.label}"
                    chart.notifyDataSetChanged()
                    chart.invalidate()
                }
                legend.addView(button)
            }
        }
        addView(HorizontalScrollView(context).apply { addView(legend) }, LayoutParams(LayoutParams.MATCH_PARENT, dp(CONTROL_HEIGHT_DP)))
    }

    private fun addPieLegend(chart: PieChart, legend: LinearLayout) {
        val original = chart.data.dataSet
        val entries = List(original.entryCount) { original.getEntryForIndex(it) }
        val hidden = mutableSetOf<Int>()
        entries.forEachIndexed { index, entry ->
            val button = control("● ${entry.label}") {}
            button.setTextColor(original.getColor(index))
            button.setOnClickListener {
                if (!hidden.add(index)) hidden.remove(index)
                button.alpha = if (index in hidden) HIDDEN_ALPHA else 1f
                button.text = "${if (index in hidden) "○" else "●"} ${entry.label}"
                val visible = entries.indices.filterNot(hidden::contains)
                chart.data = PieData(PieDataSet(visible.map(entries::get), original.label).apply {
                    colors = visible.map(original::getColor)
                    valueTextColor = this@ChartInteractionHost.foreground
                    valueTextSize = 10f
                })
                chart.notifyDataSetChanged()
                chart.invalidate()
            }
            legend.addView(button)
        }
    }

    fun closeFullscreen() { fullscreen?.dismiss() }

    private fun control(title: String, action: () -> Unit) = Button(context).apply {
        text = title
        textSize = 11f
        setTextColor(this@ChartInteractionHost.foreground)
        minWidth = 0
        minimumWidth = 0
        isAllCaps = false
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(controlBackground)
            cornerRadius = dp(12).toFloat()
        }
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, dp(36)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { action() }
    }

    private fun openFullscreen() {
        if (fullscreen != null) return
        val originalParent = parent as? ViewGroup ?: return
        val originalIndex = originalParent.indexOfChild(this)
        val originalParams = layoutParams
        val dialog = Dialog(context, android.R.style.Theme_Material_NoActionBar)
        val root = LinearLayout(context).apply { orientation = VERTICAL }
        root.addView(control("返回图表") { dialog.dismiss() })
        originalParent.removeView(this)
        root.addView(this, LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        dialog.setContentView(root)
        dialog.setOnDismissListener {
            root.removeView(this)
            originalParent.addView(this, originalIndex, originalParams)
            fullscreen = null
        }
        fullscreen = dialog
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val CONTROL_HEIGHT_DP = 40
        private const val HIDDEN_ALPHA = 0.4f
    }
}
