package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.crypto

import com.google.crypto.tink.subtle.AesGcmJce
import com.google.crypto.tink.subtle.Hkdf
import com.google.crypto.tink.subtle.Random
import com.google.crypto.tink.subtle.X25519
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Cryptography
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.KeyPair
import java.security.GeneralSecurityException

/** X25519 key agreement, HKDF-SHA256 and AES-256-GCM, all provided by Tink. */
class TinkCryptography : Cryptography {
    override fun randomBytes(size: Int): ByteArray = Random.randBytes(size)

    override fun newKeyPair(): KeyPair {
        val privateKey = X25519.generatePrivateKey()
        return KeyPair(privateKey, X25519.publicFromPrivate(privateKey))
    }

    override fun sharedSecret(privateKey: ByteArray, publicKey: ByteArray): ByteArray =
        X25519.computeSharedSecret(privateKey, publicKey)

    override fun deriveKey(inputKey: ByteArray, salt: ByteArray, info: ByteArray, size: Int): ByteArray =
        Hkdf.computeHkdf("HMACSHA256", inputKey, salt, info, size)

    override fun encrypt(key: ByteArray, plaintext: ByteArray, associatedData: ByteArray): ByteArray =
        AesGcmJce(key).encrypt(plaintext, associatedData)

    override fun decrypt(key: ByteArray, ciphertext: ByteArray, associatedData: ByteArray): ByteArray? =
        try {
            AesGcmJce(key).decrypt(ciphertext, associatedData)
        } catch (_: GeneralSecurityException) {
            null
        }
}
