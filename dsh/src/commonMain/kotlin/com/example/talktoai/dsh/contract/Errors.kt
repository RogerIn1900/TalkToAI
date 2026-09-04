package com.example.talktoai.dsh.contract

/**
 * 顶层错误码。App 端必须基于错误码决定是否重建连接、是否重试、是否提示用户，
 * 不能以 HTTP/IO 状态码推导业务语义。
 *
 * 参考设计 §9 可靠性：command 失败不伪造 Host 状态，logical stream 失败即重建。
 */
sealed class DshError(open val message: String, open val cause: Throwable? = null) {

    data class IncompatibleHost(
        val remoteVersion: String,
        val expectedVersion: String,
    ) : DshError("Incompatible Host version: remote=$remoteVersion, expected=$expectedVersion")

    sealed class Transport(override val message: String, override val cause: Throwable? = null) : DshError(message, cause) {
        data class Disconnected(override val cause: Throwable? = null) : Transport("Connection disconnected", cause)
        data class Timeout(val op: String) : Transport("Timeout: $op")
        data class Network(override val cause: Throwable? = null) : Transport("Network failure", cause)
        data class Tls(override val cause: Throwable? = null) : Transport("TLS failure", cause)
        data class AuthRequired(val reason: String) : Transport("Auth required: $reason")
        data class Unpaired(val deviceId: String) : Transport("Device not paired: $deviceId")
    }

    sealed class Protocol(override val message: String) : DshError(message) {
        data class Gap(val expectedSeq: Long, val receivedSeq: Long) :
            Protocol("Stream gap: expected=$expectedSeq, received=$receivedSeq")
        data class DroppedDuplicate(val seq: Long, val expected: Long) :
            Protocol("Dropped duplicate event seq=$seq expected=$expected")
        data object NotAnOpeningSnapshot : Protocol("First frame is not an opening snapshot")
        data object NonMonotonic : Protocol("Event seq is not monotonic after opening snapshot")
        data class UnknownStream(val type: String) : Protocol("Unknown stream type: $type")
        data class UnknownContentBlock(val type: String) : Protocol("Unknown content block: $type")
        data class UnknownInteraction(val type: String) : Protocol("Unknown interaction: $type")
    }

    sealed class Business(override val message: String) : DshError(message) {
        data class IdempotencyConflict(val requestId: String) :
            Business("Idempotency conflict for requestId=$requestId")
        data class Rejected(val reason: String) : Business("Rejected by Host: $reason")
        data class RateLimited(val retryAfterMs: Long?) : Business(
            "Rate limited" + (retryAfterMs?.let { " retryAfter=${it}ms" } ?: "")
        )
        data class AttachmentTooLarge(val sizeBytes: Long, val limitBytes: Long) :
            Business("Attachment too large: $sizeBytes > $limitBytes")
    }

    sealed class Client(override val message: String) : DshError(message) {
        data object ImageBudgetExceeded : Client("Image memory budget exceeded")
        data object ConcurrentImageUpload : Client("Concurrent image upload not allowed in v1")
        data class InvalidArgument(val arg: String) : Client("Invalid argument: $arg")
    }
}

sealed class DshResult<out T> {
    data class Ok<T>(val value: T) : DshResult<T>()
    data class Err(val error: DshError) : DshResult<Nothing>()

    inline fun <R> map(transform: (T) -> R): DshResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    inline fun onSuccess(block: (T) -> Unit): DshResult<T> {
        if (this is Ok) block(value)
        return this
    }

    inline fun onFailure(block: (DshError) -> Unit): DshResult<T> {
        if (this is Err) block(error)
        return this
    }

    fun getOrNull(): T? = (this as? Ok)?.value
    fun errorOrNull(): DshError? = (this as? Err)?.error

    companion object {
        fun <T> success(value: T): DshResult<T> = Ok(value)
        fun <T> failure(error: DshError): DshResult<T> = Err(error)
    }
}
