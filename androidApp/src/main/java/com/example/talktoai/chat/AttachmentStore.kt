package com.example.talktoai.chat

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

class AttachmentStore(private val context: Context) {
    fun import(uri: Uri): ChatAttachment {
        val resolver = context.contentResolver
        val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null else {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    Metadata(
                        name = if (nameIndex >= 0) cursor.getString(nameIndex) else null,
                        size = if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) cursor.getLong(sizeIndex) else null,
                    )
                }
            }
        val name = sanitizeFileName(displayName?.name ?: "attachment")
        val mimeType = normalizeMimeType(resolver.getType(uri), name)
        val maxBytes = if (mimeType.startsWith("image/")) MAX_IMAGE_BYTES else MAX_TEXT_BYTES
        displayName?.size?.let { require(it in 1..maxBytes) { "ATTACHMENT_SIZE_INVALID" } }

        val directory = File(context.filesDir, ATTACHMENTS_DIRECTORY).apply { mkdirs() }
        val digest = MessageDigest.getInstance("SHA-256")
        val temporary = File.createTempFile("import-", ".tmp", directory)
        var copied = 0L
        try {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "ATTACHMENT_OPEN_FAILED" }
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= maxBytes) { "ATTACHMENT_TOO_LARGE" }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            require(copied > 0L) { "ATTACHMENT_EMPTY" }
            val id = digest.digest().joinToString("") { "%02x".format(it) }.take(ID_CHARS)
            val target = File(directory, "$id-${name.take(MAX_FILE_NAME_CHARS)}")
            if (!target.exists()) check(temporary.renameTo(target)) { "ATTACHMENT_MOVE_FAILED" }
            return ChatAttachment(id, name, mimeType, copied, target.absolutePath)
        } finally {
            temporary.delete()
        }
    }

    private fun normalizeMimeType(reported: String?, fileName: String): String {
        val normalized = reported?.lowercase()?.substringBefore(';')
        if (normalized in SUPPORTED_MIME_TYPES) return normalized!!
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "csv" -> "text/csv"
            "txt" -> "text/plain"
            else -> throw IllegalArgumentException("ATTACHMENT_TYPE_UNSUPPORTED")
        }
    }

    private fun sanitizeFileName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._()\\-\\u4e00-\\u9fa5]"), "_").trim('.').ifEmpty { "attachment" }

    private data class Metadata(val name: String?, val size: Long?)

    companion object {
        const val MAX_ATTACHMENTS = 5
        const val MAX_IMAGE_BYTES = 10L * 1024 * 1024
        const val MAX_TEXT_BYTES = 2L * 1024 * 1024
        private const val COPY_BUFFER_BYTES = 16 * 1024
        private const val MAX_FILE_NAME_CHARS = 80
        private const val ID_CHARS = 24
        private const val ATTACHMENTS_DIRECTORY = "talktoai_attachments"
        val SUPPORTED_MIME_TYPES = setOf(
            "image/jpeg", "image/png", "image/webp", "image/gif", "text/csv", "text/plain",
        )
    }
}
