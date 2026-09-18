// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HandlebarStateTest {
    private val hidTaught = HandlebarState(
        inputMode = "hid",
        captureEnabled = true,
        calibrated = true,
        managedByCompanion = true,
        hidServiceEnabled = false
    )

    @Test
    fun `a state survives the crossing unchanged`() {
        assertEquals(hidTaught, HandlebarState.parse(hidTaught.encode()))
    }

    @Test
    fun `every field crosses on its own`() {
        // One flag read from the wrong position is the whole diagnosis inverted, so each is
        // moved alone and checked alone.
        val base = HandlebarState("avrcp", false, false, false, false)
        assertEquals(base, HandlebarState.parse(base.encode()))
        assertEquals(
            true,
            HandlebarState.parse(base.copy(captureEnabled = true).encode())?.captureEnabled
        )
        assertEquals(true, HandlebarState.parse(base.copy(calibrated = true).encode())?.calibrated)
        assertEquals(
            true,
            HandlebarState.parse(base.copy(managedByCompanion = true).encode())?.managedByCompanion
        )
        assertEquals(
            true,
            HandlebarState.parse(base.copy(hidServiceEnabled = true).encode())?.hidServiceEnabled
        )
    }

    @Test
    fun `anything that is not exactly this format is no answer at all`() {
        // A half-parsed state becomes a sentence about a rider's phone in a support case; every
        // one of these would have been a plausible, wrong one.
        assertNull(HandlebarState.parse(""))
        assertNull(HandlebarState.parse("avrcp|1|0|1"))
        assertNull(HandlebarState.parse("avrcp|1|0|1|0|1"))
        assertNull(HandlebarState.parse("|1|0|1|0"))
        assertNull(HandlebarState.parse("avrcp|true|0|1|0"))
        assertNull(HandlebarState.parse("avrcp|1|0|1|"))
    }

    @Test
    fun `an unknown protocol name still crosses`() {
        // Not an enum on the wire on purpose: a Core newer than this reader may know a protocol
        // this one does not, and "hid2" arriving verbatim in a report is worth more than a null.
        assertEquals("hid2", HandlebarState.parse("hid2|1|1|0|1")?.inputMode)
    }

    @Test
    fun `the call is gated behind its own contract version`() {
        assertEquals(15, IpcBridgeContract.CONTRACT_VERSION_HANDLEBAR_STATE)
        assertTrue(
            IpcBridgeContract.CONTRACT_VERSION >= IpcBridgeContract.CONTRACT_VERSION_HANDLEBAR_STATE
        )
        // Strictly after the Bluetooth grant: a Core that can say whether a press arrives cannot
        // be assumed to say what happens to it.
        assertTrue(
            IpcBridgeContract.CONTRACT_VERSION_HANDLEBAR_STATE >
                IpcBridgeContract.CONTRACT_VERSION_CORE_BLUETOOTH
        )
    }
}
