package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.DotKind
import app.dotshermes.data.model.MAIN_CHAT
import app.dotshermes.data.model.SubDotDraft
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

/** Sous-dots et salon de groupe des comptes directs (fournisseur simulé). */
class SubDotsTest {
    private class Storage : DirectAccountStorage {
        val records = HashMap<String, DirectTranscript>()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = records[accountId] ?: DirectTranscript()
        override fun save(accountId: String, transcript: DirectTranscript) { records[accountId] = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) { records.remove(accountId) }
    }

    /** Répond par le nom de qui parle (trouvé dans la consigne) ; garde chaque consigne vue. */
    private class Echo : ProviderClient {
        val systems = mutableListOf<String>()
        val users = mutableListOf<String>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            val system = turns.firstOrNull { it.role == "system" }?.text.orEmpty()
            systems += system; users += turns.last().text
            val who = Regex("You are (\\w+), a sub-dot invited").find(system)?.groupValues?.get(1)
            onDelta(if (who != null) "$who thinks so." else "Conclusion.")
            return emptyList()
        }
    }

    private fun draft(name: String, mission: String) = SubDotDraft(name, DotKind.Permanent, mission, "galet", "tangerine", "none")

    private suspend fun idle(repo: DirectChatRepository) = withTimeout(10_000) {
        delay(50)
        while (repo.activity.value != AgentActivity.Idle) delay(20)
    }

    @Test fun `a sub-dot has its own conversation and specialty`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = Echo()
            val repo = DirectChatRepository("acc", ApiProvider.Groq, "m", scope, storage, client, persona = { "You are Claydot." })
            repo.restore()
            repo.send("Hello main"); idle(repo)
            repo.createDot(draft("Ledger", "my accountant: invoices and taxes"))
            val dot = repo.dots.value.single()
            assertEquals(dot.id, repo.activeChat.value)
            assertTrue(repo.messages.value.isEmpty())
            repo.send("Hello ledger"); idle(repo)
            assertTrue(client.systems.last().contains("Your specialty: my accountant: invoices and taxes"))
            assertTrue(storage.records.containsKey("acc--d${dot.id}"))
            repo.selectChat(MAIN_CHAT)
            assertEquals(listOf("Hello main", "Conclusion."), repo.messages.value.map { (it.content as app.dotshermes.data.model.MessageContent.Text).text })
            // Les sous-dots restent après un redémarrage ; leur conversation n'entre pas dans la liste des discussions.
            val again = DirectChatRepository("acc", ApiProvider.Groq, "m", scope, storage, client)
            again.restore()
            assertEquals(listOf("Ledger"), again.dots.value.map { it.name })
            assertTrue(again.conversations.value.none { it.id.contains("--d") })
            again.deleteDot(dot.id)
            assertTrue(again.dots.value.isEmpty())
            assertTrue(!storage.records.containsKey("acc--d${dot.id}"))
        } finally { scope.cancel() }
    }

    @Test fun `guests debate for two rounds, then the conversation's dot concludes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = Echo()
            val repo = DirectChatRepository("acc", ApiProvider.Groq, "m", scope, storage, client)
            repo.restore()
            repo.createDot(draft("Ada", "engineering")); repo.createDot(draft("Bea", "budget"))
            repo.selectChat(MAIN_CHAT)
            val ids = repo.dots.value.map { it.id }
            repo.setRoom(ids + "not-a-dot")
            assertEquals(ids, repo.room.value)
            repo.send("Should I buy a new laptop?"); idle(repo)
            val said = repo.messages.value.filter { it.sender == app.dotshermes.data.model.Sender.Agent }
            assertEquals(listOf("Ada", "Bea", "Ada", "Bea", null), said.map { it.speakerName })
            assertEquals("Conclusion.", (said.last().content as app.dotshermes.data.model.MessageContent.Text).text)
            assertTrue(client.users.last().contains("Ada: Ada thinks so."))
            assertEquals(null, repo.speaker.value)
        } finally { scope.cancel() }
    }
}
