package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.background

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.chr1shaefn3r.accountabilitybuddy.AccountabilityBuddyApp
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.DeliverySchedule
import dev.chr1shaefn3r.accountabilitybuddy.infrastructure.notifications.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Sends the outbox whenever there is a network, retrying with growing pauses. */
class DeliveryWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result =
        if ((applicationContext as AccountabilityBuddyApp).buddies.deliver()) Result.success() else Result.retry()
}

class WorkManagerDeliverySchedule(
    private val context: Context,
) : DeliverySchedule {
    override fun deliverSoon() {
        val request =
            OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_AFTER_SECONDS, TimeUnit.SECONDS)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork("deliver", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val RETRY_AFTER_SECONDS = 30L
    }
}

/** Twice a day: looks for senders that have gone silent and tidies up old history. */
class SilenceWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        (applicationContext as AccountabilityBuddyApp).buddies.checkSilence()
        return Result.success()
    }

    companion object {
        private const val EVERY_HOURS = 12L

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "silence",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SilenceWorker>(EVERY_HOURS, TimeUnit.HOURS).build(),
            )
        }
    }
}

/**
 * Watches app usage while the screen is on, so that a reached limit is noticed within
 * seconds. Runs as a foreground service with a permanent notification.
 */
class TrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as AccountabilityBuddyApp
        startForeground(
            Notifications.TRACKING_ID,
            app.notifications.tracking(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        val power = getSystemService(PowerManager::class.java)
        scope.launch {
            var wasInteractive = true
            while (isActive) {
                // One more check after the screen went off settles the last seconds of use.
                val interactive = power.isInteractive
                if (interactive || wasInteractive) app.limits.check()
                wasInteractive = interactive
                if (app.limitRepository.all().isEmpty()) {
                    stopSelf()
                    break
                }
                delay(CHECK_EVERY_MILLIS)
            }
        }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHECK_EVERY_MILLIS = 20_000L

        /** Starts the service if Android currently allows it. Returns false otherwise. */
        fun start(context: Context): Boolean =
            try {
                context.startForegroundService(Intent(context, TrackingService::class.java))
                true
            } catch (_: IllegalStateException) {
                // Android forbids starting from the background; the next app start or alarm will do it.
                false
            }
    }
}

/**
 * Wakes the app at moments when it must act without being open: after a reboot, at
 * midnight, and when the clock or time zone changes.
 */
class WakeUpReceiver : BroadcastReceiver() {
    // Not exported, and every wake-up does the same harmless thing whatever the action is.
    @SuppressLint("UnsafeProtectedBroadcastReceiver")
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val pending = goAsync()
        val app = context.applicationContext as AccountabilityBuddyApp
        app.background.launch {
            try {
                app.limits.check()
            } finally {
                pending.finish()
            }
        }
        app.wakeUp()
    }

    companion object {
        private val AFTER_MIDNIGHT: Duration = Duration.ofSeconds(5)

        /** Sets an exact alarm for just after the next midnight, to close the day on time. */
        @SuppressLint("MissingPermission") // The manifest declares USE_EXACT_ALARM, which lint does not know.
        fun scheduleMidnight(
            context: Context,
            clock: Clock,
        ) {
            val midnight =
                LocalDate
                    .now(clock)
                    .plusDays(1)
                    .atStartOfDay(clock.zone)
                    .toInstant()
            val intent =
                PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, WakeUpReceiver::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val at = (midnight + AFTER_MIDNIGHT).toEpochMilli()
            val alarms = context.getSystemService(AlarmManager::class.java)
            try {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            } catch (_: SecurityException) {
                // Exact alarms were taken away: the day is then closed a little later.
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
        }
    }
}
