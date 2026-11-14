package dev.chr1shaefn3r.accountabilitybuddy.buddies.application

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.InvitationCode
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PartnerRole
import dev.chr1shaefn3r.accountabilitybuddy.testing.MutableClock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The buddy that lives on the sender's own phone, for trying the app out. */
class TestBuddyTest {
    private val clock = MutableClock("2026-11-12T10:00:00")
    private val network = Network()
    private val alex = Phone("Alex", network, clock)
    private val sam = Phone("Sam", network, clock)

    private val instagramHit =
        BuddyMessage.LimitHit(
            "Instagram",
            60,
            1_800_000_000,
            usedMinutes = 72,
            stillGoing = true,
        )
    private val quietDay = BuddyMessage.DayClosed("2026-11-12", emptyList())
    private val badDay =
        BuddyMessage.DayClosed(
            "2026-11-12",
            listOf(BuddyMessage.DayClosed.Entry("Instagram", 60, 105)),
        )

    private suspend fun pair(
        sender: Phone,
        buddy: Phone,
    ) {
        val code = InvitationCode.parse(sender.buddies.invite().encode())!!
        buddy.buddies.requestPairing(code, buddy.profile.displayName)
        network.settle()
        sender.buddies.confirm(code.pairingId)
        network.settle()
        sender.notifier.shown.clear()
        buddy.notifier.shown.clear()
    }

    private suspend fun Phone.tell(message: BuddyMessage) {
        buddies.tellBuddies(message)
        network.settle()
    }

    @Test
    fun `a test buddy shows on the sender's own phone what a buddy would be told`() =
        runTest {
            alex.buddies.addTestBuddy()

            alex.tell(instagramHit)
            alex.tell(BuddyMessage.BudgetRaised("Instagram", 60, 120))
            alex.tell(badDay)

            assertEquals(
                listOf("asTestBuddy Alex LimitHit", "asTestBuddy Alex BudgetRaised", "asTestBuddy Alex DayClosed"),
                alex.notifier.shown,
            )
        }

    @Test
    fun `a test buddy is not shown a day without hits, like any buddy`() =
        runTest {
            alex.buddies.addTestBuddy()

            alex.tell(quietDay)

            assertEquals(emptyList(), alex.notifier.shown)
        }

    @Test
    fun `nothing leaves the phone for a test buddy`() =
        runTest {
            alex.buddies.addTestBuddy()

            alex.tell(instagramHit)
            alex.buddies.changeDisplayName("Alexander")
            network.settle()

            assertEquals(emptyList(), network.traffic)
            assertEquals(emptyList(), alex.outbox.pending())
        }

    @Test
    fun `a test buddy needs no push service`() =
        runTest {
            alex.pushAvailable = false

            alex.buddies.addTestBuddy()
            alex.tell(instagramHit)

            assertEquals(listOf("asTestBuddy Alex LimitHit"), alex.notifier.shown)
        }

    @Test
    fun `a test buddy is listed as an active buddy, once`() =
        runTest {
            alex.buddies.addTestBuddy()
            alex.buddies.addTestBuddy()

            val testBuddy = alex.onlyPairing()
            assertTrue(testBuddy.isTestBuddy)
            assertEquals(PartnerRole.BUDDY, testBuddy.partnerRole)
            assertEquals(PairingState.ACTIVE, testBuddy.state)
            assertEquals("Test buddy", testBuddy.displayName)
        }

    @Test
    fun `real buddies are told alongside a test buddy`() =
        runTest {
            pair(alex, sam)
            alex.buddies.addTestBuddy()

            alex.tell(instagramHit)

            assertEquals(listOf("limitHit Alex Instagram"), sam.notifier.shown)
            assertEquals(listOf("asTestBuddy Alex LimitHit"), alex.notifier.shown)
        }

    @Test
    fun `a removed test buddy is shown nothing more, and removing it tells nobody`() =
        runTest {
            alex.buddies.addTestBuddy()

            alex.buddies.end(alex.onlyPairing().id)
            alex.tell(instagramHit)

            assertEquals(emptyList(), alex.pairings.all())
            assertEquals(emptyList(), alex.notifier.shown)
            assertEquals(emptyList(), network.traffic)
        }

    @Test
    fun `a paired buddy is not a test buddy`() =
        runTest {
            pair(alex, sam)

            assertEquals(false, alex.onlyPairing().isTestBuddy)
        }
}
