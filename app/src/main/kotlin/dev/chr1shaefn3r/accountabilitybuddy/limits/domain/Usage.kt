package dev.chr1shaefn3r.accountabilitybuddy.limits.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** A stretch of time during which [app] was in the foreground. */
data class ForegroundInterval(
    val app: AppId,
    val start: Instant,
    val end: Instant,
)

/** Foreground time per app within one day. */
class Usage private constructor(
    private val perApp: Map<AppId, Duration>,
) {
    fun of(apps: Set<AppId>): Duration = apps.fold(Duration.ZERO) { sum, app -> sum + (perApp[app] ?: Duration.ZERO) }

    fun of(limit: Limit): Duration = of(limit.apps)

    companion object {
        val NONE = Usage(emptyMap())

        /** Sums up [intervals], counting only the parts that lie within [day]. */
        fun on(
            day: Day,
            zone: ZoneId,
            intervals: List<ForegroundInterval>,
        ): Usage {
            val dayStart = day.start(zone)
            val dayEnd = day.end(zone)
            val perApp = mutableMapOf<AppId, Duration>()
            for (interval in intervals) {
                val start = maxOf(interval.start, dayStart)
                val end = minOf(interval.end, dayEnd)
                if (end > start) perApp.merge(interval.app, Duration.between(start, end), Duration::plus)
            }
            return Usage(perApp)
        }
    }
}
