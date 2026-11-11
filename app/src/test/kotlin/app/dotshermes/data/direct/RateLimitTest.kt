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
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimitTest {
    private class Storage : DirectAccountStorage {
        var transcript = DirectTranscript()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = transcript
        override fun save(accountId: String, transcript: DirectTranscript) { this.transcript = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) {}
    }

    /** 1er appel : quota par minute dépassé (attente 1 s) ; 2e : quota encore (attente 60 s) → passage au modèle agent. */
    private class Client : ProviderClient {
        val usedModels = mutableListOf<String>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            usedModels += model
            when (usedModels.size) {
                1 -> return listOf(ApiToolCall("c1", "remember", "{\"fact\":\"Uses WhatsApp\"}"))
                2 -> throw ApiException(ApiFailure.RateLimited, 1)
            }
            onDelta("Done.")
            return emptyList()
        }
    }

    @Test fun `when a model runs out of quota, the task moves to another Groq model at once`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = Client()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "openai/gpt-oss-120b", scope, storage, client)
            repo.restore(); repo.send("Open WhatsApp")
            withTimeout(10_000) {
                while (storage.transcript.messages.lastOrNull()?.state != DirectEntryState.Complete || repo.activity.value != AgentActivity.Idle) delay(20)
            }
            assertEquals(listOf("openai/gpt-oss-120b", "openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct"), client.usedModels)
            assertEquals("Done.", storage.transcript.messages.last().text)
        } finally { scope.cancel() }
    }

    @Test fun `older tool results are shortened, recent ones and other messages are kept`() {
        val long = JSONObject().put("role", "tool").put("tool_call_id", "c").put("content", "x".repeat(2_000))
        assertTrue(shrinkOldToolResult(long).getString("content").length < 500)
        val short = JSONObject().put("role", "tool").put("content", "ok")
        assertEquals("ok", shrinkOldToolResult(short).getString("content"))
        val assistant = JSONObject().put("role", "assistant").put("content", "y".repeat(2_000))
        assertEquals(2_000, shrinkOldToolResult(assistant).getString("content").length)
    }

    /** Le modèle choisi refuse les outils (400) : la tâche continue avec le modèle « agent ». */
    private class NoToolsClient : ProviderClient {
        val usedModels = mutableListOf<String>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("gemma-no-tools", "meta-llama/llama-4-scout-17b-16e-instruct")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            usedModels += model
            if (model == "gemma-no-tools" && tools != null) throw ApiException(ApiFailure.InvalidRequest)
            onDelta("Timer set.")
            return emptyList()
        }
    }

    private class Memory : DirectTools {
        override fun definitions() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "set_timer")))
        override fun label(call: ApiToolCall) = ToolKeys.of("timer")
        override suspend fun run(call: ApiToolCall) = "{}"
    }

    @Test fun `a model without tools hands the task to one that has them`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = NoToolsClient()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "gemma-no-tools", scope, storage, client, toolbox = { Memory() })
            repo.restore(); repo.send("Set a timer for 5 minutes")
            withTimeout(10_000) {
                while (storage.transcript.messages.lastOrNull()?.state != DirectEntryState.Complete || repo.activity.value != AgentActivity.Idle) delay(20)
            }
            assertEquals(listOf("gemma-no-tools", "meta-llama/llama-4-scout-17b-16e-instruct"), client.usedModels)
            assertEquals("Timer set.", storage.transcript.messages.last().text)
        } finally { scope.cancel() }
    }

    /** Tous les modèles Groq à court de quota : le modèle du téléphone répond. */
    private class AllLimited : ProviderClient {
        val used = mutableListOf<Pair<ApiProvider, String>>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            used += provider to model
            if (provider == ApiProvider.Groq) throw ApiException(ApiFailure.RateLimited, 3600)
            onDelta("Answered offline.")
            return emptyList()
        }
    }

    @Test fun `when every Groq model is out of quota the phone model answers`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = AllLimited()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "openai/gpt-oss-120b", scope, storage, client, localFallback = { "qwen3.5-2b" })
            repo.restore(); repo.send("Hello")
            withTimeout(10_000) {
                while (storage.transcript.messages.lastOrNull()?.state != DirectEntryState.Complete || repo.activity.value != AgentActivity.Idle) delay(20)
            }
            assertEquals(ApiProvider.Local to "qwen3.5-2b", client.used.last())
            assertEquals("Answered offline.", storage.transcript.messages.last().text)
            assertEquals("qwen3.5-2b", storage.transcript.messages.last().model)
        } finally { scope.cancel() }
    }

    /** Chaque appel avec outils est refusé (400 « tool_use_failed ») : autre modèle, puis réponse sans outils. */
    private class BadToolCalls : ProviderClient {
        val used = mutableListOf<Pair<String, Boolean>>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            used += model to (tools != null)
            if (tools != null) throw ApiException(ApiFailure.InvalidRequest)
            onDelta("Here is my answer.")
            return emptyList()
        }
    }

    @Test fun `a refused request tries other models, then answers without tools instead of failing`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = BadToolCalls()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "openai/gpt-oss-120b", scope, storage, client, toolbox = { Memory() })
            repo.restore(); repo.send("What's on my screen?")
            withTimeout(10_000) {
                while (storage.transcript.messages.lastOrNull()?.state != DirectEntryState.Complete || repo.activity.value != AgentActivity.Idle) delay(20)
            }
            assertTrue(client.used.map { it.first }.distinct().size >= 2)
            assertEquals(false, client.used.last().second)
            assertEquals("Here is my answer.", storage.transcript.messages.last().text)
        } finally { scope.cancel() }
    }
}
