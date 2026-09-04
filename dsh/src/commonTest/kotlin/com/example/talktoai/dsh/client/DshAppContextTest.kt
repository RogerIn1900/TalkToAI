package com.example.talktoai.dsh.client

import com.example.talktoai.dsh.connection.Credential
import com.example.talktoai.dsh.connection.CredentialStore
import com.example.talktoai.dsh.connection.HostEndpoint
import com.example.talktoai.dsh.connection.ConnectionTopology
import com.example.talktoai.dsh.observability.NoopLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

class DshAppContextTest {

    private class InMemoryStore : CredentialStore {
        var value: Credential? = null
        override suspend fun read() = value
        override suspend fun write(c: Credential) { value = c }
        override suspend fun clear() { value = null }
    }

    @Test
    fun app_context_exposes_all_components_and_connects() = runBlocking<Unit> {
        val store = InMemoryStore()
        val ctx = DshAppContext(
            remote = InMemoryFakeRemote(),
            credentialStore = store,
            deviceId = "dev-app",
            expectedHostVersion = "0.1.2-alpha.2",
            appVersion = "1.0.0",
            rawLogger = NoopLogger,
        )
        val ep = HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev-app", 0L),
        )
        val r = ctx.connect(ep)
        assertIs<com.example.talktoai.dsh.contract.DshResult.Ok<*>>(r)
        assertEquals(1L, ctx.currentConnectionGeneration())
        // Projector 的 baseline 由 Host Control 帧填充；
        // 这里只校验其作为组件对外暴露，且 lastSeenGeneration 已初始化。
        assertEquals(-1L, ctx.projector.lastSeenGeneration)
        // Logger wraps SanitizedLogger
        assertIs<com.example.talktoai.dsh.observability.SanitizedLogger>(ctx.logger)
    }

    @Test
    fun app_context_next_request_id_is_unique() {
        val ctx = DshAppContext(
            remote = InMemoryFakeRemote(),
            credentialStore = InMemoryStore(),
            deviceId = "dev-x",
            expectedHostVersion = "0.1.2-alpha.2",
            appVersion = "1.0.0",
            rawLogger = NoopLogger,
        )
        val sid = com.example.talktoai.dsh.contract.SessionId("s1")
        val a = ctx.nextRequestId(sid)
        val b = ctx.nextRequestId(sid)
        assertIs<String>(a)
        assertIs<String>(b)
        kotlin.test.assertTrue(a != b)
    }
}
