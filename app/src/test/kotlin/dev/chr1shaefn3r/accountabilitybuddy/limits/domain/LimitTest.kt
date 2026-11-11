package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LimitTest {
    private val instagram = AppId("com.instagram.android")
    private val tiktok = AppId("com.zhiliaoapp.musically")
    private val socialMedia = Limit(LimitId("1"), "Social Media", Duration.ofHours(1), setOf(instagram, tiktok))

    @Test
    fun `a limit needs a name`() {
        assertFailsWith<IllegalArgumentException> { socialMedia.copy(name = " ") }
    }

    @Test
    fun `a limit name is not longer than 30 characters`() {
        assertFailsWith<IllegalArgumentException> { socialMedia.copy(name = "x".repeat(31)) }
    }

    @Test
    fun `a limit has at least one app`() {
        assertFailsWith<IllegalArgumentException> { socialMedia.copy(apps = emptySet()) }
    }

    @Test
    fun `a budget is at least one minute and at most 24 hours`() {
        assertFailsWith<IllegalArgumentException> { socialMedia.copy(budget = Duration.ofSeconds(59)) }
        assertFailsWith<IllegalArgumentException> { socialMedia.copy(budget = Duration.ofHours(24).plusMinutes(1)) }
        socialMedia.copy(budget = Duration.ofMinutes(1))
        socialMedia.copy(budget = Duration.ofHours(24))
    }

    @Test
    fun `raising the budget weakens a limit`() {
        val weakenings = socialMedia.weakenedBy(socialMedia.copy(budget = Duration.ofHours(2)))

        assertEquals(
            listOf(Weakening.BudgetRaised("Social Media", Duration.ofHours(1), Duration.ofHours(2))),
            weakenings,
        )
    }

    @Test
    fun `removing an app weakens a limit`() {
        val weakenings = socialMedia.weakenedBy(socialMedia.copy(apps = setOf(instagram)))

        assertEquals(listOf(Weakening.AppsRemoved("Social Media", setOf(tiktok))), weakenings)
    }

    @Test
    fun `tightening a limit is not a weakening`() {
        val tightened =
            socialMedia.copy(
                name = "Social",
                budget = Duration.ofMinutes(30),
                apps = socialMedia.apps + AppId("com.snapchat.android"),
            )

        assertEquals(emptyList(), socialMedia.weakenedBy(tightened))
    }

    @Test
    fun `raising the budget and removing an app are two weakenings`() {
        val weakened = socialMedia.copy(budget = Duration.ofHours(3), apps = setOf(tiktok))

        assertEquals(2, socialMedia.weakenedBy(weakened).size)
    }

    @Test
    fun `deleting a limit is a weakening`() {
        assertEquals(Weakening.LimitDeleted("Social Media", Duration.ofHours(1)), socialMedia.deleted())
    }
}
