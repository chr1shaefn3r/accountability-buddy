package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import java.time.Duration
import java.time.Instant

/** Identifies a pairing on both phones. Sixteen random bytes, written as hex. */
@JvmInline
value class PairingId(
    val hex: String,
) {
    init {
        require(hex.length == SIZE * 2 && hex.all { it in "0123456789abcdef" }) { "Not a pairing id" }
    }

    fun bytes(): ByteArray = hex.chunked(2).map { it.toInt(HEX).toByte() }.toByteArray()

    companion object {
        const val SIZE = 16
        private const val HEX = 16

        /** The id of the test buddy. A real pairing gets a random id, which is never this one. */
        val TEST_BUDDY = PairingId("0".repeat(SIZE * 2))

        fun of(bytes: ByteArray): PairingId = PairingId(bytes.joinToString("") { "%02x".format(it) })
    }
}

/** Where push messages for a phone are sent to (its Firebase installation ID). */
@JvmInline
value class PushAddress(
    val id: String,
)

/** What the other person in a pairing is to the owner of this phone. */
enum class PartnerRole {
    /** The partner watches over my limits. */
    BUDDY,

    /** I watch over the partner's limits. */
    SENDER,
}

enum class PairingState {
    /** I scanned a sender's code and wait for the sender to confirm. */
    REQUESTED,

    /** A buddy scanned my code and I have to confirm. */
    AWAITING_CONFIRMATION,
    ACTIVE,

    /** The partner's push address is no longer valid. */
    UNREACHABLE,

    /** The partner ended or declined the pairing. */
    ENDED,
}

/** An encrypted link between one sender and one buddy, seen from this phone. */
data class Pairing(
    val id: PairingId,
    val partnerRole: PartnerRole,
    val partnerName: String,
    val partnerAddress: PushAddress,
    val state: PairingState,
    val lastSignOfLife: Instant,
    val nickname: String? = null,
    val instantNotifications: Boolean = true,
    val daySummary: Boolean = false,
    val silenceAlerted: Boolean = false,
    val nextCounter: Long = 0,
) {
    /** The name shown on this phone: the local nickname if there is one. */
    val displayName: String get() = nickname ?: partnerName

    val hasKeys: Boolean get() = state != PairingState.ENDED

    /**
     * True for the buddy that lives on the sender's own phone: what it is told is shown
     * there instead of being sent anywhere. For trying the app out without a second phone.
     */
    val isTestBuddy: Boolean get() = id == PairingId.TEST_BUDDY

    fun signOfLife(at: Instant): Pairing = copy(lastSignOfLife = at, silenceAlerted = false)

    /** True once a sender has not been heard from for too long and no alert was raised yet. */
    fun isSilentAt(now: Instant): Boolean =
        partnerRole == PartnerRole.SENDER &&
            state == PairingState.ACTIVE &&
            !silenceAlerted &&
            Duration.between(lastSignOfLife, now) >= SILENCE_THRESHOLD

    companion object {
        val SILENCE_THRESHOLD: Duration = Duration.ofDays(7)
        const val TEST_BUDDY_NAME = "Test buddy"
    }
}
