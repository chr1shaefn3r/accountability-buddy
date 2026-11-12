package dev.chr1shaefn3r.accountabilitybuddy.buddies.application

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationCode
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushUnavailableException
import dev.chr1shaefn3r.accountabilitybuddy.testing.MutableClock
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuddiesServiceTest {
    private val clock = MutableClock("2026-11-12T10:00:00")
    private val network = Network()
    private val alex = Phone("Alex", network, clock)
    private val sam = Phone("Sam", network, clock)

    private val instagramHit = BuddyMessage.LimitHit("Instagram", 60, 1_800_000_000)
    private val quietDay = BuddyMessage.DayClosed("2026-11-12", emptyList())
    private val badDay =
        BuddyMessage.DayClosed(
            "2026-11-12",
            listOf(BuddyMessage.DayClosed.Entry("Instagram", 60, 105)),
        )

    /** The buddy scans the sender's code as a real phone would: from its text form. */
    private suspend fun Phone.scan(
        sender: Phone,
        asName: String = profile.displayName,
    ): PairingRequestResult {
        val code = InvitationCode.parse(sender.buddies.invite().encode())!!
        return buddies.requestPairing(code, asName).also { network.settle() }
    }

    private suspend fun pair(
        sender: Phone,
        buddy: Phone,
    ) {
        buddy.scan(sender)
        sender.buddies.confirm(
            sender.pairings
                .all()
                .last()
                .id,
        )
        network.settle()
        sender.notifier.shown.clear()
        buddy.notifier.shown.clear()
    }

    private suspend fun Phone.tell(message: BuddyMessage) {
        buddies.tellBuddies(message)
        network.settle()
    }

    // ---- Pairing -------------------------------------------------------------------

    @Test
    fun `scanning a code asks the sender for confirmation`() =
        runTest {
            val result = sam.scan(alex, asName = "Sammy")

            assertEquals(PairingRequestResult.REQUESTED, result)
            assertEquals(PairingState.REQUESTED, sam.onlyPairing().state)
            assertEquals("Alex", sam.onlyPairing().displayName)
            assertEquals(PartnerRole.SENDER, sam.onlyPairing().partnerRole)
            assertEquals(PairingState.AWAITING_CONFIRMATION, alex.onlyPairing().state)
            assertEquals(PartnerRole.BUDDY, alex.onlyPairing().partnerRole)
            assertEquals(listOf("pairingRequested Sammy"), alex.notifier.shown)
        }

    @Test
    fun `confirming makes the pairing active on both phones`() =
        runTest {
            sam.scan(alex)

            alex.buddies.confirm(alex.onlyPairing().id)
            network.settle()

            assertEquals(PairingState.ACTIVE, alex.onlyPairing().state)
            assertEquals(PairingState.ACTIVE, sam.onlyPairing().state)
            assertEquals(listOf("pairingAccepted Alex"), sam.notifier.shown)
        }

    @Test
    fun `declining tells the buddy and leaves nothing on the sender's phone`() =
        runTest {
            sam.scan(alex)

            alex.buddies.decline(alex.onlyPairing().id)
            network.settle()

            assertEquals(emptyList(), alex.pairings.all())
            assertEquals(PairingState.ENDED, sam.onlyPairing().state)
            assertEquals(listOf("pairingEnded Alex"), sam.notifier.shown)
        }

    @Test
    fun `nothing is reported before the sender has confirmed`() =
        runTest {
            sam.scan(alex)

            alex.tell(instagramHit)

            assertEquals(emptyList(), sam.history.messages)
        }

    @Test
    fun `an expired code cannot be used`() =
        runTest {
            val code = alex.buddies.invite()
            clock.advance(Duration.ofMinutes(10))

            val result = sam.buddies.requestPairing(code, "Sam")

            assertEquals(PairingRequestResult.EXPIRED, result)
            assertEquals(emptyList(), sam.pairings.all())
        }

    @Test
    fun `a request that arrives after the invitation expired is ignored`() =
        runTest {
            val code = alex.buddies.invite()
            network.online = false
            sam.buddies.requestPairing(code, "Sam")
            clock.advance(Duration.ofMinutes(11))
            network.online = true
            sam.buddies.deliver()

            assertEquals(emptyList(), alex.pairings.all())
        }

    @Test
    fun `an invitation works for one buddy only`() =
        runTest {
            val kim = Phone("Kim", network, clock)
            val code = alex.buddies.invite()

            sam.buddies.requestPairing(code, "Sam")
            kim.buddies.requestPairing(code, "Kim")
            network.settle()

            assertEquals(listOf("Sam"), alex.pairings.all().map { it.partnerName })
        }

    @Test
    fun `a new invitation replaces the previous one`() =
        runTest {
            val old = alex.buddies.invite()
            alex.buddies.invite()

            sam.buddies.requestPairing(old, "Sam")
            network.settle()

            assertEquals(emptyList(), alex.pairings.all())
        }

    @Test
    fun `nobody can pair without having seen the code`() =
        runTest {
            val real = alex.buddies.invite()
            val guessed =
                InvitationCode(
                    real.pairingId,
                    real.publicKey,
                    ByteArray(16),
                    real.senderAddress,
                    "Alex",
                    real.expiresAt,
                )

            sam.buddies.requestPairing(guessed, "Mallory")
            network.settle()

            assertEquals(emptyList(), alex.pairings.all())
            assertEquals(emptyList(), alex.notifier.shown)
        }

    @Test
    fun `scanning the same code twice or one's own code changes nothing`() =
        runTest {
            val code = alex.buddies.invite()
            sam.buddies.requestPairing(code, "Sam")

            assertEquals(PairingRequestResult.ALREADY_KNOWN, sam.buddies.requestPairing(code, "Sam"))
            assertEquals(PairingRequestResult.OWN_INVITATION, alex.buddies.requestPairing(code, "Alex"))
            assertEquals(1, sam.pairings.all().size)
        }

    @Test
    fun `without the push service no invitation can be created`() =
        runTest {
            alex.pushAvailable = false

            assertFailsWith<PushUnavailableException> { alex.buddies.invite() }
        }

    @Test
    fun `two friends can be each other's buddy`() =
        runTest {
            pair(sender = alex, buddy = sam)
            pair(sender = sam, buddy = alex)

            alex.tell(instagramHit)
            sam.tell(BuddyMessage.LimitHit("TikTok", 30, 1_800_000_000))

            assertEquals(listOf("limitHit Alex Instagram"), sam.notifier.shown)
            assertEquals(listOf("limitHit Sam TikTok"), alex.notifier.shown)
        }

    // ---- Reporting -----------------------------------------------------------------

    @Test
    fun `a limit hit reaches the buddy as notification and history entry`() =
        runTest {
            pair(alex, sam)

            alex.tell(instagramHit)

            assertEquals(listOf("limitHit Alex Instagram"), sam.notifier.shown)
            assertEquals(listOf<BuddyMessage>(instagramHit), sam.history.messages)
        }

    @Test
    fun `every buddy of a sender is told`() =
        runTest {
            val kim = Phone("Kim", network, clock)
            pair(alex, sam)
            pair(alex, kim)

            alex.tell(instagramHit)

            assertEquals(listOf("limitHit Alex Instagram"), sam.notifier.shown)
            assertEquals(listOf("limitHit Alex Instagram"), kim.notifier.shown)
        }

    @Test
    fun `a buddy does not tell their own buddies' news to the senders they support`() =
        runTest {
            pair(sender = alex, buddy = sam)

            sam.tell(instagramHit)

            assertEquals(emptyList(), alex.notifier.shown)
        }

    @Test
    fun `with instant notifications off a hit is only kept in the history`() =
        runTest {
            pair(alex, sam)
            sam.buddies.configure(sam.onlyPairing().id, instantNotifications = false, daySummary = false)

            alex.tell(instagramHit)
            alex.tell(BuddyMessage.LimitDeleted("Instagram", 60))

            assertEquals(emptyList(), sam.notifier.shown)
            assertEquals(2, sam.history.messages.size)
        }

    @Test
    fun `a weakened setup is reported to the buddy`() =
        runTest {
            pair(alex, sam)

            alex.tell(BuddyMessage.BudgetRaised("Instagram", 60, 120))

            assertEquals(listOf("setupWeakened Alex BudgetRaised"), sam.notifier.shown)
        }

    @Test
    fun `the day summary is not shown unless the buddy subscribed`() =
        runTest {
            pair(alex, sam)

            alex.tell(badDay)

            assertEquals(emptyList(), sam.notifier.shown)
            assertEquals(listOf<BuddyMessage>(badDay), sam.history.messages)
        }

    @Test
    fun `a subscribed buddy gets the day summary`() =
        runTest {
            pair(alex, sam)
            sam.buddies.configure(sam.onlyPairing().id, instantNotifications = true, daySummary = true)

            alex.tell(badDay)

            assertEquals(listOf("daySummary Alex Instagram"), sam.notifier.shown)
        }

    @Test
    fun `a day without hits shows nothing, even to a subscribed buddy`() =
        runTest {
            pair(alex, sam)
            sam.buddies.configure(sam.onlyPairing().id, instantNotifications = true, daySummary = true)

            alex.tell(quietDay)

            assertEquals(emptyList(), sam.notifier.shown)
            assertEquals(emptyList(), sam.history.messages)
        }

    // ---- Silence -------------------------------------------------------------------

    @Test
    fun `a sender who closes every day never causes a silence alert`() =
        runTest {
            pair(alex, sam)

            repeat(10) {
                clock.advance(Duration.ofDays(1))
                alex.tell(quietDay)
                sam.buddies.checkSilence()
            }

            assertEquals(emptyList(), sam.notifier.shown)
        }

    @Test
    fun `seven days without a sign of life raise one silence alert`() =
        runTest {
            pair(alex, sam)
            alex.uninstall()

            repeat(10) {
                clock.advance(Duration.ofDays(1))
                sam.buddies.checkSilence()
            }

            assertEquals(listOf("silent Alex"), sam.notifier.shown)
        }

    @Test
    fun `after a sign of life the silence can be alerted again`() =
        runTest {
            pair(alex, sam)
            clock.advance(Duration.ofDays(8))
            sam.buddies.checkSilence()
            alex.tell(quietDay)
            clock.advance(Duration.ofDays(8))

            sam.buddies.checkSilence()

            assertEquals(listOf("silent Alex", "silent Alex"), sam.notifier.shown)
        }

    @Test
    fun `a sender is never alerted about a silent buddy`() =
        runTest {
            pair(alex, sam)
            clock.advance(Duration.ofDays(30))

            alex.buddies.checkSilence()

            assertEquals(emptyList(), alex.notifier.shown)
        }

    @Test
    fun `history older than thirty days is forgotten`() =
        runTest {
            pair(alex, sam)
            alex.tell(instagramHit)
            clock.advance(Duration.ofDays(29))
            alex.tell(BuddyMessage.LimitHit("TikTok", 30, 1_800_000_000))
            clock.advance(Duration.ofDays(2))

            sam.buddies.checkSilence()

            assertEquals(listOf("TikTok"), sam.history.messages.map { (it as BuddyMessage.LimitHit).limitName })
        }

    // ---- Ending --------------------------------------------------------------------

    @Test
    fun `a removed buddy is told and hears nothing afterwards`() =
        runTest {
            pair(alex, sam)

            alex.buddies.end(alex.onlyPairing().id)
            network.settle()
            alex.tell(instagramHit)

            assertEquals(emptyList(), alex.pairings.all())
            assertEquals(PairingState.ENDED, sam.onlyPairing().state)
            assertEquals(listOf("pairingEnded Alex"), sam.notifier.shown)
        }

    @Test
    fun `a buddy can stop supporting and the sender is told`() =
        runTest {
            pair(alex, sam)

            sam.buddies.end(sam.onlyPairing().id)
            network.settle()

            assertEquals(emptyList(), sam.pairings.all())
            assertEquals(PairingState.ENDED, alex.onlyPairing().state)
            assertEquals(listOf("pairingEnded Sam"), alex.notifier.shown)
        }

    @Test
    fun `the keys of an ended pairing are deleted on both phones`() =
        runTest {
            pair(alex, sam)
            val id = alex.onlyPairing().id

            alex.buddies.end(id)
            network.settle()

            assertNull(alex.pairings.keysOf(id))
            assertNull(sam.pairings.keysOf(id))
        }

    @Test
    fun `clearing away an ended pairing sends nothing`() =
        runTest {
            pair(alex, sam)
            alex.buddies.end(alex.onlyPairing().id)
            network.settle()
            val trafficBefore = network.traffic.size

            sam.buddies.end(sam.onlyPairing().id)
            network.settle()

            assertEquals(emptyList(), sam.pairings.all())
            assertEquals(trafficBefore, network.traffic.size)
        }

    // ---- Names ---------------------------------------------------------------------

    @Test
    fun `a changed display name reaches all partners`() =
        runTest {
            pair(alex, sam)

            sam.buddies.changeDisplayName("Samantha")
            network.settle()

            assertEquals("Samantha", alex.onlyPairing().displayName)
        }

    @Test
    fun `a nickname stays when the partner changes their name`() =
        runTest {
            pair(alex, sam)
            alex.buddies.rename(alex.onlyPairing().id, "Sam from work")

            sam.buddies.changeDisplayName("Samantha")
            network.settle()

            assertEquals("Sam from work", alex.onlyPairing().displayName)
        }

    @Test
    fun `an empty nickname brings back the partner's own name`() =
        runTest {
            pair(alex, sam)
            alex.buddies.rename(alex.onlyPairing().id, "Sammy")

            alex.buddies.rename(alex.onlyPairing().id, "  ")

            assertEquals("Sam", alex.onlyPairing().displayName)
        }

    // ---- Delivery ------------------------------------------------------------------

    @Test
    fun `messages wait while offline and arrive in order afterwards`() =
        runTest {
            pair(alex, sam)
            network.online = false
            alex.tell(instagramHit)
            alex.tell(BuddyMessage.LimitDeleted("Instagram", 60))
            assertEquals(emptyList(), sam.history.messages)

            network.online = true
            val allSent = alex.buddies.deliver()

            assertTrue(allSent)
            assertEquals(listOf(instagramHit, BuddyMessage.LimitDeleted("Instagram", 60)), sam.history.messages)
        }

    @Test
    fun `delivery reports when it has to be tried again`() =
        runTest {
            pair(alex, sam)
            network.online = false
            alex.buddies.tellBuddies(instagramHit)

            assertEquals(false, alex.buddies.deliver())
        }

    @Test
    fun `messages older than three days are given up`() =
        runTest {
            pair(alex, sam)
            network.online = false
            alex.tell(instagramHit)
            clock.advance(Duration.ofDays(3).plusMinutes(1))
            network.online = true

            alex.buddies.deliver()

            assertEquals(emptyList(), sam.history.messages)
            assertEquals(emptyList(), alex.outbox.pending())
        }

    @Test
    fun `a message delivered twice is handled once`() =
        runTest {
            pair(alex, sam)
            alex.tell(instagramHit)
            val (address, wire) = network.traffic.last()

            network.send(address, wire)

            assertEquals(1, sam.history.messages.size)
            assertEquals(1, sam.notifier.shown.size)
        }

    @Test
    fun `a message for one buddy means nothing to another phone`() =
        runTest {
            val kim = Phone("Kim", network, clock)
            pair(alex, sam)
            pair(alex, kim)
            alex.tell(instagramHit)
            val forSam = network.traffic.first { it.first == sam.address }.second
            kim.notifier.shown.clear()

            kim.buddies.receive(forSam)

            assertEquals(emptyList(), kim.notifier.shown)
        }

    @Test
    fun `everything on the wire has the same size and hides its content`() =
        runTest {
            pair(alex, sam)
            alex.tell(instagramHit)
            alex.tell(badDay)
            alex.tell(quietDay)

            assertEquals(
                1,
                network.traffic
                    .map { it.second.size }
                    .toSet()
                    .size,
            )
            assertTrue(network.traffic.none { "Instagram" in it.second.decodeToString(throwOnInvalidSequence = false) })
            assertTrue(network.traffic.none { "Sam" in it.second.decodeToString(throwOnInvalidSequence = false) })
        }

    @Test
    fun `garbage from the network is ignored`() =
        runTest {
            pair(alex, sam)

            sam.buddies.receive(ByteArray(0))
            sam.buddies.receive(ByteArray(2125) { 1 })

            assertEquals(emptyList(), sam.notifier.shown)
        }

    // ---- Addresses -----------------------------------------------------------------

    @Test
    fun `a phone tells its partners when its push address changes`() =
        runTest {
            pair(alex, sam)
            sam.address = PushAddress("new-address-of-Sam")

            sam.buddies.refreshAddress()
            network.settle()
            alex.tell(instagramHit)

            assertEquals(PushAddress("new-address-of-Sam"), alex.onlyPairing().partnerAddress)
            assertEquals(listOf("limitHit Alex Instagram"), sam.notifier.shown)
        }

    @Test
    fun `an unchanged address is not announced again`() =
        runTest {
            pair(alex, sam)
            val trafficBefore = network.traffic.size

            sam.buddies.refreshAddress()
            network.settle()

            assertEquals(trafficBefore, network.traffic.size)
        }

    @Test
    fun `a partner whose address is gone becomes unreachable`() =
        runTest {
            pair(alex, sam)
            sam.uninstall()

            alex.tell(instagramHit)

            assertEquals(PairingState.UNREACHABLE, alex.onlyPairing().state)
        }

    @Test
    fun `an unreachable partner gets the waiting messages once a new address is known`() =
        runTest {
            pair(alex, sam)
            val oldAddress = sam.address
            sam.address = PushAddress("new-address-of-Sam")
            alex.tell(instagramHit)
            assertEquals(PairingState.UNREACHABLE, alex.onlyPairing().state)

            sam.buddies.refreshAddress()
            network.settle()

            assertEquals(PairingState.ACTIVE, alex.onlyPairing().state)
            assertEquals(listOf("limitHit Alex Instagram"), sam.notifier.shown)
            assertTrue(oldAddress != alex.onlyPairing().partnerAddress)
        }

    @Test
    fun `the latest history entry of each sender can be observed`() =
        runTest {
            pair(alex, sam)
            alex.tell(instagramHit)
            clock.advance(Duration.ofMinutes(5))
            alex.tell(BuddyMessage.LimitDeleted("Instagram", 60))

            val latest = sam.history.observeLatest().first()

            assertEquals(listOf<BuddyMessage>(BuddyMessage.LimitDeleted("Instagram", 60)), latest.map { it.message })
        }
}
