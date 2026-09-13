package com.example.talktoai.talk

/** Typed presentation blocks keep transport JSON out of the Kuikly rendering decisions. */
internal sealed interface ChatAnswerBlockUi {
    data class Market(val rawJson: String, val items: List<MarketOverviewItem>) : ChatAnswerBlockUi
    data class Markdown(val content: String) : ChatAnswerBlockUi
    data class Attachments(val names: List<String>) : ChatAnswerBlockUi
    data class Citations(val urls: List<String>) : ChatAnswerBlockUi
}

internal object ChatAnswerBlockPolicy {
    fun blocks(message: ChatMessageUi): List<ChatAnswerBlockUi> = buildList {
        if (message.marketDataJson.isNotBlank()) {
            add(ChatAnswerBlockUi.Market(message.marketDataJson, MarketOverviewPolicy.parse(message.marketDataJson)))
        }
        if (message.content.isNotBlank()) add(ChatAnswerBlockUi.Markdown(message.content))
        if (message.attachmentNames.isNotEmpty()) add(ChatAnswerBlockUi.Attachments(message.attachmentNames))
        if (message.citations.isNotEmpty()) add(ChatAnswerBlockUi.Citations(message.citations.distinct()))
    }
}

/** Pure patch functions used by incremental native-to-Kuikly stream events. */
internal object ChatStreamPatchPolicy {
    fun delta(message: ChatMessageUi, text: String): ChatMessageUi {
        val existing = if (message.status == "streaming" && message.content == "▋") "" else message.content
        return message.copy(content = existing + text, status = "streaming")
    }

    fun market(message: ChatMessageUi, rawJson: String): ChatMessageUi =
        message.copy(marketDataJson = rawJson)

    fun citation(message: ChatMessageUi, url: String): ChatMessageUi =
        if (!url.startsWith("https://") || url in message.citations) message
        else message.copy(citations = message.citations + url)
}
