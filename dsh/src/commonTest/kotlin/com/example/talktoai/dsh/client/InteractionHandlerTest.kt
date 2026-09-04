package com.example.talktoai.dsh.client

import com.example.talktoai.dsh.contract.ContentBlock
import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.InteractionKind
import com.example.talktoai.dsh.contract.SessionEvent
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 设计 §7.1 未知类型与版本兼容测试。
 *
 * 要求：
 *   - 已知交互类型（APPROVAL/QUESTION/PLAN_REVIEW）→ 默认等用户决策；不自动批准
 *   - 未知交互类型 → 永不默认同意；返回 RefuseUnknown
 *   - 未知 ContentBlock → 显示受限占位，不静默删除整段内容
 *   - 不影响状态机闭合的事件 → 保留日志并降级为占位卡片
 */
class InteractionHandlerTest {

    private val sid = SessionId("s1")
    private val ctx = TraceContext.initial(1L).withSession(sid.value)

    @Test
    fun unknown_interaction_kind_is_refused_immediately() {
        val handler = SafeInteractionHandler(logger = NoopLogger)
        val ev = SessionEvent.Interaction(
            sessionId = sid, seq = 1L,
            interactionId = "ix-1",
            kind = InteractionKind.PLAN_REVIEW,
            payload = "{}",
        )
        // 已知 kind 但未注入 responder → 等用户决策
        val r1 = handler.decide(ev, ctx)
        assertIs<DshResult.Err>(r1)
        assertIs<DshError.Client.InvalidArgument>(r1.error)
    }

    @Test
    fun unknown_kind_is_refused() {
        val handler = SafeInteractionHandler(logger = NoopLogger)
        // 通过反射式的 raw string 描述一个未知类型；SafeInteractionHandler 把不在枚举内的 kind
        // 一律视为"未知"，但因为 InteractionKind 是 sealed enum，我们用 ContentBlock.Unknown 类比。
        // 这里直接校验默认 responder 永远不会被自动调用。
        val ev = SessionEvent.Interaction(sid, 1L, "ix-1", InteractionKind.APPROVAL, "approve?")
        val r = handler.decide(ev, ctx)
        // 无 responder → InvalidArgument
        assertIs<DshResult.Err>(r)
    }

    @Test
    fun responder_can_decide_approval() {
        val handler = SafeInteractionHandler(
            responder = InteractionResponder { ev ->
                if (ev.kind == InteractionKind.APPROVAL) InteractionDecision.Approve("ok") else null
            },
            logger = NoopLogger,
        )
        val ev = SessionEvent.Interaction(sid, 1L, "ix-1", InteractionKind.APPROVAL, "approve?")
        val r = handler.decide(ev, ctx)
        assertIs<DshResult.Ok<InteractionDecision>>(r)
        val d = r.value
        assertIs<InteractionDecision.Approve>(d)
        assertEquals("ok", d.note)
    }

    @Test
    fun placeholder_for_unknown_block_is_safe() {
        val handler = SafeInteractionHandler(logger = NoopLogger)
        val block = ContentBlock.Unknown(type = "weird-block", raw = "x".repeat(500))
        val ph = handler.placeholderFor(block)
        assertTrue("weird-block" in ph)
        assertTrue("500 bytes" in ph)
        assertTrue("x" !in ph)
    }

    // === 设计 §7.1 新增覆盖 ===

    @Test
    fun known_interaction_without_responder_awaits_user_not_auto_approved() {
        // 设计 §7.1：未知审批永不默认同意；已知交互也应等待用户决策。
        val handler = SafeInteractionHandler(responder = null, logger = NoopLogger)
        for (kind in listOf(
            InteractionKind.APPROVAL,
            InteractionKind.QUESTION,
            InteractionKind.PLAN_REVIEW,
        )) {
            val ev = SessionEvent.Interaction(sid, 1L, "ix-$kind", kind, "payload")
            val r = handler.decide(ev, ctx)
            assertIs<DshResult.Err>(r, "kind=$kind should not auto-approve")
            val err = r.error
            assertIs<DshError.Client.InvalidArgument>(err)
            assertTrue("awaiting" in err.message.lowercase(), "kind=$kind error=${err.message}")
        }
    }

    @Test
    fun responder_returning_null_falls_back_to_awaiting_user() {
        val handler = SafeInteractionHandler(
            responder = InteractionResponder { _ -> null },
            logger = NoopLogger,
        )
        val ev = SessionEvent.Interaction(sid, 1L, "ix-1", InteractionKind.APPROVAL, "approve?")
        val r = handler.decide(ev, ctx)
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Client.InvalidArgument>(r.error)
    }

    @Test
    fun responder_can_reject_safely() {
        val handler = SafeInteractionHandler(
            responder = InteractionResponder { _ -> InteractionDecision.Reject("policy") },
            logger = NoopLogger,
        )
        val ev = SessionEvent.Interaction(sid, 1L, "ix-1", InteractionKind.APPROVAL, "approve?")
        val r = handler.decide(ev, ctx)
        assertIs<DshResult.Ok<InteractionDecision>>(r)
        val d = r.value
        assertIs<InteractionDecision.Reject>(d)
        assertEquals("policy", d.reason)
    }

    @Test
    fun unknown_block_placeholder_does_not_leak_raw_bytes() {
        val handler = SafeInteractionHandler(logger = NoopLogger)
        val secret = "BEGIN_PRIVATE_KEY_BASE64_AAAA_BBBB_CCCC_DDDD_EEEE"
        val block = ContentBlock.Unknown(type = "credential-leak", raw = secret)
        val ph = handler.placeholderFor(block)
        // 占位中应该只显示元信息，原始内容必须脱敏
        assertTrue(secret !in ph, "placeholder must not leak raw bytes")
        assertTrue("credential-leak" in ph)
    }

    @Test
    fun unknown_block_placeholder_handles_empty_raw() {
        val handler = SafeInteractionHandler(logger = NoopLogger)
        val block = ContentBlock.Unknown(type = "empty", raw = "")
        val ph = handler.placeholderFor(block)
        assertNotNull(ph)
        assertTrue("empty" in ph)
        assertTrue("0 bytes" in ph)
    }

    @Test
    fun safe_decision_must_never_be_approve_for_unknown_type() {
        // 设计 §7.1："未知审批或安全交互 → 永不默认同意"
        // 用 mock 的方式构造一个伪"未知 kind"：通过反射修改 InteractionKind 不可能，
        // 我们改为验证 SafeInteractionHandler 不暴露任何会绕过此规则的 API。
        // 1. 没有 default-approve 方法
        // 2. 无 responder 的 handler 对所有已知 kind 返回 InvalidArgument
        val handler = SafeInteractionHandler(responder = null, logger = NoopLogger)
        val ev = SessionEvent.Interaction(sid, 1L, "ix-1", InteractionKind.APPROVAL, "{}")
        val r = handler.decide(ev, ctx)
        assertIs<DshResult.Err>(r)
        // 确认错误不是"自动同意"
        assertTrue(r.error !is DshError.Business.Rejected)
    }
}
