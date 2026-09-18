// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val PXC = 2L
private const val MEDIA_CONTROL = 3L

class ProtocolBeatCollapserTest {
    @Test
    fun `each beat command is written once and then folded`() {
        val collapser = ProtocolBeatCollapser()

        assertTrue(collapser.onEvent(PXC, "HEARTBEAT_ACK", payloadSize = 0, now = 0L) is BeatDecision.Write)
        assertTrue(collapser.onEvent(PXC, "PERIODIC_NOTIFY", payloadSize = 0, now = 100L) is BeatDecision.Write)
        // Same command, second time: the log already proves this dash sends it.
        assertTrue(collapser.onEvent(PXC, "HEARTBEAT_ACK", payloadSize = 0, now = 2_000L) is BeatDecision.Fold)
        assertTrue(collapser.onEvent(PXC, "PERIODIC_NOTIFY", payloadSize = 0, now = 2_100L) is BeatDecision.Fold)
    }

    @Test
    fun `the same command on the other source is its own first occurrence`() {
        val collapser = ProtocolBeatCollapser()

        assertTrue(collapser.onEvent(PXC, "PING", payloadSize = 0, now = 0L) is BeatDecision.Write)
        // MEDIA_CONTROL PING is a different message that happens to share a name; "the dash never
        // pinged on the media channel" has to stay readable from the log.
        assertTrue(collapser.onEvent(MEDIA_CONTROL, "PING", payloadSize = 0, now = 10L) is BeatDecision.Write)
    }

    @Test
    fun `any other event closes the run and carries its tally`() {
        val collapser = ProtocolBeatCollapser()
        collapser.onEvent(PXC, "HEARTBEAT_ACK", payloadSize = 0, now = 0L)
        repeat(3) { collapser.onEvent(PXC, "HEARTBEAT_ACK", payloadSize = 0, now = 2_000L * (it + 1)) }

        // The rollup rides on the event that ended the run, so it can never be read as having
        // happened after traffic that actually preceded it.
        val decision = collapser.onEvent(PXC, "CLIENT_INFO", payloadSize = 42, now = 8_000L)

        assertTrue(decision is BeatDecision.Write)
        val rollup = (decision as BeatDecision.Write).rollup
        assertNotNull(rollup)
        assertTrue(rollup!!, rollup.contains("3 keepalive beats folded over 6s"))
        assertTrue(rollup, rollup.contains("HEARTBEAT_ACK ×3"))
    }

    @Test
    fun `a long quiet stretch is reported without waiting for other traffic`() {
        val collapser = ProtocolBeatCollapser(rollupIntervalMillis = 60_000L)
        collapser.onEvent(PXC, "HEARTBEAT_ACK", payloadSize = 0, now = 0L)

        var lastRollup: String? = null
        // Two seconds apart, the cadence a VOGE dash keeps, for five minutes.
        for (beat in 1..150) {
            val decision = collapser.onEvent(PXC, "HEARTBEAT_ACK", payloadSize = 0, now = 2_000L * beat)
            (decision as? BeatDecision.Fold)?.rollup?.let { lastRollup = it }
        }

        // A session that only ever beats still says so once a minute rather than going silent for
        // the whole ride. 31 lines, not 30: the beat that closes the run is counted into it.
        assertNotNull(lastRollup)
        assertTrue(lastRollup!!, lastRollup!!.contains("HEARTBEAT_ACK ×31"))
        assertTrue(lastRollup!!, lastRollup!!.contains("folded over 60s"))
    }

    @Test
    fun `a beat that carries a body is evidence, not noise`() {
        val collapser = ProtocolBeatCollapser()
        collapser.onEvent(PXC, "CLOCK_KEEPALIVE", payloadSize = 0, now = 0L)

        // The clock stamp that a dash sends inside a keepalive is how HU_TIME_SYNC was read.
        assertTrue(collapser.onEvent(PXC, "CLOCK_KEEPALIVE", payloadSize = 45, now = 2_000L) is BeatDecision.Write)
    }

    @Test
    fun `an unnamed opcode is never folded`() {
        val collapser = ProtocolBeatCollapser()

        // An UNKNOWN arriving on a cadence is the shape every T-Box investigation starts from.
        repeat(4) {
            val decision = collapser.onEvent(PXC, "UNKNOWN", payloadSize = 0, now = 2_000L * it)
            assertTrue(decision is BeatDecision.Write)
        }
    }

    @Test
    fun `closing reports a pending tally and resetting forgets the session`() {
        val collapser = ProtocolBeatCollapser()
        collapser.onEvent(PXC, "PERIODIC_NOTIFY", payloadSize = 0, now = 0L)
        collapser.onEvent(PXC, "PERIODIC_NOTIFY", payloadSize = 0, now = 2_000L)

        val rollup = collapser.close(now = 4_000L)
        assertNotNull(rollup)
        assertTrue(rollup!!, rollup.contains("1 keepalive beat folded"))
        // Nothing left over: a second close must not invent a run.
        assertNull(collapser.close(now = 5_000L))

        collapser.reset()
        // The next session starts from scratch, so its own first beat is written again.
        assertTrue(collapser.onEvent(PXC, "PERIODIC_NOTIFY", payloadSize = 0, now = 6_000L) is BeatDecision.Write)
    }

    @Test
    fun `eight minutes of a VOGE dash no longer fills the log ring`() {
        // The shape from support 0df154af: three empty commands round-robin every ~2s. It spent
        // all 1500 CORE entries on 8 minutes and pushed out the handlebar presses the rider was
        // reporting. Counted in written LINES, which is what the ring holds.
        val collapser = ProtocolBeatCollapser()
        var written = 0
        var rollups = 0
        val beats = listOf("HEARTBEAT_ACK", "PERIODIC_NOTIFY", "PERIODIC_NOTIFY_ALT")
        for (tick in 0 until 240) {
            val now = 2_000L * tick
            beats.forEach { name ->
                when (val decision = collapser.onEvent(PXC, name, payloadSize = 0, now = now)) {
                    is BeatDecision.Write -> {
                        written++
                        if (decision.rollup != null) rollups++
                    }
                    is BeatDecision.Fold -> if (decision.rollup != null) rollups++
                }
            }
        }

        // Three first occurrences, plus one rollup a minute, plus the tail the session close
        // reports: 11 lines where 1440 events arrived. The ring now holds the ride, not the beat.
        assertEquals(3, written)
        assertEquals(7, rollups)
        assertNotNull(collapser.close(now = 2_000L * 240))
    }
}
