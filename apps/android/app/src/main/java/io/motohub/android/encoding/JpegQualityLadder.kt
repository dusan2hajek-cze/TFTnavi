// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

/**
 * Chooses the JPEG quality a still-fed dashboard is sent, one measurement window at a time.
 *
 * Lifted out of [JpegDisplaySource] because the only way to be sure of a controller is to run it
 * against the numbers a real ride produced, and everything else in that class needs an
 * ImageReader, a display and a socket. Nothing here touches a clock or a thread: the caller times
 * the windows and reports what happened in each one.
 *
 * The dash this exists for budgets **bytes**, not frames - the X-Cape 1200 takes roughly the same
 * kilobytes a second however they are divided up - so quality is the only lever that buys frames.
 * Three rules follow; the second fixes a defect the first one caused, and the third guards the
 * whole premise:
 *
 *  - **A window that offered nothing decides nothing.** Zero stills a second because the phone
 *    drew nothing is not the dashboard refusing them, but it used to read as maximum congestion.
 *    Rider dc735158's dashboard produced no frame for twelve seconds on 2026-08-24 and the ladder
 *    walked 60 to 12 over a completely idle wire; the frames that finally arrived went out at the
 *    coarsest rung there is.
 *  - **A dash whose ceiling sits inside the deadband must still be able to climb.** The fast climb
 *    needs the dash to beat [TARGET_FPS_HIGH], and the X-Cape never does - it
 *    lands between five and six and a half stills a second whatever it is sent. Every descent was
 *    therefore permanent. So while the dash is keeping up, the ladder probes one rung finer every
 *    [PROBE_WINDOWS] windows and lets the next window answer; a refused probe
 *    doubles the wait, capped, so a dash with no headroom pays two seconds for asking and then
 *    asks very rarely.
 *  - **A descent must pay its way.** Quality is only ever traded for frames, so a coarser rung
 *    that does not raise the accepted rate bought nothing and is taken back the next measured
 *    window. On a byte-budgeted dash like the X-Cape every descent pays and this rule never
 *    fires; a dash that paces stills by cadence would otherwise ride one congestion dip to the
 *    coarsest rung and spend the ride there, blocky for free. After a revert the ladder waits
 *    [DESCENT_RETRY_WINDOWS] windows before trading quality again, doubling up to a cap while
 *    descents keep failing to pay.
 */
class JpegQualityLadder(
    private val ladder: IntArray = QUALITY_LADDER,
    private val targetFpsLow: Double = TARGET_FPS_LOW,
    private val targetFpsHigh: Double = TARGET_FPS_HIGH,
    private val windowsBeforeClimbing: Int = WINDOWS_BEFORE_CLIMBING,
    private val probeWindows: Int = PROBE_WINDOWS,
    private val probeBackoffMaxWindows: Int = PROBE_BACKOFF_MAX_WINDOWS,
    private val descentMinGain: Double = DESCENT_MIN_GAIN,
    private val descentRetryWindows: Int = DESCENT_RETRY_WINDOWS,
    private val descentBackoffMaxWindows: Int = DESCENT_BACKOFF_MAX_WINDOWS
) {
    private var step = 0
    private var goodWindows = 0
    private var steadyWindows = 0
    private var idleWindows = 0
    private var probing = false
    private var waitBeforeProbe = probeWindows
    private var fpsBeforeDescent = -1.0
    private var descentHoldWindows = 0
    private var waitBeforeDescent = descentRetryWindows

    /** The quality the next still should be compressed at. */
    var quality: Int = ladder.first()
        private set

    /** What one window changed, so the caller can log it - and log it only when it matters. */
    sealed interface Outcome {
        /** Measured, and the quality stands. */
        data object Unchanged : Outcome

        /** Nothing was offered, so nothing was decided. [first] on the first such window only. */
        data class IdleHold(val first: Boolean) : Outcome

        /** New quality. [probe] marks the speculative step up, which reads differently in a log. */
        data class Changed(val quality: Int, val probe: Boolean) : Outcome

        /** Last window's descent bought no frames, so the rung is given back. */
        data class Reverted(val quality: Int) : Outcome
    }

    /**
     * @param offered stills handed to the transport in this window, refused ones included
     * @param accepted stills the transport took
     * @param elapsedMillis how long the window lasted
     */
    fun onWindow(offered: Int, accepted: Int, elapsedMillis: Long): Outcome {
        if (offered <= 0) return Outcome.IdleHold(first = idleWindows++ == 0)
        idleWindows = 0

        val fps = if (elapsedMillis > 0L) accepted * 1000.0 / elapsedMillis else 0.0

        if (fpsBeforeDescent >= 0.0) {
            val paid = fps > 0.0 && fps >= fpsBeforeDescent * descentMinGain
            fpsBeforeDescent = -1.0
            if (!paid) {
                step--
                quality = ladder[step]
                descentHoldWindows = waitBeforeDescent
                waitBeforeDescent = (waitBeforeDescent * 2).coerceAtMost(descentBackoffMaxWindows)
                return Outcome.Reverted(quality)
            }
            waitBeforeDescent = descentRetryWindows
        }
        if (descentHoldWindows > 0) descentHoldWindows--

        val previous = step
        var probeStep = false
        when {
            fps < targetFpsLow && step < ladder.lastIndex && descentHoldWindows == 0 -> {
                step++
                fpsBeforeDescent = fps
                goodWindows = 0
                steadyWindows = 0
                if (probing) {
                    probing = false
                    waitBeforeProbe = (waitBeforeProbe * 2).coerceAtMost(probeBackoffMaxWindows)
                }
            }

            fps > targetFpsHigh && step > 0 -> {
                // Climb back slowly. Stepping up on a single good window makes the picture pulse
                // between two qualities every couple of seconds, which reads worse than either.
                steadyWindows = 0
                if (++goodWindows >= windowsBeforeClimbing) {
                    goodWindows = 0
                    step--
                }
                probing = false
                waitBeforeProbe = probeWindows
            }

            fps >= targetFpsLow && step > 0 -> {
                goodWindows = 0
                if (probing) {
                    // The last probe held. Take the win and start counting towards the next, so
                    // the picture walks back up a rung at a time instead of waiting for a burst
                    // this dash cannot produce.
                    probing = false
                    waitBeforeProbe = probeWindows
                }
                if (++steadyWindows >= waitBeforeProbe) {
                    steadyWindows = 0
                    step--
                    probing = true
                    probeStep = true
                }
            }

            else -> {
                goodWindows = 0
                steadyWindows = 0
            }
        }
        if (step == previous) return Outcome.Unchanged
        quality = ladder[step]
        return Outcome.Changed(quality, probeStep)
    }

    companion object {
        /**
         * Ride MO's compression quality is the first rung and the ceiling; the rest is headroom
         * for a dash that cannot drink that fast. The floor is a picture that is visibly coarse in
         * gradients but keeps road lines and labels legible, which beats a sharp one that arrives
         * twice a second.
         *
         * The bottom two rungs were added after the X-Cape spent a whole ride pinned at 20 and
         * still only managing 2.2 stills a second: this dash budgets bytes, so the only way to buy
         * frames is to spend fewer of them. They are meant to be reached rarely and they do look
         * blocky; if a rider reports the picture as mushy rather than laggy, take 12 away first.
         */
        val QUALITY_LADDER = intArrayOf(60, 50, 40, 32, 25, 20, 16, 12)

        /**
         * Stills a second to aim for, with a wide deadband between them: below [TARGET_FPS_LOW]
         * the picture judders badly enough that a coarser one is the better trade, above
         * [TARGET_FPS_HIGH] there is room to spend on looking better. Nothing between the two is
         * worth a change - the ladder pulsing up and down reads worse than either rung.
         */
        const val TARGET_FPS_LOW = 5.0
        const val TARGET_FPS_HIGH = 7.0
        const val WINDOWS_BEFORE_CLIMBING = 3

        /**
         * Windows of the dash keeping up before the quality tries one finer rung.
         *
         * The fast climb above needs the dash to beat [TARGET_FPS_HIGH], which assumes every dash
         * has a burst above the deadband somewhere. The X-Cape 1200 does not: it budgets bytes and
         * lands between five and six and a half stills a second whatever it is sent, so every
         * descent used to be permanent - the ladder could reach 12 and never leave it, and the
         * rider rode the rest of the day on the coarsest picture the app can make. Fifteen windows
         * is thirty seconds: rare enough that a dash with no headroom pays two seconds for asking,
         * frequent enough that one bad minute does not cost a ride.
         */
        const val PROBE_WINDOWS = 15

        /** Ceiling for the doubling backoff - four minutes between probes on a dash that keeps
         *  refusing them. */
        const val PROBE_BACKOFF_MAX_WINDOWS = 120

        /**
         * How much a descent must raise the accepted rate to keep its rung. On the X-Cape a rung
         * is worth 15-25% in bytes and the dash converts bytes to frames, so a real descent
         * clears this; a cadence-paced dash lands at exactly 1.0x and gives the rung back. Judged
         * one window later, so a single noisy window can fake a payment - the next descent is
         * judged too, so a wrong call costs one window at the coarser rung, not the ride.
         */
        const val DESCENT_MIN_GAIN = 1.15

        /** Windows before quality is traded away again after a descent that bought nothing -
         *  thirty seconds, mirroring [PROBE_WINDOWS] in the other direction. */
        const val DESCENT_RETRY_WINDOWS = 15

        /** Ceiling for the doubling descent backoff - four minutes, like the probes'. */
        const val DESCENT_BACKOFF_MAX_WINDOWS = 120
    }
}
