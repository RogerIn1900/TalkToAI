package com.example.talktoai.dsh.client

import com.example.talktoai.dsh.connection.CredentialStore
import com.example.talktoai.dsh.connection.HostEndpoint
import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.ImageAttachmentPayload
import com.example.talktoai.dsh.contract.ImageBudget
import com.example.talktoai.dsh.contract.PromptRequest
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.media.ImageBudgetGuard
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.prompt.PromptIdGenerator
import com.example.talktoai.dsh.prompt.PromptSubmitter
import com.example.talktoai.dsh.prompt.PromptTracker
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.transport.HandshakeInfo

/**
 * 高层 DshClient（设计 §2.1）。
 * 装配：Remote + Budget + Prompt 追踪 + 凭据存储 + Logger。
 */
class DshClient(
    private val remote: DshRemote,
    private val credentialStore: CredentialStore,
    private val deviceId: String,
    private val expectedHostVersion: String,
    private val imageBudget: ImageBudget = ImageBudget.DEFAULT,
    private val logger: DshLogger = NoopLogger,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    private val budgetGuard = ImageBudgetGuard(imageBudget)
    private val promptTracker = PromptTracker(logger, clock)
    private val promptGenerator = PromptIdGenerator(deviceId, clock = clock)
    val submitter = PromptSubmitter(remote, promptTracker, clock = clock)

    @Volatile
    private var connectionGeneration: Long = 0L

    @Volatile
    var endpoint: HostEndpoint? = null
        private set

    suspend fun connect(endpoint: HostEndpoint): DshResult<HandshakeInfo> {
        this.endpoint = endpoint
        val cred = endpoint.credential ?: credentialStore.read()
        if (cred == null && endpoint.topology != com.example.talktoai.dsh.connection.ConnectionTopology.LOCAL_PROCESS) {
            return DshResult.failure(DshError.Transport.AuthRequired("missing credential for ${endpoint.topology}"))
        }
        val hs = remote.handshake()
        if (hs is DshResult.Ok) {
            if (hs.value.expectedVersion != expectedHostVersion) {
                return DshResult.failure(DshError.IncompatibleHost(hs.value.remoteVersion, expectedHostVersion))
            }
            connectionGeneration = hs.value.connectionGeneration
            logger.info(TAG, TraceContext.initial(connectionGeneration), "Connected generation=$connectionGeneration")
        }
        return hs
    }

    fun trace(sessionId: SessionId? = null): TraceContext =
        TraceContext.initial(connectionGeneration).let {
            if (sessionId != null) it.withSession(sessionId.value) else it
        }

    fun nextRequestId(sessionId: SessionId): String = promptGenerator.next(sessionId).value

    suspend fun sendPrompt(
        sessionId: SessionId,
        text: String,
        attachments: List<ImageAttachmentPayload>,
        steer: Boolean = false,
    ): DshResult<String> {
        val ctx = trace(sessionId)
        val r = budgetGuard.validateBatch(attachments)
        if (r is DshResult.Err) return r

        val token = budgetGuard.acquire()
        if (token is DshResult.Err) return DshResult.failure(token.error)
        token.getOrNull()!!.use {
            val requestId = nextRequestId(sessionId)
            val req = PromptRequest(
                sessionId = sessionId,
                requestId = requestId,
                text = text,
                attachments = attachments,
                steer = steer,
            )
            val ack = submitter.submit(req, ctx)
            return ack.map { requestId }
        }
    }

    suspend fun reconcilePrompt(
        sessionId: SessionId,
        requestId: String,
    ): DshResult<Unit> {
        val ctx = trace(sessionId)
        // Lookup 不要求携带 text/attachments；用占位附件绕开 PromptRequest 的入参校验
        val req = PromptRequest(
            sessionId = sessionId,
            requestId = requestId,
            text = "_lookup_",
            attachments = emptyList(),
        )
        val r = submitter.reconcile(req, ctx)
        return r.map { }
    }

    fun trackerSnapshot() = promptTracker.snapshot()

    suspend fun close() {
        remote.close()
    }

    companion object {
        private const val TAG = "DshClient"
    }
}
