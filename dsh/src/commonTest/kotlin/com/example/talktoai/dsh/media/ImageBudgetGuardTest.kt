package com.example.talktoai.dsh.media

import com.example.talktoai.dsh.contract.AttachmentRef
import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.ImageAttachmentPayload
import com.example.talktoai.dsh.contract.ImageBudget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ImageBudgetGuardTest {

    private fun payload(sizeBytes: Long, b64Len: Long = 100L, id: String = "img1") = ImageAttachmentPayload(
        ref = AttachmentRef(id = id, mime = "image/jpeg", sizeBytes = sizeBytes),
        base64Encoded = "a".repeat(b64Len.toInt()),
        widthPx = 1024,
        heightPx = 1024,
    )

    @Test
    fun validate_passes_when_under_budget() {
        val guard = ImageBudgetGuard(ImageBudget.DEFAULT)
        val r = guard.validate(payload(sizeBytes = 1_000_000))
        assertIs<DshResult.Ok<Unit>>(r)
    }

    @Test
    fun validate_rejects_image_above_max_image_bytes() {
        val guard = ImageBudgetGuard(ImageBudget.DEFAULT)
        val r = guard.validate(payload(sizeBytes = 10L * 1024 * 1024))
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Business.AttachmentTooLarge>(r.error)
    }

    @Test
    fun validate_rejects_when_b64_exceeds_prompt_body_budget() {
        val guard = ImageBudgetGuard(ImageBudget.DEFAULT)
        val r = guard.validate(payload(sizeBytes = 100, b64Len = 100L * 1024 * 1024))
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Client.ImageBudgetExceeded>(r.error)
    }

    @Test
    fun validate_batch_rejects_more_than_one_image() {
        val guard = ImageBudgetGuard(ImageBudget.DEFAULT)
        val r = guard.validateBatch(listOf(payload(1000, id = "a"), payload(1000, id = "b")))
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Client.ConcurrentImageUpload>(r.error)
    }

    @Test
    fun validate_batch_rejects_total_encoded_above_budget() {
        val guard = ImageBudgetGuard(ImageBudget.DEFAULT)
        val r = guard.validateBatch(listOf(payload(1000, b64Len = 9L * 1024 * 1024)))
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Client.ImageBudgetExceeded>(r.error)
    }

    @Test
    fun acquire_and_release_token_controls_concurrency() {
        val guard = ImageBudgetGuard(ImageBudget.DEFAULT)
        val t1 = guard.acquire()
        assertIs<DshResult.Ok<*>>(t1)
        val t2 = guard.acquire()
        assertIs<DshResult.Err>(t2)
        assertIs<DshError.Client.ConcurrentImageUpload>(t2.error)
        t1.getOrNull()!!.close()
        val t3 = guard.acquire()
        assertIs<DshResult.Ok<*>>(t3)
        t3.getOrNull()!!.close()
    }

    @Test
    fun low_memory_budget_rejects_smaller_payloads() {
        val guard = ImageBudgetGuard(ImageBudget.LOW_MEMORY)
        val r = guard.validate(payload(sizeBytes = 3L * 1024 * 1024))
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Business.AttachmentTooLarge>(r.error)
    }

    @Test
    fun empty_batch_passes() {
        val guard = ImageBudgetGuard(ImageBudget.DEFAULT)
        val r = guard.validateBatch(emptyList())
        assertIs<DshResult.Ok<Unit>>(r)
    }

    @Test
    fun auto_close_releases_on_token_scope_exit() {
        val guard = ImageBudgetGuard(ImageBudget.DEFAULT)
        guard.acquire().getOrNull()!!.use { /* scope */ }
        val t = guard.acquire()
        assertIs<DshResult.Ok<*>>(t)
    }
}
