package com.example.talktoai.dsh.projector

import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.contract.ToolStatus
import com.example.talktoai.dsh.transport.ControlFrame
import com.example.talktoai.dsh.transport.JobItemDto
import com.example.talktoai.dsh.transport.ProjectionItemDto
import com.example.talktoai.dsh.transport.QueueItemDto
import com.example.talktoai.dsh.transport.SessionSummaryDto
import com.example.talktoai.dsh.transport.WorkspaceSummaryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class HostSnapshotProjectorTest {

    @Test
    fun queue_baseline_then_replacement() {
        val p = HostSnapshotProjector()
        val a = ControlFrame.Queue(1L, listOf(QueueItemDto("r1", "PROMPT", "PENDING", "s1")))
        assertEquals(true, p.accept(a))
        assertNotNull(p.queueSnapshot)
        assertEquals(1, p.queueSnapshot!!.pending.size)
        val b = ControlFrame.Queue(2L, listOf(
            QueueItemDto("r1", "PROMPT", "RUNNING", "s1"),
            QueueItemDto("r2", "STEER", "PENDING", "s1"),
        ))
        assertEquals(true, p.accept(b))
        assertEquals(2, p.queueSnapshot!!.pending.size)
        assertEquals(2L, p.lastSeenGeneration)
    }

    @Test
    fun older_generation_is_ignored() {
        val p = HostSnapshotProjector()
        p.accept(ControlFrame.Queue(5L, listOf(QueueItemDto("r1", "PROMPT", "PENDING", "s1"))))
        val old = ControlFrame.Queue(3L, listOf(QueueItemDto("r0", "PROMPT", "DONE", "s1")))
        assertEquals(false, p.accept(old))
        assertEquals(1, p.queueSnapshot!!.pending.size)
        assertEquals("r1", p.queueSnapshot!!.pending.first().requestId)
    }

    @Test
    fun jobs_projection_session_workspace_all_accepted() {
        val p = HostSnapshotProjector()
        assertEquals(true, p.accept(ControlFrame.Jobs(1L, listOf(JobItemDto("j1", "tool", "RUNNING")))))
        assertEquals(true, p.accept(ControlFrame.Projection(1L, listOf(
            ProjectionItemDto("s1", "title", false, 123L)
        ))))
        assertEquals(true, p.accept(ControlFrame.SessionList(1L, listOf(
            SessionSummaryDto("s1", "title", 123L, false)
        ))))
        assertEquals(true, p.accept(ControlFrame.WorkspaceList(1L, listOf(
            WorkspaceSummaryDto("w1", "ws", 1)
        ))))
        assertNotNull(p.jobsSnapshot)
        assertNotNull(p.projectionSnapshot)
        assertNotNull(p.sessionListSnapshot)
        assertNotNull(p.workspaceListSnapshot)
        assertEquals(ToolStatus.RUNNING, p.jobsSnapshot!!.running.first().status)
    }

    @Test
    fun reset_clears_state() {
        val p = HostSnapshotProjector()
        p.accept(ControlFrame.Queue(1L, listOf(QueueItemDto("r1", "PROMPT", "PENDING", "s1"))))
        p.reset()
        assertNull(p.queueSnapshot)
        assertEquals(-1L, p.lastSeenGeneration)
    }

    @Test
    fun unrelated_session_ids_are_kept_distinct_in_projection() {
        val p = HostSnapshotProjector()
        p.accept(ControlFrame.Projection(1L, listOf(
            ProjectionItemDto("s1", "t1", false, 1L),
            ProjectionItemDto("s2", "t2", true, 2L),
        )))
        val items = p.projectionSnapshot!!.items
        assertEquals(2, items.size)
        assertEquals(SessionId("s1"), items[0].sessionId)
        assertEquals(SessionId("s2"), items[1].sessionId)
    }

    @Test
    fun generation_gap_is_detected_and_recorded() {
        val p = HostSnapshotProjector()
        p.accept(ControlFrame.Queue(1L, listOf(QueueItemDto("r1", "PROMPT", "PENDING", "s1"))))
        // 跳过 generation 2、3，直接到 4
        assertEquals(true, p.accept(ControlFrame.Queue(4L, listOf(
            QueueItemDto("r9", "PROMPT", "RUNNING", "s1")
        ))))
        assertEquals(4L, p.lastSeenGeneration)
        assertEquals(1L, p.generationGapCount)
        assertNotNull(p.lastGap)
        assertEquals("queue", p.lastGap!!.domain)
        assertEquals(4L, p.lastGap!!.receivedGeneration)
        assertEquals(1L, p.lastGap!!.lastSeenGeneration)
        assertEquals(2, p.lastGap!!.skipped)
    }

    @Test
    fun same_generation_replacement_does_not_count_as_gap() {
        val p = HostSnapshotProjector()
        p.accept(ControlFrame.Projection(1L, listOf(
            ProjectionItemDto("s1", "old", false, 1L)
        )))
        p.accept(ControlFrame.Projection(1L, listOf(
            ProjectionItemDto("s1", "new", false, 2L)
        )))
        assertEquals(0L, p.generationGapCount)
        assertEquals("new", p.projectionSnapshot!!.items.first().title)
    }

    @Test
    fun reset_clears_generation_gap_counters() {
        val p = HostSnapshotProjector()
        p.accept(ControlFrame.Queue(1L, listOf(QueueItemDto("r1", "PROMPT", "PENDING", "s1"))))
        p.accept(ControlFrame.Queue(5L, listOf(QueueItemDto("r2", "PROMPT", "RUNNING", "s1"))))
        assertEquals(1L, p.generationGapCount)
        p.reset()
        assertEquals(0L, p.generationGapCount)
        assertNull(p.lastGap)
        assertEquals(-1L, p.lastSeenGeneration)
    }
}
