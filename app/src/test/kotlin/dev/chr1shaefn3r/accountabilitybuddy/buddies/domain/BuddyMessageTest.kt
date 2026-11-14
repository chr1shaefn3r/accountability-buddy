package dev.chr1shaefn3r.accountabilitybuddy.buddies.domain

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BuddyMessageTest {
    private val everyKind =
        listOf(
            BuddyMessage.PairingRequested("Sam", "token"),
            BuddyMessage.PairingAccepted,
            BuddyMessage.PairingDeclined,
            BuddyMessage.PairingEnded,
            BuddyMessage.AddressChanged("new-token"),
            BuddyMessage.NameChanged("Samantha"),
            BuddyMessage.LimitHit("Instagram", 60, 1_800_000_000),
            BuddyMessage.LimitHit("Instagram", 60, 1_800_000_000, usedMinutes = 72, stillGoing = true),
            BuddyMessage.LimitHit("Instagram", 60, 1_800_000_000, usedMinutes = 60, stillGoing = false),
            BuddyMessage.BudgetRaised("Instagram", 60, 120),
            BuddyMessage.AppsRemoved("Social Media", listOf("TikTok")),
            BuddyMessage.LimitDeleted("Instagram", 60),
            BuddyMessage.TrackingLost(BuddyMessage.TrackingLost.Problem.USAGE_ACCESS_MISSING),
            BuddyMessage.DayClosed("2026-11-12", listOf(BuddyMessage.DayClosed.Entry("Instagram", 60, 105))),
            BuddyMessage.DayClosed("2026-11-12", emptyList()),
        )

    @Test
    fun `every kind of message survives encoding`() {
        for (message in everyKind) {
            assertEquals(message, BuddyMessage.decode(BuddyMessage.encode(message)))
        }
    }

    @Test
    fun `a limit hit from an older version, which does not say how it went on, is still understood`() {
        val fromOlderVersion = """{"k":"hit","n":"Instagram","b":60,"t":1800000000}""".encodeToByteArray()

        val hit = BuddyMessage.decode(fromOlderVersion) as BuddyMessage.LimitHit

        assertEquals(BuddyMessage.LimitHit("Instagram", 60, 1_800_000_000), hit)
        assertNull(hit.used)
        assertNull(hit.stillGoing)
    }

    @Test
    fun `a limit hit says what was used when it was sent`() {
        val hit = BuddyMessage.LimitHit("Instagram", 60, 1_800_000_000, usedMinutes = 72, stillGoing = true)

        assertEquals(Duration.ofMinutes(72), hit.used)
    }

    @Test
    fun `a message of an unknown kind is ignored`() {
        assertNull(BuddyMessage.decode("""{"k":"from-the-future","x":1}""".encodeToByteArray()))
    }

    @Test
    fun `garbage is not a message`() {
        assertNull(BuddyMessage.decode("not json".encodeToByteArray()))
        assertNull(BuddyMessage.decode(ByteArray(0)))
    }

    @Test
    fun `messages offer their values in domain terms`() {
        val closed = BuddyMessage.DayClosed("2026-11-12", listOf(BuddyMessage.DayClosed.Entry("Instagram", 60, 105)))

        assertEquals(LocalDate.of(2026, 11, 12), closed.day)
        assertEquals(Duration.ofMinutes(105), closed.entries.single().used)
        assertEquals(Duration.ofHours(1), closed.entries.single().budget)
    }

    @Test
    fun `the fullest possible day summary fits into one envelope`() {
        val longestName = "ü".repeat(Limit.MAX_NAME_LENGTH)
        val fullest =
            BuddyMessage.DayClosed(
                "2026-11-12",
                List(BuddyMessage.DayClosed.MAX_ENTRIES) { BuddyMessage.DayClosed.Entry(longestName, 1440, 1440) },
            )

        assertTrue(BuddyMessage.encode(fullest).size <= SecureChannel.MAX_MESSAGE_SIZE)
    }
}
