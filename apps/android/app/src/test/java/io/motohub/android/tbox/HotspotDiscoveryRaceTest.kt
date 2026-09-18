// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scheduling rules behind [RideDaemonTransport.discoverOverPhoneHotspot]. They are exercised
 * here rather than on a bike because the bug they fix was invisible from inside a session: rider
 * 6e77dcf7 (samsung SM-S948B, MOTO-HUB 1.1.112, 2026-09-06) hit it and the log read like a dash
 * that was simply not there, right up to the retry 14 seconds later that resolved in 180ms.
 */
class HotspotDiscoveryRaceTest {
    private val advertised = TBoxHost("192.168.43.212", 10930, "com.cfmoto.cfmotointernational")
    private val swept = TBoxHost("192.168.43.212", 10930, "net.easyconn.carman")

    @Test
    fun `an advertisement inside the head start ends discovery before the sweep is ever launched`() {
        // The common case, and the one the head start exists to protect: the dash was already on
        // the hotspot, so the phone must not put 253 TCP connects on the air to learn that.
        val race = newRace()

        val opening = race.begin() as HotspotDiscoveryVerdict.KeepGoing
        assertFalse(opening.startSweep)
        assertEquals(HEAD_START_MS, opening.deadlineAtMs)

        val verdict = race.offer(HotspotDiscoveryEvent.NsdResolved(180L, advertised))

        assertEquals(
            HotspotDiscoveryVerdict.Adopt(advertised, HotspotDiscoveryRoad.ADVERTISEMENT, 180L),
            verdict
        )
    }

    @Test
    fun `the sweep joins when the head start expires and the listener is not stopped for it`() {
        // This is the defect in one assertion. Before the fix the 15s mark tore NSD down; the
        // sweep that followed was a single pass and therefore deaf to a dash that took an
        // address it had already tried.
        val race = newRace()
        race.begin()

        val verdict = race.offer(HotspotDiscoveryEvent.DeadlineReached(HEAD_START_MS)) as
            HotspotDiscoveryVerdict.KeepGoing

        assertTrue(verdict.startSweep)
        // No timer: from here only the two roads can end the race.
        assertNull(verdict.deadlineAtMs)
    }

    @Test
    fun `rider 6e77dcf7 - an advertisement 90s in wins while the sweep is still running`() {
        // His own timeline, in milliseconds from the 11:10:21 hotspot switch-on: NSD silent
        // through the 15s head start, the sweep walking 253 addresses from 11:10:36, and the
        // announcement the dash made once it had finally associated. He proved that announcement
        // was there by pressing connect again at 11:12:06 and getting it in 180ms.
        val race = newRace()
        race.begin()
        race.offer(HotspotDiscoveryEvent.DeadlineReached(HEAD_START_MS))

        val verdict = race.offer(HotspotDiscoveryEvent.NsdResolved(90_000L, advertised))

        assertEquals(
            HotspotDiscoveryVerdict.Adopt(advertised, HotspotDiscoveryRoad.ADVERTISEMENT, 90_000L),
            verdict
        )
    }

    @Test
    fun `an advertisement that lands after the sweep gave up is still taken`() {
        // Same rider, the other side of the same second: his sweep reported nothing at 11:11:52
        // and the dash was demonstrably advertising by 11:12:06. Anything that ends discovery on
        // the exact millisecond the sweep runs out throws that away.
        val race = newRace()
        race.begin()
        race.offer(HotspotDiscoveryEvent.DeadlineReached(HEAD_START_MS))

        val lastCall = race.offer(HotspotDiscoveryEvent.SweepExhausted(90_287L)) as
            HotspotDiscoveryVerdict.KeepGoing
        assertEquals(90_287L + HOTSPOT_NSD_LAST_CALL_MS, lastCall.deadlineAtMs)

        val verdict = race.offer(HotspotDiscoveryEvent.NsdResolved(104_000L, advertised))

        assertEquals(
            HotspotDiscoveryVerdict.Adopt(advertised, HotspotDiscoveryRoad.ADVERTISEMENT, 104_000L),
            verdict
        )
    }

    @Test
    fun `a sweep confirmation is held for an advertisement that is already on the wire`() {
        // Both roads go live in the same breath - the dash takes its lease, opens 10930 and starts
        // advertising within a second of each other - and the sweep leads with the address the
        // dash announced, so its 250ms connect can beat the mDNS resolve. The advertisement is
        // worth the wait: it is the only road that reports the dash's own port and package name.
        val race = newRace()
        race.begin()
        race.offer(HotspotDiscoveryEvent.DeadlineReached(HEAD_START_MS))

        val held = race.offer(HotspotDiscoveryEvent.SweepConfirmed(20_000L, swept)) as
            HotspotDiscoveryVerdict.KeepGoing
        assertEquals(20_000L + HOTSPOT_NSD_GRACE_MS, held.deadlineAtMs)

        val verdict = race.offer(HotspotDiscoveryEvent.NsdResolved(20_300L, advertised))

        assertEquals(
            HotspotDiscoveryVerdict.Adopt(advertised, HotspotDiscoveryRoad.ADVERTISEMENT, 20_300L),
            verdict
        )
    }

    @Test
    fun `a sweep confirmation is adopted when the grace expires with nothing advertised`() {
        // The grace is a preference, never a requirement: a completed CMD_MDNS_RESPOND handshake
        // is the same proof of an endpoint that the Wi-Fi Direct path accepts, so once the wait
        // is spent there is nothing left to wait for.
        val race = newRace()
        race.begin()
        race.offer(HotspotDiscoveryEvent.DeadlineReached(HEAD_START_MS))
        race.offer(HotspotDiscoveryEvent.SweepConfirmed(20_000L, swept))

        val verdict = race.offer(
            HotspotDiscoveryEvent.DeadlineReached(20_000L + HOTSPOT_NSD_GRACE_MS)
        )

        assertEquals(
            HotspotDiscoveryVerdict.Adopt(swept, HotspotDiscoveryRoad.SWEEP, 21_000L),
            verdict
        )
    }

    @Test
    fun `a listener that cannot start sends the sweep out at once instead of waiting out the head start`() {
        // NSD is never timed out from the outside any more, so the only way it leaves early is a
        // failed startServiceDiscovery. A listener that is not listening has nothing for the head
        // start to protect, and spending the remaining 14s in silence would be pure waste.
        val race = newRace()
        race.begin()

        val verdict = race.offer(
            HotspotDiscoveryEvent.NsdStopped(900L, IllegalStateException("Android NSD start failed: 3"))
        ) as HotspotDiscoveryVerdict.KeepGoing

        assertTrue(verdict.startSweep)
        assertNull(verdict.deadlineAtMs)
    }

    @Test
    fun `with the listener gone a sweep confirmation is adopted without waiting out the grace`() {
        val race = newRace()
        race.begin()
        race.offer(HotspotDiscoveryEvent.NsdStopped(900L, IllegalStateException("Android NSD start failed: 3")))

        val verdict = race.offer(HotspotDiscoveryEvent.SweepConfirmed(4_100L, swept))

        assertEquals(
            HotspotDiscoveryVerdict.Adopt(swept, HotspotDiscoveryRoad.SWEEP, 4_100L),
            verdict
        )
    }

    @Test
    fun `the sweep is launched exactly once however the race gets there`() {
        // joinSweepToTheRace is idempotent on the transport side too, but the referee must not be
        // the thing relying on that: a second launch would put a second full /24 of connects on a
        // hotspot the dash is trying to associate to.
        val race = newRace()
        race.begin()
        val first = race.offer(HotspotDiscoveryEvent.DeadlineReached(HEAD_START_MS)) as
            HotspotDiscoveryVerdict.KeepGoing
        assertTrue(first.startSweep)

        val second = race.offer(
            HotspotDiscoveryEvent.NsdStopped(HEAD_START_MS + 10L, IllegalStateException("late failure"))
        ) as HotspotDiscoveryVerdict.KeepGoing

        assertFalse(second.startSweep)
    }

    @Test
    fun `both roads spent is one failure that names both of them`() {
        // The "sweeping" line used to mark the exact moment NSD gave up, and a reader could tell
        // the two causes apart by where the log stopped. The roads overlap now, so the sentence
        // the rider is shown has to carry both or the distinction is lost.
        val race = newRace()
        race.begin()
        race.offer(HotspotDiscoveryEvent.DeadlineReached(HEAD_START_MS))
        race.offer(HotspotDiscoveryEvent.SweepExhausted(90_287L))

        val verdict = race.offer(
            HotspotDiscoveryEvent.DeadlineReached(90_287L + HOTSPOT_NSD_LAST_CALL_MS)
        ) as HotspotDiscoveryVerdict.GiveUp

        assertNull(verdict.nsdStoppedBy)
        val message = describeHotspotDiscoveryFailure(verdict)
        assertTrue(message, message.contains("nothing announced _EasyConn._tcp. in 105s of listening"))
        assertTrue(message, message.contains("no address on the hotspot subnet completed the EasyConn handshake"))
        assertTrue(message, message.contains("hotspot Ssid and Password"))
    }

    @Test
    fun `a listener that never started is reported as a fault on this phone, not a silent dash`() {
        // Different cause, different place to look: "the dash stayed quiet" sends the reader to
        // the bike, and this one never leaves the phone.
        val race = newRace()
        race.begin()
        race.offer(HotspotDiscoveryEvent.NsdStopped(900L, IllegalStateException("Android NSD start failed: 3")))

        val verdict = race.offer(HotspotDiscoveryEvent.SweepExhausted(76_000L)) as
            HotspotDiscoveryVerdict.GiveUp

        val message = describeHotspotDiscoveryFailure(verdict)
        assertTrue(message, message.contains("could not listen for the dash's announcement at all"))
        assertTrue(message, message.contains("Android NSD start failed: 3"))
        assertFalse(message, message.contains("nothing announced"))
    }

    @Test
    fun `the shipped windows are the ones the rider's log argues for`() {
        // 15s of listening alone, then the sweep alongside it, then one more standard window for
        // the listener. His own retry - the one that worked - came 14s after the failure, so the
        // last call costs him less than the retry it replaces.
        assertEquals(15_000L, HOTSPOT_NSD_LAST_CALL_MS)
        assertEquals(1_000L, HOTSPOT_NSD_GRACE_MS)
    }

    private fun newRace() = HotspotDiscoveryRace(sweepJoinsAtMs = HEAD_START_MS)

    private companion object {
        /** DISCOVERY_TIMEOUT_MS, which the transport passes as the listener's head start. */
        const val HEAD_START_MS = 15_000L
    }
}
