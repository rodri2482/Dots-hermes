package app.dotshermes.data.direct

import app.dotshermes.BuildConfig
import kotlinx.coroutines.delay

/** Fixture explicitement compilée pour les essais hors ligne ; absente du source set release. */
fun createProviderClient(): ProviderClient = if (BuildConfig.DIRECT_API_MOCK) OfflineProviderClient() else HttpProviderClient()

private class OfflineProviderClient : ProviderClient {
    override suspend fun models(provider: ApiProvider, key: String) = listOf("offline-text", "offline-slow")
    override suspend fun transcribe(provider: ApiProvider, key: String, audio: java.io.File): String {
        delay(600)
        return "Offline voice note (${audio.length()} bytes)"
    }
    override suspend fun streamWithUsage(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        onLimits: (ProviderLimits) -> Unit, onDelta: (String) -> Unit) {
        if (provider == ApiProvider.Groq) {
            val now = System.currentTimeMillis()
            val limited = turns.last().text == "offline quota"
            onLimits(ProviderLimits(now, 1000, 999, now + 86_400_000, 8000,
                if (limited) 0 else 7900, now + if (limited) 15_000 else 60_000))
            if (limited) throw ApiException(ApiFailure.RateLimited, 15)
        }
        stream(provider, key, model, turns, onDelta)
    }
    override suspend fun streamCounted(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit) {
        streamWithUsage(provider, key, model, turns, onLimits, onDelta)
        onTokens(120L + turns.sumOf { it.text.length } / 4)
    }
    override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) {
        if (key == "invalid-fixture") throw ApiException(ApiFailure.InvalidKey)
        if (turns.last().text == "offline error") throw ApiException(ApiFailure.Unavailable)
        // Comme un vrai modèle : une balise de réaction en tête (👍, ou 🎉 si le message parle de bonne nouvelle).
        val react = if (turns.last().text.contains("news", ignoreCase = true)) "🎉" else "👍"
        val answer = "[[react:$react]] Offline ${provider.title} reply. Your mascot and saved conversation stay on this device, " +
            "and this longer sentence shows how the text unrolls smoothly while it streams in."
        for (part in answer.chunked(6)) {
            delay(if (model == "offline-slow") 1000 else 100)
            onDelta(part)
        }
    }
}
