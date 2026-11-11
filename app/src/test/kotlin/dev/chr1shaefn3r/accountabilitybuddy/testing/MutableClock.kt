package dev.chr1shaefn3r.accountabilitybuddy.testing

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** A clock for tests that only moves when told to. */
class MutableClock(
    private var now: Instant,
    private val zone: ZoneId = ZoneId.of("Europe/Berlin"),
) : Clock() {
    constructor(localTime: String, zone: ZoneId = ZoneId.of("Europe/Berlin")) :
        this(LocalDateTime.parse(localTime).atZone(zone).toInstant(), zone)

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = zone

    override fun withZone(zone: ZoneId): Clock = MutableClock(now, zone)

    fun advance(duration: Duration) {
        now += duration
    }

    fun set(localTime: String) {
        now = at(localTime)
    }

    fun at(localTime: String): Instant = LocalDateTime.parse(localTime).atZone(zone).toInstant()
}
