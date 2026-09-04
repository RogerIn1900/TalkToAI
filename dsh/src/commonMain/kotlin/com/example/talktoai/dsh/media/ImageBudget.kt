package com.example.talktoai.dsh.media

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.ImageAttachmentPayload
import com.example.talktoai.dsh.contract.ImageBudget

/**
 * 图片/内存预算校验（设计 §8.1）。
 * 单图原始字节、单消息总字节、Base64 后请求体、并发数量。
 */
class ImageBudgetGuard(private val budget: ImageBudget) {

    @Volatile
    private var inFlightCount: Int = 0

    fun acquire(): DshResult<Token> {
        if (inFlightCount >= budget.maxConcurrentImageUploads) {
            return DshResult.failure(DshError.Client.ConcurrentImageUpload)
        }
        inFlightCount++
        return DshResult.success(Token(this))
    }

    fun release() {
        check(inFlightCount > 0) { "release without acquire" }
        inFlightCount--
    }

    fun validate(payload: ImageAttachmentPayload): DshResult<Unit> {
        if (payload.ref.sizeBytes > budget.maxImageBytes) {
            return DshResult.failure(
                DshError.Business.AttachmentTooLarge(payload.ref.sizeBytes, budget.maxImageBytes)
            )
        }
        if (payload.encodedBytes > budget.maxPromptBodyBytes) {
            return DshResult.failure(DshError.Client.ImageBudgetExceeded)
        }
        return DshResult.success(Unit)
    }

    fun validateBatch(payloads: List<ImageAttachmentPayload>): DshResult<Unit> {
        if (payloads.isEmpty()) return DshResult.success(Unit)
        if (payloads.size > budget.maxConcurrentImageUploads) {
            return DshResult.failure(DshError.Client.ConcurrentImageUpload)
        }
        val totalEncoded = payloads.sumOf { it.encodedBytes }
        if (totalEncoded > budget.maxPromptBodyBytes) {
            return DshResult.failure(DshError.Client.ImageBudgetExceeded)
        }
        for (p in payloads) {
            val r = validate(p)
            if (r is DshResult.Err) return r
        }
        return DshResult.success(Unit)
    }

    class Token internal constructor(private val guard: ImageBudgetGuard) : AutoCloseable {
        private var closed = false
        override fun close() {
            if (!closed) {
                guard.release()
                closed = true
            }
        }
    }
}
