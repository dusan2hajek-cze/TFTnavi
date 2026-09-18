// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import java.util.Locale

/**
 * How often a running video pipeline may say it is still running.
 *
 * Was one line every 120 frames, which at 30 fps is one every four seconds - and
 * [ProjectionEventLog] keeps only MAX_EVENTS entries. Rider a9fb623a's report, 2026-08-24:
 * **555 of the 1500 entries in the ADVANCED half were that one line**, the buffer was full and
 * evicting, and its history began four minutes before the failure being reported - the
 * connection and the handshake that led to it had already been pushed out. An hour of riding
 * fills 900 slots; two hours leave nothing else at all.
 *
 * A minute keeps the liveness signal the line exists for, at a fifteenth of the cost. Nothing
 * is lost by it: the exact total is logged again when the session stops, and per
 * [[reference-motohub-log-frame-counter]] this counter measures local handoffs rather than
 * delivery, so its precise value was never the point.
 */
internal const val FRAME_LOG_INTERVAL_MILLIS = 60_000L

/**
 * Decides when a frame-counter line is due, and turns the gap since the previous one into a
 * rate - which is what a human reading a support log actually wants from this line, and what a
 * bare running total never gave them.
 *
 * One instance per streaming session; created where the counter lives. Safe to call from the
 * encoder callback on whatever thread the codec hands frames back on.
 */
internal class FrameLogThrottle(private val intervalMillis: Long = FRAME_LOG_INTERVAL_MILLIS) {
    /**
     * Null until the first line. Deliberately nullable rather than a Long sentinel: subtracting
     * `Long.MIN_VALUE` from a timestamp overflows into a large negative number that reads as
     * "the interval has elapsed", which is exactly how the automatic-reroute throttle was once
     * silently disabled (see NavigationRerouteCoordinator.shouldAttemptReroute).
     */
    private var lastLoggedUptimeMillis: Long? = null
    private var lastLoggedFrameCount = 0L

    /**
     * The suffix to append to a frame-counter line, or null when this frame is not due to be
     * logged. Empty for the first line of a session, which has no previous one to measure from.
     */
    @Synchronized
    fun rateSuffixIfDue(frameCount: Long, nowUptimeMillis: Long): String? {
        val last = lastLoggedUptimeMillis
        if (last == null) {
            lastLoggedUptimeMillis = nowUptimeMillis
            lastLoggedFrameCount = frameCount
            return ""
        }
        val elapsedMillis = nowUptimeMillis - last
        if (elapsedMillis < intervalMillis) return null
        val frames = frameCount - lastLoggedFrameCount
        lastLoggedUptimeMillis = nowUptimeMillis
        lastLoggedFrameCount = frameCount
        return frameRateSuffix(frames, elapsedMillis)
    }
}

/**
 * " (29.8 fps over 60s)", or an empty string when the elapsed time cannot carry a rate. Split
 * out from [FrameLogThrottle] so the wording can be asserted without driving a clock.
 */
internal fun frameRateSuffix(frames: Long, elapsedMillis: Long): String {
    if (elapsedMillis <= 0L || frames < 0L) return ""
    return String.format(
        Locale.US,
        " (%.1f fps over %ds)",
        frames * 1_000.0 / elapsedMillis,
        elapsedMillis / 1_000L
    )
}
