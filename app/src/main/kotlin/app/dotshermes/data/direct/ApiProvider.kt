package app.dotshermes.data.direct

import java.io.IOException

/** Origines fixes : une clé saisie ici ne peut jamais être envoyée à un proxy choisi par un tiers. */
enum class ApiProvider(val id: String, val title: String, val origin: String) {
    OpenAI("openai", "OpenAI", "https://api.openai.com/v1"),
    Anthropic("anthropic", "Claude", "https://api.anthropic.com/v1"),
    Groq("groq", "Groq", "https://api.groq.com/openai/v1");

    companion object {
        fun fromId(id: String): ApiProvider? = entries.firstOrNull { it.id == id }
    }
}

/** [image] : photo jointe à ce message, en « data URL » JPEG (modèles de vision Groq). */
data class ApiTurn(val role: String, val text: String, val image: String? = null) {
    // « system » : la personnalité de l'agent, toujours en tête de conversation.
    init { require(role == "user" || role == "assistant" || role == "system") }
}

enum class ApiFailure {
    MissingKey, InvalidKey, Forbidden, ModelUnavailable, InvalidRequest, Quota, RateLimited,
    Unavailable, Network, Interrupted, InvalidResponse, OutputLimit, ContextLimit, Storage, Cancelled,
}

/** Pas de message du fournisseur, corps HTTP, clé, URL ou cause brute dans une erreur d'interface. */
class ApiException(val failure: ApiFailure, val retryAfterSeconds: Long = 0) : IOException(failure.name)

interface ProviderClient {
    suspend fun models(provider: ApiProvider, key: String): List<String>
    suspend fun streamWithUsage(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        onLimits: (ProviderLimits) -> Unit, onDelta: (String) -> Unit) = stream(provider, key, model, turns, onDelta)
    suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit)
    /** [streamWithUsage] qui rapporte aussi les jetons consommés ; par défaut, aucun compte. */
    suspend fun streamCounted(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit) =
        streamWithUsage(provider, key, model, turns, onLimits, onDelta)
    /** [streamCounted] avec des outils : renvoie les appels demandés par le modèle (vide : réponse finie). */
    suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        extra: List<org.json.JSONObject>, tools: org.json.JSONArray?,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
        streamCounted(provider, key, model, turns, onLimits, onTokens, onDelta)
        return emptyList()
    }
    /** Vocal → texte. Seul Groq (Whisper) le propose ici ; une seule requête par vocal envoyé. */
    suspend fun transcribe(provider: ApiProvider, key: String, audio: java.io.File): String = throw ApiException(ApiFailure.InvalidRequest)
}
