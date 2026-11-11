package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.crypto

import com.google.crypto.tink.integration.android.AndroidKeystore
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence.Vault
import java.security.GeneralSecurityException

/** Encrypts secrets with an AES-256-GCM key that never leaves the Android Keystore. */
class KeystoreVault : Vault {
    private val aead by lazy {
        if (!AndroidKeystore.hasKey(KEY_ALIAS)) AndroidKeystore.generateNewAes256GcmKey(KEY_ALIAS)
        AndroidKeystore.getAead(KEY_ALIAS)
    }

    override fun protect(secret: ByteArray): ByteArray = aead.encrypt(secret, CONTEXT)

    override fun reveal(protectedSecret: ByteArray): ByteArray? =
        try {
            aead.decrypt(protectedSecret, CONTEXT)
        } catch (_: GeneralSecurityException) {
            null
        }

    private companion object {
        const val KEY_ALIAS = "accountability-buddy-vault"
        val CONTEXT = "pairing-secrets".encodeToByteArray()
    }
}
