package dev.chr1shaefn3r.accountabilitybuddy.limits.application

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AnnouncedWarnings
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
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
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.PendingReport
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Session
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SessionId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SessionRepository
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingHealth
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Usage
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageLevel
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageSource
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageWarning
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.WakeUp
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Weakening
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.newHits
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A limit together with how much of it is used up today. [hit] is true while a hit of today stands. */
data class LimitStatus(
    val limit: Limit,
    val used: Duration,
    val hit: Boolean,
) {
    val level: UsageLevel get() = if (hit) UsageLevel.REACHED else UsageLevel.of(used, limit.budget)
}

/** A session together with the time spent in limited apps since it started. */
data class SessionStatus(
    val session: Session,
    val used: Duration,
) {
    val level: UsageLevel get() = if (session.isUsedUp) UsageLevel.REACHED else UsageLevel.of(used, session.length)
}

/** Whether a session can be started now, and if not, what stands in the way. */
enum class SessionOffer {
    AVAILABLE,

    /** There are no limits, so there are no apps a session could count. */
    NO_LIMITS,

    /** There is one session at a time. */
    ALREADY_RUNNING,

    /** Every limit is reached: there is no time left to take a break in. */
    ALL_LIMITS_REACHED,
    ;

    companion object {
        fun of(
            limits: List<LimitStatus>,
            sessions: List<Session>,
        ): SessionOffer =
            when {
                limits.isEmpty() -> NO_LIMITS
                sessions.any { it.isRunning } -> ALREADY_RUNNING
                limits.all { it.hit } -> ALL_LIMITS_REACHED
                else -> AVAILABLE
            }
    }
}

/** The use cases of the Limits context. */
@Suppress("TooManyFunctions")
class LimitsService(
    private val limits: LimitRepository,
    private val hitLog: HitLog,
    private val sessions: SessionRepository,
    private val usageSource: UsageSource,
    private val health: TrackingHealth,
    private val memory: LimitsMemory,
    private val events: LimitsEvents,
    private val wakeUp: WakeUp,
    private val clock: Clock,
) {
    private val mutex = Mutex()
    private val latestStatuses = MutableStateFlow<List<LimitStatus>?>(null)

    /**
     * The status of all limits as it was last worked out, or null if it never was.
     * At hand at once, for showing it without waiting; every use case keeps it up to date.
     */
    val statuses: StateFlow<List<LimitStatus>?> = latestStatuses

    private val latestSessions = MutableStateFlow<List<SessionStatus>>(emptyList())

    /** Today's sessions that were not dismissed, newest first, as they were last worked out. */
    val sessionStatuses: StateFlow<List<SessionStatus>> = latestSessions

    private val latestOffer = MutableStateFlow(SessionOffer.NO_LIMITS)

    /** Whether a session can be started, as it was last worked out. */
    val sessionOffer: StateFlow<SessionOffer> = latestOffer

    /** Creates or changes a limit. Buddies learn about every way in which it got weaker. */
    suspend fun save(limit: Limit) =
        mutex.withLock {
            closePastDays()
            val previous = limits.find(limit.id)
            val weakenings = previous?.weakenedBy(limit).orEmpty()
            // Buddies first learn how far a reached limit was overrun, then that it was weakened.
            if (weakenings.isNotEmpty()) reportNow(limit.id.value)
            limits.save(limit)
            weakenings.forEach { events.setupWeakened(it) }
            if (previous != null && standingHit(previous) != null && standingHit(limit) == null) {
                events.hitLifted(limit.id)
            }
            checkToday()
            refreshStatuses()
        }

    suspend fun delete(id: LimitId) =
        mutex.withLock {
            closePastDays()
            val limit = limits.find(id) ?: return@withLock
            reportNow(id.value)
            limits.delete(id)
            events.setupWeakened(limit.deleted())
            if (standingHit(limit) != null) events.hitLifted(id)
            checkToday()
            refreshStatuses()
        }

    /**
     * Starts a session of [length]. Returns false if no session can be started now:
     * see [SessionOffer] for what can stand in the way.
     */
    suspend fun startSession(length: Duration): Boolean =
        mutex.withLock {
            closePastDays()
            checkToday()
            refreshStatuses()
            if (latestOffer.value != SessionOffer.AVAILABLE) return@withLock false
            sessions.save(Session(SessionId(UUID.randomUUID().toString()), length, clock.instant()))
            refreshStatuses()
            true
        }

    /** Stops a running session. What it had used by now stays; nobody is told. */
    suspend fun stopSession(id: SessionId) =
        mutex.withLock {
            closePastDays()
            checkToday()
            val session = sessions.all().firstOrNull { it.id == id && it.isRunning } ?: return@withLock
            val now = clock.instant()
            sessions.save(session.stopped(now, usedBy(session, currentDay(), now)))
            announceWarnings()
            refreshStatuses()
        }

    /** Takes a session that is over out of the list. If it was used up, it still appears in the day summary. */
    suspend fun dismissSession(id: SessionId) =
        mutex.withLock {
            val session = sessions.all().firstOrNull { it.id == id && !it.isRunning } ?: return@withLock
            sessions.save(session.dismissed())
            refreshStatuses()
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
            refreshStatuses()
        }

    /** Works out the status of all limits as of now. */
    suspend fun today(): List<LimitStatus> = mutex.withLock { refreshStatuses() }

    private suspend fun refreshStatuses(): List<LimitStatus> {
        val today = currentDay()
        val now = clock.instant()
        val intervals = intervalsOn(today).orEmpty()
        val usage = Usage.on(today, clock.zone, intervals)
        val hits = hitLog.hitsOn(today)
        val allLimits = limits.all()
        val limitedApps = allLimits.flatMap { it.apps }.toSet()
        val allSessions = sessions.all()
        latestSessions.value =
            allSessions
                .filterNot { it.dismissed }
                .sortedByDescending { it.startedAt }
                .map { SessionStatus(it, it.used(intervals, limitedApps, now)) }
        return allLimits
            .map { limit -> LimitStatus(limit, usage.of(limit), hits.any { it.standsFor(limit) }) }
            .also {
                latestStatuses.value = it
                latestOffer.value = SessionOffer.of(it, allSessions)
            }
    }

    /** The time [session] has counted on [day] until [until]. */
    private suspend fun usedBy(
        session: Session,
        day: Day,
        until: Instant,
    ): Duration = session.used(intervalsOn(day).orEmpty(), limits.all().flatMap { it.apps }.toSet(), until)

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
            val dayEnd = day.end(clock.zone)
            detectHits(day, dayEnd)
            sendReports(day) { it.isDueAt(dayEnd) }
            val closed = DayClosed.of(day, hitLog.hitsOn(day), usageOn(day) ?: Usage.NONE)
            events.dayClosed(closed.copy(entries = closed.entries + endSessionsOf(day)))
            memory.lastClosedDay = day
            day = day.next()
        }
        hitLog.forgetBefore(today.minusDays(MAX_DAYS_TO_CATCH_UP))
        sessions.forgetStartedBefore(today.start(clock.zone))
        // Reports of days too long ago to be closed are given up.
        memory.pendingReports = memory.pendingReports.filterNot { it.isDueAt(today.start(clock.zone)) }
    }

    /** Stops the sessions still running at the end of [day] and accounts for those that were used up. */
    private suspend fun endSessionsOf(day: Day): List<DayClosed.Entry> {
        val dayEnd = day.end(clock.zone)
        return sessions
            .all()
            .filter { Day.of(it.startedAt, clock.zone) == day }
            .map {
                if (it.isRunning) {
                    it.stopped(dayEnd, usedBy(it, day, dayEnd)).also { ended ->
                        sessions.save(ended)
                    }
                } else {
                    it
                }
            }.filter { it.isUsedUp }
            .map { DayClosed.entryFor(it, it.usedWhenStopped ?: it.length) }
    }

    private suspend fun checkToday() {
        val now = clock.instant()
        detectHits(currentDay(), now)
        announceWarnings()
        sendReports(currentDay()) { it.isDueAt(now) }
    }

    /** Remembers that the buddies are to be told about a limit or session reached at [reachedAt] on [day]. */
    private fun expectReport(
        subjectId: String,
        name: String,
        budget: Duration,
        reachedAt: Instant,
        day: Day,
    ) {
        val pending = PendingReport.after(subjectId, name, budget, reachedAt, latest = day.end(clock.zone))
        memory.pendingReports = memory.pendingReports.filterNot { it.subjectId == subjectId } + pending
        wakeUpForNextReport()
    }

    private fun wakeUpForNextReport() {
        memory.pendingReports.minOfOrNull { it.dueAt }?.let(wakeUp::at)
    }

    /** Tells the buddies now about the limit or session with this id, if they are still to be told. */
    private suspend fun reportNow(subjectId: String) = sendReports(currentDay()) { it.subjectId == subjectId }

    /**
     * Tells the buddies about the reached limits and used-up sessions chosen by [which],
     * with what is known about [day] at this moment: all that was used, and whether one
     * of the apps concerned is open right now.
     */
    private suspend fun sendReports(
        day: Day,
        which: (PendingReport) -> Boolean,
    ) {
        val (toSend, toKeep) = memory.pendingReports.partition(which)
        if (toSend.isEmpty()) return
        val until = minOf(clock.instant(), day.end(clock.zone))
        val intervals = intervalsOn(day).orEmpty()
        val usage = Usage.on(day, clock.zone, intervals)
        val allLimits = limits.all()
        val limitedApps = allLimits.flatMap { it.apps }.toSet()
        val allSessions = sessions.all()

        fun isOpen(apps: Set<AppId>) = intervals.any { it.app in apps && it.end >= until }
        for (pending in toSend) {
            val limit = allLimits.firstOrNull { it.id.value == pending.subjectId }
            val session = allSessions.firstOrNull { it.id.value == pending.subjectId }
            val report =
                when {
                    limit != null -> pending.report(usage.of(limit), isOpen(limit.apps))
                    session != null ->
                        pending.report(
                            session.used(intervals, limitedApps, until),
                            stillGoing = session.isRunning && isOpen(limitedApps),
                        )
                    else -> pending.report(pending.budget, stillGoing = false)
                }
            events.hitReported(report)
        }
        memory.pendingReports = toKeep
        wakeUpForNextReport()
    }

    /**
     * Tells the sender when a limit or the running session comes close to its end, once per
     * level and day. A level that no longer applies is taken back, so that it can be
     * announced again: this happens when a limit is weakened or deleted or a session stops.
     */
    private suspend fun announceWarnings() {
        val today = currentDay()
        val intervals = intervalsOn(today) ?: return
        val usage = Usage.on(today, clock.zone, intervals)
        val allLimits = limits.all()
        val limitedApps = allLimits.flatMap { it.apps }.toSet()
        val now = clock.instant()
        val candidates =
            allLimits.map { UsageWarning(it.id.value, it.name, it.budget, usage.of(it), UsageLevel.NORMAL) } +
                sessions.all().filter { it.isRunning }.map {
                    UsageWarning(
                        it.id.value,
                        Session.NAME,
                        it.length,
                        it.used(intervals, limitedApps, now),
                        UsageLevel.NORMAL,
                    )
                }
        val before = memory.announcedWarnings
        val levels = candidates.associate { it.subjectId to UsageLevel.of(it.used, it.budget) }
        memory.announcedWarnings = AnnouncedWarnings(today, levels)
        for (candidate in candidates) {
            val level = levels.getValue(candidate.subjectId)
            val previous = before.levelOn(today, candidate.subjectId)
            if (level > previous && level.isWarning) events.warned(candidate.copy(level = level))
            if (level < previous) events.warningLifted(candidate.subjectId)
        }
        before.levels
            .filter { (id, level) -> id !in levels && before.levelOn(today, id) == level && level.isWarning }
            .forEach { (id, _) -> events.warningLifted(id) }
    }

    private suspend fun detectHits(
        day: Day,
        at: Instant,
    ) {
        val all = limits.all()
        if (all.isEmpty()) return
        val intervals = intervalsOn(day) ?: return
        all.newHits(day, Usage.on(day, clock.zone, intervals), hitLog.hitsOn(day), at).forEach {
            hitLog.record(it)
            events.limitHit(it)
            expectReport(it.limitId.value, it.limitName, it.budget, at, day)
        }
        val limitedApps = all.flatMap { it.apps }.toSet()
        sessions
            .all()
            .filter { it.isRunning && !it.isUsedUp && Day.of(it.startedAt, clock.zone) == day }
            .filter { it.used(intervals, limitedApps, at) >= it.length }
            .forEach {
                val usedUp = it.usedUp(at)
                sessions.save(usedUp)
                events.sessionUsedUp(usedUp)
                expectReport(usedUp.id.value, Session.NAME, usedUp.length, at, day)
            }
    }

    private suspend fun usageOn(day: Day): Usage? = intervalsOn(day)?.let { Usage.on(day, clock.zone, it) }

    /** What the phone recorded for [day] so far, or null without usage access. */
    private suspend fun intervalsOn(day: Day): List<ForegroundInterval>? {
        val zone = clock.zone
        val until = minOf(clock.instant(), day.end(zone))
        return usageSource.foregroundIntervals(day.start(zone), until)
    }

    private companion object {
        const val MAX_DAYS_TO_CATCH_UP = 7L
    }
}
