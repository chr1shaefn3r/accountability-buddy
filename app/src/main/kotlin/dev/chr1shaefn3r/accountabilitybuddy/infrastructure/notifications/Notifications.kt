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
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SenderNotifier
import dev.chr1shaefn3r.accountabilitybuddy.ui.asText
import dev.chr1shaefn3r.accountabilitybuddy.ui.limitReachedText
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

/** Shows Android notifications, each kind on its own channel. */
class Notifications(private val context: Context) : BuddyNotifier, SenderNotifier {
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val nextId = AtomicInteger((System.currentTimeMillis() % FIRST_ID_RANGE).toInt() + TRACKING_ID + 1)

    private enum class Channel(val id: String, val title: String, val importance: Int) {
        MY_LIMITS("my-limits", "My limits reached", NotificationManager.IMPORTANCE_DEFAULT),
        LIMIT_REACHED("limit-reached", "Limit reached by someone I support", NotificationManager.IMPORTANCE_HIGH),
        CHANGES("changes", "Changes by someone I support", NotificationManager.IMPORTANCE_DEFAULT),
        DAY_SUMMARY("day-summary", "Day summary", NotificationManager.IMPORTANCE_LOW),
        SILENCE("silence", "Silence alert", NotificationManager.IMPORTANCE_DEFAULT),
        PAIRING("pairing", "Pairing", NotificationManager.IMPORTANCE_HIGH),
        TRACKING("tracking", "Tracking service", NotificationManager.IMPORTANCE_MIN),
    }

    init {
        manager.createNotificationChannels(Channel.entries.map { NotificationChannel(it.id, it.title, it.importance) })
    }

    override fun limitReached(hit: LimitHit) =
        show(Channel.MY_LIMITS, "Limit reached", limitReachedText(hit.limitName, hit.budget))

    override fun pairingRequested(pairing: Pairing) =
        show(Channel.PAIRING, "${pairing.displayName} wants to be your buddy", "Open the app to confirm.")

    override fun pairingAccepted(pairing: Pairing) =
        show(Channel.PAIRING, pairing.displayName, "Pairing confirmed. You are now ${pairing.displayName}'s buddy.")

    override fun pairingEnded(pairing: Pairing) =
        show(Channel.PAIRING, pairing.displayName, "The pairing has ended.")

    override fun limitHit(pairing: Pairing, message: BuddyMessage.LimitHit) =
        show(Channel.LIMIT_REACHED, pairing.displayName, message.asText())

    override fun setupWeakened(pairing: Pairing, message: BuddyMessage.SetupWeakened) =
        show(Channel.CHANGES, pairing.displayName, message.asText())

    override fun daySummary(pairing: Pairing, message: BuddyMessage.DayClosed) =
        show(Channel.DAY_SUMMARY, "${pairing.displayName}: ${message.day.asText()}", message.asText())

    override fun silent(pairing: Pairing) {
        val since = pairing.lastSignOfLife.atZone(ZoneId.systemDefault()).toLocalDate().asText()
        show(Channel.SILENCE, pairing.displayName, "No sign of life since $since.")
    }

    /** The permanent notification of the service that watches app usage. */
    fun tracking(): Notification =
        builder(Channel.TRACKING, "Watching your limits", "")
            .setOngoing(true)
            .build()

    private fun show(channel: Channel, title: String, text: String) {
        manager.notify(nextId.incrementAndGet(), builder(channel, title, text).setAutoCancel(true).build())
    }

    private fun builder(channel: Channel, title: String, text: String): Notification.Builder =
        Notification.Builder(context, channel.id)
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

    companion object {
        const val TRACKING_ID = 1
        private const val FIRST_ID_RANGE = 1_000_000
    }
}
