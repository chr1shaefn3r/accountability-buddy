package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration

/** An installed app, identified by its Android package name. */
@JvmInline
value class AppId(
    val packageName: String,
)

@JvmInline
value class LimitId(
    val value: String,
)

/** A daily time budget shared by one or more apps. */
data class Limit(
    val id: LimitId,
    val name: String,
    val budget: Duration,
    val apps: Set<AppId>,
) {
    init {
        require(name.isNotBlank()) { "A limit needs a name" }
        require(name.length <= MAX_NAME_LENGTH) { "A limit name has at most $MAX_NAME_LENGTH characters" }
        require(budget in MIN_BUDGET..MAX_BUDGET) { "A budget is between $MIN_BUDGET and $MAX_BUDGET" }
        require(apps.isNotEmpty()) { "A limit has at least one app" }
    }

    /** The ways in which [updated] is weaker than this limit. Tightening yields nothing. */
    fun weakenedBy(updated: Limit): List<Weakening> =
        buildList {
            if (updated.budget > budget) add(Weakening.BudgetRaised(name, budget, updated.budget))
            val removed = apps - updated.apps
            if (removed.isNotEmpty()) add(Weakening.AppsRemoved(name, removed))
        }

    fun deleted(): Weakening = Weakening.LimitDeleted(name, budget)

    companion object {
        const val MAX_NAME_LENGTH = 30
        val MIN_BUDGET: Duration = Duration.ofMinutes(1)
        val MAX_BUDGET: Duration = Duration.ofHours(24)
    }
}

/** A change that makes the sender's setup less strict. Buddies are told about each one. */
sealed interface Weakening {
    data class BudgetRaised(
        val limitName: String,
        val from: Duration,
        val to: Duration,
    ) : Weakening

    data class AppsRemoved(
        val limitName: String,
        val apps: Set<AppId>,
    ) : Weakening

    data class LimitDeleted(
        val limitName: String,
        val budget: Duration,
    ) : Weakening

    data class TrackingLost(
        val problem: TrackingProblem,
    ) : Weakening
}

/** A reason why usage can no longer be tracked or reported on this phone. */
enum class TrackingProblem {
    USAGE_ACCESS_MISSING,
    NOTIFICATIONS_BLOCKED,
}
