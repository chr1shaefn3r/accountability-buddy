package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration

/**
 * How far a time budget, of a limit or of a session, is used up.
 * The two warnings tell the sender that the end is near. Only [REACHED] concerns the buddies.
 */
enum class UsageLevel(
    /** The share of the budget, in percent, from which this level applies. */
    val fromPercent: Int,
) {
    NORMAL(0),
    WARNING(80),
    LAST_WARNING(95),
    REACHED(100),
    ;

    val isWarning: Boolean get() = this == WARNING || this == LAST_WARNING

    companion object {
        private const val PERCENT = 100

        fun of(
            used: Duration,
            budget: Duration,
        ): UsageLevel = entries.last { used.toMillis() * PERCENT >= budget.toMillis() * it.fromPercent }
    }
}

/** The sender is told that a limit or a session is close to its end. Buddies are not. */
data class UsageWarning(
    /** The id of the limit or session this is about. */
    val subjectId: String,
    val name: String,
    val budget: Duration,
    val used: Duration,
    val level: UsageLevel,
) {
    val left: Duration get() = budget - used
}

/** The level each limit and running session was last announced with, on one day. */
data class AnnouncedWarnings(
    val day: Day,
    val levels: Map<String, UsageLevel>,
) {
    /** What [subjectId] was announced with on [day]: nothing counts from earlier days. */
    fun levelOn(
        day: Day,
        subjectId: String,
    ): UsageLevel = levels[subjectId].takeIf { day == this.day } ?: UsageLevel.NORMAL

    companion object {
        val NONE = AnnouncedWarnings(Day(java.time.LocalDate.MIN), emptyMap())
    }
}
