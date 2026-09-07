package com.example.talktoai.talk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TalkUiPolicyTest {
    @Test
    fun displayContentUsesExplicitStateFallbacks() {
        assertEquals("▋", TalkUiPolicy.displayContent("", "streaming"))
        assertEquals("生成失败，可点击重新生成", TalkUiPolicy.displayContent("", "failed"))
        assertEquals("生成已停止", TalkUiPolicy.displayContent("", "stopped"))
        assertEquals("已完成", TalkUiPolicy.displayContent("已完成", "done"))
    }

    @Test
    fun bubbleAndAvatarStylesCycleDeterministically() {
        assertEquals(TalkUiPolicy.BUBBLE_OUTLINE, TalkUiPolicy.nextBubbleStyle(TalkUiPolicy.BUBBLE_SOFT))
        assertEquals(TalkUiPolicy.BUBBLE_COMPACT, TalkUiPolicy.nextBubbleStyle(TalkUiPolicy.BUBBLE_OUTLINE))
        assertEquals(TalkUiPolicy.BUBBLE_SOFT, TalkUiPolicy.nextBubbleStyle(TalkUiPolicy.BUBBLE_COMPACT))
        assertEquals(TalkUiPolicy.AVATAR_ROUND, TalkUiPolicy.nextAvatarStyle(TalkUiPolicy.AVATAR_TEXT))
        assertEquals(TalkUiPolicy.AVATAR_MINIMAL, TalkUiPolicy.nextAvatarStyle(TalkUiPolicy.AVATAR_ROUND))
        assertEquals(TalkUiPolicy.AVATAR_TEXT, TalkUiPolicy.nextAvatarStyle(TalkUiPolicy.AVATAR_MINIMAL))
    }

    @Test
    fun regenerateOnlyAcceptsLatestAssistantMessage() {
        val user = message("user", "user")
        val assistant = message("assistant", "assistant")
        assertFalse(TalkUiPolicy.canRegenerate(listOf(user, assistant), user.id))
        assertTrue(TalkUiPolicy.canRegenerate(listOf(user, assistant), assistant.id))
        assertFalse(TalkUiPolicy.canRegenerate(listOf(assistant, user), assistant.id))
    }

    private fun message(id: String, role: String) = ChatMessageUi(
        id = id,
        role = role,
        content = "content",
        status = "done",
        attachmentNames = emptyList(),
        citations = emptyList(),
    )
}
