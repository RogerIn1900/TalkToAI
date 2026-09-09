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

    /** All view instances share one read-modify-write boundary in this process. */
    fun appendSource(source: DashboardSource): List<DashboardSource> =
        synchronized(sourceLock) {
            val next = sources() + source
            require(next.map { it.id }.distinct().size == next.size) { "数据源 ID 重复" }
            saveSources(next)
            next
        }

    fun saveSources(sources: List<DashboardSource>) =
        synchronized(sourceLock) {
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

    private companion object {
        val sourceLock = Any()
    }
}
