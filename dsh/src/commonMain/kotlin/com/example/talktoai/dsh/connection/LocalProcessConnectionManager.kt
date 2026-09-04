package com.example.talktoai.dsh.connection

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.transport.HandshakeInfo
import kotlinx.coroutines.delay
import kotlin.math.min
import kotlin.random.Random

/**
 * 同设备 / USB 连接的连接管理器（设计 §6 P0）。
 *
 * 职责：
 *   - 持久化 / 读取 [Credential]
 *   - 调 handshake 校验版本，失败时返回 [DshError.IncompatibleHost]
 *   - 控制重连退避：失败 → 短退避；多次失败 → 长退避（设计 §9 可靠性）
 *   - 提供撤销设备凭据的能力
 *   - 跟踪每次成功连接的 generation，重连时记录 generation 跳变到 logger（设计 §9 可观测性）
 */
class LocalProcessConnectionManager(
    private val remote: DshRemote,
    private val credentialStore: CredentialStore,
    private val deviceId: String,
    private val expectedHostVersion: String,
    private val logger: DshLogger = NoopLogger,
    private val random: Random = Random.Default,
    private val backoffInitialMs: Long = 200L,
    private val backoffMaxMs: Long = 8_000L,
    private val maxAttempts: Int = 5,
) {

    @Volatile
    var lastEndpoint: HostEndpoint? = null
        private set

    @Volatile
    var lastHandshake: HandshakeInfo? = null
        private set

    /** 上一次成功 handshake 的 generation；用于检测重连是否产生新代次。 */
    @Volatile
    var lastSeenGeneration: Long = 0L
        private set

    /** 自创建以来累计成功连接次数。 */
    @Volatile
    var successfulConnectCount: Int = 0
        private set

    suspend fun connect(endpoint: HostEndpoint): DshResult<HandshakeInfo> {
        lastEndpoint = endpoint
        val cred = endpoint.credential ?: credentialStore.read()
        if (cred == null && endpoint.topology != ConnectionTopology.LOCAL_PROCESS) {
            return DshResult.failure(DshError.Transport.AuthRequired("missing credential for ${endpoint.topology}"))
        }

        var attempt = 0
        var delayMs = backoffInitialMs
        while (attempt < maxAttempts) {
            attempt++
            val r = remote.handshake()
            when (r) {
                is DshResult.Err -> {
                    logger.warn(TAG, TraceContext.EMPTY,
                        "Handshake attempt=$attempt failed: ${r.error.message}")
                    if (attempt >= maxAttempts) return r
                    delay(delayMs + random.nextLong(0, delayMs / 2 + 1))
                    delayMs = min(delayMs * 2, backoffMaxMs)
                }
                is DshResult.Ok -> {
                    if (r.value.expectedVersion != expectedHostVersion) {
                        return DshResult.failure(
                            DshError.IncompatibleHost(r.value.remoteVersion, expectedHostVersion)
                        )
                    }
                    val previousGen = lastSeenGeneration
                    val newGen = r.value.connectionGeneration
                    lastHandshake = r.value
                    lastSeenGeneration = newGen
                    successfulConnectCount++
                    if (cred != null) credentialStore.write(cred)
                    if (previousGen != 0L && newGen != previousGen + 1) {
                        logger.warn(
                            TAG,
                            TraceContext.initial(newGen),
                            "Reconnect generation jump: previous=$previousGen new=$newGen delta=${newGen - previousGen}"
                        )
                    } else if (previousGen == 0L) {
                        logger.info(
                            TAG,
                            TraceContext.initial(newGen),
                            "First handshake: remote=${r.value.remoteVersion} gen=$newGen"
                        )
                    } else {
                        logger.debug(
                            TAG,
                            TraceContext.initial(newGen),
                            "Connected: remote=${r.value.remoteVersion} gen=$newGen (consecutive=${successfulConnectCount})"
                        )
                    }
                    return r
                }
            }
        }
        return DshResult.failure(DshError.Transport.Timeout("connect after $maxAttempts attempts"))
    }

    suspend fun revokeAndClose() {
        credentialStore.clear()
        runCatching { remote.close() }
    }

    suspend fun disconnectOnly() {
        runCatching { remote.close() }
    }

    companion object {
        private const val TAG = "LocalProcessConnMgr"
    }
}
