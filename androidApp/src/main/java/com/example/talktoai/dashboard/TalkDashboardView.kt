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
    private var io = Executors.newSingleThreadExecutor()
    private var closed = false

    init {
        onSave = { document, done ->
            io.execute {
                val result = runCatching { store.save(document) }
                post { if (!closed) done(result) }
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
        io.execute {
            val loaded = runCatching { (store.load() ?: initial()) to (catalog + store.sources()) }
            post {
                if (!closed)
                    loaded.fold(
                        { (doc, data) ->
                            catalog = data
                            submit(doc, catalog)
                        },
                        { failure ->
                            // Keep corrupt stored values untouched; do not silently replace them
                            // with samples.
                            submit(DashboardDocument("看板读取失败", emptyList()), catalog)
                            onSave = { _, done ->
                                done(Result.failure(IllegalStateException("原配置读取失败，请先备份数据")))
                            }
                            Toast.makeText(context, failure.message ?: "看板读取失败", Toast.LENGTH_LONG)
                                .show()
                        },
                    )
            }
        }
    }

    private fun persistSource(source: DashboardSource) {
        io.execute {
            val result = runCatching {
                val next = store.sources() + source
                store.saveSources(next)
                demoSources() + next
            }
            post {
                if (!closed)
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
        pickAttachment { result ->
            if (!closed)
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
                            post {
                                if (!closed)
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
        if (io.isShutdown) io = Executors.newSingleThreadExecutor()
    }

    override fun onDetachedFromWindow() {
        closed = true
        io.shutdown()
        super.onDetachedFromWindow()
    }

    companion object {
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
