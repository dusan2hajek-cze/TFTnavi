// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import android.os.SystemClock

/**
 * Decides when a session that is *working* by every other measure is not actually reaching the
 * dashboard - the failure a rider describes as "it connects but the screen stays frozen".
 *
 * This is not [VideoBackpressureGuard] with different numbers, and the difference is the whole
 * reason it exists. That guard asks "is the transport dead", so any accepted frame clears its
 * streak; a dash that takes one frame in five never trips it, and must not, because tearing that
 * session down would be wrong. This asks a question nobody was asking: *is what we are sending
 * anywhere near what this dash can swallow*. A link that keeps refusing half of everything is
 * healthy at the socket and useless to the rider, and the app used to have no idea.
 *
 * Rider 315e0af3 is the shape it is cut to. A Moto Morini X-Cape 1200 driven with the generic
 * profile's 30 fps all-intra against a Yunmo send window of three frames: 132 rejected access
 * units in five seconds, session held open the whole time, rider watching a still picture and
 * concluding the app was broken. With the right profile the same ride rejected 9. Nothing in
 * between those two numbers needs a subtle threshold to separate them.
 *
 * The answer is deliberately one-shot per session and never fatal: all it does is earn the right
 * to *ask the rider something*, and asking twice for the same session is worse than not asking.
 */
class VideoDeliveryProbe(
    private val settleMillis: Long = DEFAULT_SETTLE_MILLIS,
    private val minSamples: Int = DEFAULT_MIN_SAMPLES,
    private val rejectedShareToRaise: Double = DEFAULT_REJECTED_SHARE,
    private val clock: () -> Long = SystemClock::elapsedRealtime
) {
    /** What the probe has concluded about this session, once it has seen enough to conclude. */
    enum class Verdict {
        /** Most of what we send is being refused: the rider is looking at a frozen picture. */
        FAILING,

        /**
         * The stream is landing. Worth stating rather than merely not complaining, because it is
         * the only moment the app can honestly say "that profile you just picked works" - and
         * asking a rider to confirm a choice needs evidence, not the absence of evidence.
         */
        HEALTHY
    }

    private val lock = Any()
    private var firstOfferAt = 0L
    private var accepted = 0
    private var rejected = 0
    private var verdict: Verdict? = null

    fun onAccepted() = record(acceptedFrame = true)

    fun onRejected() = record(acceptedFrame = false)

    /**
     * Non-null exactly once, on the frame that completes the picture: enough samples and enough
     * time for the encoder's own ramp-up to be over. Null on every frame before and after.
     *
     * The settle window is not politeness, it is correctness. The first second of any session is
     * codec configuration, a keyframe several times the size of everything after it, and an
     * adaptive controller that has not yet seen a single round trip - judged on that alone, a
     * perfectly good dash looks like a failing one, and a bad one can look fine.
     *
     * Both verdicts are reached at the same instant on the same evidence, deliberately. A
     * HEALTHY conclusion drawn on a laxer test than the FAILING one would be the app telling a
     * rider their profile works on weaker grounds than it used to tell them it did not.
     */
    private fun record(acceptedFrame: Boolean): Verdict? {
        synchronized(lock) {
            if (verdict != null) return null
            val now = clock()
            if (firstOfferAt == 0L) firstOfferAt = now
            if (acceptedFrame) accepted++ else rejected++
            val samples = accepted + rejected
            if (samples < minSamples) return null
            if (now - firstOfferAt < settleMillis) return null
            val reached = if (rejected.toDouble() / samples >= rejectedShareToRaise) {
                Verdict.FAILING
            } else {
                Verdict.HEALTHY
            }
            verdict = reached
            return reached
        }
    }

    /** Share of everything offered that the dashboard refused, for the message and the log. */
    fun rejectedShare(): Double = synchronized(lock) {
        val samples = accepted + rejected
        if (samples == 0) 0.0 else rejected.toDouble() / samples
    }

    fun rejectedCount(): Int = synchronized(lock) { rejected }

    fun acceptedCount(): Int = synchronized(lock) { accepted }

    /** A session that is being rebuilt is a new question; the rider may answer it again. */
    fun reset() {
        synchronized(lock) {
            firstOfferAt = 0L
            accepted = 0
            rejected = 0
            verdict = null
        }
    }

    companion object {
        /**
         * Long enough to be past codec configuration and the first keyframe, short enough that the
         * rider is still looking at the phone rather than having put it in the tank bag.
         */
        const val DEFAULT_SETTLE_MILLIS = 8_000L

        /** Below this the ratio is noise - a handful of frames says nothing about a link. */
        const val DEFAULT_MIN_SAMPLES = 60

        /**
         * Half. Not tuned to sit just under one rider's numbers: at 0.88 (315e0af3, generic
         * profile on a Yunmo dash) and 0.06 (the same dash, right profile) there is no contest,
         * and a threshold picked to hug either one would be fitting noise. Half of everything
         * refused is indefensible on any dash and any profile, which is what a threshold should
         * mean.
         */
        const val DEFAULT_REJECTED_SHARE = 0.5
    }
}
