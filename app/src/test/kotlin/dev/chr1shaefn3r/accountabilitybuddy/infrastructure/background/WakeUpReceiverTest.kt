package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.background

import android.app.AlarmManager
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.chr1shaefn3r.accountabilitybuddy.TestApp
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** What happens when the system wakes the app: at midnight, after a reboot, on a clock change. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = TestApp::class)
class WakeUpReceiverTest {
    private val app: TestApp get() = ApplicationProvider.getApplicationContext()
    private val today: LocalDate get() = LocalDate.now(app.clock)

    @Before
    fun startWorkManager() {
        val configuration = Configuration.Builder().setExecutor(SynchronousExecutor()).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, configuration)
    }

    private fun wakeUp() = WakeUpReceiver().onReceive(app, Intent(app, WakeUpReceiver::class.java))

    private fun checks(): List<WorkInfo> = WorkManager.getInstance(app).getWorkInfosForUniqueWork("check").get()

    @Test
    fun `waking up hands the work over and does not keep the system waiting`() {
        app.preferences.lastClosedDay = Day(today.minusDays(3))

        wakeUp()

        // The receiver has returned without asking the system for more time,
        // and the day is closed by a job that may take as long as it needs.
        assertEquals(1, checks().size)
    }

    @Test
    fun `the job closes the days that are over`() {
        app.preferences.lastClosedDay = Day(today.minusDays(3))

        wakeUp()

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (checks().single().state != WorkInfo.State.SUCCEEDED && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(WorkInfo.State.SUCCEEDED, checks().single().state)
        assertEquals(Day(today.minusDays(1)), app.preferences.lastClosedDay)
    }

    @Test
    fun `waking up sets the alarm for the next midnight`() {
        wakeUp()

        val alarm = shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.last()
        val expected =
            today
                .plusDays(1)
                .atStartOfDay(app.clock.zone)
                .toInstant()
                .toEpochMilli()
        assertEquals(expected + 5_000, alarm.triggerAtMs)
    }
}
