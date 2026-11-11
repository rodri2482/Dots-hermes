package app.dotshermes.data.direct

import app.dotshermes.BuildConfig
import kotlinx.coroutines.delay

/** Fixture explicitement compilée pour les essais hors ligne ; absente du source set release. */
fun createProviderClient(): ProviderClient = if (BuildConfig.DIRECT_API_MOCK) OfflineProviderClient() else HttpProviderClient()

private class OfflineProviderClient : ProviderClient {
    override suspend fun models(provider: ApiProvider, key: String) = listOf("offline-text", "offline-slow", "meta-llama/llama-4-scout-17b-16e-instruct")
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
    // Comme un vrai modèle à outils : « timer » dans le message demande un minuteur de 5 min (confirmé à l'écran).
    override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
        extra: List<org.json.JSONObject>, tools: org.json.JSONArray?,
        onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
        val offersTimer = tools?.toString()?.contains("set_timer") == true
        if (offersTimer && extra.isEmpty() && turns.last().text.contains("timer", ignoreCase = true)) {
            delay(400)
            return listOf(ApiToolCall("offline-timer", "set_timer", "{\"seconds\":300,\"label\":\"Tea\"}"))
        }
        streamCounted(provider, key, model, turns, onLimits, onTokens, onDelta)
        return emptyList()
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
        if (turns.last().image != null) {
            val seen = "[[react:👀]] Nice photo! (offline fixture: model $model got a ${turns.last().image!!.length / 1024} KB image)"
            for (part in seen.chunked(10)) { delay(60); onDelta(part) }
            return
        }
        if (turns.last().text.contains("code", ignoreCase = true)) {
            // Réponse Markdown d'exemple : titre, liste, code coloré, pour voir le rendu.
            val md = """[[react:👀]] ## Sorting a list

Two ways to do it:

- `sorted()` returns a **new** list
- `sort()` changes it *in place*

```kotlin
val names = listOf("Claydot", "Ada")
fun main() {
    println(names.sorted()) // [Ada, Claydot]
}
```

> Tip: prefer `sorted()` for read-only lists."""
            for (part in md.chunked(12)) { delay(60); onDelta(part) }
            return
        }
        val answer = "[[react:$react]] Offline ${provider.title} reply. Your mascot and saved conversation stay on this device, " +
            "and this longer sentence shows how the text unrolls smoothly while it streams in."
        for (part in answer.chunked(6)) {
            delay(if (model == "offline-slow") 1000 else 100)
            onDelta(part)
        }
    }
}
