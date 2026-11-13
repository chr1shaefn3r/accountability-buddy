package dev.chr1shaefn3r.accountabilitybuddy.limits.application

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.DayClosed
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.ForegroundInterval
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.testing.MutableClock
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionsTest {
    private val clock = MutableClock("2026-11-12T20:00:00")
    private val limits = InMemoryLimits()
    private val sessions = InMemorySessions()
    private val usage = FakeUsageSource()
    private val events = RecordingEvents()
    private val service =
        LimitsService(limits, InMemoryHitLog(), sessions, usage, FakeHealth(), InMemoryLimitsMemory(), events, clock)

    private val instagram = AppId("instagram")
    private val tiktok = AppId("tiktok")
    private val mail = AppId("mail")
    private val social = Limit(LimitId("social"), "Social", Duration.ofHours(5), setOf(instagram))
    private val videos = Limit(LimitId("videos"), "Videos", Duration.ofHours(5), setOf(tiktok))
    private val quarterOfAnHour = Duration.ofMinutes(15)

    private fun used(
        app: AppId,
        from: String,
        to: String,
    ) {
        usage.intervals += ForegroundInterval(app, clock.at("2026-11-12T$from"), clock.at("2026-11-12T$to"))
    }

    private suspend fun at(time: String) {
        clock.set(time)
        service.check()
    }

    private fun shown(): List<SessionStatus> = service.sessionStatuses.value

    @Test
    fun `without a limit there is nothing a session could count`() =
        runTest {
            assertFalse(service.startSession(quarterOfAnHour))

            assertEquals(emptyList(), shown())
        }

    @Test
    fun `a started session is running and has used nothing yet`() =
        runTest {
            service.save(social)

            assertTrue(service.startSession(quarterOfAnHour))

            val status = shown().single()
            assertTrue(status.session.isRunning)
            assertEquals(quarterOfAnHour, status.session.length)
            assertEquals(clock.instant(), status.session.startedAt)
            assertEquals(Duration.ZERO, status.used)
        }

    @Test
    fun `a session counts the time in the apps of all limits from its start`() =
        runTest {
            service.save(social)
            service.save(videos)
            used(instagram, "19:00:00", "19:30:00")
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:04:00")
            used(mail, "20:04:00", "20:06:00")
            used(tiktok, "20:06:00", "20:09:00")

            at("2026-11-12T20:10:00")

            assertEquals(Duration.ofMinutes(7), shown().single().used)
            assertEquals(emptyList(), events.usedUpSessions)
        }

    @Test
    fun `a session whose time is used up is reported once`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:30:00")

            at("2026-11-12T20:15:00")
            at("2026-11-12T20:16:00")

            assertEquals(1, events.usedUpSessions.size)
            assertEquals(clock.at("2026-11-12T20:15:00"), events.usedUpSessions.single().usedUpAt)
        }

    @Test
    fun `a used up session keeps running and counting`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:25:00")
            at("2026-11-12T20:15:00")

            at("2026-11-12T20:25:00")

            val status = shown().single()
            assertTrue(status.session.isRunning)
            assertTrue(status.session.isUsedUp)
            assertEquals(Duration.ofMinutes(25), status.used)
        }

    @Test
    fun `there is one session at a time`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)

            assertFalse(service.startSession(Duration.ofMinutes(30)))
            assertEquals(1, shown().size)

            service.stopSession(shown().single().session.id)
            clock.advance(Duration.ofMinutes(1))
            assertTrue(service.startSession(Duration.ofMinutes(30)))
            assertEquals(listOf(Duration.ofMinutes(30), quarterOfAnHour), shown().map { it.session.length })
        }

    @Test
    fun `a stopped session keeps what it used and counts no further`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:30:00")
            clock.set("2026-11-12T20:08:00")

            service.stopSession(shown().single().session.id)
            at("2026-11-12T20:30:00")

            val status = shown().single()
            assertFalse(status.session.isRunning)
            assertEquals(Duration.ofMinutes(8), status.used)
            assertEquals(emptyList(), events.usedUpSessions)
        }

    @Test
    fun `starting and stopping a session is nobody's business`() =
        runTest {
            service.save(social)

            service.startSession(quarterOfAnHour)
            service.stopSession(shown().single().session.id)
            service.dismissSession(shown().single().session.id)

            assertEquals(emptyList(), events.weakenings)
            assertEquals(emptyList(), events.hits)
            assertEquals(emptyList(), events.usedUpSessions)
        }

    @Test
    fun `a session used up just before it is stopped is still reported`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:16:00")
            clock.set("2026-11-12T20:16:00")

            service.stopSession(shown().single().session.id)

            assertEquals(1, events.usedUpSessions.size)
            assertTrue(shown().single().session.isUsedUp)
        }

    @Test
    fun `a session that is over can be dismissed, a running one cannot`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            val id = shown().single().session.id

            service.dismissSession(id)
            assertEquals(1, shown().size)

            service.stopSession(id)
            service.dismissSession(id)
            assertEquals(emptyList(), shown())
        }

    @Test
    fun `at midnight a running session ends and the new day starts without sessions`() =
        runTest {
            service.save(social)
            clock.set("2026-11-12T23:50:00")
            service.startSession(quarterOfAnHour)
            used(instagram, "23:52:00", "23:57:00")

            at("2026-11-13T00:00:05")

            assertEquals(emptyList(), shown())
            assertEquals(emptyList(), sessions.sessions)
            assertTrue(service.startSession(quarterOfAnHour))
        }

    @Test
    fun `a used up session appears in the day summary with all it used`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:40:00")
            at("2026-11-12T20:20:00")

            at("2026-11-13T00:00:05")

            assertEquals(
                listOf(DayClosed.Entry("Session", quarterOfAnHour, Duration.ofMinutes(40))),
                events.closedDays.single().entries,
            )
        }

    @Test
    fun `a dismissed session that was used up still appears in the day summary`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:20:00")
            at("2026-11-12T20:20:00")
            val id = shown().single().session.id
            service.stopSession(id)
            service.dismissSession(id)

            at("2026-11-13T00:00:05")

            assertEquals(
                listOf(DayClosed.Entry("Session", quarterOfAnHour, Duration.ofMinutes(20))),
                events.closedDays.single().entries,
            )
        }

    @Test
    fun `a session that stayed within its time does not appear in the day summary`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:10:00")

            at("2026-11-13T00:00:05")

            assertEquals(emptyList(), events.closedDays.single().entries)
            assertEquals(emptyList(), events.usedUpSessions)
        }

    @Test
    fun `a session used up unnoticed before midnight still counts for that day`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "21:00:00")

            at("2026-11-13T00:00:05")

            assertEquals(1, events.usedUpSessions.size)
            assertEquals(
                listOf(DayClosed.Entry("Session", quarterOfAnHour, Duration.ofHours(1))),
                events.closedDays.single().entries,
            )
        }

    @Test
    fun `time in an app counts for its limit and for the session`() =
        runTest {
            service.save(social.copy(budget = Duration.ofMinutes(20)))
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:30:00")

            at("2026-11-12T20:30:00")

            assertEquals(listOf("Social"), events.hits.map { it.limitName })
            assertEquals(1, events.usedUpSessions.size)
        }

    @Test
    fun `without usage access a session counts nothing`() =
        runTest {
            service.save(social)
            service.startSession(quarterOfAnHour)
            used(instagram, "20:00:00", "20:30:00")
            usage.accessGranted = false

            at("2026-11-12T20:30:00")

            assertEquals(Duration.ZERO, shown().single().used)
            assertEquals(emptyList(), events.usedUpSessions)
        }
}
