package com.example.talktoai.dashboard

import android.content.Context
import android.graphics.Color
import android.widget.Toast
import com.talktoai.marketui.dashboard.*
import com.tencent.kuikly.core.render.android.export.IKuiklyRenderViewExport
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

class TalkDashboardView(
    context: Context,
    private val pickAttachment:
        ((Result<com.example.talktoai.chat.ChatAttachment>) -> Unit) -> Unit,
) : DashboardView(context), IKuiklyRenderViewExport {
    private val store = DashboardStore(context)
    private var catalog = demoSources()
    private var closed = false
    private var generation = 0
    private var loadedOnce = false
    private var loading = true
    private var loadFailure = false
    internal val readyForInteraction: Boolean
        get() = !closed && !loading && !loadFailure

    private fun deliver(token: Int, action: () -> Unit) {
        post { if (!closed && token == generation) action() }
    }

    init {
        onSave = { document, done ->
            if (closed || loading || loadFailure) {
                done(Result.failure(IllegalStateException("看板尚未成功读取，请重新打开")))
            } else {
                val token = generation
                io.execute {
                    val result = runCatching { store.save(document) }
                    deliver(token) { done(result) }
                }
            }
        }
        onImport = { importCsv() }
        onReport = { card, source ->
            val snapshot =
                source.copy(
                    id = UUID.randomUUID().toString(),
                    name = card.title,
                    kind = SourceKind.REPORT,
                    rows = DashboardPolicy.rows(card, source, session.document.time),
                    provenance = "本地报表快照 · ${source.provenance}",
                )
            persistSource(snapshot)
        }
    }

    private fun loadState() {
        loading = true
        val token = generation
        io.execute {
            val loaded = runCatching {
                (store.load() ?: initial()) to (demoSources() + store.sources())
            }
            deliver(token) {
                loading = false
                loadFailure = loaded.isFailure
                loaded.fold(
                    { (doc, data) ->
                        catalog = data
                        if (loadedOnce) updateSources(catalog) else submit(doc, catalog)
                        loadedOnce = true
                    },
                    { failure ->
                        if (!loadedOnce) submit(DashboardDocument("看板读取失败", emptyList()), catalog)
                        Toast.makeText(context, failure.message ?: "看板读取失败", Toast.LENGTH_LONG)
                            .show()
                    },
                )
            }
        }
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean =
        if (loading) true else super.dispatchTouchEvent(event)

    private fun persistSource(source: DashboardSource) {
        if (closed || loadFailure) return
        val token = generation
        io.execute {
            val result = runCatching {
                demoSources() + store.appendSource(source)
            }
            deliver(token) {
                result.fold(
                    { next ->
                        catalog = next
                        updateSources(catalog)
                        Toast.makeText(context, "已保存，可在添加卡片中关联", Toast.LENGTH_LONG).show()
                    },
                    { Toast.makeText(context, it.message, Toast.LENGTH_LONG).show() },
                )
            }
        }
    }

    private fun importCsv() {
        val token = generation
        pickAttachment { result ->
            if (!closed && token == generation)
                result.fold(
                    { attachment ->
                        io.execute {
                            val parsed = runCatching {
                                require(
                                    attachment.mimeType == "text/csv" ||
                                        attachment.mimeType == "text/plain"
                                ) {
                                    "请选择 CSV 或 TXT 数据文件"
                                }
                                require(attachment.sizeBytes <= DashboardCsv.MAX_BYTES) {
                                    "CSV 最大 256 KB"
                                }
                                DashboardSource(
                                    UUID.randomUUID().toString(),
                                    attachment.name,
                                    SourceKind.IMPORT,
                                    "原始值",
                                    DashboardCsv.parse(File(attachment.localPath).readText()),
                                    "本机导入 · ${attachment.name}",
                                )
                            }
                            deliver(token) {
                                parsed.fold(
                                    ::persistSource,
                                    {
                                        Toast.makeText(context, it.message, Toast.LENGTH_LONG)
                                            .show()
                                    },
                                )
                            }
                        }
                    },
                    { Toast.makeText(context, it.message, Toast.LENGTH_SHORT).show() },
                )
        }
    }

    override fun setProp(propKey: String, propValue: Any): Boolean {
        if (propKey == "darkMode") {
            theme =
                if (propValue == true)
                    DashboardTheme(
                        0xFF101827.toInt(),
                        0xFF1E293B.toInt(),
                        Color.WHITE,
                        0xFF9DAAFF.toInt(),
                    )
                else DashboardTheme()
            updateSources(catalog)
            return true
        }
        return super.setProp(propKey, propValue)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        closed = false
        generation++
        loadState()
    }

    override fun onDetachedFromWindow() {
        closed = true
        generation++
        super.onDetachedFromWindow()
    }

    companion object {
        // One process-lifetime worker keeps writes and subsequent reattach loads ordered.
        private val io = Executors.newSingleThreadExecutor()

        fun demoSources(): List<DashboardSource> =
            listOf(
                DashboardSource(
                    "demo-breadth",
                    "涨跌分布",
                    SourceKind.DATA,
                    "家",
                    listOf(Datum("上涨", 1850.0), Datum("下跌", 2900.0), Datum("平盘", 250.0)),
                    "固定 UI 样例，非实时行情",
                    true,
                ),
                DashboardSource(
                    "demo-turnover",
                    "成交额趋势",
                    SourceKind.DATA,
                    "亿元",
                    listOf(
                        Datum("09-01", 10500.0, 1788220800000),
                        Datum("09-02", 12300.0, 1788307200000),
                        Datum("09-03", 11600.0, 1788393600000),
                    ),
                    "固定 UI 样例，非实时行情",
                    true,
                ),
                DashboardSource(
                    "demo-sector",
                    "板块表现",
                    SourceKind.DATA,
                    "涨跌幅 %",
                    listOf(
                        Datum("新能源", 2.45),
                        Datum("半导体", 1.82),
                        Datum("银行", 0.68),
                        Datum("传媒", -1.34),
                    ),
                    "固定 UI 样例，非实时行情",
                    true,
                ),
            )

        fun initial() =
            DashboardDocument(
                "我的市场看板",
                listOf(
                    DashboardCard(
                        "breadth",
                        "涨跌分布",
                        "demo-breadth",
                        ChartKind.PIE,
                        rect = GridRect(0, 0, 2),
                    ),
                    DashboardCard(
                        "turnover",
                        "成交额趋势",
                        "demo-turnover",
                        ChartKind.LINE,
                        rect = GridRect(2, 0, 2),
                    ),
                    DashboardCard(
                        "sectors",
                        "板块排行",
                        "demo-sector",
                        ChartKind.RANKING,
                        rect = GridRect(4, 0, 2),
                    ),
                ),
            )
    }
}
