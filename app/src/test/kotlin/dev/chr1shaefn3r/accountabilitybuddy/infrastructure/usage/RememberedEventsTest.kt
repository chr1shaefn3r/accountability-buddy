package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.usage

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RememberedEventsTest {
    private val morning = Instant.parse("2026-11-12T06:00:00Z")
    private val recorded = mutableListOf<UsageEvent>()
    private val reads = mutableListOf<Pair<Instant, Instant>>()
    private val events =
        RememberedEvents { from, to ->
            reads += from to to
            recorded.filter { it.at >= from && it.at < to }
        }

    private fun minute(n: Long): Instant = morning.plus(Duration.ofMinutes(n))

    private fun screenOffAt(at: Instant) = UsageEvent.ScreenOff(at).also { recorded += it }

    @Test
    fun `all events of the time span are returned, oldest first`() {
        val first = screenOffAt(minute(1))
        val second = screenOffAt(minute(5))
        screenOffAt(minute(20))

        assertEquals(listOf<UsageEvent>(first, second), events.between(morning, minute(10)))
    }

    @Test
    fun `looking again only reads what is new`() {
        screenOffAt(minute(1))
        events.between(morning, minute(10))
        val later = screenOffAt(minute(12))

        val all = events.between(morning, minute(15))

        assertEquals(2, all.size)
        assertEquals(later, all.last())
        assertEquals(minute(10) - RememberedEvents.SETTLE_TIME, reads.last().first)
        assertEquals(minute(15), reads.last().second)
    }

    @Test
    fun `an event that is recorded a moment late is not missed`() {
        events.between(morning, minute(10))
        val late = screenOffAt(minute(10).minusSeconds(3))

        assertEquals(listOf<UsageEvent>(late), events.between(morning, minute(11)))
    }

    @Test
    fun `no event is returned twice`() {
        val event = screenOffAt(minute(10).minusSeconds(3))

        events.between(morning, minute(10))
        events.between(morning, minute(10).plusSeconds(20))

        assertEquals(listOf<UsageEvent>(event), events.between(morning, minute(11)))
    }

    @Test
    fun `another time span is read afresh`() {
        val yesterday = screenOffAt(morning.minus(Duration.ofHours(5)))
        screenOffAt(minute(1))
        events.between(morning, minute(10))

        val earlier = events.between(morning.minus(Duration.ofDays(1)), morning)

        assertEquals(listOf<UsageEvent>(yesterday), earlier)
        assertEquals(1, events.between(morning, minute(10)).size)
    }
}
