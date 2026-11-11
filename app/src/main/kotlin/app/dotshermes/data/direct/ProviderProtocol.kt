package app.dotshermes.data.direct

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Reader

/** Formats HTTP/SSE officiels, séparés du réseau et sans jamais manipuler la clé d'API. */
internal object ProviderProtocol {
    fun requestBody(provider: ApiProvider, model: String, turns: List<ApiTurn>): String {
        if (model.isBlank() || turns.isEmpty() || turns.last().role != "user") throw ApiException(ApiFailure.InvalidRequest)
        val system = turns.filter { it.role == "system" }.joinToString("\n\n") { it.text }.trim()
        val messages = JSONArray().apply {
            if (system.isNotEmpty() && provider != ApiProvider.Anthropic) put(JSONObject().put("role", "system").put("content", system))
            turns.filter { it.role != "system" }.forEach { put(JSONObject().put("role", it.role).put("content", it.text)) }
        }
        val body = JSONObject().put("model", model.trim()).put("stream", true)
        // Groq (format OpenAI) : un dernier fragment donne le total de jetons de la réponse.
        if (provider == ApiProvider.Groq) body.put("stream_options", JSONObject().put("include_usage", true))
        if (system.isNotEmpty() && provider == ApiProvider.Anthropic) body.put("system", system)
        if (provider == ApiProvider.OpenAI) {
            body.put("input", messages).put("store", false).put("max_output_tokens", 4096)
        } else {
            body.put("messages", messages).put(if (provider == ApiProvider.Groq) "max_completion_tokens" else "max_tokens", 4096)
        }
        return body.toString()
    }

    fun httpFailure(status: Int, body: String, waitSeconds: Long = 0): ApiException {
        val code = try {
            val error = JSONObject(body).optJSONObject("error")
            error?.optString("code").orEmpty().ifEmpty { error?.optString("type").orEmpty() }
        } catch (_: JSONException) { "" }
        val failure = when {
            code in setOf("insufficient_quota", "billing_hard_limit_reached", "credit_balance_too_low") -> ApiFailure.Quota
            status == 401 -> ApiFailure.InvalidKey
            status == 403 -> ApiFailure.Forbidden
            status == 404 -> ApiFailure.ModelUnavailable
            status == 413 || code == "context_length_exceeded" -> ApiFailure.ContextLimit
            status == 429 -> ApiFailure.RateLimited
            status >= 500 -> ApiFailure.Unavailable
            else -> ApiFailure.InvalidRequest
        }
        return ApiException(failure, waitSeconds)
    }

    fun consume(provider: ApiProvider, reader: Reader, onDelta: (String) -> Unit) = consumeCounted(provider, reader, {}, onDelta)

    /** Comme [consume], et [onTokens] reçoit le total de jetons (entrée + sortie) annoncé par le fournisseur. */
    fun consumeCounted(provider: ApiProvider, reader: Reader, onTokens: (Long) -> Unit, onDelta: (String) -> Unit) {
        var tokensIn = 0L
        var tokensOut = 0L
        var completed = false
        var limited = false
        var textSeen = false
        var totalChars = 0
        fun text(value: String) {
            if (value.isEmpty()) return
            totalChars += value.length
            if (totalChars > 1_000_000) throw ApiException(ApiFailure.OutputLimit)
            textSeen = true
            onDelta(value)
        }
        fun event(data: String) {
            if (data == "[DONE]") {
                if (provider == ApiProvider.Groq) completed = true
                return
            }
            if (data.isBlank()) return
            val frame = try { JSONObject(data) } catch (_: JSONException) { throw ApiException(ApiFailure.InvalidResponse) }
            val type = frame.optString("type")
            if (type == "error" || type == "response.failed" || frame.has("error")) {
                val error = frame.optJSONObject("error") ?: frame.optJSONObject("response")?.optJSONObject("error") ?: frame
                val code = error.optString("code").ifEmpty { error.optString("type") }
                throw ApiException(when (code) {
                    "rate_limit_error", "rate_limit_exceeded" -> ApiFailure.RateLimited
                    "insufficient_quota", "credit_balance_too_low" -> ApiFailure.Quota
                    "authentication_error", "invalid_api_key" -> ApiFailure.InvalidKey
                    "overloaded_error", "server_error" -> ApiFailure.Unavailable
                    else -> ApiFailure.InvalidResponse
                })
            }
            if (provider == ApiProvider.OpenAI) {
                when (type) {
                    "response.output_text.delta", "response.refusal.delta" -> text(frame.optString("delta"))
                    "response.completed" -> {
                        frame.optJSONObject("response")?.optJSONObject("usage")?.let { tokensIn = it.optLong("total_tokens") }
                        completed = true
                    }
                    "response.incomplete" -> throw ApiException(ApiFailure.OutputLimit)
                }
            } else if (provider == ApiProvider.Groq) {
                (frame.optJSONObject("usage") ?: frame.optJSONObject("x_groq")?.optJSONObject("usage"))
                    ?.let { tokensIn = it.optLong("total_tokens") }
                val choice = frame.optJSONArray("choices")?.optJSONObject(0)
                choice?.optJSONObject("delta")?.let { text(it.optString("content")) }
                when (choice?.optString("finish_reason")) {
                    "length" -> limited = true
                    "stop" -> Unit
                    null, "", "null" -> Unit
                    else -> throw ApiException(ApiFailure.InvalidResponse)
                }
            } else {
                when (type) {
                    "content_block_start" -> frame.optJSONObject("content_block")?.let {
                        if (it.optString("type") == "text") text(it.optString("text"))
                    }
                    "content_block_delta" -> frame.optJSONObject("delta")?.let {
                        if (it.optString("type") == "text_delta") text(it.optString("text"))
                    }
                    "message_start" -> frame.optJSONObject("message")?.optJSONObject("usage")?.let {
                        tokensIn = it.optLong("input_tokens") + it.optLong("cache_read_input_tokens") + it.optLong("cache_creation_input_tokens")
                    }
                    "message_delta" -> {
                        frame.optJSONObject("usage")?.let { tokensOut = it.optLong("output_tokens") }
                        limited = frame.optJSONObject("delta")?.optString("stop_reason") == "max_tokens"
                    }
                    "message_stop" -> completed = true
                }
            }
        }
        val data = StringBuilder()
        val lines = reader.buffered()
        while (!completed) {
            val line = lines.readLine() ?: break
            if (line.isEmpty()) {
                event(data.toString())
                data.setLength(0)
            } else if (line.startsWith("data:")) {
                if (data.isNotEmpty()) data.append('\n')
                data.append(line.substring(5).removePrefix(" "))
                if (data.length > 1_048_576) throw ApiException(ApiFailure.InvalidResponse)
            }
        }
        if (!completed && data.isNotEmpty()) event(data.toString())
        if (limited) throw ApiException(ApiFailure.OutputLimit)
        if (!completed) throw ApiException(ApiFailure.Interrupted)
        if (!textSeen) throw ApiException(ApiFailure.InvalidResponse)
        (tokensIn + tokensOut).takeIf { it > 0 }?.let(onTokens)
    }
}
