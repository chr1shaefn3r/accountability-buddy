package dev.chr1shaefn3r.accountabilitybuddy.application

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage.TrackingLost.Problem
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppCatalog
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.DayClosed
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitsEvents
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SenderNotifier
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingProblem
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Weakening

/**
 * Connects the two contexts: what happens to the sender's limits is shown on the
 * sender's phone and told to the buddies.
 */
class BuddyReporter(
    private val senderNotifier: SenderNotifier,
    private val apps: AppCatalog,
    private val tellBuddies: suspend (BuddyMessage) -> Unit,
) : LimitsEvents {
    override suspend fun limitHit(hit: LimitHit) {
        senderNotifier.limitReached(hit)
        tellBuddies(BuddyMessage.LimitHit(hit.limitName, hit.budget.toMinutes(), hit.at.epochSecond))
    }

    override suspend fun setupWeakened(weakening: Weakening) {
        tellBuddies(
            when (weakening) {
                is Weakening.BudgetRaised ->
                    BuddyMessage.BudgetRaised(weakening.limitName, weakening.from.toMinutes(), weakening.to.toMinutes())
                is Weakening.AppsRemoved ->
                    BuddyMessage.AppsRemoved(weakening.limitName, weakening.apps.map(apps::label).sorted())
                is Weakening.LimitDeleted ->
                    BuddyMessage.LimitDeleted(weakening.limitName, weakening.budget.toMinutes())
                is Weakening.TrackingLost ->
                    BuddyMessage.TrackingLost(
                        when (weakening.problem) {
                            TrackingProblem.USAGE_ACCESS_MISSING -> Problem.USAGE_ACCESS_MISSING
                            TrackingProblem.NOTIFICATIONS_BLOCKED -> Problem.NOTIFICATIONS_BLOCKED
                        },
                    )
            },
        )
    }

    override suspend fun dayClosed(dayClosed: DayClosed) {
        val entries =
            dayClosed.entries
                .sortedByDescending { it.used - it.budget }
                .take(BuddyMessage.DayClosed.MAX_ENTRIES)
                .map { BuddyMessage.DayClosed.Entry(it.limitName, it.budget.toMinutes(), it.used.toMinutes()) }
        tellBuddies(BuddyMessage.DayClosed(dayClosed.day.date.toString(), entries))
    }
}
