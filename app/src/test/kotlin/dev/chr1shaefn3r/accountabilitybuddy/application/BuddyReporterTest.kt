package dev.chr1shaefn3r.accountabilitybuddy.application

import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.BuddyMessage
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.SecureChannel
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppCatalog
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.DayClosed
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.InstalledApp
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitHit
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingProblem
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Weakening
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuddyReporterTest {
    private val told = mutableListOf<BuddyMessage>()
    private val shownToSender = mutableListOf<LimitHit>()
    private val apps =
        object : AppCatalog {
            override fun label(app: AppId): String =
                app.packageName.substringAfterLast('.').replaceFirstChar(Char::uppercase)

            override fun launchableApps(): List<InstalledApp> = emptyList()
        }
    private val reporter = BuddyReporter(shownToSender::add, apps, told::add)
    private val day = Day(LocalDate.of(2026, 11, 12))
    private val hour = Duration.ofHours(1)

    @Test
    fun `a limit hit is shown to the sender and told to the buddies`() =
        runTest {
            val at = Instant.parse("2026-11-12T09:00:00Z")
            val hit = LimitHit(day, LimitId("1"), "Instagram", hour, setOf(AppId("com.instagram")), at)

            reporter.limitHit(hit)

            assertEquals(listOf(hit), shownToSender)
            assertEquals(listOf<BuddyMessage>(BuddyMessage.LimitHit("Instagram", 60, at.epochSecond)), told)
        }

    @Test
    fun `buddies learn about removed apps by the names users know`() =
        runTest {
            reporter.setupWeakened(Weakening.AppsRemoved("Social", setOf(AppId("com.tiktok"), AppId("com.instagram"))))

            assertEquals(listOf<BuddyMessage>(BuddyMessage.AppsRemoved("Social", listOf("Instagram", "Tiktok"))), told)
        }

    @Test
    fun `a long list of removed apps is shortened so that it fits into one message`() =
        runTest {
            val many = (1..40).map { AppId("com.example.app%02d".format(it) + "x".repeat(60)) }.toSet()

            reporter.setupWeakened(Weakening.AppsRemoved("Everything", many))

            val message = told.single() as BuddyMessage.AppsRemoved
            assertEquals(16, message.apps.size)
            assertEquals("25 more", message.apps.last())
            assertTrue(BuddyMessage.encode(message).size <= SecureChannel.MAX_MESSAGE_SIZE)
        }

    @Test
    fun `every kind of weakening is told to the buddies`() =
        runTest {
            reporter.setupWeakened(Weakening.BudgetRaised("Instagram", hour, hour.multipliedBy(2)))
            reporter.setupWeakened(Weakening.LimitDeleted("Instagram", hour))
            reporter.setupWeakened(Weakening.TrackingLost(TrackingProblem.USAGE_ACCESS_MISSING))
            reporter.setupWeakened(Weakening.TrackingLost(TrackingProblem.NOTIFICATIONS_BLOCKED))

            assertEquals(
                listOf<BuddyMessage>(
                    BuddyMessage.BudgetRaised("Instagram", 60, 120),
                    BuddyMessage.LimitDeleted("Instagram", 60),
                    BuddyMessage.TrackingLost(BuddyMessage.TrackingLost.Problem.USAGE_ACCESS_MISSING),
                    BuddyMessage.TrackingLost(BuddyMessage.TrackingLost.Problem.NOTIFICATIONS_BLOCKED),
                ),
                told,
            )
        }

    @Test
    fun `a closed day is told with whole minutes`() =
        runTest {
            reporter.dayClosed(DayClosed(day, listOf(DayClosed.Entry("Instagram", hour, Duration.ofSeconds(6359)))))

            assertEquals(
                listOf<BuddyMessage>(
                    BuddyMessage.DayClosed("2026-11-12", listOf(BuddyMessage.DayClosed.Entry("Instagram", 60, 105))),
                ),
                told,
            )
        }

    @Test
    fun `a day without hits is told as well`() =
        runTest {
            reporter.dayClosed(DayClosed(day, emptyList()))

            assertEquals(listOf<BuddyMessage>(BuddyMessage.DayClosed("2026-11-12", emptyList())), told)
        }

    @Test
    fun `an overlong day summary keeps the worst overruns`() =
        runTest {
            val entries = List(25) { DayClosed.Entry("Limit $it", hour, hour.plusMinutes(it.toLong())) }

            reporter.dayClosed(DayClosed(day, entries))

            val sent = (told.single() as BuddyMessage.DayClosed).entries
            assertEquals(BuddyMessage.DayClosed.MAX_ENTRIES, sent.size)
            assertEquals("Limit 24", sent.first().limitName)
        }
}
