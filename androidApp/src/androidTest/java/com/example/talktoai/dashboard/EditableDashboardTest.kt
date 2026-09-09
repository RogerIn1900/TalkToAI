package com.example.talktoai.dashboard

import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.talktoai.KuiklyRenderActivity
import com.talktoai.marketui.dashboard.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EditableDashboardTest {
    @Test
    fun importedPickerWorksWithKuiklyStyleWrappedContext() {
        var opened = false
        ActivityScenario.launch(KuiklyRenderActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val wrapper =
                    android.view.ContextThemeWrapper(activity, android.R.style.Theme_Material_Light)
                val view = TalkDashboardView(wrapper) { opened = true }
                activity.setContentView(view)
                view.onImport!!.invoke()
                assertTrue("Picker callback must not depend on casting Context to Activity", opened)
            }
        }
    }

    @Test
    fun failedSaveKeepsDraftUntilSuccessfulAcknowledgment() {
        lateinit var view: DashboardView
        ActivityScenario.launch(KuiklyRenderActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                view = DashboardView(activity)
                activity.setContentView(view)
                view.submit(TalkDashboardView.initial(), TalkDashboardView.demoSources())
                view.beginEditing()
                view.session.change(view.session.document.copy(name = "未保存草稿"))
                view.onSave = { _, done -> done(Result.failure(IllegalStateException("测试保存失败"))) }
                fun clickDone(node: View): Boolean {
                    if (node is android.widget.Button && node.text == "完成") {
                        node.performClick()
                        return true
                    }
                    if (node is android.view.ViewGroup)
                        for (i in 0 until node.childCount) if (clickDone(node.getChildAt(i)))
                            return true
                    return false
                }
                assertTrue(clickDone(view))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            scenario.onActivity {
                assertTrue(view.session.editing)
                assertEquals("未保存草稿", view.session.document.name)
            }
        }
    }

    @Test
    fun dragResizeCancelAndPersistenceWorkOnDevice() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        lateinit var board: DashboardView
        var rendererCreations = 0
        val initial = TalkDashboardView.initial()
        ActivityScenario.launch(KuiklyRenderActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                board = DashboardView(activity)
                board.contentFactory = { context, card, rows, unit, color ->
                    rendererCreations++
                    DashboardContentView(context, card, rows, unit, color)
                }
                board.submit(initial, TalkDashboardView.demoSources())
                activity.setContentView(board)
            }
            val deadline = SystemClock.uptimeMillis() + 5000
            var ready = false
            while (!ready && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity {
                    ready =
                        board.hasWindowFocus() &&
                            (find(board, "dashboard-card:breadth")?.height ?: 0) > 0
                }
                if (!ready) SystemClock.sleep(50)
            }
            assertTrue("Dashboard must have focus and measured cards", ready)
            instrument.waitForIdleSync()
            var bounds = IntArray(4)
            scenario.onActivity {
                val card = find(board, "dashboard-card:breadth")!!
                val xy = IntArray(2)
                card.getLocationOnScreen(xy)
                bounds = intArrayOf(xy[0], xy[1], card.width, card.height)
            }
            gesture(bounds[0] + bounds[2] / 2f, bounds[1] + 40f, 0f, 0f, 700)
            scenario.onActivity {
                assertTrue("Long press must enter edit mode", board.session.editing)
            }
            instrument.waitForIdleSync()
            val density = instrument.targetContext.resources.displayMetrics.density
            gesture(bounds[0] + bounds[2] - 30 * density, bounds[1] + 30 * density, 0f, 0f, 50)
            val editorDeadline = SystemClock.uptimeMillis() + 3000
            var editorVisible = false
            while (!editorVisible && SystemClock.uptimeMillis() < editorDeadline) {
                editorVisible =
                    instrument.uiAutomation.rootInActiveWindow
                        ?.findAccessibilityNodeInfosByText("编辑卡片")
                        ?.isNotEmpty() == true
                if (!editorVisible) SystemClock.sleep(50)
            }
            assertTrue("Short handle tap opens editor", editorVisible)
            instrument.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            instrument.waitForIdleSync()
            val renderersBeforeDrag = rendererCreations
            gesture(
                bounds[0] + bounds[2] - 30 * density,
                bounds[1] + 30 * density,
                0f,
                180 * density,
                400,
            )
            scenario.onActivity {
                assertEquals(
                    "Dragging must reuse the chart renderer",
                    renderersBeforeDrag,
                    rendererCreations,
                )
                assertNotEquals(
                    "Drag should change canonical position",
                    initial.cards.first().rect,
                    board.session.document.cards.first().rect,
                )
                board.cancelEditing()
                assertEquals(initial, board.session.document)
                board.beginEditing()
            }
            instrument.waitForIdleSync()
            scenario.onActivity {
                val card = find(board, "dashboard-card:breadth")!!
                val xy = IntArray(2)
                card.getLocationOnScreen(xy)
                bounds = intArrayOf(xy[0], xy[1], card.width, card.height)
            }
            gesture(
                bounds[0] + bounds[2] / 2f,
                bounds[1] + bounds[3] - 5 * density,
                0f,
                60 * density,
                350,
            )
            scenario.onActivity {
                val changed = board.session.document.cards.first()
                assertFalse(changed.autoHeight)
                assertTrue(changed.rect.h > initial.cards.first().rect.h)
                val store = DashboardStore(it)
                val existing = store.load()
                store.save(board.session.document)
                assertEquals(board.session.document, store.load())
                if (existing != null) store.save(existing)
                else
                    it.getSharedPreferences("editable_dashboard_v1", 0)
                        .edit()
                        .remove("document")
                        .commit()
                board.cancelEditing()
                assertEquals(initial, board.session.document)
            }
        }
    }

    @Test
    fun savedImportedSourcesAndReportsRetainProvenance() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DashboardStore(context)
        val before = store.sources()
        try {
            val imported =
                DashboardSource(
                    "test-import",
                    "用户 CSV",
                    SourceKind.IMPORT,
                    "元",
                    DashboardCsv.parse("label,value\nA,10\nB,20"),
                    "测试导入",
                )
            val report =
                imported.copy(id = "test-report", kind = SourceKind.REPORT, provenance = "报表快照")
            store.saveSources(listOf(imported, report))
            assertEquals(listOf(imported, report), store.sources())
        } finally {
            store.saveSources(before)
        }
    }

    private fun find(view: View, description: String): View? {
        if (view.contentDescription == description) return view
        if (view is android.view.ViewGroup)
            for (i in 0 until view.childCount) find(view.getChildAt(i), description)?.let {
                return it
            }
        return null
    }

    private fun gesture(x: Float, y: Float, dx: Float, dy: Float, duration: Long) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val start = SystemClock.uptimeMillis()
        fun send(action: Int, px: Float, py: Float) {
            val e = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, px, py, 0)
            instrument.sendPointerSync(e)
            e.recycle()
        }
        send(MotionEvent.ACTION_DOWN, x, y)
        if (dx == 0f && dy == 0f) SystemClock.sleep(duration)
        else
            for (i in 1..20) {
                SystemClock.sleep(duration / 20)
                send(MotionEvent.ACTION_MOVE, x + dx * i / 20, y + dy * i / 20)
            }
        send(MotionEvent.ACTION_UP, x + dx, y + dy)
        instrument.waitForIdleSync()
    }
}
