package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import kotlinx.coroutines.flow.Flow
import java.time.Instant

interface LimitRepository {
    fun observeAll(): Flow<List<Limit>>

    suspend fun all(): List<Limit>

    suspend fun find(id: LimitId): Limit?

    suspend fun save(limit: Limit)

    suspend fun delete(id: LimitId)
}

/** Remembers which limits were hit on which day. */
interface HitLog {
    /** The latest hit of each limit on [day]. */
    suspend fun hitsOn(day: Day): List<LimitHit>

    /** Remembers [hit], in place of an earlier hit of the same limit on the same day. */
    suspend fun record(hit: LimitHit)

    suspend fun forgetBefore(day: Day)
}

/** Keeps the sessions of the current day. */
interface SessionRepository {
    suspend fun all(): List<Session>

    suspend fun save(session: Session)

    suspend fun forgetStartedBefore(instant: Instant)
}

/** The phone's record of which app was in the foreground when. */
interface UsageSource {
    /** Foreground intervals overlapping the given time span, or null if usage access is missing. */
    suspend fun foregroundIntervals(
        from: Instant,
        to: Instant,
    ): List<ForegroundInterval>?
}

/** What the phone currently allows this app to do. */
interface TrackingHealth {
    fun currentProblems(): Set<TrackingProblem>
}

/** Small persistent facts the Limits context needs between runs. */
interface LimitsMemory {
    var lastClosedDay: Day?
    var reportedProblems: Set<TrackingProblem>
    var announcedWarnings: AnnouncedWarnings
    var pendingReports: List<PendingReport>
}

/** Asks for the app to be woken, so that it can act at a moment when it is not open. */
fun interface WakeUp {
    fun at(instant: Instant)
}

/** Receives what happens in the Limits context. */
interface LimitsEvents {
    /** A limit was reached. The sender learns it now, the buddies with [hitReported]. */
    suspend fun limitHit(hit: LimitHit)

    /** The waiting time after a reached limit or used-up session is over: time to tell the buddies. */
    suspend fun hitReported(report: HitReport)

    suspend fun setupWeakened(weakening: Weakening)

    suspend fun dayClosed(dayClosed: DayClosed)

    /** A hit of today no longer stands: the limit was weakened beyond it or deleted. */
    suspend fun hitLifted(limitId: LimitId)

    /** The time of a session is used up. This counts like a limit that was hit, and is reported the same way. */
    suspend fun sessionUsedUp(session: Session)

    /** A limit or a session is close to its end. */
    suspend fun warned(warning: UsageWarning)

    /** A warning no longer applies: the limit was weakened or deleted, or the session stopped. */
    suspend fun warningLifted(subjectId: String)
}

/** Tells the owner of this phone about their own limits. */
interface SenderNotifier {
    fun limitReached(hit: LimitHit)

    fun sessionUsedUp(session: Session)

    fun warned(warning: UsageWarning)

    /** Takes back whatever is shown about the limit or session with this id. */
    fun nothingToShowFor(subjectId: String)
}

/** Knows the apps installed on this phone. */
interface AppCatalog {
    /** The name of an app as the user knows it. */
    fun label(app: AppId): String

    /** All apps the user can start, sorted by label. */
    fun launchableApps(): List<InstalledApp>
}

data class InstalledApp(
    val id: AppId,
    val label: String,
)
