package dev.chr1shaefn3r.accountabilitybuddy.limits.application

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.ForegroundInterval
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageLevel
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageWarning
import dev.chr1shaefn3r.accountabilitybuddy.testing.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

class WarningsTest {
    private val clock = MutableClock("2026-11-12T08:00:00")
    private val usage = FakeUsageSource()
    private val events = RecordingEvents()
    private val service =
        LimitsService(
            InMemoryLimits(),
            InMemoryHitLog(),
            InMemorySessions(),
            usage,
            FakeHealth(),
            InMemoryLimitsMemory(),
            events,
            clock,
        )

    private val instagram = AppId("instagram")
    private val hour = Duration.ofHours(1)
    private val insta = Limit(LimitId("insta"), "Instagram", hour, setOf(instagram))

    /** Instagram is open from eight o'clock on; the service looks at the given time. */
    private suspend fun instagramUsedUntil(time: String) {
        usage.intervals.clear()
        usage.intervals +=
            ForegroundInterval(instagram, clock.at("2026-11-12T08:00:00"), clock.at("2026-11-12T23:00:00"))
        clock.set("2026-11-12T$time")
        service.check()
    }

    private fun levels() = events.warnings.map { it.level }

    @Test
    fun `below 80 percent nobody is warned`() =
        runTest {
            service.save(insta)

            instagramUsedUntil("08:47:00")

            assertEquals(emptyList(), events.warnings)
            assertEquals(UsageLevel.NORMAL, service.today().single().level)
        }

    @Test
    fun `at 80 percent of a limit the sender is warned once`() =
        runTest {
            service.save(insta)

            instagramUsedUntil("08:48:00")
            instagramUsedUntil("08:50:00")

            assertEquals(
                listOf(UsageWarning("insta", "Instagram", hour, Duration.ofMinutes(48), UsageLevel.WARNING)),
                events.warnings,
            )
            assertEquals(UsageLevel.WARNING, service.today().single().level)
        }

    @Test
    fun `at 95 percent of a limit the sender gets the last warning once`() =
        runTest {
            service.save(insta)
            instagramUsedUntil("08:48:00")

            instagramUsedUntil("08:57:00")
            instagramUsedUntil("08:58:00")

            assertEquals(listOf(UsageLevel.WARNING, UsageLevel.LAST_WARNING), levels())
            assertEquals(UsageLevel.LAST_WARNING, service.today().single().level)
        }

    @Test
    fun `usage that jumps past both marks gives only the last warning`() =
        runTest {
            service.save(insta)

            instagramUsedUntil("08:58:00")

            assertEquals(listOf(UsageLevel.LAST_WARNING), levels())
        }

    @Test
    fun `a limit that is reached is not warned about, it is hit`() =
        runTest {
            service.save(insta)

            instagramUsedUntil("09:00:00")

            assertEquals(emptyList(), events.warnings)
            assertEquals(1, events.hits.size)
            assertEquals(UsageLevel.REACHED, service.today().single().level)
        }

    @Test
    fun `warnings are not a hit and not a weakening`() =
        runTest {
            service.save(insta)

            instagramUsedUntil("08:57:00")

            assertEquals(emptyList(), events.hits)
            assertEquals(emptyList(), events.weakenings)
        }

    @Test
    fun `on the next day the sender is warned again`() =
        runTest {
            service.save(insta)
            instagramUsedUntil("08:48:00")
            usage.intervals.clear()
            usage.intervals +=
                ForegroundInterval(instagram, clock.at("2026-11-13T08:00:00"), clock.at("2026-11-13T09:00:00"))
            clock.set("2026-11-13T08:48:00")

            service.check()

            assertEquals(listOf(UsageLevel.WARNING, UsageLevel.WARNING), levels())
        }

    @Test
    fun `raising a limit takes its warning back, and it can be warned about again`() =
        runTest {
            service.save(insta)
            instagramUsedUntil("08:50:00")

            service.save(insta.copy(budget = Duration.ofHours(2)))
            assertEquals(listOf("insta"), events.liftedWarnings)
            assertEquals(UsageLevel.NORMAL, service.today().single().level)

            instagramUsedUntil("09:36:00")
            assertEquals(listOf(UsageLevel.WARNING, UsageLevel.WARNING), levels())
        }

    @Test
    fun `raising a reached limit to just above today's usage does not warn again`() =
        runTest {
            service.save(insta)
            instagramUsedUntil("09:00:00")

            service.save(insta.copy(budget = Duration.ofMinutes(62)))

            assertEquals(emptyList(), events.warnings)
            assertEquals(UsageLevel.LAST_WARNING, service.today().single().level)
        }

    @Test
    fun `a limit created close to its end is warned about at once`() =
        runTest {
            usage.intervals +=
                ForegroundInterval(instagram, clock.at("2026-11-12T07:00:00"), clock.at("2026-11-12T07:50:00"))

            service.save(insta)

            assertEquals(listOf(UsageLevel.WARNING), levels())
        }

    @Test
    fun `deleting a limit takes its warning back`() =
        runTest {
            service.save(insta)
            instagramUsedUntil("08:50:00")

            service.delete(insta.id)

            assertEquals(listOf("insta"), events.liftedWarnings)
        }

    @Test
    fun `without usage access nobody is warned`() =
        runTest {
            service.save(insta)
            usage.accessGranted = false

            instagramUsedUntil("08:58:00")

            assertEquals(emptyList(), events.warnings)
        }

    @Test
    fun `a session warns at 80 and 95 percent of its minutes`() =
        runTest {
            service.save(insta.copy(budget = Duration.ofHours(10)))
            service.startSession(Duration.ofMinutes(20))
            val id =
                service.sessionStatuses.value
                    .single()
                    .session.id.value

            instagramUsedUntil("08:16:00")
            instagramUsedUntil("08:19:00")
            instagramUsedUntil("08:20:00")

            assertEquals(
                listOf(
                    UsageWarning(id, "Session", Duration.ofMinutes(20), Duration.ofMinutes(16), UsageLevel.WARNING),
                    UsageWarning(
                        id,
                        "Session",
                        Duration.ofMinutes(20),
                        Duration.ofMinutes(19),
                        UsageLevel.LAST_WARNING,
                    ),
                ),
                events.warnings,
            )
            assertEquals(1, events.usedUpSessions.size)
            assertEquals(
                UsageLevel.REACHED,
                service.sessionStatuses.value
                    .single()
                    .level,
            )
        }

    @Test
    fun `stopping a session takes its warning back`() =
        runTest {
            service.save(insta.copy(budget = Duration.ofHours(10)))
            service.startSession(Duration.ofMinutes(20))
            val id =
                service.sessionStatuses.value
                    .single()
                    .session.id
            instagramUsedUntil("08:17:00")

            service.stopSession(id)

            assertEquals(listOf(id.value), events.liftedWarnings)
            assertEquals(
                UsageLevel.WARNING,
                service.sessionStatuses.value
                    .single()
                    .level,
            )
        }
}
