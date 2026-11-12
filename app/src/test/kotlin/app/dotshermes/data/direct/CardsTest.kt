package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.CardKind
import app.dotshermes.data.model.CardStatus
import app.dotshermes.data.model.ChatCard
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.StringReader

/** Cartes du fil (questions, accords, accès) et réponse de secours ; fournisseurs simulés, aucune vraie clé. */
class CardsTest {
    private class Storage : DirectAccountStorage {
        var transcript = DirectTranscript()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = transcript
        override fun save(accountId: String, transcript: DirectTranscript) { this.transcript = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) {}
    }

    /** 1er tour : l'outil [tool] ; ensuite, la réponse reprend le résultat de l'outil. */
    private class ToolOnce(private val tool: String, private val args: JSONObject) : ProviderClient {
        val results = mutableListOf<String>()
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            if (extra.isEmpty()) return listOf(ApiToolCall("c1", tool, args.toString()))
            results += extra.last().optString("content")
            onDelta("Got it.")
            return emptyList()
        }
    }

    /** Une action qui ne s'exécute qu'avec l'accès vraiment donné (comme envoyer un SMS). */
    private class Guarded(var granted: Boolean = false, private val waitMs: Long = 400) : DirectTools {
        var executed = false
        override fun definitions() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "do_it")))
        override fun label(call: ApiToolCall) = ToolKeys.of("phone")
        override suspend fun run(call: ApiToolCall): String {
            val card = ChatCard(ChatCards.newId(), CardKind.Access, "Contacts", access = PhoneAccess.Contacts.name, custom = false)
            if (!ChatCards.askAccess(card, waitMs) { granted }) return """{"status":"not_granted"}"""
            executed = true
            return """{"status":"done"}"""
        }
    }

    private fun cards() = CardTools(requestAccess = { _, _ -> false }, gmailConnected = { false })

    private suspend fun openCard(storage: Storage): ChatCard = withTimeout(10_000) {
        while (true) {
            storage.transcript.messages.mapNotNull { it.card }.firstOrNull { it.open }?.let { return@withTimeout it }
            delay(20)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private suspend fun finished(storage: Storage, repo: DirectChatRepository) = withTimeout(10_000) {
        while (storage.transcript.messages.lastOrNull()?.state != DirectEntryState.Complete || repo.activity.value != AgentActivity.Idle) delay(20)
    }

    private fun cardIn(storage: Storage, id: String) = storage.transcript.messages.first { it.card?.id == id }.card!!

    private fun withRepo(client: ProviderClient, tools: DirectTools, test: suspend (DirectChatRepository, Storage) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "m", scope, storage, client, toolbox = { tools })
            repo.restore()
            test(repo, storage)
        } finally { scope.cancel() }
    }

    private val question = JSONObject().put("question", "Which group?").put("options", JSONArray(listOf("Team", "Family")))

    @Test fun `a single choice answer goes back to the waiting task`() {
        val client = ToolOnce("ask_user", question)
        withRepo(client, cards()) { repo, storage ->
            repo.send("Post the attendance")
            val card = openCard(storage)
            assertEquals(listOf("Team", "Family"), card.options)
            assertEquals(AgentActivity.Working(ToolKeys.of("ask")), repo.activity.value)
            repo.replyCard(card.id, CardReply.Answer("Family"))
            finished(storage, repo)
            assertTrue(client.results.single().contains("\"answer\":\"Family\""))
            assertEquals(CardStatus.Answered, cardIn(storage, card.id).status)
            assertEquals("Got it.", storage.transcript.messages.last().text)
        }
    }

    @Test fun `several choices and a typed answer are sent together`() {
        val client = ToolOnce("ask_user", JSONObject(question.toString()).put("multi_select", true))
        withRepo(client, cards()) { repo, storage ->
            repo.send("Who should I tell?")
            val card = openCard(storage)
            assertTrue(card.multi)
            repo.replyCard(card.id, CardReply.Answer("Team, Family, my cousin"))
            finished(storage, repo)
            assertEquals("Team, Family, my cousin", cardIn(storage, card.id).answer)
        }
    }

    @Test fun `typing in the composer answers the open question`() {
        val client = ToolOnce("ask_user", question)
        withRepo(client, cards()) { repo, storage ->
            repo.send("Post the attendance")
            val card = openCard(storage)
            repo.send("The football group")
            finished(storage, repo)
            assertEquals("The football group", cardIn(storage, card.id).answer)
            // Pas de nouveau message utilisateur : la réponse est dans la carte.
            assertEquals(1, storage.transcript.messages.count { it.role == "user" })
        }
    }

    @Test fun `not now tells the task the user declined`() {
        val client = ToolOnce("ask_user", question)
        withRepo(client, cards()) { repo, storage ->
            repo.send("Post the attendance")
            val card = openCard(storage)
            repo.replyCard(card.id, CardReply.Decline)
            finished(storage, repo)
            assertTrue(client.results.single().contains("declined"))
            assertEquals(CardStatus.Declined, cardIn(storage, card.id).status)
        }
    }

    @Test fun `a second tap on the same card changes nothing`() {
        val client = ToolOnce("ask_user", question)
        withRepo(client, cards()) { repo, storage ->
            repo.send("Post the attendance")
            val card = openCard(storage)
            repo.replyCard(card.id, CardReply.Answer("Team"))
            repo.replyCard(card.id, CardReply.Answer("Family"))
            repo.replyCard(card.id, CardReply.Decline)
            finished(storage, repo)
            assertEquals(1, client.results.size)
            assertEquals("Team", cardIn(storage, card.id).answer)
        }
    }

    @Test fun `a confirmation only counts as approved with the approve answer`() {
        val client = ToolOnce("do_it", JSONObject())
        val confirm = object : DirectTools {
            var approved: Boolean? = null
            override fun definitions() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "do_it")))
            override fun label(call: ApiToolCall) = ToolKeys.of("alarm")
            override suspend fun run(call: ApiToolCall): String {
                val reply = ChatCards.host!!.ask(ChatCard(ChatCards.newId(), CardKind.Confirm, "Set an alarm", custom = false), 10_000)
                approved = reply?.status == CardStatus.Answered && reply.answer == ChatCards.APPROVE
                return "{}"
            }
        }
        withRepo(client, confirm) { repo, storage ->
            repo.send("Wake me at 7")
            val card = openCard(storage)
            repo.replyCard(card.id, CardReply.Answer("yes please"))   // pas une réponse valable pour un accord
            repo.replyCard(card.id, CardReply.Continue)               // « Continuer » n'existe que pour un accès
            assertTrue(cardIn(storage, card.id).open)
            repo.replyCard(card.id, CardReply.Answer(ChatCards.APPROVE))
            finished(storage, repo)
            assertEquals(true, confirm.approved)
        }
    }

    @Test fun `nothing runs before access is really given`() {
        val tool = Guarded(granted = false)
        withRepo(ToolOnce("do_it", JSONObject()), tool) { repo, storage ->
            repo.send("Text Mum")
            val card = openCard(storage)
            repo.replyCard(card.id, CardReply.Continue)
            assertEquals(CardStatus.Waiting, cardIn(storage, card.id).status)
            finished(storage, repo)     // l'accès n'arrive jamais : la vérification s'arrête
            assertFalse(tool.executed)
            assertEquals(CardStatus.Declined, cardIn(storage, card.id).status)
        }
    }

    @Test fun `access given in Android settings resumes the task`() {
        val tool = Guarded(granted = false, waitMs = 10_000)
        withRepo(ToolOnce("do_it", JSONObject()), tool) { repo, storage ->
            repo.send("Text Mum")
            val card = openCard(storage)
            repo.replyCard(card.id, CardReply.Continue)
            delay(300)
            assertFalse(tool.executed)
            tool.granted = true          // retour des réglages, accès vraiment donné
            finished(storage, repo)
            assertTrue(tool.executed)
            assertEquals(CardStatus.Answered, cardIn(storage, card.id).status)
        }
    }

    @Test fun `cancelling while waiting for access stops the task without running it`() {
        val tool = Guarded(granted = false, waitMs = 10_000)
        withRepo(ToolOnce("do_it", JSONObject()), tool) { repo, storage ->
            repo.send("Text Mum")
            val card = openCard(storage)
            repo.replyCard(card.id, CardReply.Continue)
            delay(200)
            repo.replyCard(card.id, CardReply.Decline)
            finished(storage, repo)
            assertFalse(tool.executed)
        }
    }

    @Test fun `stopping the task expires its card and a late tap does nothing`() {
        val client = ToolOnce("ask_user", question)
        withRepo(client, cards()) { repo, storage ->
            repo.send("Post the attendance")
            val card = openCard(storage)
            repo.stopTurn()
            assertEquals(CardStatus.Expired, cardIn(storage, card.id).status)
            repo.replyCard(card.id, CardReply.Answer("Team"))
            delay(100)
            assertEquals(CardStatus.Expired, cardIn(storage, card.id).status)
            assertTrue(client.results.isEmpty())
        }
    }

    @Test fun `an open card saved before a restart comes back expired and never reopens`() {
        val stored = DirectEncodedCard.roundTrip(ChatCard("card-abc", CardKind.Access, "Screen", access = "Screen", status = CardStatus.Waiting))
        assertEquals(CardStatus.Expired, stored?.status)
        val answered = DirectEncodedCard.roundTrip(ChatCard("card-def", CardKind.Question, "Which?", options = listOf("A"), status = CardStatus.Answered, answer = "A"))
        assertEquals("A", answered?.answer)
        assertNull(cardOf(JSONObject().put("id", "../bad").put("kind", "Question").put("question", "x").put("status", "Pending")))
    }

    @Test fun `a card from the model is kept short`() {
        val long = ChatCard("card-x", CardKind.Question, "q".repeat(900), options = List(12) { "option $it" + "x".repeat(200) }).bounded()
        assertEquals(ChatCard.MAX_QUESTION, long.question.length)
        assertEquals(ChatCard.MAX_OPTIONS, long.options.size)
        assertTrue(long.options.all { it.length <= ChatCard.MAX_OPTION })
    }

    @Test fun `only listed accesses can be requested`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val asked = mutableListOf<PhoneAccess>()
            val tools = CardTools(requestAccess = { a, _ -> asked += a; false }, gmailConnected = { false })
            ChatCards.host = object : CardHost {
                override suspend fun ask(card: ChatCard, timeoutMs: Long): ChatCard? = null
                override fun update(id: String, status: CardStatus, answer: String?) {}
                override fun card(id: String): ChatCard? = null
                override fun reply(id: String, reply: CardReply) {}
            }
            val out = tools.run(ApiToolCall("x", "request_access", JSONObject().put("access", "android.permission.CAMERA").toString()))
            assertTrue(out.contains("unknown access"))
            assertTrue(asked.isEmpty())
            tools.run(ApiToolCall("y", "request_access", JSONObject().put("access", "contacts").toString()))
            assertEquals(listOf(PhoneAccess.Contacts), asked)
        } finally { ChatCards.host = null; scope.cancel() }
    }

    // --- « Le fournisseur n'a pas pu terminer » ----------------------------------------------------------

    /** Recherche web, puis chaque appel avec outils est refusé ; sans outils, la réponse arrive. */
    private class FailsAfterSearch : ProviderClient {
        var plainTurn: String? = null
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m", "meta-llama/llama-4-scout-17b-16e-instruct")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) = error("unused")
        override suspend fun streamTools(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>,
            extra: List<JSONObject>, tools: JSONArray?, onLimits: (ProviderLimits) -> Unit, onTokens: (Long) -> Unit, onDelta: (String) -> Unit): List<ApiToolCall> {
            if (tools == null && extra.isEmpty()) { plainTurn = turns.last().text; onDelta("GTA VI comes out on the date found."); return emptyList() }
            if (extra.isEmpty()) return listOf(ApiToolCall("s1", "search_web", """{"query":"GTA VI release date"}"""))
            onDelta("partial ")
            throw ApiException(ApiFailure.InvalidResponse)
        }
    }

    private class Web : DirectTools {
        override fun definitions() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "search_web")))
        override fun label(call: ApiToolCall) = ToolKeys.of("web")
        override suspend fun run(call: ApiToolCall) = """{"results":[{"title":"GTA VI release date","snippet":"Rockstar date"}]}"""
    }

    @Test fun `a failed task still answers with what the search found`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = FailsAfterSearch()
            val repo = DirectChatRepository("a", ApiProvider.Groq, "m", scope, storage, client, toolbox = { Web() })
            repo.restore(); repo.send("When is GTA VI releasing?")
            finished(storage, repo)
            assertEquals("GTA VI comes out on the date found.", storage.transcript.messages.last().text)
            assertTrue(client.plainTurn!!.contains("Rockstar date"))
            assertNull(repo.requestIssue.value)
        } finally { scope.cancel() }
    }

    @Test fun `Groq tool errors inside the stream are retried instead of failing`() {
        val failure = org.junit.Assert.assertThrows(ApiException::class.java) {
            ProviderProtocol.consume(ApiProvider.Groq, StringReader(
                "data: {\"error\":{\"message\":\"x\",\"type\":\"invalid_request_error\",\"code\":\"tool_use_failed\"}}\n\n"), {})
        }
        assertEquals(ApiFailure.InvalidRequest, failure.failure)
    }

    @Test fun `an unknown finish reason no longer breaks a reply`() {
        val text = StringBuilder()
        ProviderProtocol.consume(ApiProvider.Groq, StringReader(
            "data: {\"choices\":[{\"delta\":{\"content\":\"Hi\"},\"finish_reason\":\"function_call\"}]}\n\ndata: [DONE]\n\n")) { text.append(it) }
        assertEquals("Hi", text.toString())
    }

    @Test fun `old tool names reach the web search`() = runBlocking {
        val seen = mutableListOf<String>()
        val combined = CombinedTools(listOf(object : DirectTools {
            override fun definitions() = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject().put("name", "search_web")))
            override fun label(call: ApiToolCall) = ""
            override suspend fun run(call: ApiToolCall): String { seen += call.name; return "{}" }
        }))
        combined.run(ApiToolCall("b", "brave_search", "{}"))
        assertEquals(listOf("search_web"), seen)
    }
}

/** Passe une carte par l'enregistrement chiffré (JSON) et la relit, comme au redémarrage. */
private object DirectEncodedCard {
    fun roundTrip(card: ChatCard): ChatCard? = cardOf(JSONObject(cardJson(card).toString()))
}
