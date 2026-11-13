package dev.chr1shaefn3r.accountabilitybuddy

import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.PowerManager
import android.os.Process
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.chr1shaefn3r.accountabilitybuddy.buddies.application.Phone
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PairingState
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Limit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.testing.MutableClock
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowUsageStatsManager
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives the real screens of the real app. Another phone, where one is needed, is a
 * [Phone] of the test harness that talks to the app through the test network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
class AppUiTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val sam by lazy { Phone("Sam", app.network, MutableClock(app.clock.instant())) }
    private val video = AppId("com.example.video")
    private val videoLimit = Limit(LimitId("video"), "Video", Duration.ofHours(1), setOf(video))

    /**
     * Calls into the app the way the app itself does: off the thread that draws the screen.
     * Blocking that thread while the screen waits to continue on it would stop both for good.
     */
    private fun <T> inApp(block: suspend () -> T): T {
        val result = app.background.async { block() }
        waitFor("the app to finish what the test asked for") { result.isCompleted }
        return runBlocking { result.await() }
    }

    private fun onboard() = inApp { app.buddies.changeDisplayName("Alex") }

    private fun launch() {
        ActivityScenario.launch(MainActivity::class.java)
    }

    private fun grantUsageAccess() {
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
    }

    private fun waitFor(
        what: String,
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(TIMEOUT_MILLIS, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for: $what", e)
        }
    }

    private fun waitForText(text: String) =
        waitFor(
            "text \"$text\"",
        ) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun click(text: String) = compose.onAllNodesWithText(text).onFirst().performClick()

    /** Makes Sam a confirmed buddy of the app's user. */
    private fun samBecomesBuddy() {
        inApp {
            val code = app.buddies.invite()
            sam.buddies.requestPairing(code, "Sam")
            app.network.settle()
            app.buddies.confirm(code.pairingId)
        }
        waitFor("Sam to learn about the confirmation") {
            runBlocking { sam.onlyPairing().state == PairingState.ACTIVE }
        }
    }

    /** Makes the app's user a confirmed buddy of Sam. */
    private fun userBecomesBuddyOfSam() {
        val code = inApp { sam.buddies.invite().also { app.buddies.requestPairing(it, "Alex") } }
        waitFor("the request to reach Sam") { runBlocking { sam.pairings.all().isNotEmpty() } }
        inApp {
            sam.buddies.confirm(code.pairingId)
            app.network.settle()
        }
    }

    @Test
    fun `the first start leads through the introduction to an empty home`() {
        launch()

        click("Get started")
        compose.onNodeWithText("Display name").performTextInput("Alex")
        click("Continue")
        waitForText("Allow notifications")
        click("Not now")

        waitForText("No buddy yet — add one")
        compose.onNodeWithContentDescription("Add limit").assertIsDisplayed()
        assertEquals("Alex", app.preferences.displayName)
    }

    /** Puts the video app into the phone's usage statistics, in use until half a minute ago. */
    private fun videoWasUsedFor(duration: Duration) {
        val closed = app.clock.instant().minusSeconds(30)
        val usage = shadowOf(app.getSystemService(UsageStatsManager::class.java))
        val events =
            listOf(
                closed.minus(duration) to UsageEvents.Event.ACTIVITY_RESUMED,
                closed to UsageEvents.Event.ACTIVITY_PAUSED,
            )
        events.forEach { (at, type) ->
            usage.addEvent(
                ShadowUsageStatsManager.EventBuilder
                    .buildEvent()
                    .setTimeStamp(at.toEpochMilli())
                    .setPackage(video.packageName)
                    .setClass("Player")
                    .setEventType(type)
                    .build(),
            )
        }
    }

    private fun shownNotifications(): List<String?> =
        shadowOf(app.getSystemService(NotificationManager::class.java))
            .allNotifications
            .map { it.extras.getString("android.text") }

    private fun noText(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isEmpty()

    @Test
    fun `my limits show how much of each limit is used today`() {
        onboard()
        grantUsageAccess()
        videoWasUsedFor(Duration.ofMinutes(2))
        inApp { app.limits.save(videoLimit) }

        launch()

        waitForText("Video")
        waitForText("2m of 1h")
        assertTrue(noText("Limit reached"))
        compose.onNodeWithContentDescription("Add limit").assertIsDisplayed()
    }

    @Test
    fun `a reached limit says so, until its time is raised above today's usage`() {
        onboard()
        grantUsageAccess()
        videoWasUsedFor(Duration.ofMinutes(2))
        val oneMinute = videoLimit.copy(budget = Duration.ofMinutes(1))
        inApp { app.limits.save(oneMinute) }
        launch()
        waitForText("2m of 1m")
        waitForText("Limit reached")
        assertEquals(listOf<String?>("Video: 1m limit reached"), shownNotifications())

        inApp { app.limits.save(oneMinute.copy(budget = Duration.ofMinutes(10))) }

        waitForText("2m of 10m")
        waitFor("\"Limit reached\" to disappear") { noText("Limit reached") }
        assertEquals(emptyList(), shownNotifications())
    }

    @Test
    fun `a session is started, counts, is stopped and deleted on my limits`() {
        onboard()
        grantUsageAccess()
        inApp { app.limits.save(videoLimit) }
        launch()
        waitForText("Video")

        click("Start session")
        waitForText("Start a session")
        compose.onNodeWithText("Start").assertIsNotEnabled()
        compose.onNodeWithText("Minutes").performTextInput("15")
        click("Start")

        waitForText("0m of 15m")
        assertTrue(noText("Start session"), "there is one session at a time")
        click("Stop session")
        waitForText("Session (ended)")
        waitForText("Start session")
        compose.onNodeWithContentDescription("Delete session").performClick()
        waitFor("the session to disappear") { noText("Session (ended)") }
    }

    @Test
    fun `a session can be started with one of the usual lengths`() {
        onboard()
        grantUsageAccess()
        inApp { app.limits.save(videoLimit) }
        launch()
        waitForText("Video")

        click("Start session")
        click("30 min")
        click("Start")

        waitForText("0m of 30m")
    }

    @Test
    fun `a session whose time is used up says limit reached and notifies`() {
        onboard()
        grantUsageAccess()
        inApp {
            app.limits.save(videoLimit)
            app.limits.startSession(Duration.ofMinutes(1))
        }
        launch()
        waitForText("0m of 1m")
        assertTrue(noText("Limit reached"))

        // The session started at noon; the video app is then used for two minutes.
        app.clockOffset = Duration.ofMinutes(3)
        videoWasUsedFor(Duration.ofMinutes(2))
        inApp { app.limits.check() }

        waitForText("2m of 1m")
        waitForText("Limit reached")
        assertEquals(listOf<String?>("Session: 1m limit reached"), shownNotifications())
    }

    @Test
    fun `without a limit no session is offered`() {
        onboard()
        launch()

        waitForText("No buddy yet — add one")
        assertTrue(noText("Start session"))
    }

    @Test
    fun `adding the first limit asks for usage access first`() {
        onboard()
        shadowOf(app.getSystemService(AppOpsManager::class.java))
            .setMode(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName, AppOpsManager.MODE_DEFAULT)
        launch()

        compose.onNodeWithContentDescription("Add limit").performClick()

        waitForText("Allow usage access")
        compose.onNodeWithText("Open settings").assertIsDisplayed()
    }

    @Test
    fun `a limit is created from a name, a time and apps chosen from the installed ones`() {
        onboard()
        grantUsageAccess()
        shadowOf(app.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(app.packageName, true)
        installLauncherApp(video, "Video Player")
        launch()

        compose.onNodeWithContentDescription("Add limit").performClick()
        waitForText("New limit")
        compose.onNodeWithText("Save").assertIsNotEnabled()
        compose.onNodeWithText("Name").performTextInput("Evening videos")
        click("Add apps")
        waitForText("Video Player")
        click("Video Player")
        click("Done")
        waitForText("Apps sharing this time")
        val save = compose.onNodeWithText("Save").fetchSemanticsNode().boundsInRoot
        val name = compose.onNodeWithText("Evening videos").fetchSemanticsNode().boundsInRoot
        assertTrue(
            save.bottom <= name.top,
            "Save is in the top bar, above the form, where the keyboard cannot cover it",
        )
        click("Save")

        waitFor("the limit to be saved") { runBlocking { app.limitRepository.all().isNotEmpty() } }
        val saved = inApp { app.limitRepository.all().single() }
        assertEquals("Evening videos", saved.name)
        assertEquals(Duration.ofHours(1), saved.budget)
        assertEquals(setOf(video), saved.apps)
        waitForText("0m of 1h")
    }

    @Test
    fun `an app of a limit can be swapped for another one`() {
        val music = AppId("com.example.music")
        onboard()
        grantUsageAccess()
        installLauncherApp(video, "Video Player")
        installLauncherApp(music, "Music Box")
        inApp { app.limits.save(videoLimit) }
        launch()

        click("Video")
        waitForText("Edit limit")
        waitForText("Video Player")
        compose.onNodeWithContentDescription("Remove Video Player").performClick()
        click("Add apps")
        waitForText("Music Box")
        click("Music Box")
        click("Done")
        waitForText("Apps sharing this time")
        compose.onNodeWithContentDescription("Remove Music Box").assertIsDisplayed()
        assertTrue(noText("Video Player"))
        click("Save")

        waitFor("the limit to be saved with the other app") {
            runBlocking {
                app.limitRepository
                    .all()
                    .single()
                    .apps == setOf(music)
            }
        }
        waitForText("0m of 1h")
    }

    private fun installLauncherApp(
        id: AppId,
        label: String,
    ) {
        val application =
            ApplicationInfo().apply {
                packageName = id.packageName
                nonLocalizedLabel = label
            }
        val packages = shadowOf(app.packageManager)
        packages.installPackage(
            PackageInfo().apply {
                packageName = id.packageName
                applicationInfo = application
            },
        )
        val launcher = ComponentName(id.packageName, "${id.packageName}.Main")
        packages.addOrUpdateActivity(
            ActivityInfo().apply {
                packageName = id.packageName
                name = launcher.className
                applicationInfo = application
                nonLocalizedLabel = label
            },
        )
        packages.addIntentFilterForActivity(
            launcher,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    @Test
    fun `deleting a limit warns that the buddies will be told, and tells them`() {
        onboard()
        grantUsageAccess()
        samBecomesBuddy()
        inApp { app.limits.save(videoLimit) }
        launch()
        waitForText("1 buddy")

        click("Video")
        waitForText("Edit limit")
        compose.onNodeWithContentDescription("Delete limit").performClick()
        waitForText("Your buddies will be told about this change.")
        click("Delete")

        waitFor("the limit to be deleted") { runBlocking { app.limitRepository.all().isEmpty() } }
        waitFor("Sam to be told") { sam.history.messages.contains(BuddyMessage.LimitDeleted("Video", 60)) }
        assertEquals(listOf("setupWeakened Alex LimitDeleted"), sam.notifier.shown.takeLast(1))
    }

    @Test
    fun `a buddy is added by showing a code and confirming their request`() {
        onboard()
        launch()

        click("No buddy yet — add one")
        waitForText("A buddy never sees your list of limits")
        click("Add buddy")
        waitForText("Valid for")
        inApp {
            val invitation = app.invitations.current()!!
            sam.buddies.requestPairing(invitation.code(app.address, "Alex"), "Sam")
            app.network.settle()
        }
        waitForText("Pair with Sam?")
        click("Confirm")

        waitFor(
            "Sam to learn about the confirmation",
        ) { runBlocking { sam.onlyPairing().state == PairingState.ACTIVE } }
        waitForText("Is told about your limits")
        assertEquals(
            PairingState.ACTIVE,
            runBlocking {
                app.pairings
                    .all()
                    .single()
                    .state
            },
        )
    }

    @Test
    fun `supporting shows the latest news and lets the buddy subscribe to the day summary`() {
        onboard()
        userBecomesBuddyOfSam()
        inApp {
            sam.buddies.tellBuddies(BuddyMessage.LimitHit("Instagram", 60, app.clock.instant().epochSecond))
            app.network.settle()
        }
        launch()

        click("Supporting")
        waitForText("Today: Instagram: 1h limit reached")
        click("Sam")
        waitForText("You are Sam's buddy.")
        compose.onNodeWithText("Instagram: 1h limit reached").assertIsDisplayed()
        click("Day summary")

        waitFor("the subscription to be saved") {
            runBlocking {
                app.pairings
                    .all()
                    .single()
                    .daySummary
            }
        }
        val notifications = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
        assertTrue(notifications.any { it.extras.getString("android.text") == "Instagram: 1h limit reached" })
    }

    @Test
    fun `settings show the display name and which permissions are missing`() {
        onboard()
        launch()

        compose.onNodeWithContentDescription("Settings").performClick()

        waitForText("Usage access")
        compose.onNodeWithText("Alex").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Fix").fetchSemanticsNodes().isNotEmpty())
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000L
    }
}
