package dev.chr1shaefn3r.accountabilitybuddy.limits.application

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.DayClosed
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.ForegroundInterval
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.HitLog
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitRepository
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitsEvents
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitsMemory
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Session
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SessionRepository
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingHealth
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingProblem
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageSource
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Weakening
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.Instant

class InMemoryLimits : LimitRepository {
    private val limits = MutableStateFlow<List<Limit>>(emptyList())

    override fun observeAll(): Flow<List<Limit>> = limits

    override suspend fun all(): List<Limit> = limits.value

    override suspend fun find(id: LimitId): Limit? = limits.value.find { it.id == id }

    override suspend fun save(limit: Limit) {
        limits.value = limits.value.filterNot { it.id == limit.id } + limit
    }

    override suspend fun delete(id: LimitId) {
        limits.value = limits.value.filterNot { it.id == id }
    }
}

class InMemoryHitLog : HitLog {
    val hits = mutableListOf<LimitHit>()

    override suspend fun hitsOn(day: Day): List<LimitHit> = hits.filter { it.day == day }

    override suspend fun record(hit: LimitHit) {
        hits.removeAll { it.day == hit.day && it.limitId == hit.limitId }
        hits += hit
    }

    override suspend fun forgetBefore(day: Day) {
        hits.removeAll { it.day < day }
    }
}

class InMemorySessions : SessionRepository {
    val sessions = mutableListOf<Session>()

    override suspend fun all(): List<Session> = sessions.toList()

    override suspend fun save(session: Session) {
        sessions.removeAll { it.id == session.id }
        sessions += session
    }

    override suspend fun forgetStartedBefore(instant: Instant) {
        sessions.removeAll { it.startedAt < instant }
    }
}

class FakeUsageSource : UsageSource {
    val intervals = mutableListOf<ForegroundInterval>()
    var accessGranted = true

    override suspend fun foregroundIntervals(
        from: Instant,
        to: Instant,
    ): List<ForegroundInterval>? = if (accessGranted) intervals.filter { it.start < to && it.end > from } else null
}

class FakeHealth : TrackingHealth {
    var problems = emptySet<TrackingProblem>()

    override fun currentProblems(): Set<TrackingProblem> = problems
}

class InMemoryLimitsMemory : LimitsMemory {
    override var lastClosedDay: Day? = null
    override var reportedProblems: Set<TrackingProblem> = emptySet()
}

class RecordingEvents : LimitsEvents {
    val hits = mutableListOf<LimitHit>()
    val weakenings = mutableListOf<Weakening>()
    val closedDays = mutableListOf<DayClosed>()
    val liftedHits = mutableListOf<LimitId>()
    val usedUpSessions = mutableListOf<Session>()

    override suspend fun limitHit(hit: LimitHit) {
        hits += hit
    }

    override suspend fun setupWeakened(weakening: Weakening) {
        weakenings += weakening
    }

    override suspend fun dayClosed(dayClosed: DayClosed) {
        closedDays += dayClosed
    }

    override suspend fun hitLifted(limitId: LimitId) {
        liftedHits += limitId
    }

    override suspend fun sessionUsedUp(session: Session) {
        usedUpSessions += session
    }
}
