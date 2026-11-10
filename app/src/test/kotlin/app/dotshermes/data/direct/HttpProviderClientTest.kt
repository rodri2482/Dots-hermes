package app.dotshermes.data.direct

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class HttpProviderClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HttpProviderClient
    private lateinit var http: OkHttpClient
    private val key = "fixture-key-never-a-real-credential"
    private val completed = "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hello 🌍\"}\n\n" +
        "data: {\"type\":\"response.completed\"}\n\n"

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            // Le réseau du test est uniquement loopback ; aucune requête ne rejoint le fournisseur.
            chain.proceed(request.newBuilder().url(server.url(request.url.encodedPath +
                (request.url.encodedQuery?.let { "?$it" } ?: ""))).build())
        }.build()
        client = HttpProviderClient(http)
    }

    @After fun tearDown() { http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll(); server.shutdown() }

    @Test fun `a failing quota observer cannot erase Retry-After on a 429`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120")
            .setHeader("x-ratelimit-remaining-requests", "999").setBody("{}"))
        val failure = assertThrows(ApiException::class.java) { runBlocking {
            client.streamWithUsage(ApiProvider.Groq, key, "chosen", listOf(ApiTurn("user", "Hi")),
                { throw ApiException(ApiFailure.Storage) }, {})
        } }
        assertEquals(ApiFailure.Storage, failure.failure)
        assertEquals(120L, failure.retryAfterSeconds)
        assertEquals(1, server.requestCount)
    }

    @Test fun `Groq captures limit headers even when the response is 429 and never replays`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "15")
            .setHeader("x-ratelimit-limit-requests", "1000").setHeader("x-ratelimit-remaining-requests", "999")
            .setHeader("x-ratelimit-limit-tokens", "8000").setHeader("x-ratelimit-remaining-tokens", "0")
            .setHeader("x-ratelimit-reset-tokens", "14.5s").setBody("{}"))
        var limits: ProviderLimits? = null
        val failure = assertThrows(ApiException::class.java) { runBlocking {
            client.streamWithUsage(ApiProvider.Groq, key, "chosen", listOf(ApiTurn("user", "Hi")), { limits = it }, {})
        } }
        assertEquals(ApiFailure.RateLimited, failure.failure)
        assertEquals(15L, failure.retryAfterSeconds)
        assertEquals(999L, limits!!.requestsRemaining)
        assertEquals(0L, limits!!.tokensRemaining)
        assertEquals(14_500L, limits!!.tokensResetAtMs!! - limits!!.observedAtMs)
        assertEquals(1, server.requestCount)
    }

    @Test fun `Groq models endpoint stays on its official origin path`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"openai/gpt-oss-20b\"}]}"))
        assertEquals(listOf("openai/gpt-oss-20b"), client.models(ApiProvider.Groq, key))
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/openai/v1/models", request.path)
        assertEquals("Bearer $key", request.getHeader("Authorization"))
    }

    @Test fun `Groq uses official chat completion streaming with bearer auth`() = runBlocking {
        val groq = requireNotNull(ApiProvider.fromId("groq")) { "Groq provider is missing" }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        val chunks = mutableListOf<String>()
        client.stream(groq, key, "chosen", listOf(ApiTurn("user", "Hello")), chunks::add)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/openai/v1/chat/completions", request.path)
        assertEquals("Bearer $key", request.getHeader("Authorization"))
        assertNull(request.getHeader("x-api-key"))
        assertTrue(JSONObject(request.body.readUtf8()).has("messages"))
        assertEquals("Hello", chunks.joinToString(""))
    }

    @Test fun `OpenAI HTTP streams UTF8 even when server chunks split events`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody(completed, 1))
        val chunks = mutableListOf<String>()
        client.stream(ApiProvider.OpenAI, key, "phone-selected-model", listOf(ApiTurn("user", "Hello")), chunks::add)
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer $key", request.getHeader("Authorization"))
        assertNull(request.getHeader("x-api-key"))
        assertFalse(JSONObject(request.body.readUtf8()).getBoolean("store"))
        assertEquals("Hello 🌍", chunks.joinToString(""))
    }

    @Test fun `Anthropic uses its own authentication and version headers`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n" +
                "data: {\"type\":\"message_stop\"}\n\n"))
        client.stream(ApiProvider.Anthropic, key, "phone-selected-model", listOf(ApiTurn("user", "Hello")), {})
        val request = server.takeRequest(2, TimeUnit.SECONDS)!!
        assertEquals("/v1/messages", request.path)
        assertEquals(key, request.getHeader("x-api-key"))
        assertEquals("2026-10-21", request.getHeader("anthropic-version"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test fun `401 error is safe and never includes provider echo of a credential`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"$key\"}}"))
        val failure = assertThrows(ApiException::class.java) {
            runBlocking { client.models(ApiProvider.OpenAI, key) }
        }
        assertEquals(ApiFailure.InvalidKey, failure.failure)
        assertFalse(failure.toString().contains(key))
        assertNull(failure.cause)
    }

    @Test fun `rate limited inference is never automatically replayed`() {
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120").setBody("{}"))
        server.enqueue(MockResponse().setBody(completed))
        val failure = assertThrows(ApiException::class.java) {
            runBlocking { client.stream(ApiProvider.OpenAI, key, "model", listOf(ApiTurn("user", "Hello")), {}) }
        }
        assertEquals(ApiFailure.RateLimited, failure.failure)
        assertEquals(120L, failure.retryAfterSeconds)
        assertEquals(1, server.requestCount)
    }

    @Test fun `redirects cannot forward the key to another destination`() {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", server.url("/steal-key")))
        server.enqueue(MockResponse().setBody("{\"data\":[]}"))
        assertThrows(ApiException::class.java) { runBlocking { client.models(ApiProvider.OpenAI, key) } }
        assertEquals(1, server.requestCount)
    }

    @Test fun `503 with Retry-After zero cannot silently replay paid inference`() {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("{}"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(completed))
        val failure = assertThrows(ApiException::class.java) {
            runBlocking { client.stream(ApiProvider.OpenAI, key, "model", listOf(ApiTurn("user", "Hello")), {}) }
        }
        assertEquals(ApiFailure.Unavailable, failure.failure)
        assertEquals(1, server.requestCount)
    }

    @Test fun `model listing reads provider IDs and follows Anthropic pagination`() = runBlocking {
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"model-a\"}],\"has_more\":true,\"last_id\":\"model-a\"}"))
        server.enqueue(MockResponse().setBody("{\"data\":[{\"id\":\"model-b\"}],\"has_more\":false}"))
        assertEquals(listOf("model-a", "model-b"), client.models(ApiProvider.Anthropic, key))
        server.takeRequest()
        assertEquals("/v1/models?limit=100&after_id=model-a", server.takeRequest().path)
    }

    @Test fun `Stop cancels an outstanding HTTP request`() = runBlocking {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val request = async { client.stream(ApiProvider.OpenAI, key, "model", listOf(ApiTurn("user", "Hello")), {}) }
        val received = withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) }
        assertNotNull(received)
        withTimeout(2_000) { request.cancelAndJoin() }
        assertTrue(request.isCancelled)
    }
}
