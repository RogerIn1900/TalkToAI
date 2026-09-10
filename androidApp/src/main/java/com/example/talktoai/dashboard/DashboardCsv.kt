package com.example.talktoai.dashboard

import com.talktoai.marketui.dashboard.Datum

/** Bounded UTF-8 CSV; quotes may only surround a complete field. */
object DashboardCsv {
    const val MAX_BYTES = 256 * 1024
    const val MAX_ROWS = 1000

    private enum class State {
        START,
        PLAIN,
        QUOTED,
        CLOSED,
    }

    fun parse(text: String): List<Datum> {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "CSV 最大 256 KB" }
        val records = mutableListOf<List<String>>()
        var record = mutableListOf<String>()
        val field = StringBuilder()
        var state = State.START
        fun finishField() {
            record.add(field.toString())
            field.clear()
            state = State.START
        }
        fun finishRecord() {
            finishField()
            if (record.any { it.isNotBlank() }) {
                records.add(record)
                require(records.size <= MAX_ROWS + 1) { "CSV 最多 1000 条数据" }
            }
            record = mutableListOf()
        }
        val input = text.removePrefix("\uFEFF")
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                state == State.QUOTED -> {
                    if (ch == '"') state = State.CLOSED else field.append(ch)
                }
                state == State.CLOSED && ch == '"' -> {
                    field.append('"')
                    state = State.QUOTED
                }
                ch == ',' -> finishField()
                ch == '\n' || ch == '\r' -> {
                    finishRecord()
                    if (ch == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                }
                ch == '"' -> {
                    require(state == State.START) { "CSV 引号必须位于字段开头" }
                    state = State.QUOTED
                }
                else -> {
                    require(state != State.CLOSED) { "CSV 闭合引号后只能是分隔符" }
                    field.append(ch)
                    state = State.PLAIN
                }
            }
            i++
        }
        require(state != State.QUOTED) { "CSV 引号未闭合" }
        finishRecord()
        require(records.size >= 2) { "CSV 需要表头和 1–1000 条数据" }
        val header = records.first().map(String::trim)
        require(
            header == listOf("label", "value") || header == listOf("label", "value", "timeMs")
        ) {
            "表头应为 label,value 或 label,value,timeMs"
        }
        return records.drop(1).mapIndexed { index, row ->
            require(row.size == header.size && row[0].isNotBlank()) { "第 ${index + 2} 行列数或名称无效" }
            val value = row[1].trim().toDoubleOrNull()
            require(value != null && value.isFinite()) { "第 ${index + 2} 行数值无效" }
            val time =
                if (header.size == 3 && row[2].isNotBlank()) {
                    requireNotNull(row[2].trim().toLongOrNull()) { "时间必须为毫秒时间戳" }
                } else null
            Datum(row[0].trim(), value, time)
        }
    }
}
