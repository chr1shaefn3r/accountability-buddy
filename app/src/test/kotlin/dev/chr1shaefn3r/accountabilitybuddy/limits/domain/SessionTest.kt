package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionTest {
    private val start = Instant.parse("2026-11-12T20:00:00Z")
    private val instagram = AppId("instagram")
    private val tiktok = AppId("tiktok")
    private val mail = AppId("mail")
    private val limited = setOf(instagram, tiktok)
    private val session = Session(SessionId("1"), Duration.ofMinutes(15), start)

    private fun minute(n: Long): Instant = start.plus(Duration.ofMinutes(n))

    private fun used(
        app: AppId,
        from: Long,
        to: Long,
    ) = ForegroundInterval(app, minute(from), minute(to))

    @Test
    fun `a session lasts between one minute and 24 hours`() {
        assertFailsWith<IllegalArgumentException> { session.copy(length = Duration.ofSeconds(59)) }
        assertFailsWith<IllegalArgumentException> { session.copy(length = Duration.ofHours(24).plusMinutes(1)) }
        session.copy(length = Duration.ofMinutes(1))
        session.copy(length = Duration.ofHours(24))
    }

    @Test
    fun `a session counts the time spent in limited apps since it started`() {
        val intervals = listOf(used(instagram, 1, 4), used(mail, 4, 9), used(tiktok, 9, 11))

        assertEquals(Duration.ofMinutes(5), session.used(intervals, limited, minute(30)))
    }

    @Test
    fun `time before the session started does not count`() {
        val intervals = listOf(used(instagram, -20, 2))

        assertEquals(Duration.ofMinutes(2), session.used(intervals, limited, minute(30)))
    }

    @Test
    fun `an app still open counts up to now`() {
        val intervals = listOf(used(instagram, 5, 60))

        assertEquals(Duration.ofMinutes(3), session.used(intervals, limited, minute(8)))
    }

    @Test
    fun `a new session is running and not used up`() {
        assertTrue(session.isRunning)
        assertFalse(session.isUsedUp)
    }

    @Test
    fun `a stopped session keeps the time it had used and counts no further`() {
        val stopped = session.stopped(minute(10), Duration.ofMinutes(7))

        assertFalse(stopped.isRunning)
        assertEquals(Duration.ofMinutes(7), stopped.used(listOf(used(instagram, 0, 60)), limited, minute(60)))
    }

    @Test
    fun `a used up session keeps running until it is stopped`() {
        val usedUp = session.usedUp(minute(20))

        assertTrue(usedUp.isUsedUp)
        assertTrue(usedUp.isRunning)
        assertTrue(usedUp.stopped(minute(25), Duration.ofMinutes(18)).isUsedUp)
    }

    @Test
    fun `only a session that is over can be dismissed`() {
        assertFailsWith<IllegalStateException> { session.dismissed() }
        assertTrue(session.stopped(minute(10), Duration.ofMinutes(7)).dismissed().dismissed)
    }
}
