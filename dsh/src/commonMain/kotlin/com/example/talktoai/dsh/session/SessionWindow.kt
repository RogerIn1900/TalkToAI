package com.example.talktoai.dsh.session

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.SeqRange
import com.example.talktoai.dsh.contract.SessionCursor
import com.example.talktoai.dsh.contract.SessionEvent
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.FollowFrame
import com.example.talktoai.dsh.transport.FollowStream
import com.example.talktoai.dsh.transport.FollowStreamEvent
import com.example.talktoai.dsh.transport.PageChunk
import com.example.talktoai.dsh.transport.SessionHeader

/**
 * Session Window 状态机（设计 §4 / §4.1）。
 *
 * 状态转换：
 *   IDLE  --handshake-->  OPENING  --opening frame ok--> LIVE
 *   LIVE  --event seq == liveTailSeq+1--> LIVE
 *   LIVE  --event seq <  liveTailSeq+1 (重复旧事件)--> LIVE  (丢弃)
 *   LIVE  --event seq >  liveTailSeq+1 (缺号)--> FAILED (Gap)
 *   LIVE  --page() 加载更早历史--> LIVE (合并 loadedRanges)
 *   LIVE  --stream closed--> IDLE (调用方决定是否重建)
 *   FAILED --rebuild--> OPENING
 */
class SessionWindowStateMachine(
    private val sessionId: SessionId,
    private val logger: DshLogger = NoopLogger,
) {

    enum class Phase { IDLE, OPENING, LIVE, FAILED }

    @Volatile
    var phase: Phase = Phase.IDLE
        private set

    var cursor: SessionCursor = SessionCursor.EMPTY
        private set

    var header: SessionHeader? = null
        private set

    private val _events: MutableList<SessionEvent> = mutableListOf()

    val events: List<SessionEvent> get() = _events.toList()

    fun applyOpening(
        generation: Long,
        header: SessionHeader,
        openingCursor: Long,
        records: List<SessionEvent>,
        hasMore: Boolean,
        ctx: TraceContext,
    ): DshResult<Unit> {
        phase = Phase.OPENING
        this.header = header

        val sortedRecords = records.sortedBy { it.seq }
        val baseRange: List<SeqRange> = if (sortedRecords.isEmpty()) {
            emptyList()
        } else {
            val first = sortedRecords.first().seq
            val last = sortedRecords.last().seq
            if (last - first + 1 != sortedRecords.size.toLong()) {
                return DshResult.failure(DshError.Protocol.NonMonotonic)
            }
            listOf(SeqRange(first, last))
        }

        cursor = SessionCursor(
            openingCursor = openingCursor,
            liveTailSeq = sortedRecords.lastOrNull()?.seq ?: (openingCursor - 1),
            loadedRanges = baseRange,
            hasMore = hasMore,
            oldestLoadedSeq = sortedRecords.firstOrNull()?.seq,
        )
        _events.clear()
        _events.addAll(sortedRecords)

        phase = Phase.LIVE
        logger.info(
            TAG, ctx.withSession(sessionId.value),
            "Opening snapshot applied: cursor=$cursor, records=${records.size}, hasMore=$hasMore"
        )
        return DshResult.success(Unit)
    }

    fun applyEvent(event: SessionEvent, generation: Long, ctx: TraceContext): DshResult<Unit> {
        if (phase != Phase.LIVE) {
            return DshResult.failure(DshError.Protocol.NonMonotonic)
        }
        val expected = cursor.liveTailSeq + 1
        return when {
            event.seq < expected -> {
                logger.debug(TAG, ctx.withSeq(event.seq), "Drop duplicate event seq=${event.seq} expected=$expected")
                DshResult.failure(DshError.Protocol.DroppedDuplicate(event.seq, expected))
            }
            event.seq == expected -> {
                _events.add(event)
                cursor = cursor.copy(
                    liveTailSeq = event.seq,
                    loadedRanges = mergeAppend(cursor.loadedRanges, event.seq),
                )
                DshResult.success(Unit)
            }
            else -> {
                logger.error(TAG, ctx.withSeq(event.seq), "Gap detected expected=$expected got=${event.seq}")
                phase = Phase.FAILED
                DshResult.failure(DshError.Protocol.Gap(expected, event.seq))
            }
        }
    }

    fun applyPage(chunk: PageChunk, ctx: TraceContext): DshResult<Unit> {
        if (chunk.records.isEmpty()) {
            cursor = cursor.copy(
                hasMore = chunk.hasMore,
                oldestLoadedSeq = chunk.oldestLoadedSeq ?: cursor.oldestLoadedSeq,
            )
            return DshResult.success(Unit)
        }
        val sorted = chunk.records.sortedBy { it.seq }
        val first = sorted.first().seq
        val last = sorted.last().seq
        if (last - first + 1 != sorted.size.toLong()) {
            return DshResult.failure(DshError.Protocol.NonMonotonic)
        }
        val newRange = SeqRange(first, last)
        val merged = mergePrepend(cursor.loadedRanges, newRange)
        cursor = cursor.copy(
            loadedRanges = merged,
            hasMore = chunk.hasMore,
            oldestLoadedSeq = sorted.first().seq,
        )
        _events.addAll(0, sorted)
        logger.info(TAG, ctx, "Paged history: range=[$first..$last], hasMore=${chunk.hasMore}")
        return DshResult.success(Unit)
    }

    fun onStreamClosed(reason: String, ctx: TraceContext) {
        logger.warn(TAG, ctx, "Stream closed: $reason")
        phase = Phase.IDLE
    }

    fun beginRebuild(): DshResult<Unit> {
        if (phase != Phase.FAILED && phase != Phase.IDLE) {
            return DshResult.failure(
                DshError.Client.InvalidArgument("Rebuild only allowed from FAILED/IDLE, current=$phase")
            )
        }
        phase = Phase.OPENING
        return DshResult.success(Unit)
    }

    fun reset() {
        phase = Phase.IDLE
        cursor = SessionCursor.EMPTY
        header = null
        _events.clear()
    }

    fun isLive(): Boolean = phase == Phase.LIVE

    private fun mergeAppend(ranges: List<SeqRange>, seq: Long): List<SeqRange> {
        if (ranges.isEmpty()) return listOf(SeqRange(seq, seq))
        val last = ranges.last()
        return if (last.endInclusive + 1 == seq) {
            ranges.dropLast(1) + SeqRange(last.start, seq)
        } else {
            ranges + SeqRange(seq, seq)
        }
    }

    private fun mergePrepend(ranges: List<SeqRange>, newRange: SeqRange): List<SeqRange> {
        if (ranges.isEmpty()) return listOf(newRange)
        val first = ranges.first()
        return if (newRange.endInclusive + 1 == first.start) {
            listOf(SeqRange(newRange.start, first.endInclusive)) + ranges.drop(1)
        } else if (newRange.start > first.start) {
            ranges
        } else {
            listOf(newRange) + ranges
        }
    }

    companion object {
        private const val TAG = "SessionWindow"
    }
}

class SessionFollowClient(
    private val sessionId: SessionId,
    private val state: SessionWindowStateMachine,
    private val logger: DshLogger = NoopLogger,
) {

    suspend fun run(
        ctx: TraceContext,
        openStream: suspend (TraceContext) -> FollowStream,
        onUpdate: (SessionEvent) -> Unit = {},
        maxRebuilds: Int = 5,
    ) {
        var rebuilds = 0
        while (rebuilds <= maxRebuilds) {
            state.beginRebuild()
            val stream = openStream(ctx)
            var streamOk = true
            try {
                stream.collect { event ->
                    when (event) {
                        is FollowStreamEvent.Frame -> {
                            val r = applyFrame(event.frame, ctx, onUpdate)
                            if (r is DshResult.Err) {
                                streamOk = false
                                return@collect false
                            }
                        }
                        is FollowStreamEvent.Error -> {
                            logger.error(TAG, ctx, "Stream error: ${event.error.message}", null)
                            streamOk = false
                            return@collect false
                        }
                        FollowStreamEvent.Completed -> {
                            return@collect false
                        }
                    }
                    true
                }
            } finally {
                runCatching { stream.cancel() }
            }
            if (streamOk) return
            rebuilds++
            logger.warn(TAG, ctx, "Rebuilding stream (attempt=$rebuilds)")
            state.reset()
        }
        logger.error(TAG, ctx, "Exceeded max rebuilds ($maxRebuilds)")
    }

    private fun applyFrame(
        frame: FollowFrame,
        ctx: TraceContext,
        onUpdate: (SessionEvent) -> Unit,
    ): DshResult<Unit> {
        return when (frame) {
            is FollowFrame.OpeningSnapshot -> {
                val r = state.applyOpening(
                    generation = frame.generation,
                    header = frame.header,
                    openingCursor = frame.cursor,
                    records = frame.records,
                    hasMore = frame.hasMore,
                    ctx = ctx,
                )
                if (r is DshResult.Ok) {
                    frame.records.forEach(onUpdate)
                }
                r
            }
            is FollowFrame.Event -> {
                val r = state.applyEvent(frame.event, frame.generation, ctx)
                when (r) {
                    is DshResult.Ok -> {
                        onUpdate(frame.event)
                        DshResult.success(Unit)
                    }
                    is DshResult.Err -> {
                        // DroppedDuplicate 不算错误；Gap/NonMonotonic/UnknownInteraction 才终止流
                        when (val err = r.error) {
                            is com.example.talktoai.dsh.contract.DshError.Protocol.DroppedDuplicate -> {
                                logger.debug(TAG, ctx.withSeq(frame.event.seq), "Skip onUpdate for duplicate seq=${err.seq}")
                                DshResult.success(Unit)
                            }
                            else -> r
                        }
                    }
                }
            }
            is FollowFrame.Closed -> {
                state.onStreamClosed(frame.reason, ctx)
                DshResult.success(Unit)
            }
        }
    }

    companion object {
        private const val TAG = "FollowClient"
    }
}
