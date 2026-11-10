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
                DirectEntry("a1", "assistant", "partial reply", 1001, DirectEntryState.Failed, "u1"),
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
}
