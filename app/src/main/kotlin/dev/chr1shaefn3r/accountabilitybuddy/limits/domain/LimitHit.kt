package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.Instant

/**
 * A limit was reached on a day. Keeps a snapshot of the limit, so the day can be
 * summarised even if the limit is changed or deleted later that day.
 */
data class LimitHit(
    val day: Day,
    val limitId: LimitId,
    val limitName: String,
    val budget: Duration,
    val apps: Set<AppId>,
    val at: Instant,
) {
    /**
     * True as long as [limit] is the limit that was hit and has not been weakened since.
     * A hit that no longer stands does not count any more: the limit can be hit again.
     */
    fun standsFor(limit: Limit): Boolean =
        limitId == limit.id && Limit(limitId, limitName, budget, apps).weakenedBy(limit).isEmpty()
}

/**
 * The limits that have reached their budget on [day] and have no standing hit yet.
 * A limit is therefore hit once per day, and once more each time it was weakened in between.
 */
fun List<Limit>.newHits(
    day: Day,
    usage: Usage,
    earlierHits: List<LimitHit>,
    at: Instant,
): List<LimitHit> =
    filter { limit -> earlierHits.none { it.standsFor(limit) } && usage.of(limit) >= limit.budget }
        .map { LimitHit(day, it.id, it.name, it.budget, it.apps, at) }

/** The final account of a day: every limit that was hit, with its total usage. */
data class DayClosed(
    val day: Day,
    val entries: List<Entry>,
) {
    data class Entry(
        val limitName: String,
        val budget: Duration,
        val used: Duration,
    )

    companion object {
        fun of(
            day: Day,
            hits: List<LimitHit>,
            usage: Usage,
        ): DayClosed = DayClosed(day, hits.map { Entry(it.limitName, it.budget, maxOf(usage.of(it.apps), it.budget)) })
    }
}
