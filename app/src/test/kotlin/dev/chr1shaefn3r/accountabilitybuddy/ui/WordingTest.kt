package dev.chr1shaefn3r.accountabilitybuddy.ui

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class WordingTest {
    @Test
    fun `durations are written as hours and minutes`() {
        assertEquals("1h 45m", Duration.ofMinutes(105).asText())
        assertEquals("2h", Duration.ofHours(2).asText())
        assertEquals("45m", Duration.ofMinutes(45).asText())
        assertEquals("0m", Duration.ofSeconds(59).asText())
        assertEquals("24h", Duration.ofHours(24).asText())
    }

    @Test
    fun `a reached limit is stated as a fact`() {
        assertEquals(
            "Social Media: 1h limit reached",
            BuddyMessage.LimitHit("Social Media", 60, 0).asText(),
        )
    }

    @Test
    fun `weakenings say what changed`() {
        assertEquals(
            "Instagram: limit raised from 1h to 1h 30m",
            BuddyMessage.BudgetRaised("Instagram", 60, 90).asText(),
        )
        assertEquals(
            "Social: TikTok, Snapchat removed from the limit",
            BuddyMessage.AppsRemoved("Social", listOf("TikTok", "Snapchat")).asText(),
        )
        assertEquals("Instagram: 1h limit deleted", BuddyMessage.LimitDeleted("Instagram", 60).asText())
        assertEquals(
            "Usage tracking was switched off",
            BuddyMessage.TrackingLost(BuddyMessage.TrackingLost.Problem.USAGE_ACCESS_MISSING).asText(),
        )
    }

    @Test
    fun `a day summary lists usage against each limit`() {
        val day =
            BuddyMessage.DayClosed(
                "2026-11-12",
                listOf(
                    BuddyMessage.DayClosed.Entry("Instagram", 60, 105),
                    BuddyMessage.DayClosed.Entry("Games", 30, 31),
                ),
            )

        assertEquals("Instagram: 1h 45m of 1h\nGames: 31m of 30m", day.asText())
    }

    @Test
    fun `dates are written relative to today where that is clearer`() {
        val today = LocalDate.of(2026, 11, 12)

        assertEquals("Today", today.asTextRelativeTo(today))
        assertEquals("Yesterday", today.minusDays(1).asTextRelativeTo(today))
        assertEquals("Tuesday, 10 Nov", today.minusDays(2).asTextRelativeTo(today))
    }

    @Test
    fun `quiet senders are described by how long they have been quiet`() {
        assertEquals("Nothing reported yet", quietText(null))
        assertEquals("Last report today", quietText(0))
        assertEquals("Last report yesterday", quietText(1))
        assertEquals("All quiet for 5 days", quietText(5))
    }
}
