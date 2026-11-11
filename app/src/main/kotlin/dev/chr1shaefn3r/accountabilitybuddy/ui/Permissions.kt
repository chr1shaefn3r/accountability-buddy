package dev.chr1shaefn3r.accountabilitybuddy.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Opens the system page where the user can grant usage access. */
fun Context.openUsageAccessSettings() = open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

/** Opens this app's notification settings. */
fun Context.openNotificationSettings() =
    open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))

/** Asks the system to let this app run in the background without battery restrictions. */
@SuppressLint("BatteryLife") // Watching usage all day is this app's purpose; it is not distributed through Play.
fun Context.requestBatteryExemption() =
    open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null)))

val Context.isExemptFromBatteryOptimization: Boolean
    get() = getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/**
 * A number that changes every time the screen comes back to the front. Reading it makes a
 * composable look again at things the user may have changed in the system settings.
 */
@Composable
fun rememberResumeCount(): Int {
    var count by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        count++
        onPauseOrDispose { }
    }
    return count
}
