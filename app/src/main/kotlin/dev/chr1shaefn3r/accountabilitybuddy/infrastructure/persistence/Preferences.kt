package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.persistence

import android.content.Context
import androidx.core.content.edit
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.Profile
import dev.chr1shaefn3r.accountabilitybuddy.buddies.domain.PushAddress
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.Day
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.LimitsMemory
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.TrackingProblem
import java.time.LocalDate

/** Small facts about this installation, kept in the app's private preferences. */
class Preferences(context: Context) : Profile, LimitsMemory {
    private val store = context.getSharedPreferences("accountability-buddy", Context.MODE_PRIVATE)

    override var displayName: String
        get() = store.getString(DISPLAY_NAME, null).orEmpty()
        set(value) = store.edit { putString(DISPLAY_NAME, value) }

    override var address: PushAddress?
        get() = store.getString(ADDRESS, null)?.let(::PushAddress)
        set(value) = store.edit { putString(ADDRESS, value?.id) }

    override var lastClosedDay: Day?
        get() = store.getString(LAST_CLOSED_DAY, null)?.let { Day(LocalDate.parse(it)) }
        set(value) = store.edit { putString(LAST_CLOSED_DAY, value?.date?.toString()) }

    override var reportedProblems: Set<TrackingProblem>
        get() = store.getStringSet(REPORTED_PROBLEMS, emptySet()).orEmpty().map(TrackingProblem::valueOf).toSet()
        set(value) = store.edit { putStringSet(REPORTED_PROBLEMS, value.map { it.name }.toSet()) }

    /** True once the user has been through the introduction and chosen a display name. */
    val isOnboarded: Boolean get() = displayName.isNotBlank()

    private companion object {
        const val DISPLAY_NAME = "displayName"
        const val ADDRESS = "address"
        const val LAST_CLOSED_DAY = "lastClosedDay"
        const val REPORTED_PROBLEMS = "reportedProblems"
    }
}
