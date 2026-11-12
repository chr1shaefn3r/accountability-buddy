package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.HistoryEntry
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Invitation
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Letter
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingId
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.SessionKeys
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.crypto.TinkCryptography
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The database adapters against a real (in-memory) SQLite database. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class RoomAdaptersTest {
    private val database =
        Room
            .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /** Stands in for the Android Keystore: recognisably different from the secret, and reversible. */
    private val vault =
        object : Vault {
            override fun protect(secret: ByteArray): ByteArray = secret.reversedArray() + 1

            override fun reveal(protectedSecret: ByteArray): ByteArray? =
                protectedSecret
                    .takeIf { it.last() == 1.toByte() }
                    ?.dropLast(1)
                    ?.reversed()
                    ?.toByteArray()
        }
    private val limits = RoomLimits(database.limits())
    private val pairings = RoomPairings(database.pairings(), vault)
    private val outbox = RoomOutbox(database.messages())
    private val received = RoomReceivedLog(database.messages())
    private val history = RoomHistory(database.messages())

    private val now = Instant.parse("2026-11-12T10:00:00Z")
    private val social =
        Limit(LimitId("social"), "Social Media", Duration.ofMinutes(90), setOf(AppId("instagram"), AppId("tiktok")))
    private val alexId = PairingId("00112233445566778899aabbccddeeff")
    private val alex =
        Pairing(alexId, PartnerRole.SENDER, "Alex", PushAddress("fid-alex"), PairingState.ACTIVE, lastSignOfLife = now)
    private val keys = SessionKeys(ByteArray(32) { 1 }, ByteArray(32) { 2 })

    @After
    fun close() = database.close()

    @Test
    fun `a limit comes back as it was saved`() =
        runTest {
            limits.save(social)

            assertEquals(social, limits.find(social.id))
            assertEquals(listOf(social), limits.all())
            assertEquals(listOf(social), limits.observeAll().first())
        }

    @Test
    fun `saving a limit again replaces it and deleting removes it`() =
        runTest {
            limits.save(social)
            limits.save(social.copy(name = "Social", apps = setOf(AppId("instagram"))))
            assertEquals("Social", limits.all().single().name)

            limits.delete(social.id)

            assertNull(limits.find(social.id))
        }

    @Test
    fun `hits are kept per day and old ones can be forgotten`() =
        runTest {
            val day = Day(LocalDate.of(2026, 11, 12))
            val hit = LimitHit(day, social.id, social.name, social.budget, social.apps, now)
            limits.record(hit)
            limits.record(hit.copy(at = now.plusSeconds(60)))
            limits.record(hit.copy(day = day.previous()))

            assertEquals(listOf(hit), limits.hitsOn(day))

            limits.forgetBefore(day)
            assertEquals(emptyList(), limits.hitsOn(day.previous()))
            assertEquals(1, limits.hitsOn(day).size)
        }

    @Test
    fun `a pairing comes back as it was saved, with its keys`() =
        runTest {
            pairings.create(alex, keys)
            val changed =
                alex.copy(
                    nickname = "Al",
                    state = PairingState.UNREACHABLE,
                    daySummary = true,
                    instantNotifications = false,
                    silenceAlerted = true,
                    nextCounter = 7,
                    partnerAddress = PushAddress("fid-new"),
                )

            pairings.update(changed)

            assertEquals(changed, pairings.find(alexId))
            assertEquals(listOf(changed), pairings.observeAll().first())
            assertContentEquals(keys.toBytes(), pairings.keysOf(alexId)?.toBytes())
        }

    @Test
    fun `keys are not stored as they are`() =
        runTest {
            pairings.create(alex, keys)

            val stored = assertNotNull(database.pairings().keysOf(alexId.hex)).protectedKeys

            assertFalse(stored.contentEquals(keys.toBytes()))
        }

    @Test
    fun `keys can be forgotten while the pairing stays, and deleting removes both`() =
        runTest {
            pairings.create(alex, keys)

            pairings.forgetKeys(alexId)
            assertNull(pairings.keysOf(alexId))
            assertNotNull(pairings.find(alexId))

            pairings.delete(alexId)
            assertNull(pairings.find(alexId))
        }

    @Test
    fun `there is one open invitation at most`() =
        runTest {
            val first = Invitation.create(TinkCryptography(), now)
            val second = Invitation.create(TinkCryptography(), now)
            assertNull(pairings.current())

            pairings.replace(first)
            pairings.replace(second)

            val stored = assertNotNull(pairings.current())
            assertEquals(second.pairingId, stored.pairingId)
            assertContentEquals(second.privateKey, stored.privateKey)
            assertContentEquals(second.secret, stored.secret)
            assertContentEquals(second.publicKey, stored.publicKey)
            assertEquals(second.expiresAt, stored.expiresAt)

            pairings.replace(null)
            assertNull(pairings.current())
        }

    @Test
    fun `letters leave the outbox in the order they were added`() =
        runTest {
            outbox.add(Letter(alexId, PushAddress("fid-alex"), byteArrayOf(1), now))
            outbox.add(Letter(alexId, PushAddress("fid-alex"), byteArrayOf(2), now.plusSeconds(1)))
            outbox.add(Letter(alexId, PushAddress("fid-alex"), byteArrayOf(3), now.minusSeconds(100)))

            val pending = outbox.pending()
            assertEquals(listOf<Byte>(1, 2, 3), pending.map { it.wire.single() })

            outbox.remove(pending.first())
            outbox.removeOlderThan(now)
            assertEquals(listOf<Byte>(2), outbox.pending().map { it.wire.single() })
        }

    @Test
    fun `a received message is new only once`() =
        runTest {
            assertTrue(received.isNew(alexId, 1))
            assertFalse(received.isNew(alexId, 1))
            assertTrue(received.isNew(alexId, 2))

            received.forget(alexId)
            assertTrue(received.isNew(alexId, 1))
        }

    @Test
    fun `history is kept per pairing, newest first`() =
        runTest {
            val samId = PairingId("ffeeddccbbaa99887766554433221100")
            val hit = BuddyMessage.LimitHit("Instagram", 60, now.epochSecond)
            val summary =
                BuddyMessage.DayClosed(
                    "2026-11-12",
                    listOf(BuddyMessage.DayClosed.Entry("Instagram", 60, 90)),
                )
            history.add(HistoryEntry(alexId, now, hit))
            history.add(HistoryEntry(alexId, now.plusSeconds(3600), summary))
            history.add(HistoryEntry(samId, now, hit))

            assertEquals(listOf(summary, hit), history.observe(alexId).first().map { it.message })
            assertEquals(
                mapOf(alexId to summary, samId to hit),
                history.observeLatest().first().associate { it.pairingId to it.message },
            )

            history.forgetBefore(now.plusSeconds(1))
            assertEquals(listOf<BuddyMessage>(summary), history.observe(alexId).first().map { it.message })

            history.forget(alexId)
            assertEquals(emptyList(), history.observe(alexId).first())
        }
}
