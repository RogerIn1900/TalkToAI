package com.example.talktoai.chart

import android.content.Context
import android.widget.Button
import android.widget.LinearLayout
import android.widget.HorizontalScrollView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.charts.PieChart
import com.github.mikephil.charting.data.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChartInteractionTest {
    @Test fun fullscreenButtonTogglesBackToOriginalParent() {
        lateinit var root: LinearLayout
        lateinit var host: ChartInteractionHost
        androidx.test.core.app.ActivityScenario.launch(com.example.talktoai.KuiklyRenderActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val chart = LineChart(activity).apply { data = LineData(LineDataSet(listOf(Entry(0f, 1f)), "价格")) }
                root = LinearLayout(activity)
                host = ChartInteractionHost(activity, chart, listOf(chart), false)
                root.addView(host)
                activity.setContentView(root)
                val toggle = (host.getChildAt(0) as LinearLayout).getChildAt(0) as Button
                toggle.performClick()
                assertNotSame(root, host.parent)
                toggle.performClick()
            }
            // Dialog dispatches its dismissal listener on the next main-loop turn.
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity { assertSame(root, host.parent) }
        }
    }

    @Test fun compactVolumeAxisUsesOnlyTwoLabelsAndStartsAtZero() = onMain {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val model = MarketChartPayload(listOf(MarketChartPayload.Bar("2026-09-01", 10f, 12f, 9f, 11f, 100f)))
        val chart = TalkMarketChartView(context).volumeChart(model)
        assertEquals(2, chart.axisLeft.labelCount)
        assertEquals(0f, chart.axisLeft.axisMinimum, 0f)
    }

    @Test fun legendHidesOnlySelectedSeriesAndRestoresIt() = onMain {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val first = LineDataSet(listOf(Entry(0f, 1f), Entry(1f, 2f)), "价格")
        val second = LineDataSet(listOf(Entry(0f, 3f), Entry(1f, 4f)), "均价")
        val chart = LineChart(context).apply { data = LineData(first, second) }
        val host = ChartInteractionHost(context, chart, listOf(chart), false)
        val legend = (host.getChildAt(2) as HorizontalScrollView).getChildAt(0) as LinearLayout
        val button = legend.getChildAt(0) as Button
        button.performClick()
        assertFalse(first.isVisible)
        assertTrue(second.isVisible)
        assertTrue(button.text.startsWith("○"))
        button.performClick()
        assertTrue(first.isVisible)
        assertTrue(button.text.startsWith("●"))
    }

    @Test fun pieLegendHidesOneCategoryAndCanRestoreAllHiddenCategories() = onMain {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chart = PieChart(context).apply {
            data = PieData(PieDataSet(listOf(PieEntry(2f, "A"), PieEntry(3f, "B")), "占比"))
        }
        val host = ChartInteractionHost(context, chart, listOf(chart), false)
        val legend = (host.getChildAt(2) as HorizontalScrollView).getChildAt(0) as LinearLayout
        legend.getChildAt(0).performClick()
        assertEquals(1, chart.data.entryCount)
        assertEquals("B", chart.data.dataSet.getEntryForIndex(0).label)
        legend.getChildAt(1).performClick()
        assertEquals(0, chart.data.entryCount)
        legend.getChildAt(0).performClick()
        assertEquals("A", chart.data.dataSet.getEntryForIndex(0).label)
    }

    @Test fun resetRemovesViewportZoomConstraint() = onMain {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val chart = LineChart(context).apply {
            data = LineData(LineDataSet(listOf(Entry(0f, 1f), Entry(100f, 2f)), "价格"))
            viewPortHandler.setMinimumScaleX(3f)
        }
        val host = ChartInteractionHost(context, chart, listOf(chart), false)
        (host.getChildAt(0) as LinearLayout).getChildAt(1).performClick()
        assertEquals(1f, chart.viewPortHandler.scaleX, 0.001f)
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
}
