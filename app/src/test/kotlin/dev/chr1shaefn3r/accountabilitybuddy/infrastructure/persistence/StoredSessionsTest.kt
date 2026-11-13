package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Session
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.SessionId
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class StoredSessionsTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val sessions = StoredSessions(context)
    private val evening = Instant.parse("2026-11-12T20:00:00Z")
    private val running = Session(SessionId("running"), Duration.ofMinutes(15), evening)
    private val over =
        Session(
            id = SessionId("over"),
            length = Duration.ofMinutes(30),
            startedAt = evening.minusSeconds(7200),
            usedUpAt = evening.minusSeconds(5000),
            stoppedAt = evening.minusSeconds(4000),
            usedWhenStopped = Duration.ofMinutes(41),
            dismissed = true,
        )

    @Test
    fun `sessions come back as they were saved, also after the app was closed`() =
        runTest {
            sessions.save(running)
            sessions.save(over)

            assertEquals(setOf(running, over), StoredSessions(context).all().toSet())
        }

    @Test
    fun `saving a session again replaces it`() =
        runTest {
            sessions.save(running)

            sessions.save(running.stopped(evening.plusSeconds(600), Duration.ofMinutes(4)))

            assertEquals(Duration.ofMinutes(4), sessions.all().single().usedWhenStopped)
        }

    @Test
    fun `sessions of earlier days can be forgotten`() =
        runTest {
            sessions.save(running)
            sessions.save(over)

            sessions.forgetStartedBefore(evening)

            assertEquals(listOf(running), sessions.all())
        }

    @Test
    fun `there are no sessions to begin with`() =
        runTest {
            assertEquals(emptyList(), sessions.all())
        }
}
