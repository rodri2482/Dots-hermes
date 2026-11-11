package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.MessageContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class ConversationsTest {
    /** Un dossier chiffré par identifiant, comme le vrai coffre. */
    private class Vault : DirectAccountStorage {
        val records = mutableMapOf<String, DirectTranscript>()
        override fun key(accountId: String) = "fixture-key"
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = records[accountId] ?: DirectTranscript()
        override fun save(accountId: String, transcript: DirectTranscript) { records[accountId] = transcript }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) { records.remove(accountId) }
    }
    private class Echo : ProviderClient {
        override suspend fun models(provider: ApiProvider, key: String) = listOf("m")
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) =
            onDelta("re: " + turns.last().text)
    }

    private suspend fun DirectChatRepository.say(text: String) {
        send(text)
        // On attend l'écho complet plutôt qu'un passage d'activité : une réponse instantanée le ferait manquer.
        withTimeout(5000) {
            while (texts().lastOrNull() != "re: $text" || activity.value != AgentActivity.Idle) kotlinx.coroutines.delay(10)
        }
    }
    private fun DirectChatRepository.texts() = messages.value.map { (it.content as MessageContent.Text).text }

    @Test fun `chats are separate, survive a restart, and can be deleted`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val vault = Vault()
            val repo = DirectChatRepository("api-acc", ApiProvider.Groq, "m", scope, vault, Echo())
            repo.restore(); repo.say("first chat")
            repo.newConversation()
            assertTrue(repo.messages.value.isEmpty())
            repo.say("second chat")
            assertEquals(listOf("second chat", "re: second chat"), repo.texts())
            val list = repo.conversations.value
            assertEquals(2, list.size)
            assertEquals("second chat", list.first().title)
            assertTrue(list.first().open)

            // Redémarrage : la dernière discussion ouverte revient, l'autre reste dans la liste.
            val again = DirectChatRepository("api-acc", ApiProvider.Groq, "m", scope, vault, Echo())
            again.restore()
            assertEquals(listOf("second chat", "re: second chat"), again.texts())
            val main = again.conversations.value.first { it.title == "first chat" }
            again.openConversation(main.id)
            assertEquals(listOf("first chat", "re: first chat"), again.texts())

            val second = again.conversations.value.first { it.title == "second chat" }.id
            again.deleteConversation(second)
            assertEquals(1, again.conversations.value.size)
            assertFalse(vault.records.containsKey(second))
        } finally { scope.cancel() }
    }
}
