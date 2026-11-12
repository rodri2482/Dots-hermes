package app.dotshermes.data.direct

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private class Storage(var transcript: DirectTranscript) : DirectAccountStorage {
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = transcript
        override fun save(accountId: String, transcript: DirectTranscript) { this.transcript = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) {}
    }

    private class Client : ProviderClient {
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit) = emptyList<ApiToolCall>()
    }

    @Test fun `search ignores case and accents, newest first, with the match marked`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(DirectTranscript(messages = listOf(
                DirectEntry("1", "user", "Meet me at the Café Lumière tomorrow", 1_000, DirectEntryState.Complete),
                DirectEntry("2", "assistant", "Sure, the cafe opens at 8.", 2_000, DirectEntryState.Complete, "1"),
                DirectEntry("3", "user", "Thanks!", 3_000, DirectEntryState.Complete),
            )))
            val repo = DirectChatRepository("a", ApiProvider.Groq, "m", scope, storage, Client())
            repo.restore()
            val hits = repo.searchMessages("CAFE")
            assertEquals(listOf("2", "1"), hits.map { it.messageId })
            val first = hits.last()
            assertEquals("Café", first.snippet.substring(first.matchStart, first.matchStart + first.matchLength))
            assertTrue(repo.searchMessages("x").isEmpty())
        } finally { scope.cancel() }
    }
}
