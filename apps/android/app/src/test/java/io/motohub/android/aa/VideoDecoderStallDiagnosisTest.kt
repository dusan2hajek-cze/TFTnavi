// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.aa

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The decoder stall watchdog's decision, which is the whole of it: everything else in that path is
 * MediaCodec.
 */
class VideoDecoderStallDiagnosisTest {

    @Test
    fun outputStillFlowingIsNotAStall() {
        assertEquals(StallVerdict.NONE, diagnoseStall(stallGapMs = 0, inputGapMs = 5, downstreamBlockedMs = 0))
        assertEquals(StallVerdict.NONE, diagnoseStall(stallGapMs = 2_999, inputGapMs = 5, downstreamBlockedMs = 0))
    }

    /**
     * Android Auto stops sending video during a UI transition, a call, or its own decoder
     * recovery. Restarting into that is how you end up fighting its Media Stop/Start sequence.
     */
    @Test
    fun noInputMeansAndroidAutoPausedAndNotThatAnythingBroke() {
        assertEquals(
            StallVerdict.NONE,
            diagnoseStall(stallGapMs = 20_000, inputGapMs = 1_000, downstreamBlockedMs = 0)
        )
    }

    @Test
    fun inputFlowingWithAnIdlePipeIsTheDecoder() {
        assertEquals(
            StallVerdict.DECODER,
            diagnoseStall(stallGapMs = 9_201, inputGapMs = 6, downstreamBlockedMs = 0)
        )
        // Blocked, but nowhere near enough of the window to explain the missing output.
        assertEquals(
            StallVerdict.DECODER,
            diagnoseStall(stallGapMs = 9_201, inputGapMs = 6, downstreamBlockedMs = 400)
        )
    }

    /**
     * Rider 4d8a4c5b's numbers (2026-08-26), the case this was written for: the decoder was fine
     * and the pipe behind the compositor was not moving. He got seven restarts and no picture.
     */
    @Test
    fun inputFlowingWithABlockedPipeIsNotTheDecoder() {
        assertEquals(
            StallVerdict.DOWNSTREAM,
            diagnoseStall(stallGapMs = 9_201, inputGapMs = 6, downstreamBlockedMs = 8_900)
        )
        // Half the window is the threshold, and it belongs to DOWNSTREAM.
        assertEquals(
            StallVerdict.DOWNSTREAM,
            diagnoseStall(stallGapMs = 9_200, inputGapMs = 6, downstreamBlockedMs = 4_600)
        )
        assertEquals(
            StallVerdict.DECODER,
            diagnoseStall(stallGapMs = 9_200, inputGapMs = 6, downstreamBlockedMs = 4_599)
        )
    }

    /**
     * A caller with nothing downstream that can push back passes no probe, and reads 0 forever.
     * That must leave the watchdog exactly as it behaved before the probe existed - a stall with
     * no evidence of a jam is still the decoder's fault.
     */
    @Test
    fun withoutAProbeEveryStallIsStillTheDecoders() {
        assertEquals(
            StallVerdict.DECODER,
            diagnoseStall(stallGapMs = 3_001, inputGapMs = 999, downstreamBlockedMs = 0)
        )
    }
}
