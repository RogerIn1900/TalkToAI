package com.example.talktoai.chat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.atomic.AtomicReference

class TalkToAiApiTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `stream EOF without terminal event reports retryable failure`() {
        assertStreamFailure(
            MockResponse().setBody("event: delta\ndata: {\"text\":\"partial\"}\n\n"),
            "STREAM_INTERRUPTED",
        )
    }

    @Test
    fun `stream disconnected during response reports network failure`() {
        assertStreamFailure(
            MockResponse().setBody("event: delta\ndata: " + "x".repeat(4096))
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
            "NETWORK_ERROR",
        )
    }

    private fun assertStreamFailure(response: MockResponse, expectedCode: String) {
        server.enqueue(response)
        val completed = CountDownLatch(1)
        val failure = AtomicReference<Pair<String, Boolean>>()
        TalkToAiApi(baseUrl = server.url("/").toString()).streamChat(
            "installation-test", "conversation-test", emptyList(),
            listener = object : TalkToAiApi.StreamListener {
                override fun onEvent(event: StreamEvent) = Unit
                override fun onFailure(code: String, message: String, retryable: Boolean) {
                    failure.set(code to retryable)
                    completed.countDown()
                }
            },
        )
        assertTrue("Expected terminal failure", completed.await(3, TimeUnit.SECONDS))
        assertEquals(expectedCode to true, failure.get())
    }

    @Test
    fun `stream request omits empty pending assistant message`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: done\ndata: {}\n\n"),
        )
        val completed = CountDownLatch(1)
        val api = TalkToAiApi(baseUrl = server.url("/").toString())

        api.streamChat(
            installationId = "installation-test",
            conversationId = "conversation-test",
            messages = listOf(
                ChatMessage("user", MessageRole.USER, "解释行情", MessageStatus.COMPLETE, 1L),
                ChatMessage("assistant", MessageRole.ASSISTANT, "", MessageStatus.STREAMING, 2L),
            ),
            listener = object : TalkToAiApi.StreamListener {
                override fun onEvent(event: StreamEvent) {
                    if (event.type == "done") completed.countDown()
                }

                override fun onFailure(code: String, message: String, retryable: Boolean) {
                    completed.countDown()
                }
            },
        )

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
        val messages = body.getJSONArray("messages")
        assertEquals(AiModels.DEFAULT, body.getString("model"))
        assertEquals(1, messages.length())
        assertEquals("user", messages.getJSONObject(0).getString("role"))
    }

    @Test
    fun `stream request forwards an allowlisted DeepSeek model id`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("event: done\ndata: {}\n\n"),
        )
        val completed = CountDownLatch(1)
        TalkToAiApi(baseUrl = server.url("/").toString()).streamChat(
            installationId = "installation-test",
            conversationId = "conversation-test",
            messages = listOf(ChatMessage("user", MessageRole.USER, "解释行情", MessageStatus.COMPLETE, 1L)),
            model = AiModels.DEEPSEEK_V4_FLASH,
            listener = object : TalkToAiApi.StreamListener {
                override fun onEvent(event: StreamEvent) {
                    if (event.type == "done") completed.countDown()
                }

                override fun onFailure(code: String, message: String, retryable: Boolean) = completed.countDown()
            },
        )
        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
        assertEquals(AiModels.DEEPSEEK_V4_FLASH, body.getString("model"))
    }

    @Test
    fun `failed and blank messages are not sendable`() {
        assertFalse(TalkToAiApi.isSendableMessage(ChatMessage("1", MessageRole.ASSISTANT, "", MessageStatus.STREAMING, 1L)))
        assertFalse(TalkToAiApi.isSendableMessage(ChatMessage("2", MessageRole.ASSISTANT, "error", MessageStatus.FAILED, 1L)))
        assertTrue(TalkToAiApi.isSendableMessage(ChatMessage("3", MessageRole.USER, "question", MessageStatus.COMPLETE, 1L)))
    }

    @Test
    fun `attachment upload sends bytes and identity without embedding file in JSON`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"objectRef":"cloud://fixture/file"}"""))
        val file = File.createTempFile("talktoai-test", ".txt").apply { writeText("hello") }
        val completed = CountDownLatch(1)
        val api = TalkToAiApi(baseUrl = server.url("/").toString())

        api.uploadAttachment(
            "install_1234567890abcdef",
            ChatAttachment("abcdef0123456789", "notes.txt", "text/plain", 5, file.absolutePath),
            object : TalkToAiApi.JsonListener {
                override fun onSuccess(json: JSONObject) = completed.countDown()
                override fun onFailure(code: String, message: String, retryable: Boolean) = completed.countDown()
            },
        )

        assertTrue(completed.await(2, TimeUnit.SECONDS))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("PUT", request.method)
        assertEquals("install_1234567890abcdef", request.getHeader("X-Installation-Id"))
        assertEquals("hello", request.body.readUtf8())
        file.delete()
    }
}
