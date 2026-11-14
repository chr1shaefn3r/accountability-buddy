package dev.chr1shaefn3r.accountabilitybuddy.limits.application

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.ForegroundInterval
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.HitReport
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.testing.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/** When and what the buddies are told about a reached limit or a used-up session. */
class ReportsTest {
    private val clock = MutableClock("2026-11-12T08:00:00")
    private val usage = FakeUsageSource()
    private val events = RecordingEvents()
    private val wakeUp = RecordingWakeUp()
    private val service =
        LimitsService(
            InMemoryLimits(),
            InMemoryHitLog(),
            InMemorySessions(),
            usage,
            FakeHealth(),
            InMemoryLimitsMemory(),
            events,
            wakeUp,
            clock,
        )

    private val instagram = AppId("instagram")
    private val tiktok = AppId("tiktok")
    private val hour = Duration.ofHours(1)
    private val insta = Limit(LimitId("insta"), "Instagram", hour, setOf(instagram))

    private fun used(
        app: AppId,
        from: String,
        to: String,
    ) {
        usage.intervals += ForegroundInterval(app, clock.at("2026-11-12T$from"), clock.at("2026-11-12T$to"))
    }

    private suspend fun at(time: String) {
        clock.set("2026-11-12T$time")
        service.check()
    }

    private fun report(
        used: Duration,
        stillGoing: Boolean,
    ) = HitReport("Instagram", hour, clock.at("2026-11-12T09:00:00"), used, stillGoing)

    @Test
    fun `the buddies are not told at the moment a limit is reached`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:30:00")

            at("09:00:00")
            at("09:11:59")

            assertEquals(1, events.hits.size)
            assertEquals(emptyList(), events.reports)
        }

    @Test
    fun `a fifth of the limit's time later the buddies are told that the sender is still going`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:30:00")
            at("09:00:00")

            at("09:12:00")

            assertEquals(listOf(report(Duration.ofMinutes(72), stillGoing = true)), events.reports)
        }

    @Test
    fun `if the sender stopped in the meantime the buddies are told the total`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:03:00")
            at("09:00:00")

            at("09:12:00")

            assertEquals(listOf(report(Duration.ofMinutes(63), stillGoing = false)), events.reports)
        }

    @Test
    fun `a sender who stopped right at the limit is reported with exactly the limit`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:00:00")
            at("09:00:00")

            at("09:12:00")

            assertEquals(listOf(report(hour, stillGoing = false)), events.reports)
        }

    @Test
    fun `a sender who stopped and came back is still going`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:02:00")
            used(instagram, "09:10:00", "09:30:00")
            at("09:00:00")

            at("09:12:00")

            assertEquals(listOf(report(Duration.ofMinutes(64), stillGoing = true)), events.reports)
        }

    @Test
    fun `using another app does not count as still going`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:02:00")
            used(tiktok, "09:02:00", "09:30:00")
            at("09:00:00")

            at("09:12:00")

            assertEquals(listOf(report(Duration.ofMinutes(62), stillGoing = false)), events.reports)
        }

    @Test
    fun `the buddies are told once`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:30:00")
            at("09:00:00")

            at("09:12:00")
            at("09:20:00")

            assertEquals(1, events.reports.size)
        }

    @Test
    fun `a short limit has a short waiting time`() =
        runTest {
            service.save(insta.copy(budget = Duration.ofMinutes(5)))
            used(instagram, "08:00:00", "08:30:00")
            at("08:05:00")

            at("08:05:59")
            assertEquals(emptyList(), events.reports)

            at("08:06:00")
            assertEquals(1, events.reports.size)
        }

    @Test
    fun `the app asks to be woken when the report is due`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:30:00")

            at("09:00:00")

            assertEquals(clock.at("2026-11-12T09:12:00"), wakeUp.askedFor.last())
        }

    @Test
    fun `a report that could not be sent on time is sent as soon as possible, with what is known then`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:25:00")
            at("09:00:00")

            at("11:00:00")

            assertEquals(listOf(report(Duration.ofMinutes(85), stillGoing = false)), events.reports)
        }

    @Test
    fun `raising a reached limit tells the buddies at once, before they learn of the change`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:30:00")
            at("09:00:00")
            clock.set("2026-11-12T09:02:00")

            service.save(insta.copy(budget = Duration.ofHours(3)))

            assertEquals(listOf(report(Duration.ofMinutes(62), stillGoing = true)), events.reports)
            assertEquals(listOf("report Instagram", "weakening BudgetRaised"), events.toldToBuddies)
        }

    @Test
    fun `deleting a reached limit tells the buddies at once, before they learn of the change`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:01:00")
            at("09:00:00")
            clock.set("2026-11-12T09:02:00")

            service.delete(insta.id)

            assertEquals(listOf(report(Duration.ofMinutes(61), stillGoing = false)), events.reports)
            assertEquals(listOf("report Instagram", "weakening LimitDeleted"), events.toldToBuddies)
        }

    @Test
    fun `tightening a reached limit leaves the report waiting`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "09:30:00")
            at("09:00:00")
            clock.set("2026-11-12T09:02:00")

            service.save(insta.copy(budget = Duration.ofMinutes(30)))
            assertEquals(emptyList(), events.reports)

            at("09:12:00")
            assertEquals(1, events.reports.size)
        }

    @Test
    fun `a limit raised and reached again is reported again`() =
        runTest {
            service.save(insta)
            used(instagram, "08:00:00", "11:00:00")
            at("09:00:00")
            clock.set("2026-11-12T09:02:00")
            service.save(insta.copy(budget = Duration.ofMinutes(90)))

            at("09:30:00")
            at("09:48:00")

            assertEquals(listOf(hour, Duration.ofMinutes(90)), events.reports.map { it.budget })
            assertEquals(Duration.ofMinutes(108), events.reports.last().used)
        }

    @Test
    fun `a report still waiting at midnight is sent with the end of the day, before the day summary`() =
        runTest {
            service.save(insta)
            used(instagram, "22:55:00", "23:59:00")
            at("23:55:00")
            assertEquals(emptyList(), events.reports)

            clock.set("2026-11-13T00:00:05")
            service.check()

            val sent = events.reports.single()
            assertEquals(Duration.ofMinutes(64), sent.used)
            assertEquals(false, sent.stillGoing)
            assertEquals(listOf("report Instagram", "day 2026-11-12"), events.toldToBuddies)
        }

    @Test
    fun `a limit reached unnoticed before midnight is reported with the end of the day`() =
        runTest {
            service.save(insta)
            used(instagram, "22:00:00", "23:30:00")

            clock.set("2026-11-13T00:00:05")
            service.check()

            assertEquals(Duration.ofMinutes(90), events.reports.single().used)
            assertEquals(listOf("report Instagram", "day 2026-11-12"), events.toldToBuddies)
        }

    @Test
    fun `a used up session is reported a fifth of its length later`() =
        runTest {
            service.save(insta.copy(budget = Duration.ofHours(10)))
            clock.set("2026-11-12T20:00:00")
            service.startSession(Duration.ofMinutes(15))
            used(instagram, "20:00:00", "21:00:00")
            at("20:15:00")
            assertEquals(1, events.usedUpSessions.size)

            at("20:17:59")
            assertEquals(emptyList(), events.reports)

            at("20:18:00")
            assertEquals(
                listOf(
                    HitReport(
                        "Session",
                        Duration.ofMinutes(15),
                        clock.at("2026-11-12T20:15:00"),
                        Duration.ofMinutes(18),
                        stillGoing = true,
                    ),
                ),
                events.reports,
            )
        }

    @Test
    fun `a session stopped during the waiting time is reported with what it had counted`() =
        runTest {
            service.save(insta.copy(budget = Duration.ofHours(10)))
            clock.set("2026-11-12T20:00:00")
            service.startSession(Duration.ofMinutes(15))
            used(instagram, "20:00:00", "21:00:00")
            at("20:15:00")
            clock.set("2026-11-12T20:16:00")
            service.stopSession(
                service.sessionStatuses.value
                    .single()
                    .session.id,
            )

            at("20:18:00")

            val sent = events.reports.single()
            assertEquals(Duration.ofMinutes(16), sent.used)
            assertEquals(false, sent.stillGoing)
        }
}
