package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface PairingRepository {
    fun observeAll(): Flow<List<Pairing>>

    suspend fun all(): List<Pairing>

    suspend fun find(id: PairingId): Pairing?

    suspend fun create(pairing: Pairing, keys: SessionKeys)

    suspend fun update(pairing: Pairing)

    suspend fun keysOf(id: PairingId): SessionKeys?

    suspend fun forgetKeys(id: PairingId)

    suspend fun delete(id: PairingId)
}

/** Holds the one invitation that is currently open, if any. */
interface InvitationStore {
    suspend fun current(): Invitation?

    suspend fun replace(invitation: Invitation?)
}

/** A sealed message waiting to be handed to the relay. */
data class Letter(
    val pairingId: PairingId,
    val address: PushAddress,
    val wire: ByteArray,
    val createdAt: Instant,
    val id: Long = 0,
)

/** Messages that still have to be sent, oldest first. */
interface Outbox {
    suspend fun add(letter: Letter)

    suspend fun pending(): List<Letter>

    suspend fun remove(letter: Letter)

    suspend fun removeOlderThan(instant: Instant)
}

/** Asks for the outbox to be delivered as soon as there is a network. */
fun interface DeliverySchedule {
    fun deliverSoon()
}

/** The server that passes sealed messages on to another phone. */
fun interface Relay {
    suspend fun send(to: PushAddress, wire: ByteArray): Outcome

    enum class Outcome {
        DELIVERED,

        /** The address is no longer valid. Retrying is pointless. */
        ADDRESS_GONE,
        TRY_LATER,
    }
}

/** Remembers which messages were already received, to drop replays and duplicates. */
interface ReceivedLog {
    /** Records the message and returns true if it was not seen before. */
    suspend fun isNew(pairingId: PairingId, counter: Long): Boolean

    suspend fun forget(pairingId: PairingId)
}

/** Something a sender reported, kept for the buddy to look at later. */
data class HistoryEntry(val pairingId: PairingId, val at: Instant, val message: BuddyMessage)

interface History {
    fun observe(pairingId: PairingId): Flow<List<HistoryEntry>>

    /** The most recent entry of every pairing. */
    fun observeLatest(): Flow<List<HistoryEntry>>

    suspend fun add(entry: HistoryEntry)

    suspend fun forgetBefore(instant: Instant)

    suspend fun forget(pairingId: PairingId)
}

/** Shows notifications about partners on this phone. */
interface BuddyNotifier {
    fun pairingRequested(pairing: Pairing)

    fun pairingAccepted(pairing: Pairing)

    fun pairingEnded(pairing: Pairing)

    fun limitHit(pairing: Pairing, message: BuddyMessage.LimitHit)

    fun setupWeakened(pairing: Pairing, message: BuddyMessage.SetupWeakened)

    fun daySummary(pairing: Pairing, message: BuddyMessage.DayClosed)

    fun silent(pairing: Pairing)
}

/** What this phone's owner goes by, and the address the phone last announced to partners. */
interface Profile {
    var displayName: String
    var address: PushAddress?
}

/** Asks the push service for this phone's current address. */
fun interface PushAddressProvider {
    /** @throws PushUnavailableException if the push service cannot be reached. */
    suspend fun current(): PushAddress
}

class PushUnavailableException(cause: Throwable) : Exception(cause)
