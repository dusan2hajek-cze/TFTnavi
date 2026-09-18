// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.androidauto.TBoxScreenMarginsStore
import io.motohub.android.androidauto.storedMargins
import io.motohub.android.ipc.IpcBridgeContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the 7efdfa33 fix that can be judged without a device: what the companion is
 * allowed to say about screen margins, and whether Core's port-scan answer survives the trip.
 */
class CompanionPortScanAndMarginsTest {

    @Test
    fun `a scan result survives the round trip across the bridge`() {
        val original = TBoxPortScanResult(
            peerIp = "192.168.1.1",
            entries = listOf(
                TBoxPortScanEntry(10920, TBoxPortStatus.REFUSED, "Connection refused"),
                TBoxPortScanEntry(10930, TBoxPortStatus.OPEN, null),
                TBoxPortScanEntry(10935, TBoxPortStatus.NO_RESPONSE, "timeout")
            )
        )
        val decoded = TBoxPortScanner.decode(TBoxPortScanner.encode(original))
        assertEquals(original, decoded)
    }

    /**
     * A scan that reached the dash's network but could not name the peer is a real answer - the
     * inspector prints "no usable peer address" - and must not be mistaken for Core refusing.
     */
    @Test
    fun `a result with no peer and no ports still decodes`() {
        val decoded = TBoxPortScanner.decode(
            TBoxPortScanner.encode(TBoxPortScanResult(peerIp = null, entries = emptyList()))
        )
        assertEquals(TBoxPortScanResult(peerIp = null, entries = emptyList()), decoded)
    }

    /**
     * The two apps are updated separately, so a status this build has never heard of is a normal
     * event, not corruption. It reads as NO_RESPONSE - the answer that claims the least.
     */
    @Test
    fun `an unknown status decodes as no response rather than dropping the port`() {
        val decoded = TBoxPortScanner.decode(
            """{"peerIp":"192.168.1.1","ports":[{"port":10930,"status":"HALF_OPEN"}]}"""
        )
        assertEquals(1, decoded?.entries?.size)
        assertEquals(TBoxPortStatus.NO_RESPONSE, decoded?.entries?.first()?.status)
    }

    @Test
    fun `text that is not this scanner's json decodes to null`() {
        assertNull(TBoxPortScanner.decode("not json at all"))
    }

    /**
     * Null means "not delegated", and the caller falls back to the local scan. A Core that
     * answered garbage must therefore not look like a Core that has no session.
     */
    @Test
    fun `a port entry with no port number is skipped, not guessed`() {
        val decoded = TBoxPortScanner.decode("""{"ports":[{"status":"OPEN"}]}""")
        assertEquals(emptyList<TBoxPortScanEntry>(), decoded?.entries)
    }

    @Test
    fun `margins the rider taught are reported as taught`() {
        val margins = storedMargins(listOf(0, 0, 0, 120))
        assertEquals(120, margins?.right)
        assertEquals(0, margins?.top)
    }

    /**
     * The guard the whole gate rests on: zero margins the rider taught are a real choice, and
     * "never taught" must stay distinguishable from it or the companion would push four zeros
     * over a calibration made in Core.
     */
    @Test
    fun `taught zeros are a value, never taught is not`() {
        assertEquals(TBoxScreenMarginsStore.UNSET, -1)
        assertEquals(0, storedMargins(listOf(0, 0, 0, 0))?.right)
        assertNull(storedMargins(listOf(-1, -1, -1, -1)))
    }

    /** A half-written record is not a teaching; filling its gaps with zeros would invent one. */
    @Test
    fun `one missing edge makes the whole record untaught`() {
        assertNull(storedMargins(listOf(0, 0, 0, -1)))
        assertNull(storedMargins(listOf(-1, 0, 0, 120)))
        assertNull(storedMargins(listOf(0, 0, 0)))
    }

    /**
     * The join measurement. It exists to settle "ADVANCED connects slower than CORE", so the two
     * things it must never lose are who asked and where the time went.
     */
    @Test
    fun `a join reports who asked and how the wait split`() {
        val line = joinTimingLine(
            ssid = "EASYCONN_5G-1813BC",
            totalMs = 22_795L,
            associatedMs = 22_740L,
            askedBy = "hub-ui, aidl-bridge",
            importance = 100
        )
        assertEquals(
            "Joined EASYCONN_5G-1813BC in 22795ms: associated after 22740ms, address 55ms later; " +
                "asked by hub-ui, aidl-bridge at process importance 100.",
            line
        )
    }

    /**
     * An address that turned up with no AVAILABLE callback is a real, different event. Reporting
     * it as "associated after 0ms" would invent an instant association out of a missing one.
     */
    @Test
    fun `a join with no availability callback says so instead of claiming 0ms`() {
        val line = joinTimingLine("BIKE", 4_000L, null, "pro-establisher", 125)
        assertTrue(line.contains("no AVAILABLE callback"))
        assertTrue(!line.contains("associated after"))
    }

    /** An empty ledger is not an empty sentence: the line still has to name the importance. */
    @Test
    fun `a join nobody claims still names the importance`() {
        val line = joinTimingLine("BIKE", 10L, 5L, "", 400)
        assertTrue(line.contains("nobody on the ledger"))
        assertTrue(line.contains("importance 400"))
    }

    /** A forgotten bump would make every companion skip the call it was written to reach. */
    @Test
    fun `core announces a contract new enough to be asked for a port scan`() {
        assertEquals(9, IpcBridgeContract.CONTRACT_VERSION_PORT_SCAN)
        assertTrue(IpcBridgeContract.CONTRACT_VERSION >= IpcBridgeContract.CONTRACT_VERSION_PORT_SCAN)
    }
}
