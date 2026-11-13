package dev.chr1shaefn3r.accountabilitybuddy.ui

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.HistoryEntry
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

// The words for the state of a pairing, as shown in the lists of buddies and of senders.

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
