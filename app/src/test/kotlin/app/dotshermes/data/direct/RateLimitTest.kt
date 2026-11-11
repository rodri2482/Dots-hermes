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
                3 -> throw ApiException(ApiFailure.RateLimited, 60)
            }
            onDelta("Done.")
            return emptyList()
        }
    }

    @Test fun `mid-task, a per-minute limit waits once, then finishes on the agent model`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = Client()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "openai/gpt-oss-120b", scope, storage, client)
            repo.restore(); repo.send("Open WhatsApp")
            withTimeout(10_000) {
                while (storage.transcript.messages.lastOrNull()?.state != DirectEntryState.Complete || repo.activity.value != AgentActivity.Idle) delay(20)
            }
            assertEquals(listOf("openai/gpt-oss-120b", "openai/gpt-oss-120b", "openai/gpt-oss-120b", "meta-llama/llama-4-scout-17b-16e-instruct"), client.usedModels)
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
}
