package com.example.talktoai.dsh.observability

import com.example.talktoai.dsh.contract.AttachmentRef
import com.example.talktoai.dsh.contract.ImageAttachmentPayload

/**
 * 日志脱敏器（设计 §9）。
 *
 * 设计原文："日志与反馈包默认脱敏；正文、Token、路径默认脱敏"。
 * 规则：
 *   - prompt 正文：替换为 "<redacted:N bytes>"
 *   - base64 图片载荷：替换为 "<image id=... size=...>"
 *   - 凭据 token：替换为 "<redacted>"
 *   - requestId / sessionId / seq 保留（用于关联）
 */
object LogScrubber {

    private val PROMPT_TEXT_HINT = listOf("text=", "\"text\":", "\"message\":")
    private val B64_PATTERN = Regex("[A-Za-z0-9+/]{120,}=*")
    private val TOKEN_PATTERN = Regex("(?i)(token|secret|cookie|password)\\s*[:=]\\s*[^,;\\s\"]+")

    fun scrubText(s: String, maxLen: Int = 200): String {
        if (s.isEmpty()) return s
        val withoutToken = TOKEN_PATTERN.replace(s) { m ->
            "${m.value.substringBefore("=").substringBefore(":")}=<redacted>"
        }
        val noB64 = B64_PATTERN.replace(withoutToken) { _ -> "<redacted-base64>" }
        return if (noB64.length > maxLen) noB64.take(maxLen) + "…" else noB64
    }

    fun scrubPromptText(text: String): String {
        if (text.isEmpty()) return text
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return "<redacted prompt: $bytes bytes>"
    }

    fun scrubAttachment(p: ImageAttachmentPayload): String =
        "<image id=${p.ref.id} mime=${p.ref.mime} size=${p.ref.sizeBytes}B w=${p.widthPx}h=${p.heightPx}>"

    fun scrubAttachmentRef(r: AttachmentRef): String =
        "<att id=${r.id} mime=${r.mime} size=${r.sizeBytes}B>"

    /** 是否命中 "prompt 文本" 特征（用于自动检测未知字段）。 */
    fun looksLikePromptField(name: String): Boolean =
        PROMPT_TEXT_HINT.any { name.contains(it.trim('"', ':', '='), ignoreCase = true) }
}

/**
 * 包装任意 [DshLogger]，对 text/attachments 做脱敏。
 * 保留原始 logger 用于结构化字段（connectionGeneration/sessionId/requestId/seq）。
 */
class SanitizedLogger(
    private val delegate: DshLogger,
) : DshLogger {
    override fun debug(tag: String, ctx: TraceContext, message: String) =
        delegate.debug(tag, ctx, LogScrubber.scrubText(message))

    override fun info(tag: String, ctx: TraceContext, message: String) =
        delegate.info(tag, ctx, LogScrubber.scrubText(message))

    override fun warn(tag: String, ctx: TraceContext, message: String, error: Throwable?) =
        delegate.warn(tag, ctx, LogScrubber.scrubText(message), error)

    override fun error(tag: String, ctx: TraceContext, message: String, error: Throwable?) =
        delegate.error(tag, ctx, LogScrubber.scrubText(message), error)
}
