package com.example.talktoai.dashboard

import android.content.Context
import com.talktoai.marketui.dashboard.*
import org.json.JSONArray
import org.json.JSONObject

/** Local dashboard settings and explicitly imported rows, never sent to AI or market APIs. */
class DashboardStore(context: Context) {
    private val preferences =
        context.getSharedPreferences("editable_dashboard_v1", Context.MODE_PRIVATE)

    fun load(): DashboardDocument? =
        preferences.getString("document", null)?.let(DashboardJson::decode)

    fun save(doc: DashboardDocument) {
        check(preferences.edit().putString("document", DashboardJson.encode(doc)).commit()) {
            "看板保存失败"
        }
    }

    fun sources(): List<DashboardSource> {
        val array = JSONArray(preferences.getString("sources", "[]"))
        return List(array.length()) { i ->
            val source = array.getJSONObject(i)
            val rows = source.getJSONArray("rows")
            DashboardSource(
                source.getString("id"),
                source.getString("name"),
                SourceKind.valueOf(source.getString("kind")),
                source.getString("unit"),
                List(rows.length()) { j ->
                    val row = rows.getJSONObject(j)
                    Datum(
                        row.getString("label"),
                        row.getDouble("value"),
                        if (row.isNull("time")) null else row.getLong("time"),
                    )
                },
                source.getString("provenance"),
                source.getBoolean("simulated"),
            )
        }
    }

    fun saveSources(sources: List<DashboardSource>) {
        val array = JSONArray()
        sources
            .filter { it.kind != SourceKind.DATA }
            .forEach { source ->
                array.put(
                    JSONObject()
                        .put("id", source.id)
                        .put("name", source.name)
                        .put("kind", source.kind.name)
                        .put("unit", source.unit)
                        .put("provenance", source.provenance)
                        .put("simulated", source.simulated)
                        .put(
                            "rows",
                            JSONArray().apply {
                                source.rows.forEach { row ->
                                    put(
                                        JSONObject()
                                            .put("label", row.label)
                                            .put("value", row.value)
                                            .put("time", row.timeMs)
                                    )
                                }
                            },
                        )
                )
            }
        check(preferences.edit().putString("sources", array.toString()).commit()) { "数据保存失败" }
    }
}

/** Deliberately small CSV contract, with quoted fields supported and bounded input. */
object DashboardCsv {
    const val MAX_BYTES = 256 * 1024
    const val MAX_ROWS = 1000

    fun parse(text: String): List<Datum> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "CSV 最大 256 KB" }
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            when {
                ch == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    field.append('"')
                    i++
                }
                ch == '"' -> quoted = !quoted
                ch == ',' && !quoted -> {
                    record.add(field.toString())
                    field.clear()
                }
                ch == '\n' && !quoted -> {
                    record.add(field.toString().trimEnd('\r'))
                    field.clear()
                    if (record.any { it.isNotBlank() }) records.add(record)
                    record = mutableListOf()
                }
                else -> field.append(ch)
            }
            i++
        }
        require(!quoted) { "CSV 引号未闭合" }
        record.add(field.toString().trimEnd('\r'))
        if (record.any { it.isNotBlank() }) records.add(record)
        require(records.size in 2..MAX_ROWS + 1) { "CSV 需要表头和 1–1000 条数据" }
        val header = records.first().map { it.trim().removePrefix("\uFEFF") }
        require(
            header == listOf("label", "value") || header == listOf("label", "value", "timeMs")
        ) {
            "表头应为 label,value 或 label,value,timeMs"
        }
        return records.drop(1).mapIndexed { index, row ->
            require(row.size == header.size && row[0].isNotBlank()) { "第 ${index+2} 行列数或名称无效" }
            val value = row[1].trim().toDoubleOrNull()
            require(value != null && value.isFinite()) { "第 ${index+2} 行数值无效" }
            val time =
                if (header.size == 3 && row[2].isNotBlank())
                    row[2].trim().toLongOrNull().also { require(it != null) { "时间必须为毫秒时间戳" } }
                else null
            Datum(row[0].trim(), value, time)
        }
    }
}
