package app.odicto.mobile

import app.odicto.mobile.network.HttpClients
import app.odicto.mobile.network.PhaseTimings
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

class HttpClientsTest {
    @Test fun everyProviderClientSharesOnePoolAndOnlyLivePings() {
        assertSame(HttpClients.shared.connectionPool, HttpClients.voice.connectionPool)
        assertSame(HttpClients.shared.connectionPool, HttpClients.live.connectionPool)
        assertSame(HttpClients.shared.dispatcher, HttpClients.voice.dispatcher)
        assertEquals(0, HttpClients.shared.pingIntervalMillis)
        assertEquals(0, HttpClients.voice.pingIntervalMillis)
        assertEquals(20_000, HttpClients.live.pingIntervalMillis)
        assertEquals(10_000, HttpClients.voice.connectTimeoutMillis)
        assertEquals(30_000, HttpClients.voice.readTimeoutMillis)
        assertEquals(40_000, HttpClients.voice.callTimeoutMillis)
        assertFalse(HttpClients.voice.followRedirects)
        assertFalse(HttpClients.voice.followSslRedirects)
    }

    @Test fun phaseTimingsLogHostAndDurationsButNeverContent() {
        val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().eventListenerFactory { PhaseTimings { lines.add(it) } }.build()
        LocalHttpServer("{\"text\":\"private transcript\"}").use { server ->
            repeat(2) {
                val request = Request.Builder().url("http://127.0.0.1:${server.port}/v1?key=query-secret")
                    .header("Authorization", "Bearer header-secret")
                    .post("private audio".toRequestBody("application/octet-stream".toMediaType())).build()
                client.newCall(request).execute().use { it.body!!.string() }
            }
        }
        assertEquals(2, lines.size)
        assertTrue(lines[0], lines[0].startsWith("127.0.0.1 ok total="))
        assertTrue(lines[0], lines[0].contains("reused=false"))
        assertTrue(lines[0], lines[0].contains("upload=13B"))
        assertTrue(lines[1], lines[1].contains("reused=true"))
        for (line in lines) for (secret in listOf("query-secret", "header-secret", "private", "/v1")) assertFalse(line, line.contains(secret))
    }
}
