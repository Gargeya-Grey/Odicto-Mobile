package app.odicto.mobile

import app.odicto.mobile.network.GeminiCatalogException
import app.odicto.mobile.network.GeminiCatalogModel
import app.odicto.mobile.network.GeminiModelCatalog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GeminiModelCatalogTest {
    private fun model(id: String, name: String = id, vararg methods: String) = JSONObject()
        .put("name", "models/$id").put("displayName", name)
        .put("supportedGenerationMethods", JSONArray(methods.toList()))

    private fun page(vararg models: JSONObject, token: String? = null) = JSONObject()
        .put("models", JSONArray(models.toList())).apply { if (token != null) put("nextPageToken", token) }.toString()

    private fun catalog(requests: MutableList<Request>, respond: (Int) -> Pair<Int, String>): GeminiModelCatalog =
        GeminiModelCatalog(OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests.add(request)
            val (code, body) = respond(requests.size)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .apply { if (code == 302) header("Location", "https://example.invalid/") }
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build())

    private fun failure(block: () -> Unit): GeminiCatalogException {
        try { block() } catch (error: GeminiCatalogException) { return error }
        throw AssertionError("Expected catalog request to fail")
    }

    @Test fun listsTextCandidatesAcrossPagesWithHeaderOnlyAuthentication() {
        val requests = mutableListOf<Request>()
        val pages = listOf(
            page(model("gemini-2.5-flash", "Gemini Flash", "generateContent"),
                model("gemini-2.5-flash-image", "Image", "generateContent"),
                model("gemini-2.5-computer-use-preview", "Computer use", "generateContent"),
                model("gemini-embedding-001", "Embeddings", "embedContent"),
                model("gemma-3-27b-it", "Gemma", "generateContent"),
                model("gemini-2.5-flash-preview-tts", "Speech", "generateContent"),
                token = "p/+?=& next"),
            page(model("gemini-2.5-pro", "Gemini Pro", "generateContent"),
                model("gemini-2.5-flash", "Duplicate", "generateContent"),
                model("gemini-live-audio", "Live", "generateContent"),
                model("gemini-2.5-transcribe", "Transcribe", "generateContent")),
        )
        val actual = catalog(requests) { 200 to pages[it - 1] }.fetch("test-private-api-key")
        assertEquals(listOf(GeminiCatalogModel("gemini-2.5-flash", "Gemini Flash"), GeminiCatalogModel("gemini-2.5-pro", "Gemini Pro")), actual)
        assertEquals(2, requests.size)
        for (request in requests) {
            assertEquals("https", request.url.scheme)
            assertEquals("generativelanguage.googleapis.com", request.url.host)
            assertEquals("/v1beta/models", request.url.encodedPath)
            assertEquals("1000", request.url.queryParameter("pageSize"))
            assertEquals("test-private-api-key", request.header("x-goog-api-key"))
            assertNull(request.header("Authorization"))
            assertFalse(request.url.toString().contains("test-private-api-key"))
        }
        assertEquals("p/+?=& next", requests[1].url.queryParameter("pageToken"))
    }

    @Test fun missingKeyDoesNotMakeARequest() {
        val requests = mutableListOf<Request>()
        val error = failure { catalog(requests) { 200 to page() }.fetch("   ") }
        assertTrue(error.message!!.contains("Gemini key"))
        assertTrue(requests.isEmpty())
    }

    @Test fun failedLaterPageDoesNotReturnPartialCatalogOrExposeProviderBody() {
        val requests = mutableListOf<Request>()
        val error = failure {
            catalog(requests) {
                if (it == 1) 200 to page(model("gemini-2.5-pro", "Pro", "generateContent"), token = "next")
                else 429 to "private-provider-detail"
            }.fetch("secret-key")
        }
        assertEquals(2, requests.size)
        assertTrue(error.message!!.contains("rate limit"))
        assertFalse(error.message!!.contains("private-provider-detail"))
        assertFalse(error.message!!.contains("secret-key"))
    }

    @Test fun forbiddenAndPaymentErrorsGiveAccessGuidanceWithoutBlamingTheKey() {
        for ((code, expected) in listOf(403 to "access", 402 to "payment")) {
            val requests = mutableListOf<Request>()
            val error = failure { catalog(requests) { code to "provider private body" }.fetch("test-private-api-key") }
            assertTrue(error.message!!.contains(expected, ignoreCase = true))
            assertTrue(error.message!!.contains("billing", ignoreCase = true))
            assertFalse(error.message!!.contains("rejected the API key", ignoreCase = true))
            assertFalse(error.message!!.contains("provider private body"))
        }
        val auth = failure { catalog(mutableListOf()) { 401 to "" }.fetch("test-private-api-key") }
        assertTrue(auth.message!!.contains("rejected the API key"))
    }

    @Test fun rejectsRedirectsCyclesMalformedPagesAndOversizedResponses() {
        for ((code, body) in listOf(302 to page(), 200 to "not json", 200 to "x".repeat(1024 * 1024 + 1))) {
            val requests = mutableListOf<Request>()
            failure { catalog(requests) { code to body }.fetch("private-key") }
            assertEquals(1, requests.size)
        }
        val requests = mutableListOf<Request>()
        failure { catalog(requests) { 200 to page(token = "same") }.fetch("private-key") }
        assertEquals(2, requests.size)
    }

    @Test fun rejectsExcessiveOrKeyContainingPageTokensBeforeSendingThem() {
        for (token in listOf("z".repeat(2049), "next-private-key-token")) {
            val requests = mutableListOf<Request>()
            failure { catalog(requests) { 200 to page(token = token) }.fetch("private-key") }
            assertEquals(1, requests.size)
        }
        val requests = mutableListOf<Request>()
        failure { catalog(requests) { 200 to page(token = "page-$it") }.fetch("private-key") }
        assertEquals(5, requests.size)
    }

    @Test fun invalidResourceNamesCannotBecomeModelIds() {
        val requests = mutableListOf<Request>()
        val badName = model("placeholder", "Bad", "generateContent").put("name", "models/foo/bar")
        val missingPrefix = model("placeholder", "Bad", "generateContent").put("name", "gemini-no-prefix")
        val unnamed = model("gemini-2.5-flash", "", "generateContent")
        val result = catalog(requests) { 200 to page(badName, missingPrefix, unnamed) }.fetch("private-key")
        assertEquals(listOf(GeminiCatalogModel("gemini-2.5-flash", "gemini-2.5-flash")), result)
    }
}
