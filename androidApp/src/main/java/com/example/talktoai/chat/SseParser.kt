package com.example.talktoai.chat

class SseParser {
    private var eventName = "message"
    private val dataLines = mutableListOf<String>()

    fun accept(line: String): StreamEvent? {
        if (line.isEmpty()) return flush()
        if (line.startsWith(':')) return null
        val separator = line.indexOf(':')
        val field = if (separator >= 0) line.substring(0, separator) else line
        val value = if (separator >= 0) line.substring(separator + 1).removePrefix(" ") else ""
        when (field) {
            "event" -> eventName = value
            "data" -> dataLines += value
        }
        return null
    }

    fun finish(): StreamEvent? = flush()

    private fun flush(): StreamEvent? {
        if (dataLines.isEmpty()) {
            eventName = "message"
            return null
        }
        val event = StreamEvent(eventName, dataLines.joinToString("\n"))
        eventName = "message"
        dataLines.clear()
        return event
    }
}
