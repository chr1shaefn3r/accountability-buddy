package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.Instant

@JvmInline
value class SessionId(
    val value: String,
)

/**
 * A break the sender allows themselves: so much time in the apps of their limits,
 * counted from the moment the session starts.
 *
 * A session runs until it is stopped or the day ends. When its [length] is used up this
 * counts like a reached limit, and the session keeps counting.
 */
data class Session(
    val id: SessionId,
    val length: Duration,
    val startedAt: Instant,
    val usedUpAt: Instant? = null,
    val stoppedAt: Instant? = null,
    val usedWhenStopped: Duration? = null,
    val dismissed: Boolean = false,
) {
    init {
        require(length in MIN_LENGTH..MAX_LENGTH) { "A session lasts between $MIN_LENGTH and $MAX_LENGTH" }
    }

    val isRunning: Boolean get() = stoppedAt == null

    val isUsedUp: Boolean get() = usedUpAt != null

    /** The time spent in [limitedApps] since the session started: until [now], or until it was stopped. */
    fun used(
        intervals: List<ForegroundInterval>,
        limitedApps: Set<AppId>,
        now: Instant,
    ): Duration = usedWhenStopped ?: Usage.between(startedAt, now, intervals).of(limitedApps)

    fun usedUp(at: Instant): Session = copy(usedUpAt = at)

    fun stopped(
        at: Instant,
        used: Duration,
    ): Session = copy(stoppedAt = at, usedWhenStopped = used)

    /** Takes a session that is over out of sight. It still counts for the day. */
    fun dismissed(): Session {
        check(!isRunning) { "A running session is stopped before it is dismissed" }
        return copy(dismissed = true)
    }

    companion object {
        /** What a session is called where a limit would be called by its name. */
        const val NAME = "Session"
        val MIN_LENGTH: Duration = Duration.ofMinutes(1)
        val MAX_LENGTH: Duration = Duration.ofHours(24)
    }
}
