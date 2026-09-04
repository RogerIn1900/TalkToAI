package com.example.talktoai.dsh.connection

import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.TraceContext
import com.example.talktoai.dsh.transport.CommandAck
import com.example.talktoai.dsh.transport.CommandKind
import com.example.talktoai.dsh.transport.CommandRequest
import com.example.talktoai.dsh.transport.ControlFrame
import com.example.talktoai.dsh.transport.DshRemote
import com.example.talktoai.dsh.transport.FollowStream
import com.example.talktoai.dsh.transport.HandshakeInfo
import com.example.talktoai.dsh.transport.PageChunk
import com.example.talktoai.dsh.transport.PluginEntry
import com.example.talktoai.dsh.transport.PromptLookupResult
import com.example.talktoai.dsh.transport.SessionEntry
import com.example.talktoai.dsh.transport.WorkspaceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class LocalProcessConnectionManagerTest {

    private class InMemoryStore : CredentialStore {
        var value: Credential? = null
        override suspend fun read() = value
        override suspend fun write(c: Credential) { value = c }
        override suspend fun clear() { value = null }
    }

    private class OkRemote(val gen: Long = 1L, val ver: String = "0.1.2-alpha.2") : DshRemote {
        override suspend fun handshake() = DshResult.success(
            HandshakeInfo(ver, ver, gen, setOf("session.follow"))
        )
        override suspend fun sessionFollow(sessionId: SessionId, ctx: TraceContext) = throw UnsupportedOperationException()
        override suspend fun sessionPage(sessionId: SessionId, beforeSeq: Long?, limit: Int, ctx: TraceContext) =
            DshResult.success(PageChunk(emptyList(), false, null))
        override suspend fun sessionControl(sessionId: SessionId, generation: Long?, ctx: TraceContext) =
            DshResult.success(ControlFrame.Queue(1L, emptyList()))
        override suspend fun command(req: CommandRequest, ctx: TraceContext) = DshResult.success(CommandAck(req.requestId, true))
        override suspend fun promptLookup(sessionId: SessionId, requestId: String, ctx: TraceContext) =
            DshResult.success(PromptLookupResult.NotFound)
        override suspend fun workspaceList(ctx: TraceContext) = DshResult.success(emptyList<WorkspaceEntry>())
        override suspend fun sessionList(workspaceId: String?, ctx: TraceContext) = DshResult.success(emptyList<SessionEntry>())
        override suspend fun pluginInventory(ctx: TraceContext) = DshResult.success(emptyList<PluginEntry>())
        override suspend fun close() {}
    }

    private class FailFirstRemote(private val delegate: DshRemote, var fails: Int) : DshRemote by delegate {
        override suspend fun handshake(): DshResult<HandshakeInfo> {
            if (fails > 0) {
                fails--
                return DshResult.failure(DshError.Transport.Disconnected())
            }
            return delegate.handshake()
        }
    }

    @Test
    fun connect_succeeds_and_persists_credential() = runBlocking<Unit> {
        val store = InMemoryStore()
        val mgr = LocalProcessConnectionManager(OkRemote(), store, "dev1", "0.1.2-alpha.2", backoffInitialMs = 1L, backoffMaxMs = 4L)
        val ep = HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        )
        val r = mgr.connect(ep)
        assertIs<DshResult.Ok<HandshakeInfo>>(r)
        assertNotNull(mgr.lastHandshake)
        assertEquals(Credential.LoopbackToken("dev1", 0L), store.value)
    }

    @Test
    fun connect_rejects_incompatible_host_version() = runBlocking<Unit> {
        val mgr = LocalProcessConnectionManager(OkRemote(ver = "0.1.0"), InMemoryStore(), "dev1", "0.1.2-alpha.2", backoffInitialMs = 1L)
        val r = mgr.connect(HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        ))
        assertIs<DshResult.Err>(r)
        assertIs<DshError.IncompatibleHost>(r.error)
    }

    @Test
    fun connect_retries_then_succeeds() = runBlocking<Unit> {
        val store = InMemoryStore()
        val remote = FailFirstRemote(OkRemote(), fails = 2)
        val mgr = LocalProcessConnectionManager(remote, store, "dev1", "0.1.2-alpha.2",
            backoffInitialMs = 1L, backoffMaxMs = 4L, maxAttempts = 5)
        val r = mgr.connect(HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        ))
        assertIs<DshResult.Ok<HandshakeInfo>>(r)
    }

    @Test
    fun connect_requires_credential_for_non_loopback_topology() = runBlocking<Unit> {
        // USB_DEBUG 允许 credential=null（设计 §6：开发/调试通道可临时令牌）
        // 但 manager 仍要求非 LOCAL_PROCESS 必须带凭据。
        val mgr = LocalProcessConnectionManager(OkRemote(), InMemoryStore(), "dev1", "0.1.2-alpha.2")
        val usb = HostEndpoint(
            topology = ConnectionTopology.USB_DEBUG,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = null,
        )
        val r = mgr.connect(usb)
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Transport.AuthRequired>(r.error)
    }

    @Test
    fun revoke_and_close_clears_store() = runBlocking<Unit> {
        val store = InMemoryStore()
        val mgr = LocalProcessConnectionManager(OkRemote(), store, "dev1", "0.1.2-alpha.2")
        store.value = Credential.LoopbackToken("dev1", 0L)
        mgr.revokeAndClose()
        assertEquals(null, store.value)
    }

    @Test
    fun first_connect_records_generation_and_count() = runBlocking<Unit> {
        val store = InMemoryStore()
        val mgr = LocalProcessConnectionManager(OkRemote(gen = 42L), store, "dev1", "0.1.2-alpha.2",
            backoffInitialMs = 1L)
        val r = mgr.connect(HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        ))
        assertIs<DshResult.Ok<HandshakeInfo>>(r)
        assertEquals(42L, mgr.lastSeenGeneration)
        assertEquals(1, mgr.successfulConnectCount)
    }

    @Test
    fun reconnect_with_consecutive_generation_records_count() = runBlocking<Unit> {
        val store = InMemoryStore()
        // 第一次 gen=1，第二次 gen=2 → 正常递增
        val mgr = LocalProcessConnectionManager(SequencedRemote(listOf(1L, 2L)), store, "dev1", "0.1.2-alpha.2",
            backoffInitialMs = 1L)
        val ep = HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        )
        assertIs<DshResult.Ok<HandshakeInfo>>(mgr.connect(ep))
        assertIs<DshResult.Ok<HandshakeInfo>>(mgr.connect(ep))
        assertEquals(2L, mgr.lastSeenGeneration)
        assertEquals(2, mgr.successfulConnectCount)
    }

    @Test
    fun reconnect_with_generation_jump_is_recorded_in_logger() = runBlocking<Unit> {
        val store = InMemoryStore()
        val captured = mutableListOf<String>()
        val recordingLogger = object : com.example.talktoai.dsh.observability.DshLogger {
            override fun debug(tag: String, ctx: com.example.talktoai.dsh.observability.TraceContext, message: String) {}
            override fun info(tag: String, ctx: com.example.talktoai.dsh.observability.TraceContext, message: String) {}
            override fun warn(tag: String, ctx: com.example.talktoai.dsh.observability.TraceContext, message: String, error: Throwable?) {
                captured.add(message)
            }
            override fun error(tag: String, ctx: com.example.talktoai.dsh.observability.TraceContext, message: String, error: Throwable?) {}
        }
        // 第一次 gen=1，第二次 gen=10 → 跳变 8（设计 §9：generation 跳变要埋点）
        val mgr = LocalProcessConnectionManager(
            SequencedRemote(listOf(1L, 10L)), store, "dev1", "0.1.2-alpha.2",
            backoffInitialMs = 1L, logger = recordingLogger
        )
        val ep = HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        )
        assertIs<DshResult.Ok<HandshakeInfo>>(mgr.connect(ep))
        assertIs<DshResult.Ok<HandshakeInfo>>(mgr.connect(ep))
        val gapLogged = captured.any { "generation jump" in it && "previous=1" in it && "new=10" in it }
        assertEquals(true, gapLogged, "expected generation jump warning, captured=$captured")
    }
}

/** 依次返回 [HandshakeInfo] with given connectionGeneration 序列。 */
private class SequencedRemote(private val gens: List<Long>) : DshRemote {
    private var idx = 0
    override suspend fun handshake(): DshResult<HandshakeInfo> {
        val gen = gens.getOrElse(idx) { gens.last() }
        idx++
        return DshResult.success(HandshakeInfo("0.1.2-alpha.2", "0.1.2-alpha.2", gen, setOf("session.follow")))
    }
    override suspend fun sessionFollow(sessionId: SessionId, ctx: TraceContext) = throw UnsupportedOperationException()
    override suspend fun sessionPage(sessionId: SessionId, beforeSeq: Long?, limit: Int, ctx: TraceContext) =
        DshResult.success(PageChunk(emptyList(), false, null))
    override suspend fun sessionControl(sessionId: SessionId, generation: Long?, ctx: TraceContext) =
        DshResult.success(ControlFrame.Queue(1L, emptyList()))
    override suspend fun command(req: CommandRequest, ctx: TraceContext) = DshResult.success(CommandAck(req.requestId, true))
    override suspend fun promptLookup(sessionId: SessionId, requestId: String, ctx: TraceContext) =
        DshResult.success(PromptLookupResult.NotFound)
    override suspend fun workspaceList(ctx: TraceContext) = DshResult.success(emptyList<WorkspaceEntry>())
    override suspend fun sessionList(workspaceId: String?, ctx: TraceContext) = DshResult.success(emptyList<SessionEntry>())
    override suspend fun pluginInventory(ctx: TraceContext) = DshResult.success(emptyList<PluginEntry>())
    override suspend fun close() {}
}
