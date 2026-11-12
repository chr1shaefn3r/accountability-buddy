package dev.chr1shaefn3r.accountabilitybuddy.infrastructure.usage

import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.AppId
import dev.chr1shaefn3r.accountabilitybuddy.limits.domain.ForegroundInterval
import java.time.Duration
import java.time.Instant

/** The events of Android's usage statistics that matter for foreground time. */
sealed interface UsageEvent {
    val at: Instant

    /** A screen ([activity]) of [app] came to the front. */
    data class Resumed(
        val app: AppId,
        val activity: String,
        override val at: Instant,
    ) : UsageEvent

    /** A screen ([activity]) of [app] left the front. */
    data class Paused(
        val app: AppId,
        val activity: String,
        override val at: Instant,
    ) : UsageEvent

    /** The screen went off or the phone shut down: nothing is in the foreground any more. */
    data class ScreenOff(
        override val at: Instant,
    ) : UsageEvent
}

/**
 * Turns a stream of usage events, oldest first, into foreground intervals.
 *
 * An app is in the foreground from the moment one of its screens is resumed until all of
 * its screens are paused, another app is resumed or the screen goes off. An app that is
 * still in the foreground at the end counts until [until]. An app that was already in the
 * foreground before the first event counts from [from].
 */
fun foregroundIntervals(
    events: List<UsageEvent>,
    from: Instant,
    until: Instant,
): List<ForegroundInterval> {
    val tracker = ForegroundTracker(from)
    for (event in events) {
        when (event) {
            is UsageEvent.Resumed -> tracker.resumed(event)
            is UsageEvent.Paused -> tracker.paused(event)
            is UsageEvent.ScreenOff -> tracker.close(event.at)
        }
    }
    tracker.close(until)
    return tracker.intervals
}

private class ForegroundTracker(
    private val from: Instant,
) {
    val intervals = mutableListOf<ForegroundInterval>()
    private var current: AppId? = null
    private var since = from
    private val resumedScreens = mutableSetOf<String>()
    private var sawActivity = false

    fun resumed(event: UsageEvent.Resumed) {
        if (current != event.app) {
            close(event.at)
            current = event.app
            since = event.at
            resumedScreens.clear()
        }
        resumedScreens += event.activity
        sawActivity = true
    }

    fun paused(event: UsageEvent.Paused) {
        if (current == event.app) {
            resumedScreens -= event.activity
            if (resumedScreens.isEmpty()) close(event.at)
        } else if (!sawActivity) {
            intervals += ForegroundInterval(event.app, from, event.at)
        }
        sawActivity = true
    }

    fun close(at: Instant) {
        current?.let { if (at > since) intervals += ForegroundInterval(it, since, at) }
        current = null
    }
}

/**
 * Remembers the usage events of a time span, so that looking at the span again only costs
 * reading the newest events instead of the whole day.
 *
 * Events of the last [SETTLE_TIME] are read again every time, because the system may
 * record an event a moment after it happened.
 */
class RememberedEvents(
    private val read: (from: Instant, to: Instant) -> List<UsageEvent>,
) {
    private var spanStart: Instant? = null
    private var settledUntil: Instant = Instant.MIN
    private val settled = mutableListOf<UsageEvent>()

    /** The events from [from] (inclusive) to [to] (exclusive), oldest first. */
    @Synchronized
    fun between(
        from: Instant,
        to: Instant,
    ): List<UsageEvent> {
        if (from != spanStart || to < settledUntil) {
            spanStart = from
            settledUntil = from
            settled.clear()
        }
        val fresh = read(settledUntil, to)
        val all = settled + fresh
        val settleUntil = to - SETTLE_TIME
        if (settleUntil > settledUntil) {
            settled += fresh.filter { it.at < settleUntil }
            settledUntil = settleUntil
        }
        return all
    }

    companion object {
        val SETTLE_TIME: Duration = Duration.ofSeconds(10)
    }
}
