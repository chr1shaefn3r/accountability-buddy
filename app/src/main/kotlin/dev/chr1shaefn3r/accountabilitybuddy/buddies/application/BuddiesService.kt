package dev.chr1shaefn3r.accountabilitybuddy.buddies.application

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyNotifier
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Cryptography
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.DeliverySchedule
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Envelope
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.History
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.HistoryEntry
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Invitation
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationCode
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationStore
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Letter
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Outbox
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingId
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingRepository
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Profile
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddressProvider
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.ReceivedLog
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Relay
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.SecureChannel
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.SessionKeys
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration

/** Everything the Buddies context needs from the outside world. */
class BuddiesPorts(
    val pairings: PairingRepository,
    val invitations: InvitationStore,
    val outbox: Outbox,
    val deliverySchedule: DeliverySchedule,
    val relay: Relay,
    val received: ReceivedLog,
    val history: History,
    val notifier: BuddyNotifier,
    val profile: Profile,
    val addressProvider: PushAddressProvider,
    val crypto: Cryptography,
    val clock: Clock,
)

/** What came of scanning an invitation code. */
enum class PairingRequestResult {
    REQUESTED,
    EXPIRED,
    ALREADY_KNOWN,
    OWN_INVITATION,
}

/** The use cases of the Buddies context. */
@Suppress("TooManyFunctions")
class BuddiesService(
    ports: BuddiesPorts,
) {
    private val pairings = ports.pairings
    private val invitations = ports.invitations
    private val outbox = ports.outbox
    private val deliverySchedule = ports.deliverySchedule
    private val relay = ports.relay
    private val received = ports.received
    private val history = ports.history
    private val notifier = ports.notifier
    private val profile = ports.profile
    private val addressProvider = ports.addressProvider
    private val crypto = ports.crypto
    private val clock = ports.clock
    private val channel = SecureChannel(crypto)
    private val mutex = Mutex()

    // ---- Pairing -------------------------------------------------------------------

    /** Sender: opens a new invitation, replacing any earlier one, and returns its code. */
    suspend fun invite(): InvitationCode =
        mutex.withLock {
            val address = currentAddress()
            val invitation = Invitation.create(crypto, clock.instant())
            invitations.replace(invitation)
            invitation.code(address, profile.displayName)
        }

    /** Buddy: answers a scanned invitation, appearing under [myName]. */
    suspend fun requestPairing(
        code: InvitationCode,
        myName: String,
    ): PairingRequestResult =
        mutex.withLock {
            when {
                invitations.current()?.pairingId == code.pairingId ->
                    return@withLock PairingRequestResult.OWN_INVITATION
                pairings.find(code.pairingId) != null -> return@withLock PairingRequestResult.ALREADY_KNOWN
                !code.isValidAt(clock.instant()) -> return@withLock PairingRequestResult.EXPIRED
            }
            val address = currentAddress()
            val keyPair = crypto.newKeyPair()
            val keys = SessionKeys.derive(crypto, keyPair.privateKey, code.publicKey, code.secret(), iAmSender = false)
            pairings.create(
                Pairing(
                    id = code.pairingId,
                    partnerRole = PartnerRole.SENDER,
                    partnerName = code.senderName,
                    partnerAddress = code.senderAddress,
                    state = PairingState.REQUESTED,
                    lastSignOfLife = clock.instant(),
                ),
                keys,
            )
            send(code.pairingId, BuddyMessage.PairingRequested(myName, address.id), keyPair.publicKey)
            PairingRequestResult.REQUESTED
        }

    /** Sender: accepts the buddy who answered the invitation. */
    suspend fun confirm(id: PairingId) =
        mutex.withLock {
            val pairing = pairings.find(id) ?: return@withLock
            if (pairing.state != PairingState.AWAITING_CONFIRMATION) return@withLock
            pairings.update(pairing.copy(state = PairingState.ACTIVE))
            send(id, BuddyMessage.PairingAccepted)
        }

    /** Sender: turns down the buddy who answered the invitation. */
    suspend fun decline(id: PairingId) =
        mutex.withLock {
            val pairing = pairings.find(id) ?: return@withLock
            if (pairing.state != PairingState.AWAITING_CONFIRMATION) return@withLock
            send(id, BuddyMessage.PairingDeclined)
            forget(id)
        }

    /** Ends a pairing from this side. The partner is told, unless they ended it first. */
    suspend fun end(id: PairingId) =
        mutex.withLock {
            val pairing = pairings.find(id) ?: return@withLock
            if (pairing.hasKeys) send(id, BuddyMessage.PairingEnded)
            forget(id)
        }

    suspend fun rename(
        id: PairingId,
        nickname: String?,
    ) = mutex.withLock {
        val pairing = pairings.find(id) ?: return@withLock
        pairings.update(pairing.copy(nickname = nickname?.trim()?.ifEmpty { null }))
    }

    /** Buddy: chooses which notifications about a sender are shown. */
    suspend fun configure(
        id: PairingId,
        instantNotifications: Boolean,
        daySummary: Boolean,
    ) = mutex.withLock {
        val pairing = pairings.find(id) ?: return@withLock
        pairings.update(pairing.copy(instantNotifications = instantNotifications, daySummary = daySummary))
    }

    // ---- Sending -------------------------------------------------------------------

    /** Sender: sends [message] to every active buddy. */
    suspend fun tellBuddies(message: BuddyMessage) =
        mutex.withLock {
            pairings
                .all()
                .filter { it.partnerRole == PartnerRole.BUDDY && it.state == PairingState.ACTIVE }
                .forEach { send(it.id, message) }
        }

    /** Announces this phone's push address to all partners if it changed. */
    suspend fun refreshAddress() =
        mutex.withLock {
            currentAddress()
        }

    suspend fun changeDisplayName(name: String) =
        mutex.withLock {
            if (name == profile.displayName) return@withLock
            profile.displayName = name
            tellEveryone(BuddyMessage.NameChanged(name))
        }

    /**
     * Hands waiting messages to the relay, in order per pairing.
     * Returns false if some could not be sent yet and should be retried.
     */
    suspend fun deliver(): Boolean =
        mutex.withLock {
            outbox.removeOlderThan(clock.instant() - MAX_LETTER_AGE)
            val waiting = mutableSetOf<PairingId>()
            var allSent = true
            for (letter in outbox.pending()) {
                val pairing = pairings.find(letter.pairingId)
                if (letter.pairingId in waiting || pairing?.state == PairingState.UNREACHABLE) continue
                when (relay.send(pairing?.partnerAddress ?: letter.address, letter.wire)) {
                    Relay.Outcome.DELIVERED -> outbox.remove(letter)
                    Relay.Outcome.ADDRESS_GONE -> {
                        waiting += letter.pairingId
                        if (pairing == null) outbox.remove(letter) else markUnreachable(pairing)
                    }
                    Relay.Outcome.TRY_LATER -> {
                        waiting += letter.pairingId
                        allSent = false
                    }
                }
            }
            allSent
        }

    // ---- Receiving -----------------------------------------------------------------

    /** Handles a message that arrived from the relay. Anything unreadable is dropped. */
    suspend fun receive(wire: ByteArray) =
        mutex.withLock {
            val envelope = channel.read(wire) ?: return@withLock
            val pairing = pairings.find(envelope.pairingId) ?: return@withLock receivePairingRequest(envelope)
            val keys = pairings.keysOf(pairing.id) ?: return@withLock
            val opened = channel.open(envelope, keys) ?: return@withLock
            if (!received.isNew(pairing.id, opened.counter)) return@withLock
            val alive = pairing.signOfLife(clock.instant())
            pairings.update(alive)
            if (!receivedFromEither(alive, opened.message) && alive.partnerRole == PartnerRole.SENDER) {
                receivedFromSender(alive, opened.message)
            }
        }

    private suspend fun receivePairingRequest(envelope: Envelope) {
        val invitation = invitations.current() ?: return
        if (invitation.pairingId != envelope.pairingId || !invitation.isValidAt(clock.instant())) return
        val keys =
            SessionKeys.derive(crypto, invitation.privateKey, envelope.publicKey, invitation.secret(), iAmSender = true)
        val opened = channel.open(envelope, keys) ?: return
        val request = opened.message as? BuddyMessage.PairingRequested ?: return
        invitations.replace(null)
        val pairing =
            Pairing(
                id = invitation.pairingId,
                partnerRole = PartnerRole.BUDDY,
                partnerName = request.buddyName,
                partnerAddress = PushAddress(request.buddyAddress),
                state = PairingState.AWAITING_CONFIRMATION,
                lastSignOfLife = clock.instant(),
            )
        pairings.create(pairing, keys)
        received.isNew(pairing.id, opened.counter)
        notifier.pairingRequested(pairing)
    }

    /** Messages both roles may send. Returns false if [message] is not one of them. */
    private suspend fun receivedFromEither(
        pairing: Pairing,
        message: BuddyMessage,
    ): Boolean {
        when (message) {
            is BuddyMessage.PairingEnded -> partnerEnded(pairing)
            is BuddyMessage.NameChanged -> pairings.update(pairing.copy(partnerName = message.name))
            is BuddyMessage.AddressChanged -> {
                val reachable = if (pairing.state == PairingState.UNREACHABLE) PairingState.ACTIVE else pairing.state
                pairings.update(pairing.copy(partnerAddress = PushAddress(message.address), state = reachable))
                deliverySchedule.deliverSoon()
            }
            else -> return false
        }
        return true
    }

    private suspend fun receivedFromSender(
        pairing: Pairing,
        message: BuddyMessage,
    ) {
        if (pairing.state == PairingState.REQUESTED) {
            when (message) {
                is BuddyMessage.PairingAccepted -> {
                    val active = pairing.copy(state = PairingState.ACTIVE)
                    pairings.update(active)
                    notifier.pairingAccepted(active)
                }
                is BuddyMessage.PairingDeclined -> partnerEnded(pairing)
                else -> Unit
            }
            return
        }
        val entry = HistoryEntry(pairing.id, clock.instant(), message)
        when (message) {
            is BuddyMessage.LimitHit -> {
                history.add(entry)
                if (pairing.instantNotifications) notifier.limitHit(pairing, message)
            }
            is BuddyMessage.SetupWeakened -> {
                history.add(entry)
                if (pairing.instantNotifications) notifier.setupWeakened(pairing, message)
            }
            is BuddyMessage.DayClosed ->
                if (message.entries.isNotEmpty()) {
                    history.add(entry)
                    if (pairing.daySummary) notifier.daySummary(pairing, message)
                }
            else -> Unit
        }
    }

    private suspend fun partnerEnded(pairing: Pairing) {
        val ended = pairing.copy(state = PairingState.ENDED)
        pairings.update(ended)
        pairings.forgetKeys(pairing.id)
        received.forget(pairing.id)
        notifier.pairingEnded(ended)
    }

    // ---- Housekeeping --------------------------------------------------------------

    /** Buddy: raises an alert for every sender that has been silent for too long. */
    suspend fun checkSilence() =
        mutex.withLock {
            val now = clock.instant()
            pairings.all().filter { it.isSilentAt(now) }.forEach {
                pairings.update(it.copy(silenceAlerted = true))
                notifier.silent(it)
            }
            history.forgetBefore(now - HISTORY_AGE)
        }

    private suspend fun currentAddress(): PushAddress {
        val address = addressProvider.current()
        if (address != profile.address) {
            profile.address = address
            tellEveryone(BuddyMessage.AddressChanged(address.id))
        }
        return address
    }

    private suspend fun tellEveryone(message: BuddyMessage) {
        pairings.all().filter { it.hasKeys }.forEach { send(it.id, message) }
    }

    private suspend fun send(
        id: PairingId,
        message: BuddyMessage,
        publicKey: ByteArray? = null,
    ) {
        val pairing = pairings.find(id) ?: return
        val keys = pairings.keysOf(id) ?: return
        val wire = channel.seal(id, keys, pairing.nextCounter, message, publicKey)
        pairings.update(pairing.copy(nextCounter = pairing.nextCounter + 1))
        outbox.add(Letter(id, pairing.partnerAddress, wire, clock.instant()))
        deliverySchedule.deliverSoon()
    }

    private suspend fun markUnreachable(pairing: Pairing) {
        if (pairing.hasKeys) pairings.update(pairing.copy(state = PairingState.UNREACHABLE))
    }

    private suspend fun forget(id: PairingId) {
        pairings.delete(id)
        received.forget(id)
        history.forget(id)
    }

    companion object {
        val HISTORY_AGE: Duration = Duration.ofDays(30)
        val MAX_LETTER_AGE: Duration = Duration.ofDays(3)
    }
}
