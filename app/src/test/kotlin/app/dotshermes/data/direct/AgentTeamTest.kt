package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.CardKind
import app.dotshermes.data.model.CardStatus
import app.dotshermes.data.model.DotKind
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.data.model.SubDotDraft
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

/** L'équipe : le dot principal confie le travail à ses sous-dots (chacun son modèle), sans boucle ; carte « Modèle conseillé ». */
class AgentTeamTest {
    /** Un enregistrement par conversation (le fil principal et celui de chaque sous-dot). */
    private class Storage : DirectAccountStorage {
        val records = HashMap<String, DirectTranscript>()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = records[accountId] ?: DirectTranscript()
        override fun save(accountId: String, transcript: DirectTranscript) { records[accountId] = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) { records.remove(accountId) }
    }

    private class Peers : AgentPeers {
        override fun others(self: String) = emptyList<Triple<String, String, String>>()
        override fun facts(self: String) = emptyList<String>()
        override suspend fun search(self: String, query: String) = emptyList<String>()
        override suspend fun ask(self: String, peerId: String, message: String, onConnected: () -> Unit): String? = null
        override fun switchOptions(self: String) = listOf(SwitchOption("agent:g", "gpt-oss-120b", "Groq", strong = true))
        override fun keyFor(provider: ApiProvider) = if (provider == ApiProvider.OpenAI) "openai-fixture-key" else null
    }

    /** Le dot principal confie la question au sous-dot « Researcher », qui répond « Paris » ; on note qui a été appelé. */
    private class Team : ProviderClient {
        val used = mutableListOf<String>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            if (turns.first().text.contains("one of Claydot's sub-dots")) { used += "${provider.id}:$model:$key"; onDelta("Paris"); return emptyList() }
            if (extra.none { it.optString("role") == "tool" })
                return listOf(ApiToolCall("c1", "ask_dot", """{"dot":"Researcher","message":"Capital of France?","mode":"call"}"""))
            onDelta("Researcher says Paris."); return emptyList()
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

    private fun turn(provider: ApiProvider, client: ProviderClient, text: String, dotModel: String? = null,
        check: (Storage, DirectChatRepository) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage()
            val repo = DirectChatRepository("a", provider, "m", scope, storage, client, peers = Peers())
            repo.restore()
            if (dotModel != "none") {
                repo.createDot(SubDotDraft("Researcher", DotKind.Temporary, "find facts", "Nuage", "sky", "WingedCap", model = dotModel))
                repo.selectChat(MAIN_CHAT)
            }
            repo.send(text)
            withTimeout(10_000) {
                while (storage.load("a").messages.none { it.role == "assistant" && it.state == DirectEntryState.Complete && it.card == null } ||
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

    @Test fun `the main dot calls a sub-dot, which answers in its own chat`() {
        val client = Team()
        turn(ApiProvider.Groq, client, "Find the capital of France with your team") { storage, repo ->
            val action = repo.tools.value.single()
            assertEquals(ToolKind.Delegate, action.kind)
            assertEquals(ToolState.Done, action.state)
            assertEquals("Researcher · Capital of France?", action.detail)
            assertEquals("Researcher says Paris.", storage.load("a").messages.last().text)
            val dot = repo.dots.value.single()
            assertEquals(listOf("(From Claydot, the lead dot) Capital of France?", "Paris"), storage.load("a--d${dot.id}").messages.map { it.text })
            assertEquals(listOf("groq:m:fixture-key"), client.used)                   // même modèle que le dot principal
            runBlocking { withTimeout(5_000) { while (AgentCalls.active.value.isNotEmpty()) delay(50) } }
        }
    }

    @Test fun `a sub-dot runs on its own model with that account's key`() {
        val client = Team()
        turn(ApiProvider.Groq, client, "Ask your team", dotModel = "openai:gpt-test") { _, _ ->
            assertEquals(listOf("openai:gpt-test:openai-fixture-key"), client.used)
        }
    }

    @Test fun `a phone model that cannot do the task offers a better model`() {
        turn(ApiProvider.Local, Unable(), "Read my screen", dotModel = "none") { storage, repo ->
            val card = storage.load("a").messages.mapNotNull { it.card }.single()
            assertEquals(CardKind.Switch, card.kind)
            assertEquals("agent:g", card.access)
            repo.reply(card.id, CardReply.Answer(ChatCards.APPROVE))
            assertEquals(CardStatus.Answered, storage.load("a").messages.mapNotNull { it.card }.single().status)
        }
    }

    @Test fun `sub-dots get a name from the key words of their mission`() {
        assertEquals("Trip to Lagos", dotName("I want to plan my trip to Lagos"))
        assertEquals("Wedding speech", dotName("Can you write a wedding speech"))
        assertEquals("Budget", dotName("budget"))
        assertEquals((ApiProvider.Groq to "openai/gpt-oss-120b"), modelSpecOf("groq:openai/gpt-oss-120b"))
        assertEquals(null, modelSpecOf("nope:x"))
    }
}
