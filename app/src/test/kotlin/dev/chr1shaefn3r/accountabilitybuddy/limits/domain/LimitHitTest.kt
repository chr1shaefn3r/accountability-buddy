package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals

class LimitHitTest {
    private val day = Day(LocalDate.of(2026, 11, 12))
    private val noon: Instant = day.start(ZoneOffset.UTC).plus(Duration.ofHours(12))
    private val instagram = AppId("instagram")
    private val tiktok = AppId("tiktok")
    private val social = Limit(LimitId("social"), "Social Media", Duration.ofHours(1), setOf(instagram, tiktok))
    private val insta = Limit(LimitId("insta"), "Instagram", Duration.ofMinutes(30), setOf(instagram))

    private fun usage(vararg minutes: Pair<AppId, Long>): Usage {
        var cursor = day.start(ZoneOffset.UTC)
        val intervals =
            minutes.map { (app, m) ->
                ForegroundInterval(app, cursor, cursor.plus(Duration.ofMinutes(m))).also { cursor = it.end }
            }
        return Usage.on(day, ZoneOffset.UTC, intervals)
    }

    @Test
    fun `a limit is hit when its usage reaches the budget`() {
        val hits = listOf(social, insta).newHits(day, usage(instagram to 30), emptyList(), noon)

        assertEquals(listOf(LimitHit(day, insta.id, "Instagram", insta.budget, insta.apps, noon)), hits)
    }

    @Test
    fun `a limit below its budget is not hit`() {
        assertEquals(emptyList(), listOf(social, insta).newHits(day, usage(instagram to 29), emptyList(), noon))
    }

    @Test
    fun `an app counts against every limit it belongs to`() {
        val hits = listOf(social, insta).newHits(day, usage(instagram to 45, tiktok to 15), emptyList(), noon)

        assertEquals(setOf(social.id, insta.id), hits.map { it.limitId }.toSet())
    }

    @Test
    fun `a limit is hit at most once per day`() {
        val first = listOf(insta).newHits(day, usage(instagram to 30), emptyList(), noon)
        val second = listOf(insta).newHits(day, usage(instagram to 50), first, noon.plusSeconds(60))

        assertEquals(emptyList(), second)
    }

    @Test
    fun `a closed day lists every hit with its final usage`() {
        val hits = listOf(insta).newHits(day, usage(instagram to 30), emptyList(), noon)

        val closed = DayClosed.of(day, hits, usage(instagram to 105))

        assertEquals(
            DayClosed(day, listOf(DayClosed.Entry("Instagram", Duration.ofMinutes(30), Duration.ofMinutes(105)))),
            closed,
        )
    }

    @Test
    fun `a closed day never reports less usage than the budget of a hit limit`() {
        val hits = listOf(insta).newHits(day, usage(instagram to 30), emptyList(), noon)

        val closed = DayClosed.of(day, hits, Usage.NONE)

        assertEquals(Duration.ofMinutes(30), closed.entries.single().used)
    }

    @Test
    fun `a day without hits closes empty`() {
        assertEquals(DayClosed(day, emptyList()), DayClosed.of(day, emptyList(), usage(instagram to 5)))
    }
}
