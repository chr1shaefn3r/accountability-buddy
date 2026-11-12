package dev.chr1shaefn3r.accountabilitybuddy.application

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage.TrackingLost.Problem
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppCatalog
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.DayClosed
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
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

    /** Only the sender's own notification is taken back. Buddies keep what they were told: it happened. */
    override suspend fun hitLifted(limitId: LimitId) = senderNotifier.limitNoLongerReached(limitId)

    override suspend fun setupWeakened(weakening: Weakening) {
        tellBuddies(
            when (weakening) {
                is Weakening.BudgetRaised ->
                    BuddyMessage.BudgetRaised(weakening.limitName, weakening.from.toMinutes(), weakening.to.toMinutes())
                is Weakening.AppsRemoved ->
                    BuddyMessage.AppsRemoved(weakening.limitName, namesOf(weakening.apps))
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

    /** The apps by the names users know, shortened so that the message always fits. */
    private fun namesOf(removed: Set<AppId>): List<String> {
        val names = removed.map { apps.label(it).take(MAX_APP_NAME_LENGTH) }.sorted()
        return if (names.size <=
            MAX_APP_NAMES
        ) {
            names
        } else {
            names.take(MAX_APP_NAMES) + "${names.size - MAX_APP_NAMES} more"
        }
    }

    override suspend fun dayClosed(dayClosed: DayClosed) {
        val entries =
            dayClosed.entries
                .sortedByDescending { it.used - it.budget }
                .take(BuddyMessage.DayClosed.MAX_ENTRIES)
                .map { BuddyMessage.DayClosed.Entry(it.limitName, it.budget.toMinutes(), it.used.toMinutes()) }
        tellBuddies(BuddyMessage.DayClosed(dayClosed.day.date.toString(), entries))
    }

    private companion object {
        const val MAX_APP_NAMES = 15
        const val MAX_APP_NAME_LENGTH = 40
    }
}
