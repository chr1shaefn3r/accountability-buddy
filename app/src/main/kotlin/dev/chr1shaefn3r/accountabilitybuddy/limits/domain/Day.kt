package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A calendar day in the sender phone's local time zone, from 00:00 to 24:00. */
data class Day(
    val date: LocalDate,
) : Comparable<Day> {
    fun start(zone: ZoneId): Instant = date.atStartOfDay(zone).toInstant()

    fun end(zone: ZoneId): Instant = date.plusDays(1).atStartOfDay(zone).toInstant()

    fun next(): Day = Day(date.plusDays(1))

    fun previous(): Day = Day(date.minusDays(1))

    fun minusDays(days: Long): Day = Day(date.minusDays(days))

    override fun compareTo(other: Day): Int = date.compareTo(other.date)

    companion object {
        fun of(
            instant: Instant,
            zone: ZoneId,
        ): Day = Day(instant.atZone(zone).toLocalDate())
    }
}
