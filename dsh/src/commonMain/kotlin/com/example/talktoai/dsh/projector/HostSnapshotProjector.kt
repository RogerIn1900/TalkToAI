package com.example.talktoai.dsh.projector

import com.example.talktoai.dsh.contract.HostSnapshot
import com.example.talktoai.dsh.contract.JobItem
import com.example.talktoai.dsh.contract.ProjectionItem
import com.example.talktoai.dsh.contract.QueueItem
import com.example.talktoai.dsh.contract.QueueKind
import com.example.talktoai.dsh.contract.QueueStatus
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.contract.SessionSummary
import com.example.talktoai.dsh.contract.ToolStatus
import com.example.talktoai.dsh.contract.WorkspaceSummary
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.transport.ControlFrame
import com.example.talktoai.dsh.transport.JobItemDto
import com.example.talktoai.dsh.transport.ProjectionItemDto
import com.example.talktoai.dsh.transport.QueueItemDto
import com.example.talktoai.dsh.transport.SessionSummaryDto
import com.example.talktoai.dsh.transport.WorkspaceSummaryDto

/**
 * Host 派生快照投影器（设计 §3 多通道状态同步 / §4 Session Control）。
 *
 * 规则（设计 §3）：
 *   - 每代完整 baseline；后续 replacement 或高版本覆盖
 *   - 旧的 generation 延迟帧 → 忽略（避免误覆盖新 baseline）
 *
 * generation 模型（设计 §4 + §3）：
 *   - 同一域（Queue / Jobs / Projection / SessionList / WorkspaceList）的 snapshot
 *     以 generation 为版本号；baseline 总是整代替换
 *   - 新帧的 generation < lastSeen → 丢弃（延迟帧）
 *   - 新帧的 generation > lastSeen + 1 → 检测为 generation gap，记录但不丢弃
 *     （Host 可能跨代发送，例如重连后新 generation 跳过中间值）
 *
 * 行为：
 *   - [accept] 一帧 ControlFrame；如果 generation < lastSeenGeneration 则直接忽略
 *   - 否则替换 baseline；记录 generation 跳变到 logger（用于诊断）
 */
class HostSnapshotProjector(
    private val logger: DshLogger = NoopLogger,
) {

    @Volatile
    var lastSeenGeneration: Long = -1L
        private set

    /** 自上次 reset 起累计的 generation gap 次数（设计 §9 可观测性）。 */
    @Volatile
    var generationGapCount: Long = 0L
        private set

    /** 最近一次 generation gap 的详情；null 表示未发生过。 */
    @Volatile
    var lastGap: GenerationGap? = null
        private set

    data class GenerationGap(
        val domain: String,
        val receivedGeneration: Long,
        val lastSeenGeneration: Long,
        val skipped: Int,
        val observedAtMs: Long,
    )

    private val queue: MutableMap<Long, HostSnapshot.Queue> = LinkedHashMap()
    private val jobs: MutableMap<Long, HostSnapshot.Jobs> = LinkedHashMap()
    private val projection: MutableMap<Long, HostSnapshot.Projection> = LinkedHashMap()
    private val sessionList: MutableMap<Long, HostSnapshot.SessionList> = LinkedHashMap()
    private val workspaceList: MutableMap<Long, HostSnapshot.WorkspaceList> = LinkedHashMap()

    val queueSnapshot: HostSnapshot.Queue?
        get() = queue[lastSeenGeneration] ?: queue.values.lastOrNull()
    val jobsSnapshot: HostSnapshot.Jobs?
        get() = jobs[lastSeenGeneration] ?: jobs.values.lastOrNull()
    val projectionSnapshot: HostSnapshot.Projection?
        get() = projection[lastSeenGeneration] ?: projection.values.lastOrNull()
    val sessionListSnapshot: HostSnapshot.SessionList?
        get() = sessionList[lastSeenGeneration] ?: sessionList.values.lastOrNull()
    val workspaceListSnapshot: HostSnapshot.WorkspaceList?
        get() = workspaceList[lastSeenGeneration] ?: workspaceList.values.lastOrNull()

    fun accept(frame: ControlFrame): Boolean {
        return when (frame) {
            is ControlFrame.Queue -> applyQueue(frame)
            is ControlFrame.Jobs -> applyJobs(frame)
            is ControlFrame.Projection -> applyProjection(frame)
            is ControlFrame.SessionList -> applySessionList(frame)
            is ControlFrame.WorkspaceList -> applyWorkspaceList(frame)
        }
    }

    /**
     * 共用的 baseline 替换逻辑。
     * - generation < currentLastSeen → 丢弃（延迟帧）
     * - generation == currentLastSeen → 替换 baseline（正常更新）
     * - generation == currentLastSeen + 1 → 替换 baseline
     * - generation > currentLastSeen + 1 → generation gap；记录并替换
     */
    private fun applyBaseline(
        domain: String,
        newGeneration: Long,
        replace: (Long) -> Unit,
    ): Boolean {
        val current = if (lastSeenGeneration == -1L) newGeneration else lastSeenGeneration
        if (newGeneration < current) {
            logger.debug(TAG, com.example.talktoai.dsh.observability.TraceContext.EMPTY,
                "Drop stale $domain frame generation=$newGeneration < lastSeen=$current")
            return false
        }
        if (lastSeenGeneration != -1L && newGeneration > lastSeenGeneration + 1) {
            val skipped = (newGeneration - lastSeenGeneration - 1).toInt()
            generationGapCount++
            lastGap = GenerationGap(
                domain = domain,
                receivedGeneration = newGeneration,
                lastSeenGeneration = lastSeenGeneration,
                skipped = skipped,
                observedAtMs = System.currentTimeMillis(),
            )
            logger.warn(TAG, com.example.talktoai.dsh.observability.TraceContext.EMPTY,
                "Generation gap in $domain: received=$newGeneration lastSeen=$lastSeenGeneration skipped=$skipped")
        }
        lastSeenGeneration = newGeneration
        replace(newGeneration)
        return true
    }

    private fun applyQueue(f: ControlFrame.Queue): Boolean =
        applyBaseline("queue", f.generation) { gen ->
            queue[gen] = HostSnapshot.Queue(generation = gen, pending = f.items.map(::toQueueItem))
        }

    private fun applyJobs(f: ControlFrame.Jobs): Boolean =
        applyBaseline("jobs", f.generation) { gen ->
            jobs[gen] = HostSnapshot.Jobs(generation = gen, running = f.items.map(::toJobItem))
        }

    private fun applyProjection(f: ControlFrame.Projection): Boolean =
        applyBaseline("projection", f.generation) { gen ->
            projection[gen] = HostSnapshot.Projection(generation = gen, items = f.items.map(::toProjectionItem))
        }

    private fun applySessionList(f: ControlFrame.SessionList): Boolean =
        applyBaseline("session-list", f.generation) { gen ->
            sessionList[gen] = HostSnapshot.SessionList(generation = gen, sessions = f.sessions.map(::toSessionSummary))
        }

    private fun applyWorkspaceList(f: ControlFrame.WorkspaceList): Boolean =
        applyBaseline("workspace-list", f.generation) { gen ->
            workspaceList[gen] = HostSnapshot.WorkspaceList(generation = gen, workspaces = f.workspaces.map(::toWorkspaceSummary))
        }

    fun reset() {
        lastSeenGeneration = -1L
        generationGapCount = 0L
        lastGap = null
        queue.clear(); jobs.clear(); projection.clear(); sessionList.clear(); workspaceList.clear()
    }

    private fun toQueueItem(it: QueueItemDto) = QueueItem(
        requestId = it.requestId,
        kind = runCatching { QueueKind.valueOf(it.kind.uppercase()) }.getOrDefault(QueueKind.PROMPT),
        status = runCatching { QueueStatus.valueOf(it.status.uppercase()) }.getOrDefault(QueueStatus.PENDING),
        sessionId = SessionId(it.sessionId),
        summary = it.summary,
    )

    private fun toJobItem(it: JobItemDto) = JobItem(
        jobId = it.jobId,
        kind = it.kind,
        status = runCatching { ToolStatus.valueOf(it.status.uppercase()) }.getOrDefault(ToolStatus.PENDING),
    )

    private fun toProjectionItem(it: ProjectionItemDto) = ProjectionItem(
        sessionId = SessionId(it.sessionId),
        title = it.title,
        archived = it.archived,
        updatedAtMs = it.updatedAtMs,
    )

    private fun toSessionSummary(it: SessionSummaryDto) = SessionSummary(
        sessionId = SessionId(it.sessionId),
        title = it.title,
        updatedAtMs = it.updatedAtMs,
        archived = it.archived,
    )

    private fun toWorkspaceSummary(it: WorkspaceSummaryDto) = WorkspaceSummary(
        workspaceId = it.workspaceId,
        name = it.name,
        sessionCount = it.sessionCount,
    )

    companion object {
        private const val TAG = "HostSnapshotProjector"
    }
}
