package app.dotshermes.data.direct

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class EncryptedAccountStorageTest {
    @Test fun `a new store restores private turns and retry state without exposing credentials`() {
        val root = Files.createTempDirectory("claydot-account-").toFile()
        val keyMap = mutableMapOf<String, SecretKey>()
        val keys = object : RecordKeys {
            override fun get(accountId: String, create: Boolean): SecretKey? = keyMap[accountId]
                ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keyMap[accountId] = it } else null
            override fun remove(accountId: String) { keyMap.remove(accountId) }
        }
        try {
            val records = EncryptedRecords(root, keys)
            val store = EncryptedAccountStorage(records)
            store.saveKey("account-a", "fixture-private-key")
            val history = DirectTranscript(listOf(
                DirectEntry("u1", "user", "private prompt", 1000, DirectEntryState.Complete),
                DirectEntry("a1", "assistant", "partial reply", 1001, DirectEntryState.Failed, "u1",
                    teamReport = TeamProgress(stage = TeamStage.Done, verification = TeamVerification.NeedsReview,
                        sources = listOf(TeamSource("Retrieved page", "https://example.com/evidence", true)), checker = "Missing a source")),
            ), "u1", ApiFailure.Network, ProviderLimits(1000, 1000, 999, 2000, 8000, 0, 2000), 2000, quotaScope(ApiProvider.Groq, "chosen", "fixture-key"))
            store.save("account-a", history)
            val restored = EncryptedAccountStorage(EncryptedRecords(root, keys))
            assertEquals(history, restored.load("account-a"))
            assertEquals("fixture-private-key", restored.key("account-a"))
            assertEquals(DirectTranscript(), restored.load("account-b"))
            assertNull(restored.key("account-b"))
            root.walkTopDown().filter { it.isFile }.forEach {
                val bytes = it.readBytes().toString(Charsets.ISO_8859_1)
                assertFalse(bytes.contains("fixture-private-key"))
                assertFalse(bytes.contains("private prompt"))
            }
            restored.removeKey("account-a")
            assertNull(restored.key("account-a"))
            assertEquals(history, restored.load("account-a"))
            restored.saveKey("account-a", "replacement-fixture")
            assertEquals("replacement-fixture", restored.key("account-a"))
            records.write("account-a", AccountRecord.History,
                "{\"version\":1,\"messages\":[],\"retry_user_id\":null,\"failure\":null}".toByteArray())
            assertEquals(DirectTranscript(), restored.load("account-a"))
            // Une version inconnue ne devient jamais silencieusement une conversation vide.
            records.write("account-a", AccountRecord.History, "{\"version\":999,\"messages\":[]}".toByteArray())
            val error = assertThrows(ApiException::class.java) { restored.load("account-a") }
            assertEquals(ApiFailure.Storage, error.failure)
            restored.remove("account-a")
            assertNull(restored.key("account-a"))
            assertEquals(DirectTranscript(), restored.load("account-a"))
        } finally {
            check(root.canonicalFile.parentFile == java.io.File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile && root.name.startsWith("claydot-account-"))
            root.deleteRecursively()
        }
    }

    @Test fun `invalid roles cannot enter provider conversation context`() {
        assertThrows(IllegalArgumentException::class.java) {
            DirectEntry("entry", "system", "untrusted role", 1, DirectEntryState.Complete)
        }
    }

    /** Mise à jour depuis 0.3.0.8 : ses conversations n'ont pas de « team_report » ; un rapport abîmé n'efface rien. */
    @Test fun `history saved by 0_3_0_8 and damaged team reports still load`() {
        val root = Files.createTempDirectory("claydot-account-").toFile()
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val keys = object : RecordKeys {
            override fun get(accountId: String, create: Boolean): SecretKey? = key
            override fun remove(accountId: String) {}
        }
        try {
            val records = EncryptedRecords(root, keys)
            val store = EncryptedAccountStorage(records)
            // Enregistrement au format exact de 0.3.0.8 (même schéma, sans le champ ajouté depuis).
            records.write("a", AccountRecord.History, """{"version":1,"retry_user_id":null,"failure":null,"messages":[
                {"id":"u1","role":"user","text":"Plan a weekend","timestamp_ms":1000,"state":"Complete","reply_to":null},
                {"id":"d1","role":"assistant","text":"My part","timestamp_ms":1001,"state":"Complete","reply_to":null,"speaker":"abc123","speaker_name":"Nova"},
                {"id":"a1","role":"assistant","text":"Final answer","timestamp_ms":1002,"state":"Complete","reply_to":"u1","model":"gpt-oss-120b","duration_ms":900,"tokens":120,"reaction":"👍"}]}""".toByteArray())
            val old = store.load("a")
            assertEquals(listOf("Plan a weekend", "My part", "Final answer"), old.messages.map { it.text })
            assertEquals("Nova", old.messages[1].speakerName)
            assertEquals(120L, old.messages[2].tokens)
            assertTrue(old.messages.all { it.teamReport == null })
            // Un rapport d'équipe illisible (version future, données abîmées) est ignoré, la conversation reste.
            records.write("a", AccountRecord.History, """{"version":1,"retry_user_id":null,"failure":null,"messages":[
                {"id":"a1","role":"assistant","text":"Answer","timestamp_ms":1,"state":"Complete","reply_to":null,
                 "team_report":{"stage":"Teleporting","effort":"Ultra","sources":"nope"}}]}""".toByteArray())
            val damaged = store.load("a")
            assertEquals("Answer", damaged.messages.single().text)
            assertNull(damaged.messages.single().teamReport)
        } finally {
            check(root.name.startsWith("claydot-account-"))
            root.deleteRecursively()
        }
    }
}
