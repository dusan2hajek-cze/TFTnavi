// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.encoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The numbers in these tests are the ones rider dc735158's X-Cape 1200 actually produced on
 * 2026-08-24: about 6.6 stills a second at its best, 3.9 to 4.1 in the steady state, and a
 * twelve-second stretch where the dashboard offered nothing at all.
 */
class JpegQualityLadderTest {

    /** The window the source measures over, mirrored here so the tests read in real seconds. */
    private val windowMillis = 2_000L

    /** One window: [fps] stills a second accepted out of a full ten-a-second offer. */
    private fun JpegQualityLadder.window(fps: Double): JpegQualityLadder.Outcome {
        val accepted = (fps * windowMillis / 1000.0).toInt()
        val offered = (10.0 * windowMillis / 1000.0).toInt()
        return onWindow(offered.coerceAtLeast(accepted), accepted, windowMillis)
    }

    @Test
    fun `a window with nothing offered never changes the quality`() {
        val ladder = JpegQualityLadder()
        repeat(6) { index ->
            val outcome = ladder.onWindow(offered = 0, accepted = 0, elapsedMillis = windowMillis)
            val hold = outcome as JpegQualityLadder.Outcome.IdleHold
            assertEquals("only the first idle window explains itself", index == 0, hold.first)
        }
        assertEquals(60, ladder.quality)
    }

    @Test
    fun `the stall that cost a ride no longer walks the quality to the floor`() {
        val ladder = JpegQualityLadder()
        // Twelve seconds of a dashboard producing nothing - the MapLibre scene that came up dead.
        repeat(6) { ladder.onWindow(offered = 0, accepted = 0, elapsedMillis = windowMillis) }
        assertEquals("the wire was idle, not congested", 60, ladder.quality)
        // Then the frames arrive, at the rate this dash actually manages.
        ladder.window(6.6)
        assertEquals(60, ladder.quality)
    }

    @Test
    fun `a dash that converts bytes to frames still walks down`() {
        // Every rung pays: the accepted rate climbs with each descent, the way a byte-budgeted
        // dash actually behaves (the X-Cape took 2.2 stills a second at quality 20 and 4.4 at 12).
        val ladder = JpegQualityLadder()
        assertEquals(50, (ladder.window(1.0) as JpegQualityLadder.Outcome.Changed).quality)
        assertEquals(40, (ladder.window(1.5) as JpegQualityLadder.Outcome.Changed).quality)
        for (fps in listOf(2.0, 2.5, 3.0, 3.5, 4.5)) ladder.window(fps)
        assertEquals("the floor is the last rung", 12, ladder.quality)
    }

    @Test
    fun `a ceiling inside the deadband is no longer a one-way trip`() {
        val ladder = JpegQualityLadder()
        for (fps in listOf(2.0, 2.5, 3.0, 3.5)) ladder.window(fps)
        assertEquals(25, ladder.quality)
        // 6.6 a second is this dash keeping up, and is below the fast climb's threshold of 7 -
        // which is why the quality used to be stuck at 25 for the rest of the ride.
        repeat(JpegQualityLadder.PROBE_WINDOWS - 1) {
            assertTrue(ladder.window(6.6) is JpegQualityLadder.Outcome.Unchanged)
        }
        val probe = ladder.window(6.6) as JpegQualityLadder.Outcome.Changed
        assertTrue("a speculative step reads differently in the log", probe.probe)
        assertEquals("one rung finer, not coarser", 32, probe.quality)
    }

    @Test
    fun `a probe the dash refuses is taken back and asked again later`() {
        val ladder = JpegQualityLadder(probeWindows = 3, probeBackoffMaxWindows = 12)
        ladder.window(2.0)
        ladder.window(2.5)
        assertEquals(40, ladder.quality)

        repeat(2) { ladder.window(6.0) }
        assertEquals(50, (ladder.window(6.0) as JpegQualityLadder.Outcome.Changed).quality)
        // The finer rung costs it the frames: straight back down, and the wait doubles.
        assertEquals(40, (ladder.window(3.0) as JpegQualityLadder.Outcome.Changed).quality)

        repeat(5) { assertTrue(ladder.window(6.0) is JpegQualityLadder.Outcome.Unchanged) }
        assertEquals(50, (ladder.window(6.0) as JpegQualityLadder.Outcome.Changed).quality)
    }

    @Test
    fun `a probe that holds keeps climbing a rung at a time`() {
        val ladder = JpegQualityLadder(probeWindows = 2)
        for (fps in listOf(2.0, 2.5, 3.0)) ladder.window(fps)
        assertEquals(32, ladder.quality)

        ladder.window(6.0)
        assertEquals(40, (ladder.window(6.0) as JpegQualityLadder.Outcome.Changed).quality)
        ladder.window(6.0)
        assertEquals(
            "the wait resets after a probe that held, so it walks back up",
            50,
            (ladder.window(6.0) as JpegQualityLadder.Outcome.Changed).quality
        )
    }

    @Test
    fun `a dash with real headroom still climbs the fast way`() {
        val ladder = JpegQualityLadder()
        ladder.window(2.0)
        ladder.window(2.5)
        assertEquals(40, ladder.quality)
        repeat(JpegQualityLadder.WINDOWS_BEFORE_CLIMBING - 1) {
            assertTrue(ladder.window(9.0) is JpegQualityLadder.Outcome.Unchanged)
        }
        val climb = ladder.window(9.0) as JpegQualityLadder.Outcome.Changed
        assertFalse("a real climb is not a probe", climb.probe)
        assertEquals(50, climb.quality)
    }

    @Test
    fun `the top rung is never probed past`() {
        val ladder = JpegQualityLadder(probeWindows = 1)
        repeat(20) { ladder.window(6.5) }
        assertEquals(60, ladder.quality)
    }

    @Test
    fun `a descent that buys no frames is taken back`() {
        // A dash that paces stills by cadence: 4.5 a second whatever it is sent. Trading quality
        // away moved nothing, so the rung comes straight back.
        val ladder = JpegQualityLadder()
        assertEquals(50, (ladder.window(4.5) as JpegQualityLadder.Outcome.Changed).quality)
        val back = ladder.window(4.5) as JpegQualityLadder.Outcome.Reverted
        assertEquals(60, back.quality)
        assertEquals(60, ladder.quality)
    }

    @Test
    fun `a futile descent is retried after the wait, and the wait doubles`() {
        val ladder = JpegQualityLadder(descentRetryWindows = 3, descentBackoffMaxWindows = 12)
        ladder.window(4.5)
        assertTrue(ladder.window(4.5) is JpegQualityLadder.Outcome.Reverted)

        repeat(2) { assertTrue(ladder.window(4.5) is JpegQualityLadder.Outcome.Unchanged) }
        assertEquals(50, (ladder.window(4.5) as JpegQualityLadder.Outcome.Changed).quality)
        assertTrue("still buys nothing", ladder.window(4.5) is JpegQualityLadder.Outcome.Reverted)

        repeat(5) { assertTrue(ladder.window(4.5) is JpegQualityLadder.Outcome.Unchanged) }
        assertEquals(50, (ladder.window(4.5) as JpegQualityLadder.Outcome.Changed).quality)
    }

    @Test
    fun `a dash accepting nothing does not ride the ladder to the floor`() {
        // Offers made, none taken: no quality can buy frames from a dash that refuses them all,
        // and the stall used to end at the coarsest rung anyway.
        val ladder = JpegQualityLadder()
        ladder.onWindow(offered = 20, accepted = 0, elapsedMillis = windowMillis)
        val outcome = ladder.onWindow(offered = 20, accepted = 0, elapsedMillis = windowMillis)
        assertTrue(outcome is JpegQualityLadder.Outcome.Reverted)
        assertEquals(60, ladder.quality)
    }
}
