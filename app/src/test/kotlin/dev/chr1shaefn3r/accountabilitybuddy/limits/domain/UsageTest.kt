package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class UsageTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private val day = Day(LocalDate.of(2026, 11, 12))
    private val instagram = AppId("instagram")
    private val tiktok = AppId("tiktok")

    private fun at(time: String): Instant = LocalDateTime.parse(time).atZone(berlin).toInstant()

    private fun interval(app: AppId, from: String, to: String) = ForegroundInterval(app, at(from), at(to))

    @Test
    fun `usage of several apps is added up`() {
        val usage =
            Usage.on(
                day,
                berlin,
                listOf(
                    interval(instagram, "2026-11-12T08:00:00", "2026-11-12T08:20:00"),
                    interval(tiktok, "2026-11-12T09:00:00", "2026-11-12T09:15:00"),
                    interval(instagram, "2026-11-12T10:00:00", "2026-11-12T10:05:00"),
                ),
            )

        assertEquals(Duration.ofMinutes(25), usage.of(setOf(instagram)))
        assertEquals(Duration.ofMinutes(40), usage.of(setOf(instagram, tiktok)))
    }

    @Test
    fun `apps that were not used have no usage`() {
        assertEquals(Duration.ZERO, Usage.NONE.of(setOf(instagram)))
    }

    @Test
    fun `usage across midnight is split at midnight`() {
        val acrossMidnight = listOf(interval(instagram, "2026-11-11T23:50:00", "2026-11-12T00:30:00"))

        assertEquals(Duration.ofMinutes(10), Usage.on(day.previous(), berlin, acrossMidnight).of(setOf(instagram)))
        assertEquals(Duration.ofMinutes(30), Usage.on(day, berlin, acrossMidnight).of(setOf(instagram)))
    }

    @Test
    fun `usage on other days does not count`() {
        val yesterday = listOf(interval(instagram, "2026-11-11T10:00:00", "2026-11-11T11:00:00"))

        assertEquals(Duration.ZERO, Usage.on(day, berlin, yesterday).of(setOf(instagram)))
    }

    @Test
    fun `a day follows the time zone, also when clocks change`() {
        val dayOfClockChange = Day(LocalDate.of(2026, 10, 25))

        assertEquals(
            Duration.ofHours(25),
            Duration.between(dayOfClockChange.start(berlin), dayOfClockChange.end(berlin)),
        )
        assertEquals(dayOfClockChange, Day.of(at("2026-10-25T23:59:59"), berlin))
    }
}
