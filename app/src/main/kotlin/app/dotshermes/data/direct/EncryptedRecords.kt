package app.dotshermes.data.direct

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

interface RecordKeys {
    fun get(accountId: String, create: Boolean): SecretKey?
    fun remove(accountId: String)
}

enum class AccountRecord(val fileName: String) { Credentials("credentials.enc"), History("history.enc"), Memory("memory.enc") }

/** Stockage authentifié, borné et atomique. Les fichiers temporaires ne contiennent que du chiffré. */
class EncryptedRecords(private val directory: File, private val keys: RecordKeys) {
    @Synchronized fun write(accountId: String, record: AccountRecord, plaintext: ByteArray) = safe {
        if (plaintext.size > MAX_BYTES) throw ApiException(ApiFailure.Storage)
        val folder = folder(accountId)
        if (!folder.isDirectory && !folder.mkdirs()) throw ApiException(ApiFailure.Storage)
        // Une clé perdue avec des données existantes exige une décision de l'utilisateur, pas une remise à zéro.
        val hasRecords = folder.listFiles()?.any { it.isFile } ?: false
        val key = keys.get(accountId, create = !hasRecords) ?: throw ApiException(ApiFailure.Storage)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        cipher.updateAAD(aad(accountId, record))
        val encrypted = cipher.doFinal(plaintext)
        val iv = cipher.iv
        if (iv.size != 12) throw ApiException(ApiFailure.Storage)
        val target = File(folder, record.fileName).toPath()
        val pending = Files.createTempFile(folder.toPath(), record.fileName, ".pending")
        try {
            FileOutputStream(pending.toFile()).use {
                it.write(byteArrayOf(1))
                it.write(iv)
                it.write(encrypted)
                it.fd.sync()
            }
            Files.move(pending, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(pending) }
    }

    @Synchronized fun read(accountId: String, record: AccountRecord): ByteArray? = safe {
        val file = File(folder(accountId), record.fileName).toPath()
        if (Files.notExists(file)) return@safe null
        if (Files.isSymbolicLink(file) || Files.size(file) !in 29L..(MAX_BYTES + 29L)) throw ApiException(ApiFailure.Storage)
        val bytes = Files.readAllBytes(file)
        if (bytes[0] != 1.toByte()) throw ApiException(ApiFailure.Storage)
        val key = keys.get(accountId, create = false) ?: throw ApiException(ApiFailure.Storage)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
        cipher.updateAAD(aad(accountId, record))
        cipher.doFinal(bytes, 13, bytes.size - 13)
    }

    @Synchronized fun removeRecord(accountId: String, record: AccountRecord) = safe {
        Files.deleteIfExists(File(folder(accountId), record.fileName).toPath())
    }

    @Synchronized fun remove(accountId: String) = safe {
        val folder = folder(accountId)
        // Pas de suppression récursive : chaque enfant est un fichier de ce compte privé uniquement.
        if (folder.exists()) {
            for (file in folder.listFiles() ?: throw ApiException(ApiFailure.Storage)) {
                if (Files.isDirectory(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw ApiException(ApiFailure.Storage)
                Files.delete(file.toPath())
            }
            Files.delete(folder.toPath())
        }
        keys.remove(accountId)
    }

    private fun folder(accountId: String): File {
        if (!accountId.matches(Regex("[A-Za-z0-9_-]{1,80}"))) throw ApiException(ApiFailure.Storage)
        val root = directory.canonicalFile
        val folder = File(root, accountId)
        if (folder.canonicalFile.parentFile != root || Files.isSymbolicLink(folder.toPath())) throw ApiException(ApiFailure.Storage)
        return folder
    }

    private fun aad(accountId: String, record: AccountRecord): ByteArray =
        "claydot-direct-v1\u0000$accountId\u0000${record.fileName}".toByteArray(Charsets.UTF_8)

    private inline fun <T> safe(block: () -> T): T = try { block() }
    catch (e: ApiException) { throw e }
    catch (_: Exception) { throw ApiException(ApiFailure.Storage) }

    companion object { private const val MAX_BYTES = 8 * 1024 * 1024 }
}
