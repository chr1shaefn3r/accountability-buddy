package dev.chr1shaefn3r.accountabilitybuddy.buddies.application

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyNotifier
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.History
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.HistoryEntry
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Invitation
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationStore
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Letter
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Outbox
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingId
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingRepository
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Profile
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushUnavailableException
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.ReceivedLog
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Relay
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.SessionKeys
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.crypto.TinkCryptography
import dev.chr1shaefn3r.accountabilitybuddy.testing.MutableClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.Instant

/** The relay server and push service of the tests: passes sealed messages between phones. */
class Network {
    private val phones = mutableMapOf<PushAddress, Phone>()
    private val others = mutableMapOf<PushAddress, suspend (ByteArray) -> Unit>()
    val traffic = mutableListOf<Pair<PushAddress, ByteArray>>()
    var online = true

    /** While set, the relay accepts a message but does not answer until [answer] is called. */
    var stalled: CompletableDeferred<Unit>? = null

    fun stall() {
        stalled = CompletableDeferred()
    }

    fun answer() {
        stalled?.complete(Unit)
        stalled = null
    }

    fun register(
        address: PushAddress,
        phone: Phone,
    ) {
        phones[address] = phone
    }

    /** Connects something that is not a [Phone], for example the real app under test. */
    fun connect(
        address: PushAddress,
        receive: suspend (ByteArray) -> Unit,
    ) {
        others[address] = receive
    }

    fun unregister(address: PushAddress) {
        phones.remove(address)
    }

    suspend fun send(
        to: PushAddress,
        wire: ByteArray,
    ): Relay.Outcome {
        if (!online) return Relay.Outcome.TRY_LATER
        stalled?.await()
        val receive = phones[to]?.let { it.buddies::receive } ?: others[to] ?: return Relay.Outcome.ADDRESS_GONE
        traffic += to to wire
        receive(wire)
        return Relay.Outcome.DELIVERED
    }

    /** Lets all phones deliver until nobody has anything left to send. */
    suspend fun settle() {
        do {
            val busy = phones.values.toSet().filter { it.deliveryRequested }
            busy.forEach {
                it.deliveryRequested = false
                it.buddies.deliver()
            }
        } while (busy.isNotEmpty())
    }
}

/** One phone with the Buddies context running on in-memory adapters. */
class Phone(
    name: String,
    private val network: Network,
    val clock: MutableClock,
) {
    val pairings = InMemoryPairings()
    val invitations = InMemoryInvitations()
    val outbox = InMemoryOutbox()
    val history = InMemoryHistory()
    val notifier = RecordingNotifier()
    val profile = InMemoryProfile(name)
    var address = PushAddress("address-of-$name")
        set(value) {
            network.unregister(field)
            field = value
            network.register(value, this)
        }
    var pushAvailable = true

    /** While set, the push service does not answer when asked for this phone's address. */
    var pushStalled: CompletableDeferred<Unit>? = null
    var deliveryRequested = false

    val buddies =
        BuddiesService(
            BuddiesPorts(
                pairings = pairings,
                invitations = invitations,
                outbox = outbox,
                deliverySchedule = { deliveryRequested = true },
                relay = { to, wire -> network.send(to, wire) },
                received = InMemoryReceivedLog(),
                history = history,
                notifier = notifier,
                profile = profile,
                addressProvider = {
                    pushStalled?.await()
                    if (pushAvailable) address else throw PushUnavailableException(IOException("no push service"))
                },
                crypto = TinkCryptography(),
                clock = clock,
            ),
        )

    init {
        network.register(address, this)
    }

    fun uninstall() = network.unregister(address)

    suspend fun onlyPairing(): Pairing = pairings.all().single()
}

class InMemoryPairings : PairingRepository {
    private val pairings = MutableStateFlow<List<Pairing>>(emptyList())
    private val keys = mutableMapOf<PairingId, SessionKeys>()

    override fun observeAll(): Flow<List<Pairing>> = pairings

    override suspend fun all(): List<Pairing> = pairings.value

    override suspend fun find(id: PairingId): Pairing? = pairings.value.find { it.id == id }

    override suspend fun create(
        pairing: Pairing,
        keys: SessionKeys,
    ) {
        check(find(pairing.id) == null)
        pairings.value += pairing
        this.keys[pairing.id] = keys
    }

    override suspend fun update(pairing: Pairing) {
        pairings.value = pairings.value.map { if (it.id == pairing.id) pairing else it }
    }

    override suspend fun keysOf(id: PairingId): SessionKeys? = keys[id]

    override suspend fun forgetKeys(id: PairingId) {
        keys.remove(id)
    }

    override suspend fun delete(id: PairingId) {
        pairings.value = pairings.value.filterNot { it.id == id }
        keys.remove(id)
    }
}

class InMemoryInvitations : InvitationStore {
    private var invitation: Invitation? = null

    override suspend fun current(): Invitation? = invitation

    override suspend fun replace(invitation: Invitation?) {
        this.invitation = invitation
    }
}

class InMemoryOutbox : Outbox {
    private val letters = mutableListOf<Letter>()
    private var nextId = 1L

    override suspend fun add(letter: Letter) {
        letters += letter.copy(id = nextId++)
    }

    override suspend fun pending(): List<Letter> = letters.toList()

    override suspend fun remove(letter: Letter) {
        letters.removeAll { it.id == letter.id }
    }

    override suspend fun removeOlderThan(instant: Instant) {
        letters.removeAll { it.createdAt < instant }
    }
}

class InMemoryReceivedLog : ReceivedLog {
    private val seen = mutableSetOf<Pair<PairingId, Long>>()

    override suspend fun isNew(
        pairingId: PairingId,
        counter: Long,
    ): Boolean = seen.add(pairingId to counter)

    override suspend fun forget(pairingId: PairingId) {
        seen.removeAll { it.first == pairingId }
    }
}

class InMemoryHistory : History {
    private val entries = MutableStateFlow<List<HistoryEntry>>(emptyList())

    val messages: List<BuddyMessage> get() = entries.value.map { it.message }

    override fun observe(pairingId: PairingId): Flow<List<HistoryEntry>> =
        entries.map { all -> all.filter { it.pairingId == pairingId }.sortedByDescending { it.at } }

    override fun observeLatest(): Flow<List<HistoryEntry>> =
        entries.map { all -> all.groupBy { it.pairingId }.values.map { of -> of.maxBy { it.at } } }

    override suspend fun add(entry: HistoryEntry) {
        entries.value += entry
    }

    override suspend fun forgetBefore(instant: Instant) {
        entries.value = entries.value.filterNot { it.at < instant }
    }

    override suspend fun forget(pairingId: PairingId) {
        entries.value = entries.value.filterNot { it.pairingId == pairingId }
    }
}

class InMemoryProfile(
    override var displayName: String,
) : Profile {
    override var address: PushAddress? = null
}

/** Writes down every notification as a short line, e.g. "limitHit Alex Instagram". */
class RecordingNotifier : BuddyNotifier {
    val shown = mutableListOf<String>()

    override fun pairingRequested(pairing: Pairing) {
        shown += "pairingRequested ${pairing.displayName}"
    }

    override fun pairingAccepted(pairing: Pairing) {
        shown += "pairingAccepted ${pairing.displayName}"
    }

    override fun pairingEnded(pairing: Pairing) {
        shown += "pairingEnded ${pairing.displayName}"
    }

    override fun limitHit(
        pairing: Pairing,
        message: BuddyMessage.LimitHit,
    ) {
        shown += "limitHit ${pairing.displayName} ${message.limitName}"
    }

    override fun setupWeakened(
        pairing: Pairing,
        message: BuddyMessage.SetupWeakened,
    ) {
        shown += "setupWeakened ${pairing.displayName} ${message::class.simpleName}"
    }

    override fun daySummary(
        pairing: Pairing,
        message: BuddyMessage.DayClosed,
    ) {
        shown += "daySummary ${pairing.displayName} ${message.entries.joinToString { it.limitName }}"
    }

    override fun silent(pairing: Pairing) {
        shown += "silent ${pairing.displayName}"
    }

    override fun asTestBuddy(
        senderName: String,
        message: BuddyMessage,
    ) {
        shown += "asTestBuddy $senderName ${message::class.simpleName}"
    }
}
