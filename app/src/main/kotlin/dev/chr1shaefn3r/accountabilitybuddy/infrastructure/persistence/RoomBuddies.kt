package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.History
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.HistoryEntry
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Invitation
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationStore
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Letter
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Outbox
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingId
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingRepository
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.ReceivedLog
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.SessionKeys
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant

/** Protects secrets before they are written to the database. */
interface Vault {
    fun protect(secret: ByteArray): ByteArray

    /** Returns null if the secret cannot be recovered, for example after a restore to another phone. */
    fun reveal(protectedSecret: ByteArray): ByteArray?
}

/** Stores pairings, their keys and the open invitation in the local database. */
class RoomPairings(
    private val dao: PairingDao,
    private val vault: Vault,
) : PairingRepository,
    InvitationStore {
    override fun observeAll(): Flow<List<Pairing>> = dao.observeAll().map { rows -> rows.map { it.toPairing() } }

    override suspend fun all(): List<Pairing> = dao.all().map { it.toPairing() }

    override suspend fun find(id: PairingId): Pairing? = dao.find(id.hex)?.toPairing()

    override suspend fun create(
        pairing: Pairing,
        keys: SessionKeys,
    ) {
        dao.insert(pairing.toRow())
        dao.saveKeys(PairingKeysRow(pairing.id.hex, vault.protect(keys.toBytes())))
    }

    override suspend fun update(pairing: Pairing) = dao.update(pairing.toRow())

    override suspend fun keysOf(id: PairingId): SessionKeys? =
        dao.keysOf(id.hex)?.let { vault.reveal(it.protectedKeys) }?.let(SessionKeys::fromBytes)

    override suspend fun forgetKeys(id: PairingId) = dao.forgetKeys(id.hex)

    override suspend fun delete(id: PairingId) {
        dao.delete(id.hex)
        dao.forgetKeys(id.hex)
    }

    override suspend fun current(): Invitation? {
        val row = dao.invitation() ?: return null
        return Invitation(
            pairingId = PairingId(row.pairingId),
            secret = vault.reveal(row.protectedSecret) ?: return null,
            privateKey = vault.reveal(row.protectedPrivateKey) ?: return null,
            publicKey = row.publicKey,
            expiresAt = Instant.ofEpochMilli(row.expiresAt),
        )
    }

    override suspend fun replace(invitation: Invitation?) {
        if (invitation == null) {
            dao.clearInvitation()
            return
        }
        dao.saveInvitation(
            InvitationRow(
                slot = 0,
                pairingId = invitation.pairingId.hex,
                protectedSecret = vault.protect(invitation.secret),
                protectedPrivateKey = vault.protect(invitation.privateKey),
                publicKey = invitation.publicKey,
                expiresAt = invitation.expiresAt.toEpochMilli(),
            ),
        )
    }

    private fun PairingRow.toPairing() =
        Pairing(
            id = PairingId(id),
            partnerRole = PartnerRole.valueOf(partnerRole),
            partnerName = partnerName,
            partnerAddress = PushAddress(partnerAddress),
            state = PairingState.valueOf(state),
            lastSignOfLife = Instant.ofEpochMilli(lastSignOfLife),
            nickname = nickname,
            instantNotifications = instantNotifications,
            daySummary = daySummary,
            silenceAlerted = silenceAlerted,
            nextCounter = nextCounter,
        )

    private fun Pairing.toRow() =
        PairingRow(
            id = id.hex,
            partnerRole = partnerRole.name,
            partnerName = partnerName,
            partnerAddress = partnerAddress.id,
            state = state.name,
            lastSignOfLife = lastSignOfLife.toEpochMilli(),
            nickname = nickname,
            instantNotifications = instantNotifications,
            daySummary = daySummary,
            silenceAlerted = silenceAlerted,
            nextCounter = nextCounter,
        )
}

/** Stores the letters that still have to be sent in the local database. */
class RoomOutbox(
    private val dao: MessageDao,
) : Outbox {
    override suspend fun add(letter: Letter) =
        dao.enqueue(
            OutboxRow(0, letter.pairingId.hex, letter.address.id, letter.wire, letter.createdAt.toEpochMilli()),
        )

    override suspend fun pending(): List<Letter> =
        dao.pending().map {
            Letter(PairingId(it.pairingId), PushAddress(it.address), it.wire, Instant.ofEpochMilli(it.createdAt), it.id)
        }

    override suspend fun remove(letter: Letter) = dao.removeFromOutbox(letter.id)

    override suspend fun removeOlderThan(instant: Instant) = dao.removeFromOutboxBefore(instant.toEpochMilli())
}

/** Remembers received messages in the local database. */
class RoomReceivedLog(
    private val dao: MessageDao,
) : ReceivedLog {
    override suspend fun isNew(
        pairingId: PairingId,
        counter: Long,
    ): Boolean = dao.recordReceived(ReceivedRow(pairingId.hex, counter)) != ALREADY_RECORDED

    override suspend fun forget(pairingId: PairingId) = dao.forgetReceived(pairingId.hex)

    private companion object {
        const val ALREADY_RECORDED = -1L
    }
}

/** Keeps what senders reported in the local database. */
class RoomHistory(
    private val dao: MessageDao,
) : History {
    override fun observe(pairingId: PairingId): Flow<List<HistoryEntry>> =
        dao.observeHistory(pairingId.hex).map { rows -> rows.mapNotNull { it.toEntry() } }

    override fun observeLatest(): Flow<List<HistoryEntry>> =
        dao.observeLatestHistory().map { rows -> rows.mapNotNull { it.toEntry() } }

    override suspend fun add(entry: HistoryEntry) =
        dao.addToHistory(
            HistoryRow(
                id = 0,
                pairingId = entry.pairingId.hex,
                at = entry.at.toEpochMilli(),
                message = BuddyMessage.encode(entry.message).decodeToString(),
            ),
        )

    override suspend fun forgetBefore(instant: Instant) = dao.forgetHistoryBefore(instant.toEpochMilli())

    override suspend fun forget(pairingId: PairingId) = dao.forgetHistory(pairingId.hex)

    private fun HistoryRow.toEntry(): HistoryEntry? =
        BuddyMessage.decode(message.encodeToByteArray())?.let {
            HistoryEntry(PairingId(pairingId), Instant.ofEpochMilli(at), it)
        }
}
