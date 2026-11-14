package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.Instant

/**
 * What the buddies are told about a reached limit or a used-up session.
 *
 * They are not told at the moment it happens but a waiting time later, so that the
 * report can say how it went on: whether the sender stopped, or is [stillGoing].
 */
data class HitReport(
    val name: String,
    val budget: Duration,
    val reachedAt: Instant,
    /** All the time counted by the moment of the report. Never less than the budget. */
    val used: Duration,
    /** True if one of the apps concerned was open at the moment of the report. */
    val stillGoing: Boolean,
)

/** A reached limit or used-up session that the buddies still have to be told about. */
data class PendingReport(
    /** The id of the limit or session this is about. */
    val subjectId: String,
    val name: String,
    val budget: Duration,
    val reachedAt: Instant,
    val dueAt: Instant,
) {
    fun isDueAt(now: Instant): Boolean = now >= dueAt

    fun report(
        used: Duration,
        stillGoing: Boolean,
    ): HitReport = HitReport(name, budget, reachedAt, maxOf(used, budget), stillGoing)

    companion object {
        /** The waiting time, as a share of the budget that was reached. */
        const val WAIT_PERCENT = 20L
        private const val PERCENT = 100L

        fun waitingTimeFor(budget: Duration): Duration = budget.multipliedBy(WAIT_PERCENT).dividedBy(PERCENT)

        /** The report is due one waiting time after [reachedAt], but no later than [latest]: the end of the day. */
        fun after(
            subjectId: String,
            name: String,
            budget: Duration,
            reachedAt: Instant,
            latest: Instant,
        ): PendingReport =
            PendingReport(subjectId, name, budget, reachedAt, minOf(reachedAt + waitingTimeFor(budget), latest))
    }
}
