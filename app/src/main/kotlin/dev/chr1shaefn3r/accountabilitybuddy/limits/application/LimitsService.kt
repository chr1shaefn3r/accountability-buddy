package dev.chr1shaefn3r.accountabilitybuddy.limits.application

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.DayClosed
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.HitLog
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitRepository
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitsEvents
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitsMemory
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingHealth
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Usage
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageSource
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Weakening
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.newHits
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** A limit together with how much of it is used up today. [hit] is true while a hit of today stands. */
data class LimitStatus(
    val limit: Limit,
    val used: Duration,
    val hit: Boolean,
)

/** The use cases of the Limits context. */
class LimitsService(
    private val limits: LimitRepository,
    private val hitLog: HitLog,
    private val usageSource: UsageSource,
    private val health: TrackingHealth,
    private val memory: LimitsMemory,
    private val events: LimitsEvents,
    private val clock: Clock,
) {
    private val mutex = Mutex()

    /** Creates or changes a limit. Buddies learn about every way in which it got weaker. */
    suspend fun save(limit: Limit) =
        mutex.withLock {
            closePastDays()
            val previous = limits.find(limit.id)
            limits.save(limit)
            previous?.weakenedBy(limit)?.forEach { events.setupWeakened(it) }
            if (previous != null && standingHit(previous) != null && standingHit(limit) == null) {
                events.hitLifted(limit.id)
            }
            checkToday()
        }

    suspend fun delete(id: LimitId) =
        mutex.withLock {
            closePastDays()
            val limit = limits.find(id) ?: return@withLock
            limits.delete(id)
            events.setupWeakened(limit.deleted())
            if (standingHit(limit) != null) events.hitLifted(id)
        }

    /**
     * Brings everything up to date: reports tracking problems, closes days that are over
     * and detects limits hit today. Safe to call as often as needed.
     */
    suspend fun check() =
        mutex.withLock {
            reportTrackingProblems()
            closePastDays()
            checkToday()
        }

    suspend fun today(): List<LimitStatus> =
        mutex.withLock {
            val today = currentDay()
            val usage = usageOn(today) ?: Usage.NONE
            val hits = hitLog.hitsOn(today)
            limits.all().map { limit -> LimitStatus(limit, usage.of(limit), hits.any { it.standsFor(limit) }) }
        }

    /** Today's hit of [limit], unless the limit was weakened since. */
    private suspend fun standingHit(limit: Limit): LimitHit? =
        hitLog.hitsOn(currentDay()).firstOrNull { it.standsFor(limit) }

    private fun currentDay(): Day = Day.of(clock.instant(), clock.zone)

    private suspend fun reportTrackingProblems() {
        if (limits.all().isEmpty()) {
            memory.reportedProblems = emptySet()
            return
        }
        val current = health.currentProblems()
        val new = current - memory.reportedProblems
        memory.reportedProblems = current
        new.forEach { events.setupWeakened(Weakening.TrackingLost(it)) }
    }

    private suspend fun closePastDays() {
        val today = currentDay()
        val yesterday = today.previous()
        val lastClosed = memory.lastClosedDay
        if (lastClosed == null) {
            memory.lastClosedDay = yesterday
            return
        }
        var day = maxOf(lastClosed.next(), today.minusDays(MAX_DAYS_TO_CATCH_UP))
        while (day <= yesterday) {
            detectHits(day, day.end(clock.zone))
            events.dayClosed(DayClosed.of(day, hitLog.hitsOn(day), usageOn(day) ?: Usage.NONE))
            memory.lastClosedDay = day
            day = day.next()
        }
        hitLog.forgetBefore(today.minusDays(MAX_DAYS_TO_CATCH_UP))
    }

    private suspend fun checkToday() = detectHits(currentDay(), clock.instant())

    private suspend fun detectHits(
        day: Day,
        at: Instant,
    ) {
        val all = limits.all()
        if (all.isEmpty()) return
        val usage = usageOn(day) ?: return
        all.newHits(day, usage, hitLog.hitsOn(day), at).forEach {
            hitLog.record(it)
            events.limitHit(it)
        }
    }

    private fun usageOn(day: Day): Usage? {
        val zone = clock.zone
        val until = minOf(clock.instant(), day.end(zone))
        val intervals = usageSource.foregroundIntervals(day.start(zone), until) ?: return null
        return Usage.on(day, zone, intervals)
    }

    private companion object {
        const val MAX_DAYS_TO_CATCH_UP = 7L
    }
}
