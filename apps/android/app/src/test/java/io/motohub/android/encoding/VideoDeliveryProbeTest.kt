// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import io.motohub.android.encoding.VideoDeliveryProbe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDeliveryProbeTest {

    private var now = 0L
    private fun probe() = VideoDeliveryProbe(clock = { now })

    /** Runs [frames] at 30fps, refusing all but one in [acceptOneIn]; null accepts everything. */
    private fun feed(probe: VideoDeliveryProbe, frames: Int, acceptOneIn: Int?): List<Verdict> {
        val verdicts = mutableListOf<Verdict>()
        repeat(frames) { index ->
            now += 33
            val accept = acceptOneIn == null || index % acceptOneIn == 0
            val verdict = if (accept) probe.onAccepted() else probe.onRejected()
            verdict?.let(verdicts::add)
        }
        return verdicts
    }

    @Test
    fun aDashThatRefusesMostOfWhatWeSendIsReportedOnce() {
        // Rider 315e0af3's numbers: ~88% refused, sustained. Nothing subtle about it.
        val probe = probe()
        val verdicts = feed(probe, frames = 600, acceptOneIn = 8)
        assertEquals("the rider may only be asked once per session", listOf(Verdict.FAILING), verdicts)
        assertTrue(probe.rejectedShare() > 0.8)
    }

    @Test
    fun aHealthySessionSaysSoOnceRatherThanStayingSilent() {
        // The same dash on the right profile. This verdict is what earns the app the right to ask
        // "keep this profile?" - silence could not tell that apart from a session that never sent
        // a frame at all.
        val probe = probe()
        val verdicts = feed(probe, frames = 600, acceptOneIn = null)
        assertEquals(listOf(Verdict.HEALTHY), verdicts)
        assertTrue(probe.rejectedShare() < 0.01)
    }

    @Test
    fun anOccasionalRejectionIsStillAWorkingDashboard() {
        // One in four refused is a link under pressure that is nonetheless painting a picture.
        val probe = probe()
        val verdicts = mutableListOf<Verdict>()
        repeat(600) { index ->
            now += 33
            val verdict = if (index % 4 == 0) probe.onRejected() else probe.onAccepted()
            verdict?.let(verdicts::add)
        }
        assertEquals(listOf(Verdict.HEALTHY), verdicts)
    }

    @Test
    fun theFirstSecondsOfASessionConcludeNothingEitherWay() {
        // Codec configuration and the opening keyframe: judged on those alone a good dash looks
        // broken and a broken one can look fine, so neither verdict may be reached here.
        val probe = probe()
        repeat(200) {
            now += 20 // 200 frames inside 4s, all refused - still inside the settle window
            assertNull(probe.onRejected())
        }
        now += VideoDeliveryProbe.DEFAULT_SETTLE_MILLIS
        assertEquals(Verdict.FAILING, probe.onRejected())
    }

    @Test
    fun aHandfulOfFramesSaysNothingHoweverGoodOrBadItLooks() {
        val probe = probe()
        repeat(VideoDeliveryProbe.DEFAULT_MIN_SAMPLES - 1) {
            now += 1_000 // far past the settle window, so only the sample floor is in play
            assertNull(probe.onAccepted())
        }
        assertEquals("the very next frame completes the picture", Verdict.HEALTHY, probe.onAccepted())
    }

    @Test
    fun bothVerdictsAreReachedOnTheSameEvidence() {
        // A healthy conclusion drawn on a laxer test than the failing one would be the app
        // telling a rider their profile works on weaker grounds than it told them it did not.
        // 400 frames at 33ms is 13s - comfortably past the settle window both must clear.
        val failing = probe()
        val failingVerdicts = feed(failing, frames = 400, acceptOneIn = 8)
        now = 0
        val healthy = probe()
        val healthyVerdicts = feed(healthy, frames = 400, acceptOneIn = null)
        assertEquals(listOf(Verdict.FAILING), failingVerdicts)
        assertEquals(listOf(Verdict.HEALTHY), healthyVerdicts)
        // Same frame count consumed before concluding: the two arms share one test, not two.
        assertEquals(failing.acceptedCount() + failing.rejectedCount(), healthy.acceptedCount())
    }

    @Test
    fun aRebuiltSessionMayAskAgain() {
        val probe = probe()
        assertEquals(listOf(Verdict.FAILING), feed(probe, frames = 600, acceptOneIn = 8))
        assertNull("still the same session", probe.onRejected())
        probe.reset()
        assertEquals(0, probe.rejectedCount())
        assertEquals(0.0, probe.rejectedShare(), 0.0)
        assertEquals(
            "a session rebuilt around a new profile is a new question",
            listOf(Verdict.HEALTHY),
            feed(probe, frames = 600, acceptOneIn = null)
        )
    }

    @Test
    fun theCountsItReportsAreTheOnesItJudgedOn() {
        val probe = probe()
        repeat(30) { now += 33; probe.onAccepted() }
        repeat(70) { now += 33; probe.onRejected() }
        assertEquals(30, probe.acceptedCount())
        assertEquals(70, probe.rejectedCount())
        assertEquals(0.7, probe.rejectedShare(), 0.001)
    }
}
