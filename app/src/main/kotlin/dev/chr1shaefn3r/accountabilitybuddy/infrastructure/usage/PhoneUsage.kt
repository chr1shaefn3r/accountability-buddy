package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.usage

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppCatalog
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.ForegroundInterval
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.InstalledApp
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingHealth
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingProblem
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageSource
import java.time.Duration
import java.time.Instant

/** Reads usage, permissions and installed apps from the Android system. */
class PhoneUsage(private val context: Context) : UsageSource, TrackingHealth, AppCatalog {
    private val usageStats = context.getSystemService(UsageStatsManager::class.java)
    private val appOps = context.getSystemService(AppOpsManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val packages = context.packageManager

    val hasUsageAccess: Boolean
        get() =
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                Process.myUid(),
                context.packageName,
            ) == AppOpsManager.MODE_ALLOWED

    override fun foregroundIntervals(from: Instant, to: Instant): List<ForegroundInterval>? {
        if (!hasUsageAccess) return null
        val queryFrom = from - LOOK_BACK
        val events = usageStats.queryEvents(queryFrom.toEpochMilli(), to.toEpochMilli())
        val translated = mutableListOf<UsageEvent>()
        val event = UsageEvents.Event()
        while (events.getNextEvent(event)) {
            val at = Instant.ofEpochMilli(event.timeStamp)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED ->
                    translated += UsageEvent.Resumed(AppId(event.packageName), event.className.orEmpty(), at)
                UsageEvents.Event.ACTIVITY_PAUSED ->
                    translated += UsageEvent.Paused(AppId(event.packageName), event.className.orEmpty(), at)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE,
                UsageEvents.Event.DEVICE_SHUTDOWN,
                -> translated += UsageEvent.ScreenOff(at)
            }
        }
        return foregroundIntervals(translated, queryFrom, to)
    }

    override fun currentProblems(): Set<TrackingProblem> =
        buildSet {
            if (!hasUsageAccess) add(TrackingProblem.USAGE_ACCESS_MISSING)
            if (!notifications.areNotificationsEnabled()) add(TrackingProblem.NOTIFICATIONS_BLOCKED)
        }

    override fun label(app: AppId): String =
        try {
            packages.getApplicationLabel(packages.getApplicationInfo(app.packageName, 0)).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            app.packageName
        }

    override fun launchableApps(): List<InstalledApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(launcher, 0)
            .map { InstalledApp(AppId(it.activityInfo.packageName), it.loadLabel(packages).toString()) }
            .filter { it.id.packageName != context.packageName }
            .distinctBy { it.id }
            .sortedBy { it.label.lowercase() }
    }

    private companion object {
        /** How far before the requested time span events are read, to catch apps opened earlier. */
        val LOOK_BACK: Duration = Duration.ofHours(6)
    }
}
