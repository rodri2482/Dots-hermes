package app.dotshermes.data.direct

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

class EncryptedRecordsTest {
    private lateinit var root: File
    private lateinit var keys: RecordKeys
    private lateinit var store: EncryptedRecords
    private lateinit var goodKey: SecretKey
    private var selectedKey: SecretKey? = null

    @Before fun setUp() {
        root = Files.createTempDirectory("claydot-vault-").toFile()
        goodKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        selectedKey = goodKey
        keys = object : RecordKeys {
            override fun get(accountId: String, create: Boolean): SecretKey? = selectedKey
            override fun remove(accountId: String) { selectedKey = null }
        }
        store = EncryptedRecords(root, keys)
    }

    @After fun tearDown() {
        check(root.name.startsWith("claydot-vault-") && root.canonicalFile.parentFile == File(requireNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile)
        root.deleteRecursively()
    }

    @Test fun `keys and chat history round trip without plaintext files`() {
        store.write("account-a", AccountRecord.Credentials, "fixture-api-key".toByteArray())
        store.write("account-a", AccountRecord.History, "private fixture conversation".toByteArray())
        assertEquals("fixture-api-key", store.read("account-a", AccountRecord.Credentials)!!.decodeToString())
        assertEquals("private fixture conversation", store.read("account-a", AccountRecord.History)!!.decodeToString())
        for (file in root.walkTopDown().filter { it.isFile }) {
            val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
            assertFalse(bytes.contains("fixture-api-key"))
            assertFalse(bytes.contains("private fixture conversation"))
        }
    }

    @Test fun `fresh encryptions never reuse the same record nonce`() {
        store.write("account-a", AccountRecord.History, "same text".toByteArray())
        val first = root.resolve("account-a/history.enc").readBytes()
        store.write("account-a", AccountRecord.History, "same text".toByteArray())
        assertFalse(first.contentEquals(root.resolve("account-a/history.enc").readBytes()))
    }

    @Test fun `ciphertext tampering fails closed and never erases the record`() {
        store.write("account-a", AccountRecord.History, "private".toByteArray())
        val file = root.resolve("account-a/history.enc")
        val bytes = file.readBytes().apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        file.writeBytes(bytes)
        val error = assertThrows(ApiException::class.java) { store.read("account-a", AccountRecord.History) }
        assertEquals(ApiFailure.Storage, error.failure)
        assertTrue(bytes.contentEquals(file.readBytes()))
    }

    @Test fun `swapping accounts or record types is rejected even with the same AES key`() {
        store.write("account-a", AccountRecord.Credentials, "key-a".toByteArray())
        store.write("account-b", AccountRecord.Credentials, "key-b".toByteArray())
        root.resolve("account-a/credentials.enc").copyTo(root.resolve("account-b/credentials.enc"), overwrite = true)
        assertThrows(ApiException::class.java) { store.read("account-b", AccountRecord.Credentials) }
        root.resolve("account-a/credentials.enc").copyTo(root.resolve("account-a/history.enc"))
        assertThrows(ApiException::class.java) { store.read("account-a", AccountRecord.History) }
    }

    @Test fun `failed encryption preserves the previously committed history`() {
        store.write("account-a", AccountRecord.History, "previous".toByteArray())
        val previous = root.resolve("account-a/history.enc").readBytes()
        selectedKey = SecretKeySpec(byteArrayOf(1, 2, 3), "AES")
        assertThrows(ApiException::class.java) { store.write("account-a", AccountRecord.History, "replacement".toByteArray()) }
        assertTrue(previous.contentEquals(root.resolve("account-a/history.enc").readBytes()))
        selectedKey = goodKey
        assertEquals("previous", store.read("account-a", AccountRecord.History)!!.decodeToString())
    }

    @Test fun `a missing Keystore key does not silently reset encrypted history`() {
        store.write("account-a", AccountRecord.History, "retained".toByteArray())
        selectedKey = null
        assertThrows(ApiException::class.java) { store.read("account-a", AccountRecord.History) }
        assertTrue(root.resolve("account-a/history.enc").isFile)
    }

    @Test fun `writing after key loss cannot replace retained history with a newly generated key`() {
        store.write("account-a", AccountRecord.History, "retained".toByteArray())
        val previous = root.resolve("account-a/history.enc").readBytes()
        val regenerating = EncryptedRecords(root, object : RecordKeys {
            override fun get(accountId: String, create: Boolean): SecretKey? =
                if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey() else null
            override fun remove(accountId: String) {}
        })
        assertThrows(ApiException::class.java) { regenerating.write("account-a", AccountRecord.History, "replacement".toByteArray()) }
        assertTrue(previous.contentEquals(root.resolve("account-a/history.enc").readBytes()))
    }

    @Test fun `a new account has no history and unsafe account ids never escape the vault`() {
        assertNull(store.read("new-account", AccountRecord.History))
        assertThrows(ApiException::class.java) { store.write("../outside", AccountRecord.Credentials, byteArrayOf(1)) }
        assertEquals(0, root.listFiles()!!.size)
    }

    @Test fun `removing an account deletes both encrypted records and its key`() {
        store.write("account-a", AccountRecord.Credentials, "fixture".toByteArray())
        store.write("account-a", AccountRecord.History, "history".toByteArray())
        store.remove("account-a")
        assertNull(store.read("account-a", AccountRecord.Credentials))
        assertNull(store.read("account-a", AccountRecord.History))
        assertNull(selectedKey)
    }
}
