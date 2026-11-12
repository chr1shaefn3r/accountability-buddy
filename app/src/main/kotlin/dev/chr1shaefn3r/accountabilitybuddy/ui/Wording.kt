package dev.chr1shaefn3r.accountabilitybuddy.ui

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.HistoryEntry
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/*
 * The words the app uses for what happens, on screen and in notifications.
 * They state facts and never judge.
 */

private const val MINUTES_PER_HOUR = 60

/** A duration the way the app always writes it: "1h 45m", "2h", "45m". */
fun Duration.asText(): String {
    val hours = toMinutes() / MINUTES_PER_HOUR
    val minutes = toMinutes() % MINUTES_PER_HOUR
    return when {
        hours == 0L -> "${minutes}m"
        minutes == 0L -> "${hours}h"
        else -> "${hours}h ${minutes}m"
    }
}

private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.ENGLISH)

/** A date as text: "Thursday, 12 Nov". */
fun LocalDate.asText(): String = format(dateFormat)

/** A date relative to [today]: "Today", "Yesterday" or the date. */
fun LocalDate.asTextRelativeTo(today: LocalDate): String =
    when (ChronoUnit.DAYS.between(this, today)) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> asText()
    }

fun limitReachedText(
    limitName: String,
    budget: Duration,
): String = "$limitName: ${budget.asText()} limit reached"

fun usageText(
    used: Duration,
    budget: Duration,
): String = "${used.asText()} of ${budget.asText()}"

/** One line that says what a message from a sender reports. */
fun BuddyMessage.asText(): String =
    when (this) {
        is BuddyMessage.LimitHit -> limitReachedText(limitName, budget)
        is BuddyMessage.BudgetRaised -> "$limitName: limit raised from ${from.asText()} to ${to.asText()}"
        is BuddyMessage.AppsRemoved -> "$limitName: ${apps.joinToString()} removed from the limit"
        is BuddyMessage.LimitDeleted -> "$limitName: ${budget.asText()} limit deleted"
        is BuddyMessage.TrackingLost ->
            when (problem) {
                BuddyMessage.TrackingLost.Problem.USAGE_ACCESS_MISSING -> "Usage tracking was switched off"
                BuddyMessage.TrackingLost.Problem.NOTIFICATIONS_BLOCKED -> "Notifications were switched off"
            }
        is BuddyMessage.DayClosed -> entries.joinToString("\n") { it.asText() }
        is BuddyMessage.PairingRequested,
        is BuddyMessage.PairingAccepted,
        is BuddyMessage.PairingDeclined,
        is BuddyMessage.PairingEnded,
        is BuddyMessage.AddressChanged,
        is BuddyMessage.NameChanged,
        -> ""
    }

fun BuddyMessage.DayClosed.Entry.asText(): String = "$limitName: ${usageText(used, budget)}"

/** How long ago a sender last reported something, for the list of senders. */
fun quietText(daysSinceLastEvent: Long?): String =
    when (daysSinceLastEvent) {
        null -> "Nothing reported yet"
        0L -> "Last report today"
        1L -> "Last report yesterday"
        else -> "All quiet for $daysSinceLastEvent days"
    }

/** The one line shown for a sender in the list of people the user supports. */
fun senderStatusText(
    pairing: Pairing,
    latest: HistoryEntry?,
    today: LocalDate,
    zone: ZoneId,
): String =
    when (pairing.state) {
        PairingState.REQUESTED -> "Waiting for ${pairing.displayName} to confirm"
        PairingState.AWAITING_CONFIRMATION -> "Waiting for your confirmation"
        PairingState.ENDED -> "Pairing ended"
        PairingState.UNREACHABLE -> "Cannot be reached at the moment"
        PairingState.ACTIVE -> activeSenderStatusText(latest, today, zone)
    }

private fun activeSenderStatusText(
    latest: HistoryEntry?,
    today: LocalDate,
    zone: ZoneId,
): String {
    if (latest == null) return quietText(null)
    val date = latest.at.atZone(zone).toLocalDate()
    val daysAgo = ChronoUnit.DAYS.between(date, today)
    return if (daysAgo <= 1) {
        "${date.asTextRelativeTo(today)}: ${latest.message.asText().lineSequence().first()}"
    } else {
        quietText(daysAgo)
    }
}

/** The one line shown for a buddy in the sender's list of buddies. */
fun buddyStatusText(pairing: Pairing): String =
    when (pairing.state) {
        PairingState.AWAITING_CONFIRMATION -> "Wants to be your buddy"
        PairingState.REQUESTED -> "Waiting for confirmation"
        PairingState.ACTIVE -> "Is told about your limits"
        PairingState.UNREACHABLE -> "Cannot be reached. Pair again to continue."
        PairingState.ENDED -> "Ended the pairing"
    }
