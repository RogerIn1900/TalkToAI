package com.example.talktoai.dsh.session

import com.example.talktoai.dsh.transport.FollowStream
import com.example.talktoai.dsh.transport.FollowStreamEvent

/**
 * 测试 fixture：按列表依次喂入 [FollowStreamEvent] 的可编程流。
 * 用于 follow/状态机/重建测试，避免依赖真实 DSH Remote。
 */
class ScriptedStream(
    private val frames: List<FollowStreamEvent>,
    private val terminal: FollowStreamEvent = FollowStreamEvent.Completed,
) : FollowStream {
    override suspend fun collect(handler: suspend (FollowStreamEvent) -> Boolean) {
        for (f in frames) {
            if (!handler(f)) return
        }
        handler(terminal)
    }
    override suspend fun cancel() {}
}
