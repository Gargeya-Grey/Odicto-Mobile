package app.odicto.mobile

import app.odicto.mobile.ime.FieldPolishClient
import app.odicto.mobile.ime.FieldPolishResult
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class FieldPolishClientTest {
    private fun reply(content: String = "Fixed text", finish: String = "stop") =
        JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("finish_reason", finish)
            .put("message", JSONObject().put("content", content)))).toString()

    private fun client(code: Int = 200, body: String = reply(), requests: MutableList<Request> = mutableListOf()): FieldPolishClient =
        FieldPolishClient(OkHttpClient.Builder().addInterceptor { chain ->
            synchronized(requests) { requests.add(chain.request()) }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())

    @Test fun sendsTextOnlyWithDefaultModelPromptAndTransientKey() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client(requests = requests).correct("  Here is a proper name: QwErty.\n", apiKey = "secret")
        assertEquals(FieldPolishResult.Success("Fixed text"), result)
        val request = synchronized(requests) { requests.single() }
        assertEquals("https://openrouter.ai/api/v1/chat/completions", request.url.toString())
        assertEquals("POST", request.method)
        assertEquals("Bearer secret", request.header("Authorization"))
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        assertEquals("poolside/laguna-xs-2.1", payload.getString("model"))
        assertEquals(8192, payload.getInt("max_tokens"))
        assertEquals("latency", payload.getJSONObject("provider").getString("sort"))
        val messages = payload.getJSONArray("messages")
        assertEquals(2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertTrue(messages.getJSONObject(0).getString("content").contains("Preserve proper-noun spellings exactly as entered"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("  Here is a proper name: QwErty.\n", messages.getJSONObject(1).getString("content"))
        assertFalse(payload.toString().contains("secret"))
    }

    @Test fun customModelAndPromptAreUsedWithoutFallbackAndFormattingIsKept() = runBlocking {
        val requests = mutableListOf<Request>()
        val result = client(body = reply("  Line one\nLine two  "), requests = requests)
            .correct("input", model = "custom/model", systemPrompt = "Custom prompt", apiKey = "key")
        assertEquals(FieldPolishResult.Success("  Line one\nLine two  "), result)
        val buffer = Buffer()
        synchronized(requests) { requests.single() }.body!!.writeTo(buffer)
        val payload = JSONObject(buffer.readUtf8())
        assertEquals("custom/model", payload.getString("model"))
        assertEquals("Custom prompt", payload.getJSONArray("messages").getJSONObject(0).getString("content"))
    }

    @Test fun invalidInputIsRejectedWithoutNetwork() = runBlocking {
        val client = FieldPolishClient(OkHttpClient.Builder().addInterceptor { throw AssertionError("Unexpected request") }.build())
        val cases = listOf(
            client.correct("", apiKey = "key"),
            client.correct("x".repeat(20_001), apiKey = "key"),
            client.correct("x", systemPrompt = "p".repeat(8_001), apiKey = "key"),
            client.correct("x", model = " ", apiKey = "key"),
            client.correct("x", apiKey = " "),
        )
        assertTrue(cases.all { it is FieldPolishResult.Failure && it.reason == FieldPolishResult.Reason.INVALID_INPUT })
        assertEquals(FieldPolishResult.Success("Fixed text"), client().correct("x".repeat(20_000), systemPrompt = "p".repeat(8_000), apiKey = "key"))
    }

    @Test fun responseFailuresAreActionableAndNeverReturnPartialText() = runBlocking {
        for ((code, reason) in listOf(401 to FieldPolishResult.Reason.AUTHENTICATION,
            404 to FieldPolishResult.Reason.MODEL_UNAVAILABLE, 429 to FieldPolishResult.Reason.RATE_LIMITED,
            302 to FieldPolishResult.Reason.PROVIDER)) {
            val result = client(code, "provider private body").correct("input", apiKey = "key") as FieldPolishResult.Failure
            assertEquals(reason, result.reason)
            assertFalse(result.message.contains("provider private body"))
        }
        val responses = listOf(reply("partial", "length") to FieldPolishResult.Reason.TRUNCATED,
            reply("") to FieldPolishResult.Reason.INVALID_RESPONSE,
            reply("x", "content_filter") to FieldPolishResult.Reason.INVALID_RESPONSE,
            reply("x".repeat(20_001)) to FieldPolishResult.Reason.INVALID_RESPONSE,
            "not json" to FieldPolishResult.Reason.INVALID_RESPONSE,
            "x".repeat(128 * 1024 + 1) to FieldPolishResult.Reason.INVALID_RESPONSE)
        for ((body, reason) in responses) {
            assertEquals(reason, (client(body = body).correct("input", apiKey = "key") as FieldPolishResult.Failure).reason)
        }
    }

    @Test fun networkFailureReturnsSafeError() = runBlocking {
        val failing = FieldPolishClient(OkHttpClient.Builder().addInterceptor { throw java.io.IOException("sensitive provider detail") }.build())
        val result = failing.correct("input", apiKey = "key") as FieldPolishResult.Failure
        assertEquals(FieldPolishResult.Reason.NETWORK, result.reason)
        assertFalse(result.message.contains("sensitive provider detail"))
    }

    @Test fun cancellingCoroutineCancelsInFlightOkHttpCall() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val observedCall = AtomicReference<okhttp3.Call>()
        val client = FieldPolishClient(OkHttpClient.Builder().addInterceptor { chain ->
            observedCall.set(chain.call())
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(reply().toResponseBody("application/json".toMediaType())).build()
        }.build())
        val job = launch(start = CoroutineStart.UNDISPATCHED) { client.correct("input", apiKey = "key") }
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            job.cancel()
            assertTrue(observedCall.get().isCanceled())
            job.join()
            assertTrue(job.isCancelled)
        } finally {
            release.countDown()
        }
    }
}
