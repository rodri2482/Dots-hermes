package app.dotshermes.data.direct

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.Reader

/** Formats HTTP/SSE officiels, séparés du réseau et sans jamais manipuler la clé d'API. */
internal object ProviderProtocol {
    /**
     * [extra] : échanges d'outils du tour en cours (message assistant avec `tool_calls`, puis résultats),
     * ajoutés après le dernier message ; [tools] : définitions proposées au modèle. Groq seulement.
     */
    fun requestBody(provider: ApiProvider, model: String, turns: List<ApiTurn>,
        extra: List<JSONObject> = emptyList(), tools: JSONArray? = null): String {
        if (model.isBlank() || turns.isEmpty() || turns.last().role != "user") throw ApiException(ApiFailure.InvalidRequest)
        val system = turns.filter { it.role == "system" }.joinToString("\n\n") { it.text }.trim()
        val messages = JSONArray().apply {
            if (system.isNotEmpty() && provider != ApiProvider.Anthropic) put(JSONObject().put("role", "system").put("content", system))
            turns.filter { it.role != "system" }.forEach { turn ->
                val content: Any = if (turn.image != null && provider.chatFormat) JSONArray()
                    .put(JSONObject().put("type", "text").put("text", turn.text))
                    .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", turn.image)))
                else turn.text
                put(JSONObject().put("role", turn.role).put("content", content))
            }
            if (provider.chatFormat) extra.forEach { put(it) }
        }
        val body = JSONObject().put("model", model.trim()).put("stream", true)
        // Groq (format OpenAI) : un dernier fragment donne le total de jetons de la réponse.
        if (provider.chatFormat) body.put("stream_options", JSONObject().put("include_usage", true))
        if (provider.chatFormat && tools != null && tools.length() > 0) body.put("tools", tools).put("tool_choice", "auto")
        if (system.isNotEmpty() && provider == ApiProvider.Anthropic) body.put("system", system)
        if (provider == ApiProvider.OpenAI) body.put("store", false)
        body.put("messages", messages).put(if (provider.chatFormat) "max_completion_tokens" else "max_tokens", 4096)
        return body.toString()
    }

    /** Le message « assistant » qui rejoue les appels d'outils, puis un résultat par appel. */
    fun toolExchange(calls: List<ApiToolCall>, results: List<String>): List<JSONObject> {
        val assistant = JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("tool_calls", JSONArray().apply {
            calls.forEach { c -> put(JSONObject().put("id", c.id).put("type", "function")
                .put("function", JSONObject().put("name", c.name).put("arguments", c.arguments))) }
        })
        return listOf(assistant) + calls.zip(results).map { (c, r) ->
            JSONObject().put("role", "tool").put("tool_call_id", c.id).put("content", r)
        }
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

    fun consume(provider: ApiProvider, reader: Reader, onDelta: (String) -> Unit) { consumeCounted(provider, reader, {}, onDelta) }

    /** Comme [consume], et [onTokens] reçoit le total de jetons (entrée + sortie) annoncé par le fournisseur. */
    fun consumeCounted(provider: ApiProvider, reader: Reader, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
        // Appels d'outils Groq : le nom et les arguments arrivent par morceaux, rangés par index.
        val calls = sortedMapOf<Int, Triple<StringBuilder, StringBuilder, StringBuilder>>()
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
                if (provider.chatFormat) completed = true
                return
            }
            if (data.isBlank()) return
            val frame = try { JSONObject(data) } catch (_: JSONException) { throw ApiException(ApiFailure.InvalidResponse) }
            val type = frame.optString("type")
            if (type == "error" || type == "response.failed" || frame.has("error")) {
                val error = frame.optJSONObject("error") ?: frame.optJSONObject("response")?.optJSONObject("error") ?: frame
                val code = error.optString("code").ifEmpty { error.optString("type") }
                // Groq signale dans le flux (statut 200) un appel d'outil mal écrit ou vers un outil inconnu
                // (« tool_use_failed », Llama qui appelle « brave_search ») : c'est une requête à refaire, pas une réponse illisible.
                val kind = error.optString("type")
                throw ApiException(when {
                    code == "rate_limit_error" || code == "rate_limit_exceeded" -> ApiFailure.RateLimited
                    code == "insufficient_quota" || code == "credit_balance_too_low" -> ApiFailure.Quota
                    code == "authentication_error" || code == "invalid_api_key" -> ApiFailure.InvalidKey
                    code == "overloaded_error" || code == "server_error" -> ApiFailure.Unavailable
                    code == "context_length_exceeded" -> ApiFailure.ContextLimit
                    code == "tool_use_failed" || code == "invalid_request_error" || kind == "invalid_request_error" -> ApiFailure.InvalidRequest
                    else -> ApiFailure.InvalidResponse
                })
            }
            if (provider.chatFormat) {
                (frame.optJSONObject("usage") ?: frame.optJSONObject("x_groq")?.optJSONObject("usage"))
                    ?.let { tokensIn = it.optLong("total_tokens") }
                val choice = frame.optJSONArray("choices")?.optJSONObject(0)
                choice?.optJSONObject("delta")?.let { delta ->
                    if (!delta.isNull("content")) text(delta.optString("content"))
                    delta.optJSONArray("tool_calls")?.let { parts ->
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val slot = calls.getOrPut(part.optInt("index", i)) { Triple(StringBuilder(), StringBuilder(), StringBuilder()) }
                            part.optString("id").takeIf { it.isNotEmpty() }?.let { slot.first.setLength(0); slot.first.append(it) }
                            part.optJSONObject("function")?.let { f ->
                                if (!f.isNull("name")) slot.second.append(f.optString("name"))
                                if (!f.isNull("arguments")) slot.third.append(f.optString("arguments"))
                            }
                            if (slot.third.length > 65_536) throw ApiException(ApiFailure.InvalidResponse)
                        }
                    }
                }
                // « stop », « tool_calls », « function_call »… : seule une réponse coupée par la limite compte ici.
                if (choice?.optString("finish_reason") == "length") limited = true
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
        val tools = calls.values.mapNotNull { (id, name, args) ->
            ApiToolCall(id.toString().ifEmpty { "call_${name}" }, name.toString(), args.toString()).takeIf { it.name.isNotBlank() }
        }
        if (!textSeen && tools.isEmpty()) throw ApiException(ApiFailure.InvalidResponse)
        (tokensIn + tokensOut).takeIf { it > 0 }?.let(onTokens)
        return tools
    }
}
