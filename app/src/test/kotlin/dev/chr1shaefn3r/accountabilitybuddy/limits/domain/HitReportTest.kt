package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HitReportTest {
    private val reached = Instant.parse("2026-11-12T20:00:00Z")
    private val midnight = Instant.parse("2026-11-13T00:00:00Z")
    private val hour = Duration.ofHours(1)

    @Test
    fun `the waiting time is a fifth of the budget`() {
        assertEquals(Duration.ofMinutes(12), PendingReport.waitingTimeFor(hour))
        assertEquals(Duration.ofMinutes(1), PendingReport.waitingTimeFor(Duration.ofMinutes(5)))
        assertEquals(Duration.ofSeconds(12), PendingReport.waitingTimeFor(Duration.ofMinutes(1)))
    }

    @Test
    fun `a report is due one waiting time after the limit was reached`() {
        val pending = PendingReport.after("1", "Instagram", hour, reached, midnight)

        assertEquals(reached.plusSeconds(12 * 60), pending.dueAt)
        assertFalse(pending.isDueAt(reached.plusSeconds(12 * 60 - 1)))
        assertTrue(pending.isDueAt(reached.plusSeconds(12 * 60)))
    }

    @Test
    fun `a report is due at the end of the day at the latest`() {
        val late = Instant.parse("2026-11-12T23:55:00Z")

        assertEquals(midnight, PendingReport.after("1", "Instagram", hour, late, midnight).dueAt)
    }

    @Test
    fun `a report says what was used, and never less than the budget that was reached`() {
        val pending = PendingReport.after("1", "Instagram", hour, reached, midnight)

        assertEquals(
            HitReport("Instagram", hour, reached, Duration.ofMinutes(72), stillGoing = true),
            pending.report(Duration.ofMinutes(72), stillGoing = true),
        )
        assertEquals(hour, pending.report(Duration.ofMinutes(10), stillGoing = false).used)
    }
}
