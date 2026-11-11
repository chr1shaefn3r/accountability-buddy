package dev.chr1shaefn3r.accountabilitybuddy.limits.application

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.DayClosed
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.ForegroundInterval
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingProblem
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Weakening
import dev.chr1shaefn3r.accountabilitybuddy.testing.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LimitsServiceTest {
    private val clock = MutableClock("2026-11-12T08:00:00")
    private val limits = InMemoryLimits()
    private val hitLog = InMemoryHitLog()
    private val usage = FakeUsageSource()
    private val health = FakeHealth()
    private val memory = InMemoryLimitsMemory()
    private val events = RecordingEvents()
    private val service = LimitsService(limits, hitLog, usage, health, memory, events, clock)

    private val instagram = AppId("instagram")
    private val tiktok = AppId("tiktok")
    private val insta = Limit(LimitId("insta"), "Instagram", Duration.ofHours(1), setOf(instagram))
    private val today = Day(LocalDate.of(2026, 11, 12))

    private fun used(app: AppId, from: String, to: String) {
        usage.intervals += ForegroundInterval(app, clock.at(from), clock.at(to))
    }

    @Test
    fun `a limit that reaches its budget is hit`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T09:00:00")
            clock.set("2026-11-12T09:00:00")

            service.check()

            assertEquals(listOf("Instagram"), events.hits.map { it.limitName })
            assertEquals(clock.instant(), events.hits.single().at)
        }

    @Test
    fun `a limit is reported only once per day however often it is checked`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T10:00:00")
            clock.set("2026-11-12T09:00:00")
            service.check()
            clock.set("2026-11-12T10:00:00")

            service.check()

            assertEquals(1, events.hits.size)
        }

    @Test
    fun `everything is reset at midnight`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T09:00:00")
            clock.set("2026-11-12T09:00:00")
            service.check()
            clock.set("2026-11-13T00:00:30")

            service.check()

            val status = service.today().single()
            assertEquals(Duration.ZERO, status.used)
            assertFalse(status.hit)
        }

    @Test
    fun `a limit can be hit again on the next day`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T09:00:00")
            clock.set("2026-11-12T09:00:00")
            service.check()
            used(instagram, "2026-11-13T08:00:00", "2026-11-13T09:00:00")
            clock.set("2026-11-13T09:00:00")

            service.check()

            assertEquals(2, events.hits.size)
        }

    @Test
    fun `today shows the usage of each limit`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T08:45:00")
            clock.set("2026-11-12T08:45:00")

            assertEquals(listOf(LimitStatus(insta, Duration.ofMinutes(45), hit = false)), service.today())
        }

    @Test
    fun `a day that is over is closed with the final usage of every hit limit`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T09:00:00")
            clock.set("2026-11-12T09:00:00")
            service.check()
            used(instagram, "2026-11-12T20:00:00", "2026-11-12T20:45:00")
            clock.set("2026-11-13T00:00:05")

            service.check()

            assertEquals(
                listOf(
                    DayClosed(
                        today,
                        listOf(DayClosed.Entry("Instagram", Duration.ofHours(1), Duration.ofMinutes(105))),
                    ),
                ),
                events.closedDays,
            )
        }

    @Test
    fun `a day without hits is closed too, as a sign of life`() =
        runTest {
            service.save(insta)
            clock.set("2026-11-13T00:00:05")

            service.check()

            assertEquals(listOf(DayClosed(today, emptyList())), events.closedDays)
        }

    @Test
    fun `a day is closed only once`() =
        runTest {
            service.save(insta)
            clock.set("2026-11-13T00:00:05")
            service.check()

            service.check()

            assertEquals(1, events.closedDays.size)
        }

    @Test
    fun `days missed while the phone was off are closed afterwards`() =
        runTest {
            service.save(insta)
            clock.set("2026-11-15T07:00:00")

            service.check()

            assertEquals(
                listOf(today, today.next(), today.next().next()),
                events.closedDays.map { it.day },
            )
        }

    @Test
    fun `at most the last seven days are closed afterwards`() =
        runTest {
            service.save(insta)
            clock.set("2026-12-24T07:00:00")

            service.check()

            assertEquals(7, events.closedDays.size)
            assertEquals(Day(LocalDate.of(2026, 12, 23)), events.closedDays.last().day)
        }

    @Test
    fun `a limit reached unnoticed just before midnight still counts for that day`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T22:59:50", "2026-11-12T23:59:55")
            clock.set("2026-11-13T00:00:05")

            service.check()

            assertEquals(today, events.hits.single().day)
            assertEquals(listOf("Instagram"), events.closedDays.single().entries.map { it.limitName })
        }

    @Test
    fun `a limit created today is not applied to yesterday`() =
        runTest {
            service.save(insta)
            used(tiktok, "2026-11-12T10:00:00", "2026-11-12T12:00:00")
            clock.set("2026-11-13T07:00:00")

            service.save(Limit(LimitId("tiktok"), "TikTok", Duration.ofHours(1), setOf(tiktok)))

            assertEquals(emptyList(), events.hits)
            assertEquals(listOf(DayClosed(today, emptyList())), events.closedDays)
        }

    @Test
    fun `a hit limit that is deleted later still appears in the day summary`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T09:30:00")
            clock.set("2026-11-12T09:30:00")
            service.check()
            service.delete(insta.id)
            clock.set("2026-11-13T00:00:05")

            service.check()

            assertEquals(
                listOf(DayClosed.Entry("Instagram", Duration.ofHours(1), Duration.ofMinutes(90))),
                events.closedDays.single().entries,
            )
        }

    @Test
    fun `raising a budget is reported as a weakening`() =
        runTest {
            service.save(insta)

            service.save(insta.copy(budget = Duration.ofHours(2)))

            assertEquals(
                listOf<Weakening>(Weakening.BudgetRaised("Instagram", Duration.ofHours(1), Duration.ofHours(2))),
                events.weakenings,
            )
        }

    @Test
    fun `creating or tightening a limit is silent`() =
        runTest {
            service.save(insta)

            service.save(insta.copy(budget = Duration.ofMinutes(30), apps = setOf(instagram, tiktok)))

            assertEquals(emptyList(), events.weakenings)
        }

    @Test
    fun `deleting a limit is reported as a weakening`() =
        runTest {
            service.save(insta)

            service.delete(insta.id)

            assertEquals(listOf<Weakening>(Weakening.LimitDeleted("Instagram", Duration.ofHours(1))), events.weakenings)
            assertEquals(emptyList(), service.today())
        }

    @Test
    fun `lowering a budget below today's usage hits the limit right away`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T08:40:00")
            clock.set("2026-11-12T08:40:00")

            service.save(insta.copy(budget = Duration.ofMinutes(30)))

            assertTrue(service.today().single().hit)
        }

    @Test
    fun `losing usage access is reported once`() =
        runTest {
            service.save(insta)
            health.problems = setOf(TrackingProblem.USAGE_ACCESS_MISSING)
            usage.accessGranted = false

            service.check()
            service.check()

            assertEquals(
                listOf<Weakening>(Weakening.TrackingLost(TrackingProblem.USAGE_ACCESS_MISSING)),
                events.weakenings,
            )
        }

    @Test
    fun `losing usage access again after it was restored is reported again`() =
        runTest {
            service.save(insta)
            health.problems = setOf(TrackingProblem.USAGE_ACCESS_MISSING)
            service.check()
            health.problems = emptySet()
            service.check()
            health.problems = setOf(TrackingProblem.USAGE_ACCESS_MISSING)

            service.check()

            assertEquals(2, events.weakenings.size)
        }

    @Test
    fun `without limits there is nothing to track and nothing to report`() =
        runTest {
            health.problems = setOf(TrackingProblem.USAGE_ACCESS_MISSING, TrackingProblem.NOTIFICATIONS_BLOCKED)

            service.check()

            assertEquals(emptyList(), events.weakenings)
        }

    @Test
    fun `without usage access no limit is hit and usage shows as zero`() =
        runTest {
            service.save(insta)
            used(instagram, "2026-11-12T08:00:00", "2026-11-12T10:00:00")
            clock.set("2026-11-12T10:00:00")
            usage.accessGranted = false

            service.check()

            assertEquals(emptyList(), events.hits)
            assertEquals(Duration.ZERO, service.today().single().used)
        }
}
