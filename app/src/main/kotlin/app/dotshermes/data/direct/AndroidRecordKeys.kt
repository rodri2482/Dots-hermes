package app.dotshermes.data.direct

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/** Clés AES non exportables, une par compte. Ne jamais recréer une clé manquante pour déchiffrer. */
class AndroidRecordKeys : RecordKeys {
    @Synchronized override fun get(accountId: String, create: Boolean): SecretKey? {
        val alias = alias(accountId)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (store.containsAlias(alias)) return store.getKey(alias, null) as? SecretKey
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build())
        }.generateKey()
    }

    @Synchronized override fun remove(accountId: String) {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias(accountId))
    }

    private fun alias(accountId: String): String {
        require(accountId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        return "claydot.direct.v1.$accountId"
    }
}
