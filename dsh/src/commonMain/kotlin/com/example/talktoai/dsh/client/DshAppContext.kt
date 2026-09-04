package com.example.talktoai.dsh.client

import com.example.talktoai.dsh.connection.HostEndpoint
import com.example.talktoai.dsh.connection.LocalProcessConnectionManager
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.SanitizedLogger
import com.example.talktoai.dsh.projector.HostSnapshotProjector
import com.example.talktoai.dsh.prompt.PromptSubmitter
import com.example.talktoai.dsh.prompt.PromptTracker
import com.example.talktoai.dsh.prompt.ReconciliationService
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.diagnostics.DiagnosticsExporter
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.HostSnapshot
import com.example.talktoai.dsh.transport.HandshakeInfo

/**
 * 全量装配（设计 §2.1）：把 Remote / ConnectionManager / Projector / Prompt / Diagnostics 拼成一个 Native 入口。
 *
 * Native 侧只持有一个 [DshAppContext]；UI 通过 RPC 拿它的方法。
 */
class DshAppContext(
    remote: DshRemote,
    private val credentialStore: com.example.talktoai.dsh.connection.CredentialStore,
    deviceId: String,
    expectedHostVersion: String,
    appVersion: String,
    rawLogger: DshLogger,
    clock: () -> Long = { System.currentTimeMillis() },
) {
    val logger: DshLogger = SanitizedLogger(rawLogger)
    val connection: LocalProcessConnectionManager = LocalProcessConnectionManager(
        remote = remote,
        credentialStore = credentialStore,
        deviceId = deviceId,
        expectedHostVersion = expectedHostVersion,
        logger = logger,
    )
    val tracker: PromptTracker = PromptTracker(logger, clock)
    val submitter: PromptSubmitter = PromptSubmitter(remote, tracker, clock = clock)
    val reconciler: ReconciliationService = ReconciliationService(remote, tracker, logger)
    val projector: HostSnapshotProjector = HostSnapshotProjector()
    val diagnostics: DiagnosticsExporter = DiagnosticsExporter(
        deviceId = deviceId,
        appVersion = appVersion,
        expectedHostVersion = expectedHostVersion,
    )
    val interaction: SafeInteractionHandler = SafeInteractionHandler(responder = null, logger = logger)
    private val promptGenerator = com.example.talktoai.dsh.prompt.PromptIdGenerator(deviceId, clock = clock)

    @Volatile
    private var connectionGeneration: Long = 0L

    val client: DshClient = DshClient(
        remote = remote,
        credentialStore = credentialStore,
        deviceId = deviceId,
        expectedHostVersion = expectedHostVersion,
        logger = logger,
        clock = clock,
    )

    suspend fun connect(endpoint: HostEndpoint): DshResult<HandshakeInfo> {
        val r = connection.connect(endpoint)
        if (r is DshResult.Ok) {
            connectionGeneration = r.value.connectionGeneration
            client.connect(endpoint)
        }
        return r
    }

    fun currentConnectionGeneration(): Long = connectionGeneration

    fun projectionSnapshot(): HostSnapshot.Projection? = projector.projectionSnapshot
    fun queueSnapshot(): HostSnapshot.Queue? = projector.queueSnapshot
    fun sessionListSnapshot(): HostSnapshot.SessionList? = projector.sessionListSnapshot
    fun workspaceListSnapshot(): HostSnapshot.WorkspaceList? = projector.workspaceListSnapshot

    fun nextRequestId(sessionId: com.example.talktoai.dsh.contract.SessionId): String =
        promptGenerator.next(sessionId).value
}
