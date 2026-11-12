package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import java.nio.ByteBuffer

class KeyPair(
    val privateKey: ByteArray,
    val publicKey: ByteArray,
)

/** The cryptographic primitives the Buddies context relies on. */
interface Cryptography {
    fun randomBytes(size: Int): ByteArray

    fun newKeyPair(): KeyPair

    fun sharedSecret(
        privateKey: ByteArray,
        publicKey: ByteArray,
    ): ByteArray

    fun deriveKey(
        inputKey: ByteArray,
        salt: ByteArray,
        info: ByteArray,
        size: Int,
    ): ByteArray

    fun encrypt(
        key: ByteArray,
        plaintext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray

    /** Returns null if the ciphertext was not made with this key and associated data. */
    fun decrypt(
        key: ByteArray,
        ciphertext: ByteArray,
        associatedData: ByteArray,
    ): ByteArray?
}

/** The two keys of a pairing: one per direction. */
class SessionKeys(
    val send: ByteArray,
    val receive: ByteArray,
) {
    fun toBytes(): ByteArray = send + receive

    companion object {
        const val KEY_SIZE = 32

        fun fromBytes(bytes: ByteArray): SessionKeys {
            require(bytes.size == 2 * KEY_SIZE) { "Not a pair of session keys" }
            return SessionKeys(bytes.copyOfRange(0, KEY_SIZE), bytes.copyOfRange(KEY_SIZE, 2 * KEY_SIZE))
        }

        /**
         * Both phones derive the same two keys from the key agreement and the one-time
         * secret of the invitation. Without the secret the keys cannot be derived.
         */
        fun derive(
            crypto: Cryptography,
            myPrivateKey: ByteArray,
            theirPublicKey: ByteArray,
            invitation: InvitationSecret,
            iAmSender: Boolean,
        ): SessionKeys {
            val shared = crypto.sharedSecret(myPrivateKey, theirPublicKey)
            val info = "accountability-buddy/v1/${invitation.pairingId.hex}".encodeToByteArray()
            val keys = crypto.deriveKey(shared, invitation.secret, info, 2 * KEY_SIZE)
            val senderToBuddy = keys.copyOfRange(0, KEY_SIZE)
            val buddyToSender = keys.copyOfRange(KEY_SIZE, 2 * KEY_SIZE)
            return if (iAmSender) {
                SessionKeys(
                    senderToBuddy,
                    buddyToSender,
                )
            } else {
                SessionKeys(buddyToSender, senderToBuddy)
            }
        }
    }
}

class InvitationSecret(
    val pairingId: PairingId,
    val secret: ByteArray,
)

/** An encrypted message as it travels: readable header, sealed body. */
class Envelope internal constructor(
    val pairingId: PairingId,
    val publicKey: ByteArray,
    internal val header: ByteArray,
    internal val body: ByteArray,
)

class OpenedMessage(
    val counter: Long,
    val message: BuddyMessage,
)

/**
 * Turns messages into envelopes and back.
 *
 * Wire format: version (1 byte), pairing id (16), public key field (32), sealed body.
 * The body is always a frame of [FRAME_SIZE] bytes: counter (8), length (2), message,
 * zero padding. Every envelope therefore has the same size, whatever it contains.
 * The public key field carries the buddy's public key in a pairing request and
 * random bytes otherwise.
 */
class SecureChannel(
    private val crypto: Cryptography,
) {
    fun seal(
        pairingId: PairingId,
        keys: SessionKeys,
        counter: Long,
        message: BuddyMessage,
        publicKey: ByteArray? = null,
    ): ByteArray {
        val encoded = BuddyMessage.encode(message)
        require(encoded.size <= MAX_MESSAGE_SIZE) { "Message of ${encoded.size} bytes is too long" }
        val frame =
            ByteBuffer
                .allocate(FRAME_SIZE)
                .putLong(counter)
                .putShort(encoded.size.toShort())
                .put(encoded)
                .array()
        val header = byteArrayOf(VERSION) + pairingId.bytes() + (publicKey ?: crypto.randomBytes(PUBLIC_KEY_SIZE))
        return header + crypto.encrypt(keys.send, frame, header)
    }

    /** Reads the header of an envelope. Returns null if this is not an envelope. */
    fun read(wire: ByteArray): Envelope? {
        if (wire.size <= HEADER_SIZE || wire[0] != VERSION) return null
        val header = wire.copyOfRange(0, HEADER_SIZE)
        return Envelope(
            pairingId = PairingId.of(header.copyOfRange(1, 1 + PairingId.SIZE)),
            publicKey = header.copyOfRange(1 + PairingId.SIZE, HEADER_SIZE),
            header = header,
            body = wire.copyOfRange(HEADER_SIZE, wire.size),
        )
    }

    /** Returns null if the envelope was not sealed with the matching keys or is malformed. */
    fun open(
        envelope: Envelope,
        keys: SessionKeys,
    ): OpenedMessage? {
        val frame = crypto.decrypt(keys.receive, envelope.body, envelope.header) ?: return null
        if (frame.size != FRAME_SIZE) return null
        val buffer = ByteBuffer.wrap(frame)
        val counter = buffer.getLong()
        val size = buffer.getShort().toInt()
        if (size !in 0..MAX_MESSAGE_SIZE) return null
        val encoded = ByteArray(size).also { buffer.get(it) }
        return BuddyMessage.decode(encoded)?.let { OpenedMessage(counter, it) }
    }

    companion object {
        const val FRAME_SIZE = 2048
        const val MAX_MESSAGE_SIZE = FRAME_SIZE - Long.SIZE_BYTES - Short.SIZE_BYTES
        private const val VERSION: Byte = 1
        private const val PUBLIC_KEY_SIZE = 32
        private const val HEADER_SIZE = 1 + PairingId.SIZE + PUBLIC_KEY_SIZE
    }
}
