package com.example.talktoai.dashboard

import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.talktoai.marketui.dashboard.*
import com.talktoai.marketui.importer.CsvTableDecoder
import com.talktoai.marketui.importer.ImportMapper
import com.talktoai.marketui.importer.ImportMapping
import com.talktoai.marketui.importer.ImportedTable
import com.talktoai.marketui.importer.JsonTableDecoder
import com.talktoai.marketui.importer.XlsxTableDecoder
import com.talktoai.marketui.showcase.*
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
        onImport = { importDataFile() }
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

    private fun importDataFile() {
        val token = generation
        pickAttachment { result ->
            if (!closed && token == generation)
                result.fold(
                    { attachment ->
                        io.execute {
                            val parsed = runCatching {
                                decodeTable(attachment.name, attachment.mimeType, File(attachment.localPath))
                            }
                            deliver(token) {
                                parsed.fold(
                                    { table -> showMappingDialog(attachment.name, table) },
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

    private fun decodeTable(name: String, mimeType: String, file: File): ImportedTable {
        require(file.length() in 1..MAX_IMPORT_BYTES) { "数据文件最大 2 MiB" }
        val extension = name.substringAfterLast('.', "").lowercase()
        val result = when {
            extension == "xlsx" || mimeType == com.example.talktoai.chat.AttachmentStore.XLSX_MIME_TYPE ->
                XlsxTableDecoder.decode(file.readBytes())
            extension == "json" || mimeType == "application/json" -> JsonTableDecoder.decode(file.readText())
            extension in setOf("csv", "txt") || mimeType in setOf("text/csv", "text/plain") ->
                CsvTableDecoder.decode(file.readText())
            else -> error("请选择 CSV、JSON 或 XLSX 数据文件")
        }
        return requireNotNull(result.value) { formatIssues(result.issues.map { it.message }) }
            .also { require(it.rows.size <= MAX_IMPORT_ROWS) { "导入数据最多 $MAX_IMPORT_ROWS 行" } }
    }

    private fun showMappingDialog(fileName: String, table: ImportedTable) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }
        val label = content.addMappingSpinner("标签列", table.headers, guess(table.headers, LABEL_NAMES))
        val value = content.addMappingSpinner("数值列", table.headers, guess(table.headers, VALUE_NAMES, 1))
        val id = content.addMappingSpinner("数据 ID 列（可选）", table.headers, -1, optional = true)
        val time = content.addMappingSpinner("毫秒时间戳列（可选）", table.headers, guess(table.headers, TIME_NAMES), optional = true)
        val series = content.addMappingSpinner("序列列（可选）", table.headers, guess(table.headers, SERIES_NAMES), optional = true)
        val axis = content.addMappingSpinner("纵轴列（可选，最多两个）", table.headers, guess(table.headers, AXIS_NAMES), optional = true)
        content.addView(TextView(context).apply { text = "单位" })
        val unit = EditText(context).apply { hint = "例如：亿元、%、元" }
        content.addView(unit, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val dialog = android.app.AlertDialog.Builder(context)
            .setTitle("映射 $fileName")
            .setMessage("已读取 ${table.rows.size} 行。请确认字段映射后导入。")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("取消", null)
            .setPositiveButton("导入", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mapping = ImportMapping(
                    idColumn = id.optionalSelection(),
                    labelColumn = label.requiredSelection(),
                    valueColumn = value.requiredSelection(),
                    timestampColumn = time.optionalSelection(),
                    seriesColumn = series.optionalSelection(),
                    axisColumn = axis.optionalSelection(),
                    unit = unit.text.toString().trim(),
                )
                val mapped = ImportMapper.map(table, mapping)
                val data = mapped.value
                if (data == null) {
                    Toast.makeText(context, formatIssues(mapped.issues.map { issue ->
                        listOfNotNull(issue.row?.let { "第${it}行" }, issue.column, issue.message).joinToString(" · ")
                    }), Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                val rows = data.series.flatMap { chartSeries ->
                    chartSeries.points.map { point ->
                        Datum(point.label, point.value, point.timestampMs, point.id, chartSeries.id, chartSeries.axisId)
                    }
                }
                persistSource(DashboardSource(
                    UUID.randomUUID().toString(), fileName, SourceKind.IMPORT, data.unit, rows,
                    "本机导入 · $fileName · App 显式映射",
                ))
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun LinearLayout.addMappingSpinner(
        title: String,
        headers: List<String>,
        selectedHeader: Int,
        optional: Boolean = false,
    ): Spinner {
        addView(TextView(context).apply { text = title })
        val values = if (optional) listOf(OPTIONAL_COLUMN) + headers else headers
        return Spinner(context).also { spinner ->
            spinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, values)
            spinner.setSelection(if (optional) selectedHeader + 1 else selectedHeader.coerceAtLeast(0))
            addView(spinner, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun Spinner.requiredSelection() = selectedItem.toString()
    private fun Spinner.optionalSelection() = selectedItem.toString().takeUnless { it == OPTIONAL_COLUMN }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun guess(headers: List<String>, candidates: Set<String>, fallback: Int = -1): Int =
        headers.indexOfFirst { header -> header.trim().lowercase() in candidates }.takeIf { it >= 0 }
            ?: fallback.coerceIn(-1, headers.lastIndex)

    private fun formatIssues(issues: List<String>): String =
        issues.take(MAX_VISIBLE_ISSUES).joinToString("\n").ifBlank { "数据解析失败" }

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

        fun demoSources(): List<DashboardSource> = ScreenshotMockData.cards.map(::mockSource)

        private fun mockSource(card: ScreenshotCardData): DashboardSource {
            val chart = card.chart
            val series = when (chart) {
                is BarChartData -> chart.series
                is LineChartData -> chart.series
                is FinancialMarketData -> chart.priceAndAverage.series
                else -> emptyList()
            }
            val rows = when (chart) {
                is PieChartData -> chart.slices.map { Datum(it.name, it.value, id = it.id) }
                is RatioChartData -> chart.entries.map { Datum(it.name, it.value, id = it.id) }
                is RankingChartData -> chart.entries.map { Datum(it.name, it.value, id = "rank-${it.rank}") }
                else -> series.flatMap { item -> item.points.map { point ->
                    Datum(point.label, point.value, point.timestampMs, "${item.id}:${point.id}", item.id, item.axisId)
                } }
            }
            val unit = when (chart) {
                is BarChartData -> chart.axes.firstOrNull()?.unit
                is LineChartData -> chart.axes.firstOrNull()?.unit
                is PieChartData -> chart.unit
                is RatioChartData -> chart.unit
                is RankingChartData -> chart.entries.firstOrNull()?.unit
                is FinancialMarketData -> chart.priceAndAverage.axes.firstOrNull()?.unit
            }.orEmpty()
            return DashboardSource(
                "screenshot-${card.screenshot}", card.text.title, SourceKind.DATA, unit, rows,
                "截图 ${card.screenshot} 结构化 Mock · ${card.note ?: "可替换为真实行情"}", true,
            )
        }

        fun initial() =
            DashboardDocument(
                "我的市场看板",
                listOf(
                    DashboardCard(
                        "capital-category",
                        "资金流入",
                        "screenshot-2",
                        ChartKind.BAR,
                        rect = GridRect(0, 0, 2),
                    ),
                    DashboardCard(
                        "capital-and-price",
                        "资金与股价",
                        "screenshot-7",
                        ChartKind.LINE,
                        rect = GridRect(2, 0, 2),
                    ),
                    DashboardCard(
                        "industry-ranking",
                        "行业排行",
                        "screenshot-5",
                        ChartKind.RANKING,
                        rect = GridRect(4, 0, 2),
                    ),
                ),
            )

        private const val MAX_IMPORT_BYTES = 2L * 1024 * 1024
        private const val MAX_IMPORT_ROWS = 5_000
        private const val MAX_VISIBLE_ISSUES = 6
        private const val OPTIONAL_COLUMN = "（不映射）"
        private val LABEL_NAMES = setOf("label", "name", "标签", "名称", "日期", "时间")
        private val VALUE_NAMES = setOf("value", "数值", "值", "amount", "金额", "涨跌幅")
        private val TIME_NAMES = setOf("timems", "timestamp", "timestampms", "时间戳")
        private val SERIES_NAMES = setOf("series", "seriesid", "序列", "指标")
        private val AXIS_NAMES = setOf("axis", "axisid", "纵轴")
    }
}
