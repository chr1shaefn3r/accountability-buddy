package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.chr1shaefn3r.accountabilitybuddy.MainActivity
import dev.chr1shaefn3r.accountabilitybuddy.R
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyNotifier
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Pairing
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.background.SessionReceiver
import dev.chr1shaefn3r.accountabilitybuddy.limits.application.SessionStatus
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SenderNotifier
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Session
import dev.chr1shaefn3r.accountabilitybuddy.ui.asText
import dev.chr1shaefn3r.accountabilitybuddy.ui.limitReachedText
import dev.chr1shaefn3r.accountabilitybuddy.ui.usageText
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** The kinds of notification. Each has its own Android channel, so users can tune them one by one. */
private enum class Channel(
    val id: String,
    val title: String,
    val importance: Int,
) {
    MY_LIMITS("my-limits", "My limits reached", NotificationManager.IMPORTANCE_DEFAULT),
    LIMIT_REACHED("limit-reached", "Limit reached by someone I support", NotificationManager.IMPORTANCE_HIGH),
    CHANGES("changes", "Changes by someone I support", NotificationManager.IMPORTANCE_DEFAULT),
    DAY_SUMMARY("day-summary", "Day summary", NotificationManager.IMPORTANCE_LOW),
    SILENCE("silence", "Silence alert", NotificationManager.IMPORTANCE_DEFAULT),
    PAIRING("pairing", "Pairing", NotificationManager.IMPORTANCE_HIGH),
    SESSION("session", "Running session", NotificationManager.IMPORTANCE_LOW),
    TRACKING("tracking", "Tracking service", NotificationManager.IMPORTANCE_MIN),
}

/** What all notifications of the app have in common: the channels, the look, and what a tap does. */
private class NotificationBoard(
    private val context: Context,
) {
    val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val nextId = AtomicInteger((System.currentTimeMillis() % FIRST_ID_RANGE).toInt() + FIRST_FREE_ID)

    init {
        manager.createNotificationChannels(Channel.entries.map { NotificationChannel(it.id, it.title, it.importance) })
    }

    /** Shows a notification that stays until it is tapped or swiped away. */
    fun show(
        channel: Channel,
        title: String,
        text: String,
    ) = manager.notify(nextId.incrementAndGet(), builder(channel, title, text).setAutoCancel(true).build())

    fun builder(
        channel: Channel,
        title: String,
        text: String,
    ): Notification.Builder =
        Notification
            .Builder(context, channel.id)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )

    private companion object {
        const val FIRST_ID_RANGE = 1_000_000
        const val FIRST_FREE_ID = 10
    }
}

/** Shows what partners report, on the phone of the one who is told. */
private class PartnerNotifications(
    private val board: NotificationBoard,
) : BuddyNotifier {
    override fun pairingRequested(pairing: Pairing) =
        board.show(Channel.PAIRING, "${pairing.displayName} wants to be your buddy", "Open the app to confirm.")

    override fun pairingAccepted(pairing: Pairing) =
        board.show(
            Channel.PAIRING,
            pairing.displayName,
            "Pairing confirmed. You are now ${pairing.displayName}'s buddy.",
        )

    override fun pairingEnded(pairing: Pairing) =
        board.show(Channel.PAIRING, pairing.displayName, "The pairing has ended.")

    override fun limitHit(
        pairing: Pairing,
        message: BuddyMessage.LimitHit,
    ) = board.show(Channel.LIMIT_REACHED, pairing.displayName, message.asText())

    override fun setupWeakened(
        pairing: Pairing,
        message: BuddyMessage.SetupWeakened,
    ) = board.show(Channel.CHANGES, pairing.displayName, message.asText())

    override fun daySummary(
        pairing: Pairing,
        message: BuddyMessage.DayClosed,
    ) = board.show(Channel.DAY_SUMMARY, "${pairing.displayName}: ${message.day.asText()}", message.asText())

    override fun silent(pairing: Pairing) {
        val since =
            pairing.lastSignOfLife
                .atZone(ZoneId.systemDefault())
                .toLocalDate()
                .asText()
        board.show(Channel.SILENCE, pairing.displayName, "No sign of life since $since.")
    }
}

/**
 * Shows Android notifications. This class shows the sender what happens to their own
 * limits and sessions; [aboutPartners] shows what partners report.
 */
class Notifications(
    private val context: Context,
) : SenderNotifier {
    private val board = NotificationBoard(context)

    val aboutPartners: BuddyNotifier = PartnerNotifications(board)

    /** One notification per limit, so that a later hit replaces it and a lifted hit can remove it. */
    override fun limitReached(hit: LimitHit) =
        showReached(tag = hit.limitId.value, text = limitReachedText(hit.limitName, hit.budget))

    override fun limitNoLongerReached(limitId: LimitId) = board.manager.cancel(limitId.value, MY_LIMIT_ID)

    override fun sessionUsedUp(session: Session) =
        showReached(tag = session.id.value, text = limitReachedText(Session.NAME, session.length))

    private fun showReached(
        tag: String,
        text: String,
    ) {
        val notification = board.builder(Channel.MY_LIMITS, "Limit reached", text).setAutoCancel(true).build()
        board.manager.notify(tag, MY_LIMIT_ID, notification)
    }

    /**
     * The permanent notification of the service that watches app usage. While no session
     * is running it offers to start one, with one button per usual length.
     */
    fun tracking(offerSessions: Boolean): Notification =
        board
            .builder(Channel.TRACKING, "Watching your limits", if (offerSessions) "Start a session:" else "")
            .setOngoing(true)
            .apply {
                if (offerSessions) {
                    SESSION_PRESETS_MINUTES.forEach {
                        addAction(button("$it min", SessionReceiver.startIntent(context, it)))
                    }
                }
            }.build()

    /** Brings the permanent notification in line with whether a session can be started. */
    fun showTracking(offerSessions: Boolean) = board.manager.notify(TRACKING_ID, tracking(offerSessions))

    /** Shows the running session with a way to stop it, or removes that notification if there is none. */
    fun showSession(running: SessionStatus?) {
        if (running == null) {
            board.manager.cancel(SESSION_ID)
            return
        }
        val session = running.session
        val title = if (session.isUsedUp) "Session: limit reached" else "Session running"
        val notification =
            board
                .builder(Channel.SESSION, title, usageText(running.used, session.length))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(button("Stop", SessionReceiver.stopIntent(context, session.id)))
                .build()
        board.manager.notify(SESSION_ID, notification)
    }

    private fun button(
        title: String,
        intent: PendingIntent,
    ): Notification.Action = Notification.Action.Builder(null, title, intent).build()

    companion object {
        const val TRACKING_ID = 1
        private const val MY_LIMIT_ID = 2
        private const val SESSION_ID = 3

        /** The session lengths offered as buttons: on the notification and in the app. */
        val SESSION_PRESETS_MINUTES = listOf(5, 15, 30)
    }
}
