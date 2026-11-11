package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader

class DirectToolsTest {
    @Test fun `groq streamed tool call is assembled from fragments`() {
        val stream = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"search_gmail\",\"arguments\":\"{\\\"query\\\":\"}}]}}]}\n\n" +
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"\\\"is:unread\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}\n\n" +
            "data: [DONE]\n\n"
        val calls = ProviderProtocol.consumeCounted(ApiProvider.Groq, StringReader(stream), {}) {}
        assertEquals(listOf(ApiToolCall("call_1", "search_gmail", "{\"query\":\"is:unread\"}")), calls)
    }

    @Test fun `tools and tool exchanges go into the groq request only`() {
        val tools = JSONArray().put(JSONObject().put("type", "function"))
        val extra = ProviderProtocol.toolExchange(listOf(ApiToolCall("c", "read_gmail", "{}")), listOf("{\"message\":{}}"))
        val groq = JSONObject(ProviderProtocol.requestBody(ApiProvider.Groq, "m", listOf(ApiTurn("user", "Hi")), extra, tools))
        val messages = groq.getJSONArray("messages")
        assertEquals("tool", messages.getJSONObject(messages.length() - 1).getString("role"))
        assertEquals("c", messages.getJSONObject(1).getJSONArray("tool_calls").getJSONObject(0).getString("id"))
        assertEquals(1, groq.getJSONArray("tools").length())
        val claude = JSONObject(ProviderProtocol.requestBody(ApiProvider.Anthropic, "m", listOf(ApiTurn("user", "Hi")), extra, tools))
        assertFalse(claude.has("tools"))
        assertEquals(1, claude.getJSONArray("messages").length())
    }

    private class Storage : DirectAccountStorage {
        var transcript = DirectTranscript()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = transcript
        override fun save(accountId: String, transcript: DirectTranscript) { this.transcript = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) {}
        var saved = emptyList<String>()
        override fun facts(accountId: String) = saved
        override fun saveFacts(accountId: String, facts: List<String>) { saved = facts }
    }

    /** Le modèle retient un fait, puis le message suivant le retrouve dans le tour « system ». */
    private class MemoryClient : ProviderClient {
        val systems = mutableListOf<String>()
        val offered = mutableListOf<String>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            if (extra.isEmpty()) {
                systems += turns.firstOrNull { it.role == "system" }?.text.orEmpty()
                tools?.let { t -> (0 until t.length()).forEach { offered += t.getJSONObject(it).getJSONObject("function").getString("name") } }
                if (turns.last().text.startsWith("Remember")) return listOf(ApiToolCall("m1", "remember", "{\"fact\":\"Has a cat   named Miso\"}"))
            }
            onDelta("Noted.")
            return emptyList()
        }
    }

    /** Premier tour : le modèle demande une recherche ; second tour : il répond avec le résultat. */
    private class ToolClient : ProviderClient {
        val extras = mutableListOf<Int>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            extras += extra.size
            onTokens(10)
            if (extra.isEmpty()) return listOf(ApiToolCall("c1", "search_gmail", "{\"query\":\"from:bank\"}"))
            onDelta("You have 1 email from the bank.")
            return emptyList()
        }
    }

    private class FakeGmail : DirectTools {
        val ran = mutableListOf<String>()
        override fun definitions() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "search_gmail")))
        override fun label(call: ApiToolCall) = "Gmail…"
        override suspend fun run(call: ApiToolCall): String { ran += call.name; return "{\"messages\":[]}" }
    }

    @Test fun `a tool round runs the tool, then the answer streams and tokens add up`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = ToolClient(); val gmail = FakeGmail()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "m", scope, storage, client, toolbox = { gmail })
            repo.restore(); repo.send("Any mail from the bank?")
            awaitReply(repo, storage, 2)
            assertEquals(listOf("search_gmail"), gmail.ran)
            assertEquals("second round carries the call and its result", listOf(0, 2), client.extras)
            val answer = storage.transcript.messages.last()
            assertEquals("You have 1 email from the bank.", answer.text)
            assertEquals(DirectEntryState.Complete, answer.state)
            assertEquals(20L, answer.tokens)
        } finally { scope.cancel() }
    }

    /** Attend la réponse complète n° [messages] (sans course : on lit l'état, pas un passage d'activité). */
    private suspend fun awaitReply(repo: DirectChatRepository, storage: Storage, messages: Int) = withTimeout(5000) {
        while (storage.transcript.messages.size < messages || storage.transcript.messages.last().state != DirectEntryState.Complete ||
            repo.activity.value != AgentActivity.Idle) kotlinx.coroutines.delay(10)
    }

    @Test fun `remembered facts reach the next request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = MemoryClient()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "m", scope, storage, client, persona = { "You are Claydot." })
            repo.restore()
            repo.send("Remember my cat is Miso")
            awaitReply(repo, storage, 2)
            assertEquals(listOf("Has a cat named Miso"), storage.saved)
            assertEquals(listOf("Has a cat named Miso"), repo.facts.value)
            repo.send("What is my cat called?")
            awaitReply(repo, storage, 4)
            assertTrue("memory offered as a tool", "remember" in client.offered)
            assertTrue(client.systems.last(), client.systems.last().contains("- Has a cat named Miso"))
        } finally { scope.cancel() }
    }
}
