// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that stops one owner of the shared T-Box connector from tearing down the Wi-Fi
 * request another owner is still streaming over.
 *
 * Reproducing the bug this prevents needs a motorcycle, a companion app and a closed activity
 * (rider a9fb623a, 2026-08-04: MainActivity's teardown dropped the AP under the live companion
 * session), so the ledger's answers are pinned here instead. All calls run under
 * [TBoxNetworkConnectors]' single monitor in production, so sequential tests cover the
 * concurrent case: two racing releases serialize, and [NetworkInterestLedger.releaseIsLast]
 * answers true at most once per population, which is asserted below.
 */
class NetworkInterestLedgerTest {
    private val ledger = NetworkInterestLedger()

    @Test
    fun `the last owner out releases the network, exactly once`() {
        ledger.acquire("hub-ui")
        ledger.acquire("session")

        assertFalse(ledger.releaseIsLast("hub-ui"))
        assertTrue(ledger.releaseIsLast("session"))
        // A second release of the same owner must not answer true again.
        assertFalse(ledger.releaseIsLast("session"))
    }

    /** The port scanner finishing must not disconnect the ride. */
    @Test
    fun `an owner's release does nothing while another owner still holds`() {
        ledger.acquire("session")
        ledger.acquire("port-scan")

        assertFalse(ledger.releaseIsLast("port-scan"))
        assertEquals("session", ledger.describe())
    }

    /** Auto-connect acquires on every resume; the count must not inflate. */
    @Test
    fun `re-acquiring by the same owner is one interest, not two`() {
        assertTrue(ledger.acquire("hub-ui"))
        assertFalse(ledger.acquire("hub-ui"))

        assertTrue(ledger.releaseIsLast("hub-ui"))
    }

    /**
     * Stricter than SessionConsumers on purpose: a release never matched by an acquire is a
     * caller bug, and answering true here would turn that bug into a mid-ride disconnect even
     * when the ledger is empty.
     */
    @Test
    fun `a release that was never acquired can never drop the network`() {
        assertFalse(ledger.releaseIsLast("aidl-bridge"))

        ledger.acquire("session")
        assertFalse(ledger.releaseIsLast("aidl-bridge"))
        assertEquals("session", ledger.describe())
    }

    @Test
    fun `isHeldByOthers ignores the asking owner's own interest`() {
        ledger.acquire("port-scan")
        assertFalse(ledger.isHeldByOthers("port-scan"))

        ledger.acquire("session")
        assertTrue(ledger.isHeldByOthers("port-scan"))
    }

    @Test
    fun `an empty ledger is held by nobody`() {
        assertFalse(ledger.isHeldByOthers("port-scan"))
        assertEquals("", ledger.describe())
    }

    /** The 2026-08-04 shape: UI acquires, session installs, UI dies, bridge still streams. */
    @Test
    fun `the ticket sequence keeps the network until the last participant leaves`() {
        ledger.acquire("hub-ui")          // 17:08:23 UI connect
        ledger.acquire("session")         // session installed
        ledger.acquire("aidl-bridge")     // 17:09:03 companion connect

        assertFalse(ledger.releaseIsLast("hub-ui"))     // 17:11:39 MainActivity destroyed
        assertFalse(ledger.releaseIsLast("session"))
        assertTrue(ledger.releaseIsLast("aidl-bridge"))
    }
}
