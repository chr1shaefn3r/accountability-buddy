package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AnnouncedWarnings
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.UsageLevel
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PreferencesTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun `nothing was announced to begin with`() {
        assertEquals(AnnouncedWarnings.NONE, Preferences(context).announcedWarnings)
    }

    @Test
    fun `announced warnings are remembered, also after the app was closed`() {
        val announced =
            AnnouncedWarnings(
                Day(LocalDate.of(2026, 11, 12)),
                mapOf(
                    "0b6d5c7e-limit" to UsageLevel.WARNING,
                    "a=b" to UsageLevel.LAST_WARNING,
                    "reached" to UsageLevel.REACHED,
                    "calm" to UsageLevel.NORMAL,
                ),
            )

        Preferences(context).announcedWarnings = announced

        assertEquals(announced, Preferences(context).announcedWarnings)
    }

    @Test
    fun `the last closed day and the display name are remembered`() {
        val preferences = Preferences(context)

        preferences.lastClosedDay = Day(LocalDate.of(2026, 11, 11))
        preferences.displayName = "Alex"

        assertEquals(Day(LocalDate.of(2026, 11, 11)), Preferences(context).lastClosedDay)
        assertEquals("Alex", Preferences(context).displayName)
    }
}
