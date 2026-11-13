package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsageLevelTest {
    private val hour = Duration.ofHours(1)

    private fun levelAt(
        minutes: Long,
        seconds: Long = 0,
    ) = UsageLevel.of(Duration.ofMinutes(minutes).plusSeconds(seconds), hour)

    @Test
    fun `below 80 percent everything is normal`() {
        assertEquals(UsageLevel.NORMAL, levelAt(0))
        assertEquals(UsageLevel.NORMAL, levelAt(47, 59))
    }

    @Test
    fun `from 80 percent on there is a warning`() {
        assertEquals(UsageLevel.WARNING, levelAt(48))
        assertEquals(UsageLevel.WARNING, levelAt(56, 59))
    }

    @Test
    fun `from 95 percent on there is the last warning`() {
        assertEquals(UsageLevel.LAST_WARNING, levelAt(57))
        assertEquals(UsageLevel.LAST_WARNING, levelAt(59, 59))
    }

    @Test
    fun `at 100 percent the budget is reached`() {
        assertEquals(UsageLevel.REACHED, levelAt(60))
        assertEquals(UsageLevel.REACHED, levelAt(600))
    }

    @Test
    fun `the levels follow one another`() {
        assertEquals(
            listOf(UsageLevel.NORMAL, UsageLevel.WARNING, UsageLevel.LAST_WARNING, UsageLevel.REACHED),
            UsageLevel.entries.sorted(),
        )
    }

    @Test
    fun `only the two levels before the end are warnings`() {
        assertEquals(listOf(UsageLevel.WARNING, UsageLevel.LAST_WARNING), UsageLevel.entries.filter { it.isWarning })
    }

    @Test
    fun `a very short budget has the same levels`() {
        val minute = Duration.ofMinutes(1)

        assertEquals(UsageLevel.NORMAL, UsageLevel.of(Duration.ofSeconds(47), minute))
        assertEquals(UsageLevel.WARNING, UsageLevel.of(Duration.ofSeconds(48), minute))
        assertEquals(UsageLevel.LAST_WARNING, UsageLevel.of(Duration.ofSeconds(57), minute))
        assertEquals(UsageLevel.REACHED, UsageLevel.of(Duration.ofSeconds(60), minute))
    }

    @Test
    fun `a warning knows how much time is left`() {
        val warning = UsageWarning("1", "Social", hour, Duration.ofMinutes(48), UsageLevel.WARNING)

        assertEquals(Duration.ofMinutes(12), warning.left)
    }

    @Test
    fun `announced warnings count for their day only`() {
        val today = Day(LocalDate.of(2026, 11, 12))
        val announced = AnnouncedWarnings(today, mapOf("1" to UsageLevel.WARNING))

        assertEquals(UsageLevel.WARNING, announced.levelOn(today, "1"))
        assertEquals(UsageLevel.NORMAL, announced.levelOn(today, "2"))
        assertEquals(UsageLevel.NORMAL, announced.levelOn(today.next(), "1"))
        assertTrue(AnnouncedWarnings.NONE.levels.isEmpty())
        assertFalse(UsageLevel.NORMAL.isWarning)
    }
}
