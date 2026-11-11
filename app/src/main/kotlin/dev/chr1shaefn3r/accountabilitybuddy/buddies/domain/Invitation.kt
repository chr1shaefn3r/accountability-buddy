package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** A sender's open offer to pair. Valid for a short time and for one buddy only. */
class Invitation(
    val pairingId: PairingId,
    val secret: ByteArray,
    val privateKey: ByteArray,
    val publicKey: ByteArray,
    val expiresAt: Instant,
) {
    fun isValidAt(now: Instant): Boolean = now < expiresAt

    fun secret(): InvitationSecret = InvitationSecret(pairingId, secret)

    /** What the buddy needs to know to answer this invitation. */
    fun code(senderAddress: PushAddress, senderName: String): InvitationCode =
        InvitationCode(pairingId, publicKey, secret, senderAddress, senderName, expiresAt)

    companion object {
        val VALIDITY: Duration = Duration.ofMinutes(10)
        const val SECRET_SIZE = 16

        fun create(crypto: Cryptography, now: Instant): Invitation {
            val keyPair = crypto.newKeyPair()
            return Invitation(
                pairingId = PairingId.of(crypto.randomBytes(PairingId.SIZE)),
                secret = crypto.randomBytes(SECRET_SIZE),
                privateKey = keyPair.privateKey,
                publicKey = keyPair.publicKey,
                expiresAt = now + VALIDITY,
            )
        }
    }
}

/** The content of the QR code a sender shows to a future buddy. */
class InvitationCode(
    val pairingId: PairingId,
    val publicKey: ByteArray,
    val secret: ByteArray,
    val senderAddress: PushAddress,
    val senderName: String,
    val expiresAt: Instant,
) {
    fun isValidAt(now: Instant): Boolean = now < expiresAt

    fun secret(): InvitationSecret = InvitationSecret(pairingId, secret)

    fun encode(): String {
        val base64 = Base64.getUrlEncoder().withoutPadding()
        val content =
            Content(
                pairingId = pairingId.hex,
                publicKey = base64.encodeToString(publicKey),
                secret = base64.encodeToString(secret),
                senderAddress = senderAddress.token,
                senderName = senderName,
                expiresAtEpochSecond = expiresAt.epochSecond,
            )
        return PREFIX + Json.encodeToString(Content.serializer(), content)
    }

    @Serializable
    private data class Content(
        @SerialName("i") val pairingId: String,
        @SerialName("k") val publicKey: String,
        @SerialName("s") val secret: String,
        @SerialName("a") val senderAddress: String,
        @SerialName("n") val senderName: String,
        @SerialName("x") val expiresAtEpochSecond: Long,
    )

    companion object {
        private const val PREFIX = "accountability-buddy:1:"

        /** Returns null if [text] is not an invitation code, for example a foreign QR code. */
        fun parse(text: String): InvitationCode? {
            if (!text.startsWith(PREFIX)) return null
            return try {
                val content = Json.decodeFromString(Content.serializer(), text.removePrefix(PREFIX))
                val base64 = Base64.getUrlDecoder()
                InvitationCode(
                    pairingId = PairingId(content.pairingId),
                    publicKey = base64.decode(content.publicKey),
                    secret = base64.decode(content.secret),
                    senderAddress = PushAddress(content.senderAddress),
                    senderName = content.senderName,
                    expiresAt = Instant.ofEpochSecond(content.expiresAtEpochSecond),
                )
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
