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
    suspend fun hitsOn(day: Day): List<LimitHit>

    suspend fun record(hit: LimitHit)

    suspend fun forgetBefore(day: Day)
}

/** The phone's record of which app was in the foreground when. */
interface UsageSource {
    /** Foreground intervals overlapping the given time span, or null if usage access is missing. */
    fun foregroundIntervals(
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
}

/** Receives what happens in the Limits context. */
interface LimitsEvents {
    suspend fun limitHit(hit: LimitHit)

    suspend fun setupWeakened(weakening: Weakening)

    suspend fun dayClosed(dayClosed: DayClosed)
}

/** Tells the owner of this phone about their own limits. */
fun interface SenderNotifier {
    fun limitReached(hit: LimitHit)
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
