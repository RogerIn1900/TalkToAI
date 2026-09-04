package com.example.talktoai.dsh.client

import com.example.talktoai.dsh.contract.ContentBlock
import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.InteractionKind
import com.example.talktoai.dsh.contract.SessionEvent
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger

/**
 * 交互响应（用户对审批/问题/PlanReview 的回执）。
 *
 * 设计 §7.1：未知审批或安全交互 → 永不默认同意。
 */
sealed class InteractionDecision {
    data class Approve(val note: String? = null) : InteractionDecision()
    data class Reject(val reason: String) : InteractionDecision()
    /** 未知交互类型：拒绝，等待用户手动处理。 */
    data object RefuseUnknown : InteractionDecision()
}

/**
 * 处理 [SessionEvent.Interaction] 的回调。
 * 返回 null 表示"未知/不安全 → 拒绝"。
 */
fun interface InteractionResponder {
    fun decide(event: SessionEvent.Interaction): InteractionDecision?
}

/**
 * 默认策略（设计 §7.1）：
 *   - 已知交互：返回 null（等待 UI 注入 responder，由真实用户决策）
 *   - 未知 type：直接拒绝
 *
 * 注意：默认实现 **不会自动批准任何交互**，符合"未知审批永不默认同意"。
 */
class SafeInteractionHandler(
    private val responder: InteractionResponder? = null,
    private val logger: DshLogger = NoopLogger,
) {
    fun decide(event: SessionEvent.Interaction, ctx: com.example.talktoai.dsh.observability.TraceContext): DshResult<InteractionDecision> {
        val known = when (event.kind) {
            InteractionKind.APPROVAL,
            InteractionKind.QUESTION,
            InteractionKind.PLAN_REVIEW -> true
            else -> false
        }
        if (!known) {
            logger.warn(
                TAG, ctx,
                "Refused unknown interaction: kind=${event.kind} id=${event.interactionId}"
            )
            return DshResult.success(InteractionDecision.RefuseUnknown)
        }
        val d = responder?.decide(event)
            ?: run {
                logger.info(TAG, ctx, "Awaiting user decision for ${event.kind} id=${event.interactionId}")
                return DshResult.failure(DshError.Client.InvalidArgument("awaiting user decision"))
            }
        return DshResult.success(d)
    }

    /**
     * 校验渲染降级：未知 ContentBlock → 显示受限占位，不静默删除（§7.1）。
     */
    fun placeholderFor(block: ContentBlock.Unknown): String =
        "[unrendered block type=${block.type} ${block.raw.length} bytes]"

    companion object {
        private const val TAG = "InteractionHandler"
    }
}
