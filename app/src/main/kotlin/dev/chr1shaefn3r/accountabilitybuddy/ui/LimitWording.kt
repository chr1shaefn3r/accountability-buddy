package dev.chr1shaefn3r.accountabilitybuddy.ui

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
fun warningText(warning: UsageWarning): String {
    val left = if (warning.left < Duration.ofMinutes(1)) "less than 1m" else warning.left.asText()
    return "${warning.name}: ${usageText(warning.used, warning.budget)}, $left left"
}

/** What a session is called in the list: "Session" while it runs, "Session (ended)" afterwards. */
fun sessionTitle(session: Session): String = if (session.isRunning) Session.NAME else "${Session.NAME} (ended)"
