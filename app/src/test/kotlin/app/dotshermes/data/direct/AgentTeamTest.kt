package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.CardKind
import app.dotshermes.data.model.CardStatus
import app.dotshermes.data.model.ToolKind
import app.dotshermes.data.model.ToolState
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Agents ensemble : échanges sans boucle, appel visible, liste d'actions, carte « Modèle conseillé ». */
class AgentTeamTest {
    private class Storage : DirectAccountStorage {
        var transcript = DirectTranscript()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = transcript
        override fun save(accountId: String, transcript: DirectTranscript) { this.transcript = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) {}
    }

    private class Peers : AgentPeers {
        var connected = 0
        var asked = mutableListOf<String>()
        override fun others(self: String) = listOf(Triple("g", "Groq", "gpt-oss-120b"))
        override fun facts(self: String) = emptyList<String>()
        override suspend fun search(self: String, query: String) = emptyList<String>()
        override suspend fun ask(self: String, peerId: String, message: String, onConnected: () -> Unit): String? {
            asked += message; onConnected(); connected++
            assertEquals(1, AgentCalls.active.value.size)
            return "Paris"
        }
        override fun switchOptions(self: String) = listOf(SwitchOption("agent:g", "gpt-oss-120b", "Groq", strong = true))
    }

    /** Premier tour : demande à l'agent Groq ; ensuite, répond. */
    private class Delegator : ProviderClient {
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            if (extra.none { it.optString("role") == "tool" })
                return listOf(ApiToolCall("c1", "ask_agent", """{"agent":"Groq","message":"Capital of France?","mode":"call"}"""))
            onDelta("Groq says Paris."); return emptyList()
        }
    }

    /** Un petit modèle qui avoue ne pas pouvoir. */
    private class Unable : ProviderClient {
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            onDelta("Sorry, I can't read your screen."); return emptyList()
        }
    }

    private fun turn(provider: ApiProvider, client: ProviderClient, peers: Peers, text: String, check: (Storage, DirectChatRepository) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage()
            val repo = DirectChatRepository("a", provider, "m", scope, storage, client, peers = peers)
            repo.restore(); repo.send(text)
            withTimeout(10_000) {
                while (storage.transcript.messages.none { it.role == "assistant" && it.state == DirectEntryState.Complete && it.card == null } ||
                    repo.activity.value != AgentActivity.Idle) delay(20)
            }
            check(storage, repo)
        } finally { scope.cancel() }
    }

    @Test fun `agents cannot loop but one agent can ask several at once`() {
        assertTrue(AgentTalk.start("A", "B"))
        assertFalse(AgentTalk.start("B", "A"))      // B répondait à A : pas de retour vers A
        assertTrue(AgentTalk.start("A", "C"))       // A interroge aussi C, en parallèle
        assertFalse(AgentTalk.start("D", "B"))      // B est déjà occupé
        AgentTalk.end("A", "B"); AgentTalk.end("A", "C")
        assertTrue(AgentTalk.start("B", "A")); AgentTalk.end("B", "A")
    }

    @Test fun `a call to another agent shows in the header and in the action list`() {
        val peers = Peers()
        turn(ApiProvider.Groq, Delegator(), peers, "Ask Groq the capital of France") { storage, repo ->
            assertEquals(listOf("Capital of France?"), peers.asked)
            assertEquals(1, peers.connected)
            // L'appel reste affiché un instant, puis il est raccroché.
            runBlocking { withTimeout(5_000) { while (AgentCalls.active.value.isNotEmpty()) delay(50) } }
            val action = repo.tools.value.single()
            assertEquals(ToolKind.Delegate, action.kind)
            assertEquals(ToolState.Done, action.state)
            assertEquals("Groq · Capital of France?", action.detail)
            assertEquals("Groq says Paris.", storage.transcript.messages.last().text)
        }
    }

    @Test fun `a phone model that cannot do the task offers a better model`() {
        turn(ApiProvider.Local, Unable(), Peers(), "Read my screen") { storage, repo ->
            val card = storage.transcript.messages.mapNotNull { it.card }.single()
            assertEquals(CardKind.Switch, card.kind)
            assertEquals("agent:g", card.access)
            repo.reply(card.id, CardReply.Answer(ChatCards.APPROVE))
            assertEquals(CardStatus.Answered, storage.transcript.messages.mapNotNull { it.card }.single().status)
        }
    }
}
