package app.odicto.mobile

import app.odicto.mobile.network.DirectVoiceConfig
import app.odicto.mobile.network.DirectVoiceTransport
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiLiveTransportTest {
    private class Socket : WebSocket {
        lateinit var original: Request
        val sent = mutableListOf<JSONObject>()
        var cancellations = 0
        var closes = 0
        override fun request() = original
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent.add(JSONObject(text)); return true }
        override fun send(bytes: ByteString) = true
        override fun close(code: Int, reason: String?): Boolean { closes++; return true }
        override fun cancel() { cancellations++ }
    }

    private class Fixture(model: String = "gpt-live-transcribe") {
        val socket = Socket()
        val events = mutableListOf<JSONObject>()
        lateinit var listener: WebSocketListener
        val transport = DirectVoiceTransport(DirectVoiceConfig("live", liveProvider = "openai", openaiLiveModel = model, liveKey = "openai-test"), "op", 7,
            { events.add(it) }, socketFactory = WebSocket.Factory { request, callback ->
                socket.original = request; listener = callback; socket
            })
        init {
            transport.open()
            listener.onOpen(socket, Response.Builder().request(socket.original).protocol(Protocol.HTTP_1_1).code(101).message("test").build())
        }
        fun message(text: String) { listener.onMessage(socket, text) }
        fun ready() = message("{\"type\":\"session.updated\"}")
        fun audio() { assertTrue(transport.sendAudio(ByteArray(3200), 3200)) }
        fun types() = events.map { it.getString("type") }
    }

    @Test fun selectedProviderUsesOnlyAuthorizationHeaderAndWaitsForAcknowledgedSetup() {
        for (model in listOf("gpt-live-transcribe", "gpt-realtime-whisper")) {
            val f = Fixture(model)
            try {
                assertEquals("api.openai.com", f.socket.original.url.host)
                assertEquals("/v1/realtime", f.socket.original.url.encodedPath)
                assertEquals("intent=transcription", f.socket.original.url.encodedQuery)
                assertEquals("Bearer openai-test", f.socket.original.header("Authorization"))
                assertNull(f.socket.original.header("x-goog-api-key"))
                val setup = f.socket.sent.single()
                assertEquals("session.update", setup.getString("type"))
                val session = setup.getJSONObject("session")
                assertEquals("transcription", session.getString("type"))
                val input = session.getJSONObject("audio").getJSONObject("input")
                assertEquals(24000, input.getJSONObject("format").getInt("rate"))
                assertEquals(model, input.getJSONObject("transcription").getString("model"))
                assertTrue(input.isNull("turn_detection"))
                assertFalse(input.getJSONObject("transcription").has("prompt"))
                assertFalse(f.transport.sendAudio(ByteArray(3200), 3200))
                f.message("{\"type\":\"session.created\"}")
                f.message("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"a\",\"delta\":\"Before ready\"}")
                assertTrue(f.events.isEmpty())
                f.ready(); f.ready()
                assertEquals(listOf("ready"), f.types())
                assertEquals("op", f.events.single().getString("operationId"))
                assertEquals(7, f.events.single().getInt("editorSession"))
            } finally { f.transport.cancel() }
        }
    }

    @Test fun liveDeltasComposeOneTurnAndMatchingFinalCompletesExactlyOnceAfterCommit() {
        val f = Fixture()
        try {
            f.ready(); f.audio(); f.audio()
            f.message("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"a\",\"delta\":\"Hello\"}")
            f.message("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"a\",\"delta\":\" world\"}")
            assertEquals("Hello world", f.events.last().getString("text"))
            f.transport.finish(); f.transport.finish()
            assertEquals(1, f.socket.sent.count { it.optString("type") == "input_audio_buffer.commit" })
            val audioBytes = f.socket.sent.filter { it.optString("type") == "input_audio_buffer.append" }
                .sumOf { java.util.Base64.getDecoder().decode(it.getString("audio")).size }
            assertEquals("200 ms capture stays 200 ms at 24 kHz", 9600, audioBytes)
            f.message("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"a\"}")
            f.message("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"other\",\"transcript\":\"Wrong turn\"}")
            assertFalse(f.types().contains("result"))
            val final = "{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"a\",\"transcript\":\"Hello world.\"}"
            f.message(final); f.message(final)
            f.transport.finishLiveFallback()
            f.listener.onClosed(f.socket, 1000, "closed")
            assertEquals(listOf("ready", "interim", "interim", "final", "result"), f.types())
            assertEquals("Hello world.", f.events.last().getString("text"))
            assertEquals("Hello world.", f.events.last().getString("transcript"))
            assertEquals(7, f.events.last().getInt("editorSession"))
        } finally { f.transport.cancel() }
    }

    @Test fun finalBeforeCommitAcknowledgementWaitsForTheMatchingItem() {
        val f = Fixture()
        try {
            f.ready(); f.audio(); f.transport.finish()
            f.message("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"a\",\"transcript\":\"Final\"}")
            assertEquals(listOf("ready"), f.types())
            f.message("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"a\"}")
            assertEquals(listOf("ready", "final", "result"), f.types())
        } finally { f.transport.cancel() }
    }

    @Test fun timeoutWithPartialTextIsAnErrorAndNeverAResult() {
        val f = Fixture()
        try {
            f.ready(); f.audio()
            f.message("{\"type\":\"conversation.item.input_audio_transcription.delta\",\"item_id\":\"a\",\"delta\":\"Partial\"}")
            f.transport.finish(); f.transport.finishLiveFallback(); f.transport.finishLiveFallback()
            f.message("{\"type\":\"input_audio_buffer.committed\",\"item_id\":\"a\"}")
            f.message("{\"type\":\"conversation.item.input_audio_transcription.completed\",\"item_id\":\"a\",\"transcript\":\"Late\"}")
            assertEquals(listOf("ready", "interim", "error"), f.types())
            assertTrue(f.events.last().getString("message").contains("already typed"))
        } finally { f.transport.cancel() }
    }

    @Test fun providerFailureIsGenericAndCancelSilencesLateCallbacks() {
        val f = Fixture()
        f.ready(); f.audio()
        f.message("{\"type\":\"error\",\"error\":{\"message\":\"secret-provider-response\"}}")
        f.listener.onClosed(f.socket, 1001, "secret-provider-response")
        assertEquals(listOf("ready", "error"), f.types())
        assertFalse(f.events.last().toString().contains("secret-provider-response"))
        f.transport.cancel()
        val cancelled = Fixture()
        cancelled.transport.cancel()
        cancelled.ready()
        cancelled.listener.onFailure(cancelled.socket, java.io.IOException("secret"), null)
        assertTrue(cancelled.events.isEmpty())
    }

    @Test fun disconnectAndShortAudioNeverClaimSuccessfulDelivery() {
        val disconnected = Fixture()
        try {
            disconnected.ready(); disconnected.audio()
            disconnected.listener.onClosed(disconnected.socket, 1001, "closed")
            disconnected.transport.finish(); disconnected.transport.finishLiveFallback()
            assertEquals(listOf("ready", "error"), disconnected.types())
        } finally { disconnected.transport.cancel() }
        val short = Fixture()
        try {
            short.ready()
            assertTrue(short.transport.sendAudio(ByteArray(2), 2))
            short.transport.finish(); short.transport.finishLiveFallback()
            assertEquals(listOf("ready", "error"), short.types())
            assertFalse(short.socket.sent.any { it.optString("type") == "input_audio_buffer.commit" })
        } finally { short.transport.cancel() }
    }

    @Test fun unknownModelAndMissingSelectedKeyNeverCreateASocket() {
        for (config in listOf(
            DirectVoiceConfig("live", liveProvider = "openai"),
            DirectVoiceConfig("live", liveProvider = "openai", liveKey = "test", openaiLiveModel = "gpt-chat"),
        )) {
            var calls = 0
            val transport = DirectVoiceTransport(config, "op", 1, {}, socketFactory = WebSocket.Factory { _, _ -> calls++; error("must not connect") })
            try { transport.open(); fail("Invalid config accepted") } catch (_: IllegalArgumentException) {} finally { transport.cancel() }
            assertEquals(0, calls)
        }
    }

    @Test fun cancellationDuringSocketCreationClosesTheLateSocket() {
        val socket = Socket()
        lateinit var transport: DirectVoiceTransport
        transport = DirectVoiceTransport(DirectVoiceConfig("live", liveProvider = "openai", liveKey = "test"), "op", 1, {},
            socketFactory = WebSocket.Factory { request, _ -> socket.original = request; transport.cancel(); socket })
        transport.open()
        assertTrue(socket.cancellations > 0)
    }
}
