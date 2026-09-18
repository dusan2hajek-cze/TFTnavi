// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TBoxHandshakeOwnershipTest {
    /**
     * Rider 94b0a3da, 2026-08-30: one native startup timeout arrived twice - as the return value of
     * the handshake call and as a Stopped event - and the event ended the session 175 ms before the
     * handshake's own re-discover-and-retry could finish. The retry had never once run.
     */
    @Test
    fun `a failure reported while the handshake is still running does not end the session`() {
        assertEquals(
            "Not ending Android Auto: the EasyConn handshake that reported this is still " +
                "running and owns the outcome - The T-Box ended Android Auto.",
            tBoxFailureOwnedByHandshake(
                handshakeInFlight = true,
                sessionName = "Android Auto",
                message = "The T-Box ended Android Auto."
            )
        )
    }

    /**
     * The Ride Dashboard reaches the same teardown by a different route - requestRecovery's
     * "nothing is running yet" branch - so it needs the same guard and its own name in the line.
     */
    @Test
    fun `the same guard names whichever mode is being kept alive`() {
        assertEquals(
            "Not ending the Ride Dashboard: the EasyConn handshake that reported this is still " +
                "running and owns the outcome - The T-Box ended the Ride Dashboard session.",
            tBoxFailureOwnedByHandshake(
                handshakeInFlight = true,
                sessionName = "the Ride Dashboard",
                message = "The T-Box ended the Ride Dashboard session."
            )
        )
    }

    @Test
    fun `a failure with no handshake running is the session ending and is routed on`() {
        assertNull(
            tBoxFailureOwnedByHandshake(
                handshakeInFlight = false,
                sessionName = "Android Auto",
                message = "The T-Box ended Android Auto."
            )
        )
    }
}
