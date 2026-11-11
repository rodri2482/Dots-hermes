package app.dotshermes.data.direct

import app.dotshermes.data.model.AgentActivity
import app.dotshermes.data.model.MessageContent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

class DirectChatRepositoryTest {
    private class Storage : DirectAccountStorage {
        var transcript = DirectTranscript()
        var failSave = false
        var failRateLimitSave = false
        var credential = "fixture-key"
        override fun key(accountId: String) = credential
        override fun saveKey(accountId: String, key: String) {}
        override fun load(accountId: String) = transcript
        override fun save(accountId: String, transcript: DirectTranscript) {
            if (failSave || (failRateLimitSave && transcript.failure == ApiFailure.RateLimited)) throw ApiException(ApiFailure.Storage)
            this.transcript = transcript
        }
        override fun removeKey(accountId: String) {}
        override fun remove(accountId: String) {}
    }
    private class Client : ProviderClient {
        val calls = mutableListOf<List<ApiTurn>>()
        var action: suspend ((String) -> Unit) -> Unit = { it("Hello") }
        override suspend fun models(provider: ApiProvider, key: String) = emptyList<String>() // aucun autre modèle : le quota reste celui du modèle choisi
        override suspend fun stream(provider: ApiProvider, key: String, model: String, turns: List<ApiTurn>, onDelta: (String) -> Unit) {
            calls.add(turns)
            action(onDelta)
        }
    }
    private suspend fun idle(repo: DirectChatRepository) = withTimeout(5000) { repo.activity.first { it == AgentActivity.Idle } }

    @Test fun `quota belongs to the credential and model that received it`() = runBlocking {
        for (changeKey in listOf(false, true)) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val storage = Storage()
                val client = Client().apply { action = { throw ApiException(ApiFailure.RateLimited, 120) } }
                val first = DirectChatRepository("account", ApiProvider.Groq, "model-a", scope, storage, client)
                first.restore(); first.send("Hi"); idle(first)
                if (changeKey) storage.credential = "different-fixture-organization"
                val second = DirectChatRepository("account", ApiProvider.Groq, if (changeKey) "model-a" else "model-b", scope, storage, client)
                second.restore()
                assertEquals("Changed credential/model must not inherit another quota", 0L, second.directUsage.value.cooldownUntilMs)
                assertNull(second.directUsage.value.limits)
                assertEquals(2, second.messages.value.size)
                client.action = { it("Hello") }; second.send("New configuration"); idle(second)
                assertEquals(2, client.calls.size)
            } finally { scope.cancel() }
        }
    }

    @Test fun `a failed cooldown save blocks inference until safe restoration`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage()
            val client = Client().apply { action = {
                storage.failRateLimitSave = true
                throw ApiException(ApiFailure.RateLimited, 120)
            } }
            val repo = DirectChatRepository("account", ApiProvider.Groq, "chosen", scope, storage, client)
            repo.restore(); repo.send("Hi"); idle(repo)
            assertEquals(ApiFailure.Storage, repo.failure.value!!.failure)
            assertTrue(repo.directUsage.value.cooldownUntilMs > System.currentTimeMillis())
            storage.failRateLimitSave = false
            repo.send("Must remain blocked"); delay(100)
            assertEquals(1, client.calls.size)
            // Même remplacement que le registre lors de l'enregistrement du formulaire de récupération.
            val inherited = repo.cooldownFor(ApiProvider.Groq, "chosen", "fixture-key")
            assertEquals(0L, repo.cooldownFor(ApiProvider.Groq, "other-model", "fixture-key"))
            assertEquals(0L, repo.cooldownFor(ApiProvider.Groq, "chosen", "different-key"))
            val replacement = DirectChatRepository("account", ApiProvider.Groq, "chosen", scope, storage, client,
                inheritedCooldownUntilMs = inherited)
            replacement.restore(); replacement.send("Still cooling down"); delay(100)
            assertEquals(1, client.calls.size)
            assertTrue(storage.transcript.cooldownUntilMs > System.currentTimeMillis())
        } finally { scope.cancel() }
    }

    @Test fun `cooldown expiry waits for explicit retry and keeps saved usage`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val clock = java.util.concurrent.atomic.AtomicLong(1000)
        try {
            val storage = Storage().apply { transcript = DirectTranscript(limits = ProviderLimits(900, 1000, 999), quotaScope = quotaScope(ApiProvider.Groq, "chosen", "fixture-key")) }
            val client = Client().apply { action = { throw ApiException(ApiFailure.RateLimited, 2) } }
            val repo = DirectChatRepository("account", ApiProvider.Groq, "chosen", scope, storage, client, clock::get)
            repo.restore(); repo.send("Hi"); idle(repo)
            assertEquals(3000L, repo.directUsage.value.cooldownUntilMs)
            clock.set(3000); delay(100)
            assertEquals(1, client.calls.size)
            client.action = { it("Hello") }; repo.retry(); idle(repo)
            assertEquals(2, client.calls.size)
            assertNull(repo.failure.value)
            assertEquals(999L, repo.directUsage.value.limits!!.requestsRemaining)
            assertEquals(0L, storage.transcript.cooldownUntilMs)
        } finally { scope.cancel() }
    }

    @Test fun `cooldown blocks fresh prompts and survives repository restore`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage()
            val client = Client().apply { action = { throw ApiException(ApiFailure.RateLimited, 120) } }
            val repo = DirectChatRepository("account", ApiProvider.OpenAI, "chosen", scope, storage, client)
            repo.restore(); repo.send("Hi"); idle(repo)
            assertEquals(1, client.calls.size)
            repo.send("Another prompt"); delay(150)
            assertEquals("New sends must respect the same cooldown as Retry", 1, client.calls.size)
            val restored = DirectChatRepository("account", ApiProvider.OpenAI, "chosen", scope, storage, client)
            restored.restore(); restored.retry(); restored.send("After restart"); delay(150)
            assertEquals("Restart must not clear a provider cooldown", 1, client.calls.size)
        } finally { scope.cancel() }
    }

    @Test fun `streamed replies are persisted and restored without starting another inference`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = Client()
            val repo = DirectChatRepository("account", ApiProvider.OpenAI, "chosen", scope, storage, client)
            repo.restore(); repo.send("Hi"); idle(repo)
            assertEquals(2, repo.messages.value.size)
            assertEquals("Hello", (repo.messages.value.last().content as MessageContent.Text).text)
            assertNull(repo.failure.value)
            val restored = DirectChatRepository("account", ApiProvider.OpenAI, "chosen", scope, storage, client)
            restored.restore()
            assertEquals(repo.messages.value, restored.messages.value)
            assertEquals(1, client.calls.size)
        } finally { scope.cancel() }
    }

    @Test fun `explicit retry replaces the failed answer without duplicating the user turn`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = Client().apply { action = { throw ApiException(ApiFailure.Network) } }
            val repo = DirectChatRepository("account", ApiProvider.Anthropic, "chosen", scope, storage, client)
            repo.restore(); repo.send("Hi"); idle(repo)
            assertEquals(1, client.calls.size)
            assertEquals(ApiFailure.Network, repo.failure.value!!.failure)
            client.action = { it("Recovered") }
            repo.retry(); idle(repo)
            assertEquals(2, repo.messages.value.size)
            assertEquals(listOf(ApiTurn("user", "Hi")), client.calls.last())
            assertNull(storage.transcript.retryUserId)
        } finally { scope.cancel() }
    }

    @Test fun `Stop keeps partial output and ignores callbacks from the cancelled request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ready = CompletableDeferred<Unit>(); var late: ((String) -> Unit)? = null
            val storage = Storage(); val client = Client().apply { action = { delta ->
                late = delta; delta("Partial"); ready.complete(Unit); awaitCancellation()
            } }
            val repo = DirectChatRepository("account", ApiProvider.OpenAI, "chosen", scope, storage, client)
            repo.restore(); repo.send("Hi"); withTimeout(5000) { ready.await() }
            repo.stopTurn(); late!!(" WRONG ACCOUNT OUTPUT")
            assertEquals("Partial", (repo.messages.value.last().content as MessageContent.Text).text)
            assertEquals(DirectEntryState.Stopped, storage.transcript.messages.last().state)
            assertEquals(ApiFailure.Cancelled, repo.failure.value!!.failure)
        } finally { scope.cancel() }
    }

    @Test fun `a failed initial save blocks inference instead of spending against unsaved history`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage(); val client = Client()
            val repo = DirectChatRepository("account", ApiProvider.OpenAI, "chosen", scope, storage, client)
            repo.restore(); storage.failSave = true; repo.send("Hi"); idle(repo)
            assertEquals(0, client.calls.size)
            assertEquals(ApiFailure.Storage, repo.failure.value!!.failure)
            assertTrue(storage.transcript.messages.isEmpty())
        } finally { scope.cancel() }
    }
    @Test fun `a stopped prompt is not replayed with the next request`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val ready = CompletableDeferred<Unit>()
            val storage = Storage(); val client = Client().apply { action = { delta ->
                delta("Partial"); ready.complete(Unit); awaitCancellation()
            } }
            val repo = DirectChatRepository("account", ApiProvider.OpenAI, "chosen", scope, storage, client)
            repo.restore(); repo.send("Abandoned private prompt"); withTimeout(5000) { ready.await() }
            repo.stopTurn()
            client.action = { it("New answer") }
            repo.send("New prompt"); idle(repo)
            assertEquals(listOf(ApiTurn("user", "New prompt")), client.calls.last())
        } finally { scope.cancel() }
    }

    @Test fun `restored interrupted prompt is excluded while completed pairs remain in context`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val storage = Storage().apply { transcript = DirectTranscript(listOf(
                DirectEntry("u1", "user", "Complete question", 1, DirectEntryState.Complete),
                DirectEntry("a1", "assistant", "Complete answer", 2, DirectEntryState.Complete, "u1"),
                DirectEntry("u2", "user", "Interrupted question", 3, DirectEntryState.Complete),
                DirectEntry("a2", "assistant", "Partial answer", 4, DirectEntryState.Streaming, "u2"),
            ), "u2") }
            val client = Client()
            val repo = DirectChatRepository("account", ApiProvider.OpenAI, "chosen", scope, storage, client)
            repo.restore(); repo.send("New question"); idle(repo)
            assertEquals(listOf(ApiTurn("user", "Complete question"), ApiTurn("assistant", "Complete answer"), ApiTurn("user", "New question")), client.calls.single())
        } finally { scope.cancel() }
    }

}
