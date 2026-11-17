package dev.chr1shaefn3r.accountabilitybuddy.ui

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Session
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageLevel
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageWarning
import java.time.Duration

// The words for limits and sessions: how much is used, and which level that is.

fun limitReachedText(
    limitName: String,
    budget: Duration,
): String = "$limitName: ${budget.asText()} limit reached"

fun usageText(
    used: Duration,
    budget: Duration,
): String = "${used.asText()} of ${budget.asText()}"

/** What a level is called, on a card and as the title of a notification. Nothing is said while all is normal. */
fun UsageLevel.asText(): String =
    when (this) {
        UsageLevel.NORMAL -> ""
        UsageLevel.WARNING -> "Warning"
        UsageLevel.LAST_WARNING -> "Last warning"
        UsageLevel.REACHED -> "Limit reached"
    }

/** What a warning says: how much is used and how much is left. */
fun warningText(warning: UsageWarning): String =
    "${warning.name}: ${usageText(warning.used, warning.budget)}, ${leftText(warning.budget - warning.used)}"

/**
 * The line under the bar of a limit or session. Times are written in whole minutes, so
 * once a warning applies it also says what is left: "14m of 15m" alone would not explain
 * a bar that is nearly full.
 */
fun cardUsageText(
    used: Duration,
    budget: Duration,
    level: UsageLevel,
): String = if (level.isWarning) "${usageText(used, budget)}, ${leftText(budget - used)}" else usageText(used, budget)

private fun leftText(left: Duration): String =
    if (left < Duration.ofMinutes(1)) "less than 1m left" else "${left.asText()} left"

/** What a session is called in the list: "Session" while it runs, "Session (ended)" afterwards. */
fun sessionTitle(session: Session): String = if (session.isRunning) Session.NAME else "${Session.NAME} (ended)"

/**
 * What a buddy reads about a reached limit: whether the sender is still going, or stopped
 * and with how much in total. A message that does not say how it went on only states the limit.
 */
fun reportText(hit: BuddyMessage.LimitHit): String {
    val used = hit.used ?: return limitReachedText(hit.limitName, hit.budget)
    val limit = hit.budget.asText()
    return when {
        hit.stillGoing == true -> "${hit.limitName}: over the $limit limit and still going, ${used.asText()} so far"
        used > hit.budget -> "${hit.limitName}: went over the $limit limit, ${used.asText()} in total"
        else -> "${hit.limitName}: reached the $limit limit and stopped"
    }
}
