package app.odicto.mobile

import app.odicto.mobile.network.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import okio.ByteString.Companion.encodeUtf8
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirectVoiceTransportTest {
    @Test fun finalizedLiveTextBeforeReleaseCompletesThroughFallbackOnce() {
        val events = mutableListOf<JSONObject>()
        lateinit var listener: WebSocketListener
        val ws = object : WebSocket {
            override fun request() = Request.Builder().url("https://generativelanguage.googleapis.com/").build()
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: okio.ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() {}
        }
        val transport = DirectVoiceTransport(DirectVoiceConfig("live", liveKey = "test"), "op", 1, { events.add(it) },
            socketFactory = WebSocket.Factory { _, callback -> listener = callback; ws })
        try {
            transport.open()
            listener.onMessage(ws, "{\"setupComplete\":{}}")
            listener.onMessage(ws, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"Finished sentence\"}}}")
            assertFalse(events.any { it.optString("type") == "result" })
            transport.finish(); transport.finishLiveFallback(); transport.finishLiveFallback()
            assertEquals(1, events.count { it.optString("type") == "result" })
            assertEquals("Finished sentence", events.last().getString("text"))
        } finally { transport.cancel() }
    }
    @Test fun aiSendsSelectionAndCustomPromptOnlyToAnswerProvider() {
        for (provider in listOf("gemini", "openrouter")) {
            val reply = if (provider == "gemini") "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"revised\"}]}}]}"
                else "{\"choices\":[{\"message\":{\"content\":\"revised\"}}]}"
            val (requests, events) = runRequest(DirectVoiceConfig("ai", provider, "test-model", groqKey = "test", answerKey = "answer",
                selectedText = "Original \"quoted\" text\nSecond line", systemPrompt = "Use British English."),
                listOf(200 to "{\"text\":\"Refactor this\"}", 200 to reply))
            val buffer = okio.Buffer(); requests[1].body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            val system = if (provider == "gemini") body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
                else body.getJSONArray("messages").getJSONObject(0).getString("content")
            val user = if (provider == "gemini") body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text")
                else body.getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(system.startsWith("Use British English."))
            assertEquals("Refactor this", user)
            assertEquals("Original \"quoted\" text\nSecond line", JSONObject(system.substringAfterLast('\n')).getString("selectedText"))
            val groq = okio.Buffer(); requests[0].body!!.writeTo(groq)
            val groqBody = groq.readUtf8()
            assertFalse(groqBody.contains("Original")); assertFalse(groqBody.contains("British"))
            assertEquals("revised", events.last().getString("text"))
        }
    }
    @Test fun truncatedAiResultsNeverReplaceSelection() {
        for ((provider, reply) in listOf("gemini" to "{\"candidates\":[{\"finishReason\":\"MAX_TOKENS\",\"content\":{\"parts\":[{\"text\":\"partial\"}]}}]}",
            "openrouter" to "{\"choices\":[{\"finish_reason\":\"length\",\"message\":{\"content\":\"partial\"}}]}")) {
            val (_, events) = runRequest(DirectVoiceConfig("ai", provider, "model", groqKey = "test", answerKey = "test", selectedText = "original"),
                listOf(200 to "{\"text\":\"Refactor this\"}", 200 to reply))
            assertFalse(events.any { it.optString("type") == "result" })
            assertTrue(events.last().getString("message").contains("Nothing replaced"))
        }
    }
    @Test fun aiWithoutSelectionUsesPlainInstructionAndPromptDefaultOrOverride() {
        for (prompt in listOf("", "Write very briefly.")) {
            val (requests, _) = runRequest(DirectVoiceConfig("ai", groqKey = "test", answerKey = "test", systemPrompt = prompt),
                listOf(200 to "{\"text\":\"Write a greeting\"}", 200 to "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Hello\"}]}}]}"))
            val buffer = okio.Buffer(); requests[1].body!!.writeTo(buffer)
            val body = JSONObject(buffer.readUtf8())
            assertEquals("Write a greeting", body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts").getJSONObject(0).getString("text"))
            val system = body.getJSONObject("systemInstruction").getJSONArray("parts").getJSONObject(0).getString("text")
            if (prompt.isEmpty()) assertTrue(system.startsWith("Execute the user's spoken instruction")) else assertEquals(prompt, system)
        }
    }
    @Test fun aiRejectsOversizedContextBeforeRecording() {
        for (config in listOf(DirectVoiceConfig("ai", groqKey = "test", answerKey = "test", selectedText = "a".repeat(20_001)),
            DirectVoiceConfig("ai", groqKey = "test", answerKey = "test", systemPrompt = "a".repeat(8_001)))) {
            val transport = DirectVoiceTransport(config, "op", 1, { fail("Oversized request became ready") })
            try { transport.open(); fail("Oversized context accepted") } catch (_: VoiceConfigurationException) {} finally { transport.cancel() }
        }
    }
    @Test fun cancellationClosesConnectionsOffCallerThreadAndOnlyOnce() {
        val caller = Thread.currentThread()
        val closed = java.util.concurrent.CountDownLatch(1)
        val cleanupThread = java.util.concurrent.atomic.AtomicReference<Thread>()
        val closures = java.util.concurrent.atomic.AtomicInteger()
        val client = OkHttpClient()
        val transport = DirectVoiceTransport(DirectVoiceConfig("raw", groqKey = "test"), "op", 1, {}, client,
            closeConnections = {
                cleanupThread.set(Thread.currentThread())
                closures.incrementAndGet()
                closed.countDown()
            })
        transport.open()
        transport.cancel()
        transport.cancel()
        assertFalse(transport.sendAudio(ByteArray(32), 32))
        assertTrue(closed.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertNotSame("TLS close must not run on the service/UI caller", caller, cleanupThread.get())
        assertTrue(client.dispatcher.executorService.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(1, closures.get())
    }
    @Test fun liveAcceptsBinaryJsonFramesLikeTheDesktopClient() {
        val events = mutableListOf<JSONObject>()
        lateinit var listener: WebSocketListener
        val request = Request.Builder().url("https://generativelanguage.googleapis.com/").build()
        val ws = object : WebSocket {
            override fun request() = request
            override fun queueSize() = 0L
            override fun send(text: String) = true
            override fun send(bytes: okio.ByteString) = true
            override fun close(code: Int, reason: String?) = true
            override fun cancel() {}
        }
        val factory = WebSocket.Factory { _, callback -> listener = callback; ws }
        val transport = DirectVoiceTransport(DirectVoiceConfig("live", liveKey = "test"), "op", 9, { events.add(it) }, socketFactory = factory)
        try {
            transport.open()
            listener.onMessage(ws, "{\"setupComplete\":{}}".encodeUtf8())
            assertEquals("ready", events.single().getString("type"))
            transport.finish()
            listener.onMessage(ws, "{\"serverContent\":{\"inputTranscription\":{\"text\":\"Live works\"}}}".encodeUtf8())
            assertEquals("Live works", events.last().getString("text"))
            assertEquals("result", events.last().getString("type"))
            transport.finishLiveFallback()
            assertEquals(1, events.count { it.optString("type") == "result" })
        } finally { transport.cancel() }
    }
    private fun runRequest(config: DirectVoiceConfig, responses: List<Pair<Int, String>>): Pair<List<Request>, List<JSONObject>> {
        val requests = mutableListOf<Request>()
        val events = mutableListOf<JSONObject>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            requests.add(chain.request())
            if (chain.request().url.host == "api.groq.com") {
                val body = chain.request().body as MultipartBody
                val buffer = okio.Buffer(); body.parts.last().body.writeTo(buffer)
                val wav = buffer.readByteArray()
                assertEquals(3200, ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN).getInt(40))
            }
            val response = responses[requests.lastIndex]
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(response.first).message("test")
                .body(response.second.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val transport = DirectVoiceTransport(config, "operation", 17, { events.add(it) }, client)
        transport.open()
        assertEquals("ready", events.first().getString("type"))
        assertTrue(transport.sendAudio(ByteArray(3200), 3200))
        transport.finish()
        transport.cancel()
        return requests to events
    }
    @Test fun rawCallsOnlyGroqAndKeepsEditorIdentity() {
        val (requests, events) = runRequest(DirectVoiceConfig("raw", groqKey = "groq-test"), listOf(200 to "{\"text\":\"Hello world\"}"))
        assertEquals(1, requests.size)
        assertEquals("api.groq.com", requests[0].url.host)
        assertEquals("Bearer groq-test", requests[0].header("Authorization"))
        assertEquals(17, events.last().getInt("editorSession"))
        assertEquals("Hello world", events.last().getString("text"))
    }
    @Test fun aiUsesGroqThenGeminiWithSeparateKeys() {
        val (requests, events) = runRequest(DirectVoiceConfig("ai", groqKey = "groq-test", answerKey = "gemini-test"),
            listOf(200 to "{\"text\":\"Write a greeting\"}", 200 to "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"Happy birthday!\"}]}}]}"))
        assertEquals(listOf("api.groq.com", "generativelanguage.googleapis.com"), requests.map { it.url.host })
        assertEquals("gemini-test", requests[1].header("x-goog-api-key"))
        assertNull(requests[1].header("Authorization"))
        assertEquals("Happy birthday!", events.last().getString("text"))
    }
    @Test fun aiUsesOpenRouterAndDoesNotInsertQuestionOnFailure() {
        val (requests, events) = runRequest(DirectVoiceConfig("ai", "openrouter", "test/model", groqKey = "groq-test", answerKey = "router-test"),
            listOf(200 to "{\"text\":\"Write a greeting\"}", 429 to "{}"))
        assertEquals("openrouter.ai", requests[1].url.host)
        assertEquals("Bearer router-test", requests[1].header("Authorization"))
        assertTrue(events.last().getString("message").contains("quota"))
        assertFalse(events.any { it.optString("type") == "result" })
        assertTrue(events.any { it.optString("type") == "final" })
    }
    @Test fun cancelledAudioNeverReachesProvider() {
        val client = OkHttpClient.Builder().addInterceptor { throw AssertionError("Cancelled audio was sent") }.build()
        val events = mutableListOf<JSONObject>()
        val transport = DirectVoiceTransport(DirectVoiceConfig("raw", groqKey = "test"), "op", 1, { events.add(it) }, client)
        transport.open(); transport.sendAudio(ByteArray(3200), 3200); transport.cancel(); transport.finish()
        assertEquals(1, events.size)
    }
    @Test fun missingKeysFailBeforeRecordingAndLiveUsesTranscriptionSetup() {
        val transport = DirectVoiceTransport(DirectVoiceConfig("raw"), "op", 1, {})
        try { transport.open(); fail("Missing key accepted") } catch (e: IllegalArgumentException) { assertTrue(e.message!!.contains("Groq")) } finally { transport.cancel() }
        val setup = DirectVoiceTransport.liveSetup("gemini-3.5-transcribe-live").getJSONObject("setup")
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        assertEquals("SMART", setup.getJSONObject("inputAudioTranscription").getString("mode"))
        assertTrue(setup.getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection").getBoolean("disabled"))
    }
}
