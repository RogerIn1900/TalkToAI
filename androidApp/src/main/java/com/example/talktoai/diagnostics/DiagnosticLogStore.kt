package com.example.talktoai.diagnostics

import android.content.Context
import android.os.Build
import com.example.talktoai.BuildConfig
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticLogStore(
    private val context: Context,
    private val redactor: ReversibleRedactor = ReversibleRedactor(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val directory = File(context.filesDir, LOG_DIRECTORY).apply { mkdirs() }

    fun record(level: String, event: String, identifiers: Map<String, String> = emptyMap(), attributes: Map<String, Any> = emptyMap()) {
        synchronized(lock) {
            purgeExpired()
            val file = todayFile()
            if (lineCount(file) >= MAX_LOGS_PER_DAY) return
            val safeEvent = sanitizeToken(event)
            val json = JSONObject()
                .put("schemaVersion", 1)
                .put("timestamp", Instant.ofEpochMilli(nowMs()).toString())
                .put("level", level.takeIf { it in LEVELS } ?: "info")
                .put("event", safeEvent)
                .put("identifiers", JSONObject().apply {
                    identifiers.forEach { (key, value) ->
                        if (value.isNotBlank()) put(sanitizeToken(key), redactor.encode(value.take(MAX_IDENTIFIER_CHARS)))
                    }
                })
                .put("attributes", JSONObject().apply {
                    attributes.forEach { (key, value) -> put(sanitizeToken(key), sanitizeAttribute(value)) }
                })
            file.appendText(json.toString() + "\n", Charsets.UTF_8)
        }
    }

    fun summary(): JSONObject = synchronized(lock) {
        purgeExpired()
        val files = logFiles()
        JSONObject()
            .put("retentionDays", RETENTION_DAYS)
            .put("dailyLimit", MAX_LOGS_PER_DAY)
            .put("fileCount", files.size)
            .put("eventCount", files.sumOf(::lineCount))
            .put("redaction", "AES-256-GCM/AndroidKeyStore/local-reversible")
    }

    fun createFeedbackPackage(): File = synchronized(lock) {
        purgeExpired()
        val feedbackDirectory = File(context.cacheDir, FEEDBACK_DIRECTORY).apply { mkdirs() }
        val target = File(feedbackDirectory, "talktoai-feedback-${nowMs()}.zip")
        ZipOutputStream(target.outputStream().buffered()).use { zip ->
            val manifest = JSONObject()
                .put("schemaVersion", 1)
                .put("createdAt", Instant.ofEpochMilli(nowMs()).toString())
                .put("appVersion", BuildConfig.VERSION_NAME)
                .put("appVersionCode", BuildConfig.VERSION_CODE)
                .put("androidSdk", Build.VERSION.SDK_INT)
                .put("device", "${Build.MANUFACTURER} ${Build.MODEL}".take(MAX_ATTRIBUTE_CHARS))
                .put("contents", "manifest.json + structured-logs/*.jsonl")
                .put("excluded", "chat bodies, attachment bytes, secrets, access tokens, raw identifiers")
                .put("identifierRedaction", "AES-256-GCM; reversible only on the originating Android installation")
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(manifest.toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            logFiles().forEach { file ->
                zip.putNextEntry(ZipEntry("structured-logs/${file.name}"))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        target
    }

    private fun todayFile(): File = File(directory, "${localDate(nowMs())}.jsonl")
    private fun logFiles(): List<File> = directory.listFiles { file -> file.extension == "jsonl" }?.sortedBy { it.name }.orEmpty()

    private fun purgeExpired() {
        val cutoff = localDate(nowMs()).minusDays((RETENTION_DAYS - 1).toLong())
        logFiles().forEach { file ->
            val date = runCatching { LocalDate.parse(file.nameWithoutExtension) }.getOrNull()
            if (date == null || date.isBefore(cutoff)) file.delete()
        }
    }

    private fun localDate(epochMs: Long): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(ZoneId.of(APP_TIME_ZONE)).toLocalDate()

    private fun lineCount(file: File): Int = if (!file.exists()) 0 else file.useLines { lines -> lines.take(MAX_LOGS_PER_DAY + 1).count() }
    private fun sanitizeToken(value: String): String = value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(MAX_TOKEN_CHARS)
    private fun sanitizeAttribute(value: Any): Any = when (value) {
        is Boolean, is Int, is Long, is Float, is Double -> value
        else -> value.toString().replace(Regex("(?i)(bearer\\s+|api[_-]?key[=:]?)[^\\s,;]+"), "$1[REDACTED]").take(MAX_ATTRIBUTE_CHARS)
    }

    companion object {
        const val RETENTION_DAYS = 10
        const val MAX_LOGS_PER_DAY = 5_000
        private const val APP_TIME_ZONE = "Asia/Shanghai"
        private const val LOG_DIRECTORY = "talktoai_logs"
        private const val FEEDBACK_DIRECTORY = "feedback"
        private const val MAX_TOKEN_CHARS = 64
        private const val MAX_IDENTIFIER_CHARS = 128
        private const val MAX_ATTRIBUTE_CHARS = 256
        private val LEVELS = setOf("debug", "info", "warn", "error")
    }
}
