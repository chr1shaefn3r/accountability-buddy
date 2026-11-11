package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.usage

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.ForegroundInterval
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ForegroundIntervalsTest {
    private val start = Instant.parse("2026-11-12T08:00:00Z")
    private val instagram = AppId("instagram")
    private val tiktok = AppId("tiktok")

    private fun minute(n: Long): Instant = start.plusSeconds(n * 60)

    private fun resumed(app: AppId, at: Long, screen: String = "main") = UsageEvent.Resumed(app, screen, minute(at))

    private fun paused(app: AppId, at: Long, screen: String = "main") = UsageEvent.Paused(app, screen, minute(at))

    private fun screenOff(at: Long) = UsageEvent.ScreenOff(minute(at))

    private fun interval(app: AppId, from: Long, to: Long) = ForegroundInterval(app, minute(from), minute(to))

    private fun intervals(vararg events: UsageEvent, until: Long = 60) =
        foregroundIntervals(events.toList(), start, minute(until))

    @Test
    fun `an app is in the foreground from resume to pause`() {
        assertEquals(
            listOf(interval(instagram, 5, 15)),
            intervals(resumed(instagram, 5), paused(instagram, 15)),
        )
    }

    @Test
    fun `switching to another app ends the first one`() {
        assertEquals(
            listOf(interval(instagram, 5, 10), interval(tiktok, 10, 20)),
            intervals(resumed(instagram, 5), resumed(tiktok, 10), paused(instagram, 10), paused(tiktok, 20)),
        )
    }

    @Test
    fun `moving between screens of the same app does not interrupt it`() {
        assertEquals(
            listOf(interval(instagram, 5, 30)),
            intervals(
                resumed(instagram, 5, "feed"),
                resumed(instagram, 12, "profile"),
                paused(instagram, 12, "feed"),
                paused(instagram, 30, "profile"),
            ),
        )
    }

    @Test
    fun `it does not matter whether the old screen pauses before the new one resumes`() {
        assertEquals(
            listOf(interval(instagram, 5, 12), interval(instagram, 12, 30)),
            intervals(
                resumed(instagram, 5, "feed"),
                paused(instagram, 12, "feed"),
                resumed(instagram, 12, "profile"),
                paused(instagram, 30, "profile"),
            ),
        )
    }

    @Test
    fun `an app still open at the end counts until then`() {
        assertEquals(listOf(interval(instagram, 5, 60)), intervals(resumed(instagram, 5)))
    }

    @Test
    fun `turning the screen off ends the foreground time`() {
        assertEquals(listOf(interval(instagram, 5, 8)), intervals(resumed(instagram, 5), screenOff(8)))
    }

    @Test
    fun `an app opened before the first event counts from the beginning`() {
        assertEquals(listOf(interval(instagram, 0, 7)), intervals(paused(instagram, 7)))
    }

    @Test
    fun `a stray pause after other activity is ignored`() {
        assertEquals(
            listOf(interval(tiktok, 5, 10)),
            intervals(resumed(tiktok, 5), paused(tiktok, 10), paused(instagram, 12)),
        )
    }

    @Test
    fun `opening the same app several times gives several intervals`() {
        assertEquals(
            listOf(interval(instagram, 1, 2), interval(instagram, 10, 12)),
            intervals(resumed(instagram, 1), paused(instagram, 2), resumed(instagram, 10), paused(instagram, 12)),
        )
    }

    @Test
    fun `no events mean no usage`() {
        assertEquals(emptyList(), intervals())
        assertEquals(emptyList(), intervals(screenOff(3)))
    }
}
