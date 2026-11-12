package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Appui long → Modifier : le message envoyé et sa réponse sont remplacés par le nouveau texte et une nouvelle réponse. */
class EditMessageTest {
    private class Storage : DirectAccountStorage {
        var transcript = DirectTranscript()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = transcript
        override fun save(accountId: String, transcript: DirectTranscript) { this.transcript = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) {}
    }

    /** Répond « re: » suivi du dernier message de l'utilisateur. */
    private class Echo : ProviderClient {
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            onDelta("re: " + turns.last { it.role == "user" }.text); return emptyList()
        }
    }

    @Test fun `editing a sent message replaces it and its answer`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "m", scope, storage, Echo())
            repo.restore()
            suspend fun settle(count: Int) = withTimeout(10_000) {
                while (storage.transcript.messages.size < count || storage.transcript.messages.last().state != DirectEntryState.Complete ||
                    repo.activity.value != AgentActivity.Idle) delay(20)
            }
            repo.send("first"); settle(2)
            repo.send("helo"); settle(4)
            val typo = storage.transcript.messages[2].id
            repo.editMessage(typo, "hello")
            delay(50); settle(4)
            assertEquals(listOf("first", "re: first", "hello", "re: hello"), storage.transcript.messages.map { it.text })
        } finally { scope.cancel() }
    }
}
