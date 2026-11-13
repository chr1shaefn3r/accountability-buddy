package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.background

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import dev.chr1shaefn3r.accountabilitybuddy.TestApp
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The notifications of the tracking service and what their buttons do. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
class SessionNotificationsTest {
    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val notifications get() = shadowOf(app.getSystemService(NotificationManager::class.java))
    private lateinit var service: ServiceController<TrackingService>

    private val watching: Notification? get() = notifications.getNotification(WATCHING)
    private val session: Notification? get() = notifications.getNotification(SESSION)

    private fun Notification?.buttons(): List<String> = this?.actions.orEmpty().map { it.title.toString() }

    private fun Notification.text(): String? = extras.getString(Notification.EXTRA_TEXT)

    /** Presses a button of a notification: delivers its intent to the receiver, as Android would. */
    private fun Notification.press(button: String) {
        val action = actions.single { it.title.toString() == button }
        SessionReceiver().onReceive(app, shadowOf(action.actionIntent).savedIntent)
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
        assert(condition()) { "Timed out waiting for: $what" }
    }

    @Before
    fun startTracking() {
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        runBlocking {
            app.limits.save(Limit(LimitId("video"), "Video", Duration.ofHours(1), setOf(AppId("com.example.video"))))
        }
        service = Robolectric.buildService(TrackingService::class.java).create()
        waitFor("the permanent notification to offer sessions") { watching.buttons().isNotEmpty() }
    }

    @After
    fun stopTracking() {
        service.destroy()
    }

    @Test
    fun `the permanent notification offers to start a session of a usual length`() {
        assertEquals(listOf("5 min", "15 min", "30 min"), watching.buttons())
        assertNull(session)
    }

    @Test
    fun `the permanent notification offers no session once all limits are reached`() {
        // An hour and ten minutes pass, nearly all of them in the video app, whose limit is one hour.
        val opened = app.clock.instant().plusSeconds(60)
        app.clockOffset = Duration.ofMinutes(70)
        val usage = shadowOf(app.getSystemService(UsageStatsManager::class.java))
        val events =
            listOf(
                opened to UsageEvents.Event.ACTIVITY_RESUMED,
                app.clock.instant().minusSeconds(30) to UsageEvents.Event.ACTIVITY_PAUSED,
            )
        events.forEach { (at, type) ->
            usage.addEvent(
                ShadowUsageStatsManager.EventBuilder
                    .buildEvent()
                    .setTimeStamp(at.toEpochMilli())
                    .setPackage("com.example.video")
                    .setClass("Player")
                    .setEventType(type)
                    .build(),
            )
        }

        runBlocking { app.limits.check() }

        waitFor("the permanent notification to stop offering sessions") { watching.buttons().isEmpty() }
        assertEquals(false, runBlocking { app.limits.startSession(Duration.ofMinutes(15)) })
    }

    @Test
    fun `a button on the permanent notification starts a session of that length`() {
        assertNotNull(watching).press("15 min")

        waitFor("the session to start") {
            app.limits.sessionStatuses.value
                .isNotEmpty()
        }
        val started =
            app.limits.sessionStatuses.value
                .single()
                .session
        assertEquals(Duration.ofMinutes(15), started.length)
        assertEquals(true, started.isRunning)
    }

    @Test
    fun `a running session has its own notification with a stop button`() {
        runBlocking { app.limits.startSession(Duration.ofMinutes(15)) }

        waitFor("the session notification") { session != null }
        assertEquals(listOf("Stop"), session.buttons())
        assertEquals("0m of 15m", session?.text())
        waitFor("the permanent notification to stop offering sessions") { watching.buttons().isEmpty() }
    }

    @Test
    fun `the stop button ends the session and its notification`() {
        runBlocking { app.limits.startSession(Duration.ofMinutes(15)) }
        waitFor("the session notification") { session != null }

        assertNotNull(session).press("Stop")

        waitFor("the session notification to disappear") { session == null }
        assertEquals(
            false,
            app.limits.sessionStatuses.value
                .single()
                .session.isRunning,
        )
        waitFor("the permanent notification to offer sessions again") { watching.buttons().size == 3 }
    }

    @Test
    fun `a session stopped in the app loses its notification too`() {
        runBlocking { app.limits.startSession(Duration.ofMinutes(15)) }
        waitFor("the session notification") { session != null }

        runBlocking {
            app.limits.stopSession(
                app.limits.sessionStatuses.value
                    .single()
                    .session.id,
            )
        }

        waitFor("the session notification to disappear") { session == null }
    }

    private companion object {
        const val WATCHING = 1
        const val SESSION = 3
    }
}
