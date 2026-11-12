package app.dotshermes.data.direct

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Modèle de transcription de Groq : rapide et multilingue (le même que le serveur Hermes, côté STT). */
const val GROQ_TRANSCRIPTION_MODEL = "whisper-large-v3-turbo"

/** Une seule tentative HTTP par action utilisateur ; annuler la coroutine ferme la vraie socket. */
class HttpProviderClient(client: OkHttpClient = OkHttpClient()) : ProviderClient {
    private val http = client.newBuilder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(65, TimeUnit.SECONDS).callTimeout(180, TimeUnit.SECONDS)
        .addNetworkInterceptor { chain ->
            val response = chain.proceed(chain.request())
            // OkHttp peut rejouer un 503/Retry-After:0 même sans retryOnConnectionFailure.
            // Sortir avant son mécanisme de follow-up : une inférence exige une nouvelle action.
            if (response.code == 503) {
                val wait = waitSeconds(response)
                response.close()
                throw ApiException(ApiFailure.Unavailable, wait)
            }
            response
        }
        .build()

    override suspend fun models(provider: ApiProvider, key: String): List<String> {
        val models = linkedSetOf<String>()
        val cursors = HashSet<String>()
        var after: String? = null
        repeat(20) {
            val url = "${provider.origin}/models".toHttpUrl().newBuilder().apply {
                    if (provider == ApiProvider.Anthropic) {
                        addQueryParameter("limit", "100")
                        after?.let { addQueryParameter("after_id", it) }
                    }
                }.build()
            val page = exchange(request(provider, key, url.toString()).build()) { response ->
                try { JSONObject(body(response)) } catch (_: JSONException) { throw ApiException(ApiFailure.InvalidResponse) }
            }
            val data = page.optJSONArray("data") ?: throw ApiException(ApiFailure.InvalidResponse)
            for (index in 0 until data.length()) {
                val id = data.optJSONObject(index)?.optString("id").orEmpty()
                if (id.isNotBlank() && id.length <= 256) models.add(id)
            }
            if (provider != ApiProvider.Anthropic || !page.optBoolean("has_more")) return models.toList()
            after = page.optString("last_id").takeIf { it.isNotBlank() && cursors.add(it) }
                ?: throw ApiException(ApiFailure.InvalidResponse)
        }
        throw ApiException(ApiFailure.InvalidResponse)
    }

    override suspend fun transcribe(provider: ApiProvider, key: String, audio: java.io.File): String {
        if (provider != ApiProvider.Groq) throw ApiException(ApiFailure.InvalidRequest)
        // Groq refuse au-delà de 25 Mo ; un vocal de 5 min en AAC en fait environ 2.
        if (!audio.isFile || audio.length() == 0L || audio.length() > 25L * 1024 * 1024) throw ApiException(ApiFailure.InvalidRequest)
        val form = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", GROQ_TRANSCRIPTION_MODEL)
            .addFormDataPart("response_format", "json")
            .addFormDataPart("file", "voice.m4a", audio.asRequestBody("audio/mp4".toMediaType()))
            .build()
        val reply = exchange(request(provider, key, "${provider.origin}/audio/transcriptions").post(form).build()) { response ->
            try { JSONObject(body(response)) } catch (_: JSONException) { throw ApiException(ApiFailure.InvalidResponse) }
        }
        return reply.optString("text").trim()
    }

    override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) {
        streamWithUsage(provider, key, model, turns, {}, onDelta)
    }

    override suspend fun streamWithUsage(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        onLimits: (ProviderLimits) -> Unit, onDelta: (String) -> Unit) = streamCounted(provider, key, model, turns, onLimits, {}, onDelta)

    override suspend fun streamCounted(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit) {
        streamTools(provider, key, model, turns, emptyList(), null, onLimits, onTokens, onDelta)
    }

    override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        extra: List<JSONObject>, tools: org.json.JSONArray?,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
        val path = when (provider) {
            ApiProvider.OpenAI -> "chat/completions"
            ApiProvider.Anthropic -> "messages"
            ApiProvider.Groq -> "chat/completions"
            ApiProvider.Local -> throw ApiException(ApiFailure.InvalidRequest)
        }
        val request = request(provider, key, "${provider.origin}/$path")
            .header("Accept", "text/event-stream")
            .post(ProviderProtocol.requestBody(provider, model, turns, extra, tools).toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return exchange(request, { response ->
            if (provider == ApiProvider.Groq) groqLimits(response::header, System.currentTimeMillis())?.let(onLimits)
        }) { response ->
            val body = response.body ?: throw ApiException(ApiFailure.InvalidResponse)
            ProviderProtocol.consumeCounted(provider, body.charStream(), onTokens, onDelta)
        }
    }

    private fun request(provider: ApiProvider, key: String, url: String): Request.Builder {
        if (key.isBlank()) throw ApiException(ApiFailure.MissingKey)
        if (key.any { it <= ' ' || it >= '\u007f' }) throw ApiException(ApiFailure.InvalidKey)
        return Request.Builder().url(url).apply {
            if (provider != ApiProvider.Anthropic) header("Authorization", "Bearer $key")
            else header("x-api-key", key).header("anthropic-version", "2026-10-21")
        }
    }

    private suspend fun <T> exchange(request: Request, observe: (Response) -> Unit = {}, decode: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(if (e is ApiException) e else ApiException(ApiFailure.Network))
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!continuation.isActive) return
                    val now = System.currentTimeMillis()
                    val reportedUntil = groqLimits(response::header, now)?.exhaustedUntilMs() ?: 0
                    val reportedWait = maxOf(waitSeconds(response), cooldownSeconds(reportedUntil, now))
                    val knownWait = if (response.code == 429 && reportedWait == 0L) 60 else reportedWait
                    try {
                        if (!response.isSuccessful) {
                            val errorBody = try { body(response) } catch (_: Exception) { "" }
                            val failure = ProviderProtocol.httpFailure(response.code, errorBody, knownWait)
                            try { observe(response) } catch (_: Exception) { throw ApiException(ApiFailure.Storage, knownWait) }
                            throw failure
                        }
                        try { observe(response) } catch (_: Exception) { throw ApiException(ApiFailure.Storage, knownWait) }
                        val result = decode(response)
                        if (continuation.isActive) continuation.resume(result)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(when (e) {
                            is ApiException -> e
                            is IOException -> ApiException(ApiFailure.Network)
                            else -> ApiException(ApiFailure.InvalidResponse)
                        })
                    }
                }
            }
        })
    }

    private fun body(response: Response): String {
        val stream = response.body?.byteStream() ?: throw ApiException(ApiFailure.InvalidResponse)
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) break
            if (bytes.size() + count > 1_048_576) throw ApiException(ApiFailure.InvalidResponse)
            bytes.write(buffer, 0, count)
        }
        return bytes.toString("UTF-8")
    }

    private fun waitSeconds(response: Response): Long {
        val delay = retryDelayMs(0, response.header("Retry-After"), System.currentTimeMillis())
        return delay / 1_000 + if (delay % 1_000 != 0L) 1 else 0
    }
}
