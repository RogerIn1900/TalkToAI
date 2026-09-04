package com.example.talktoai.dsh.contract

data class AttachmentRef(val id: String, val mime: String, val sizeBytes: Long, val sha256: String? = null) {
    init { require(id.isNotEmpty()) { "AttachmentRef.id must not be empty" } }
}

data class ImageAttachmentPayload(
    val ref: AttachmentRef,
    val base64Encoded: String,
    val widthPx: Int,
    val heightPx: Int,
) {
    val encodedBytes: Long get() = base64Encoded.length.toLong()
}

data class ImageBudget(
    val maxImageBytes: Long = 5L * 1024 * 1024,
    val maxPromptBodyBytes: Long = 8L * 1024 * 1024,
    val maxPeakMemoryBytes: Long = 40L * 1024 * 1024,
    val maxConcurrentImageUploads: Int = 1,
) {
    companion object {
        val DEFAULT = ImageBudget()
        val LOW_MEMORY = ImageBudget(
            maxImageBytes = 2L * 1024 * 1024,
            maxPromptBodyBytes = 3L * 1024 * 1024,
            maxPeakMemoryBytes = 16L * 1024 * 1024,
        )
    }
}
