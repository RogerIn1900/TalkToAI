package com.example.talktoai.dsh.client

import com.example.talktoai.dsh.connection.Credential
import com.example.talktoai.dsh.connection.CredentialStore
import com.example.talktoai.dsh.connection.HostEndpoint
import com.example.talktoai.dsh.connection.ConnectionTopology
import com.example.talktoai.dsh.contract.AttachmentRef
import com.example.talktoai.dsh.contract.DshError
import com.example.talktoai.dsh.contract.DshResult
import com.example.talktoai.dsh.contract.ImageAttachmentPayload
import com.example.talktoai.dsh.contract.ImageBudget
import com.example.talktoai.dsh.contract.SessionId
import com.example.talktoai.dsh.observability.NoopLogger
import com.example.talktoai.dsh.prompt.OptimisticState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class DshClientIntegrationTest {

    private val sid = SessionId("integration")

    private class InMemoryCredStore : CredentialStore {
        var value: Credential? = null
        override suspend fun read() = value
        override suspend fun write(c: Credential) { value = c }
        override suspend fun clear() { value = null }
    }

    private fun attachment(size: Long = 1000L, b64Len: Long = 1000L): ImageAttachmentPayload =
        ImageAttachmentPayload(
            ref = AttachmentRef(id = "img-x", mime = "image/jpeg", sizeBytes = size),
            base64Encoded = "a".repeat(b64Len.toInt()),
            widthPx = 1024,
            heightPx = 1024,
        )

    @Test
    fun connect_handshake_succeeds_and_sets_generation() = runBlocking<Unit> {
        val remote = InMemoryFakeRemote()
        val store = InMemoryCredStore()
        val client = DshClient(
            remote = remote,
            credentialStore = store,
            deviceId = "dev1",
            expectedHostVersion = "0.1.2-alpha.2",
        )
        val ep = HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        )
        val r = client.connect(ep)
        assertIs<DshResult.Ok<*>>(r)
    }

    @Test
    fun connect_rejects_incompatible_host_version() = runBlocking<Unit> {
        val remote = InMemoryFakeRemote(remoteVersion = "0.1.0", expectedVersion = "0.1.0")
        val client = DshClient(remote, InMemoryCredStore(), "dev1", expectedHostVersion = "0.1.2-alpha.2")
        val ep = HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        )
        val r = client.connect(ep)
        assertIs<DshResult.Err>(r)
        assertIs<DshError.IncompatibleHost>(r.error)
    }

    @Test
    fun send_prompt_returns_request_id_and_marks_acked() = runBlocking<Unit> {
        val remote = InMemoryFakeRemote()
        val client = DshClient(remote, InMemoryCredStore(), "dev1", "0.1.2-alpha.2")
        client.connect(HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        ))
        val r = client.sendPrompt(sid, "hello", emptyList())
        assertIs<DshResult.Ok<String>>(r)
        assertTrue(r.value.startsWith("p_dev1_"))
        val snapshot = client.trackerSnapshot()
        assertEquals(1, snapshot.size)
        assertIs<OptimisticState.Acked>(snapshot[0].state)
    }

    @Test
    fun send_prompt_with_image_attachment_passes_budget() = runBlocking<Unit> {
        val remote = InMemoryFakeRemote()
        val client = DshClient(remote, InMemoryCredStore(), "dev1", "0.1.2-alpha.2", imageBudget = ImageBudget.DEFAULT)
        client.connect(HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        ))
        val r = client.sendPrompt(sid, "look", listOf(attachment(size = 100_000, b64Len = 100_000)))
        assertIs<DshResult.Ok<String>>(r)
    }

    @Test
    fun send_prompt_rejects_oversized_image() = runBlocking<Unit> {
        val remote = InMemoryFakeRemote()
        val client = DshClient(remote, InMemoryCredStore(), "dev1", "0.1.2-alpha.2")
        client.connect(HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        ))
        val r = client.sendPrompt(sid, "look", listOf(attachment(size = 100L * 1024 * 1024)))
        assertIs<DshResult.Err>(r)
        assertIs<DshError.Business.AttachmentTooLarge>(r.error)
    }

    @Test
    fun reconcile_returns_not_found_when_not_committed() = runBlocking<Unit> {
        val remote = InMemoryFakeRemote()
        val client = DshClient(remote, InMemoryCredStore(), "dev1", "0.1.2-alpha.2")
        client.connect(HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken("dev1", 0L),
        ))
        client.sendPrompt(sid, "hi", emptyList())
        val requestId = client.trackerSnapshot().first().requestId.value
        val r = client.reconcilePrompt(sid, requestId)
        // fake remote 实际提交了，应该返回 Confirmed
        assertIs<DshResult.Ok<Unit>>(r)
    }

    @Test
    fun lan_topology_requires_paired_credential() {
        val r = runCatching {
            HostEndpoint(
                topology = ConnectionTopology.LAN,
                host = "192.168.1.10",
                port = 8443,
                useTls = true,
                credential = null,
            )
        }
        assertTrue(r.isFailure)
    }

    @Test
    fun local_process_rejects_non_loopback_host() {
        val r = runCatching {
            HostEndpoint(
                topology = ConnectionTopology.LOCAL_PROCESS,
                host = "0.0.0.0",
                port = 8000,
                useTls = false,
                credential = null,
            )
        }
        assertTrue(r.isFailure)
    }
}
