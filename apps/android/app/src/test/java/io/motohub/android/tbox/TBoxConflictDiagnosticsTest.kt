// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxConflictDiagnosticsTest {
    @Test
    fun detectsNativeBindFailureOnAnEasyConnLinkPort() {
        assertTrue(
            TBoxConflictDiagnostics.isPortConflict(
                "listen tcp 0.0.0.0:10920: bind: address already in use"
            )
        )
    }

    @Test
    fun detectsPortHeldMessageWithoutAStandardSocketException() {
        assertTrue(
            TBoxConflictDiagnostics.isPortConflict(
                "The bike link ports 10921-10922 are held by another client"
            )
        )
    }

    @Test
    fun ignoresUnrelatedTBoxErrors() {
        assertFalse(TBoxConflictDiagnostics.isPortConflict("connection timed out"))
    }

    @Test
    fun replacesTechnicalConflictWithActionableMessage() {
        assertEquals(
            TBoxConflictDiagnostics.PORT_CONFLICT_MESSAGE,
            TBoxConflictDiagnostics.userFacingMessage("bind: address already in use on 10922")
        )
    }

    /**
     * The Zontes rider of 2026-08-23 was told to force-stop CFMOTO's app, which they do not have,
     * while `tayo.com.ZontesIntelligence` held the ports for four minutes. Naming no brand is the
     * floor; naming the one actually installed is the point.
     */
    @Test
    fun namesTheCompanionAppWhenOneIsKnownToBeInstalled() {
        val message = TBoxConflictDiagnostics.userFacingMessage(
            "bind: address already in use on 10922",
            companionAppName = "Zontes Smart"
        )
        assertTrue(message.contains("Zontes Smart"))
        assertFalse(message.contains("CFMOTO"))
    }

    @Test
    fun fallsBackToTheBrandNeutralMessageWhenNoCompanionAppIsInstalled() {
        assertEquals(
            TBoxConflictDiagnostics.PORT_CONFLICT_MESSAGE,
            TBoxConflictDiagnostics.userFacingMessage(
                "bind: address already in use on 10922",
                companionAppName = null
            )
        )
        assertEquals(
            TBoxConflictDiagnostics.PORT_CONFLICT_MESSAGE,
            TBoxConflictDiagnostics.userFacingMessage(
                "bind: address already in use on 10922",
                companionAppName = "   "
            )
        )
    }

    /** A non-conflict failure must pass through untouched, named companion app or not. */
    @Test
    fun leavesUnrelatedFailuresAlone() {
        assertEquals(
            "connection timed out",
            TBoxConflictDiagnostics.userFacingMessage(
                "connection timed out",
                companionAppName = "Zontes Smart"
            )
        )
    }
}
