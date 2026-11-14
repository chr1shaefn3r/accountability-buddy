package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Everything two paired phones can tell each other. Always sent encrypted. */
@Serializable
sealed interface BuddyMessage {
    @Serializable
    @SerialName("req")
    data class PairingRequested(
        @SerialName("n") val buddyName: String,
        @SerialName("a") val buddyAddress: String,
    ) : BuddyMessage

    @Serializable
    @SerialName("acc")
    data object PairingAccepted : BuddyMessage

    @Serializable
    @SerialName("dec")
    data object PairingDeclined : BuddyMessage

    @Serializable
    @SerialName("end")
    data object PairingEnded : BuddyMessage

    @Serializable
    @SerialName("addr")
    data class AddressChanged(
        @SerialName("a") val address: String,
    ) : BuddyMessage

    @Serializable
    @SerialName("name")
    data class NameChanged(
        @SerialName("n") val name: String,
    ) : BuddyMessage

    /**
     * A limit was reached, or a session used up. Sent a waiting time after it happened,
     * with how it went on. Messages of older versions carry neither of the last two.
     */
    @Serializable
    @SerialName("hit")
    data class LimitHit(
        @SerialName("n") val limitName: String,
        @SerialName("b") val budgetMinutes: Long,
        @SerialName("t") val atEpochSecond: Long,
        @SerialName("u") val usedMinutes: Long? = null,
        @SerialName("g") val stillGoing: Boolean? = null,
    ) : BuddyMessage {
        val budget: Duration get() = Duration.ofMinutes(budgetMinutes)
        val at: Instant get() = Instant.ofEpochSecond(atEpochSecond)

        /** All that was used when the message was sent, if the message says so. */
        val used: Duration? get() = usedMinutes?.let(Duration::ofMinutes)
    }

    /** The sender made the setup less strict. */
    @Serializable
    sealed interface SetupWeakened : BuddyMessage

    @Serializable
    @SerialName("raised")
    data class BudgetRaised(
        @SerialName("n") val limitName: String,
        @SerialName("f") val fromMinutes: Long,
        @SerialName("o") val toMinutes: Long,
    ) : SetupWeakened {
        val from: Duration get() = Duration.ofMinutes(fromMinutes)
        val to: Duration get() = Duration.ofMinutes(toMinutes)
    }

    @Serializable
    @SerialName("removed")
    data class AppsRemoved(
        @SerialName("n") val limitName: String,
        @SerialName("a") val apps: List<String>,
    ) : SetupWeakened

    @Serializable
    @SerialName("deleted")
    data class LimitDeleted(
        @SerialName("n") val limitName: String,
        @SerialName("b") val budgetMinutes: Long,
    ) : SetupWeakened {
        val budget: Duration get() = Duration.ofMinutes(budgetMinutes)
    }

    @Serializable
    @SerialName("lost")
    data class TrackingLost(
        @SerialName("p") val problem: Problem,
    ) : SetupWeakened {
        @Serializable
        enum class Problem {
            @SerialName("usage")
            USAGE_ACCESS_MISSING,

            @SerialName("notif")
            NOTIFICATIONS_BLOCKED,
        }
    }

    /** The sender's day is over. Sent every day; [entries] is empty when no limit was hit. */
    @Serializable
    @SerialName("day")
    data class DayClosed(
        @SerialName("d") val date: String,
        @SerialName("e") val entries: List<Entry>,
    ) : BuddyMessage {
        val day: LocalDate get() = LocalDate.parse(date)

        @Serializable
        data class Entry(
            @SerialName("n") val limitName: String,
            @SerialName("b") val budgetMinutes: Long,
            @SerialName("u") val usedMinutes: Long,
        ) {
            val budget: Duration get() = Duration.ofMinutes(budgetMinutes)
            val used: Duration get() = Duration.ofMinutes(usedMinutes)
        }

        companion object {
            /** More entries than this do not fit into one message. */
            const val MAX_ENTRIES = 20
        }
    }

    companion object {
        private val json =
            Json {
                classDiscriminator = "k"
                ignoreUnknownKeys = true
            }

        fun encode(message: BuddyMessage): ByteArray = json.encodeToString(serializer(), message).encodeToByteArray()

        /** Returns null for anything that is not a message this version understands. */
        fun decode(bytes: ByteArray): BuddyMessage? =
            try {
                json.decodeFromString(serializer(), bytes.decodeToString())
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
    }
}
