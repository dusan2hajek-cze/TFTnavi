// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Measures what the two halves of one MOTO-HUB pair actually frame a dashboard with, using the
 * same geometry the compositor and the Ride Dashboard use at runtime.
 *
 * The numbers here are not invented: they are rider 87bc5a7c's, read off one session on
 * 2026-08-25 with an 800x480 EasyConn panel, Core taught right=120 and the companion app taught
 * nothing.
 *
 *   21:38:41 (Core, Android Auto)     margins right=120  ->  viewport 680x408 @(0,36)
 *   21:42:00 (companion, Dashboard)   margins right=0    ->  area     800x480
 *
 * One dashboard, one session, four minutes apart, two framings.
 */
class ScreenMarginAgreementTest {

    /** The 800x480 EasyConn panel, and the 800x480 Android Auto source composited onto it. */
    private val panel = DisplayGeometry(800, 480)
    private val source = DisplayGeometry(800, 480)

    /**
     * What Core's compositor lays out, step for step with AaCompositor.configureTftViewport's
     * LETTERBOX branch: fit the source inside the panel minus the margins, then push it past the
     * left/top furniture. Calling the same `calculatePreviewViewport` it does rather than
     * restating the centring, so this measures that code instead of a copy of it.
     */
    private fun androidAutoViewport(margins: TBoxScreenMargins): PreviewViewport {
        val fitted = calculatePreviewViewport(margins.inset(panel), source)
        return fitted.copy(x = fitted.x + margins.left, y = fitted.y + margins.top)
    }

    /** What the Ride Dashboard renders into: the panel minus the same margins. */
    private fun dashboardArea(margins: TBoxScreenMargins): DisplayGeometry = margins.inset(panel)

    @Test
    fun `the measurement reproduces the rider's logged geometry`() {
        val core = androidAutoViewport(TAUGHT_IN_CORE)
        assertEquals(680, core.width)
        assertEquals(408, core.height)
        assertEquals(0, core.x)
        assertEquals(36, core.y)
        assertEquals(DisplayGeometry(800, 480), dashboardArea(TBoxScreenMargins.NONE))
    }

    /**
     * BEFORE. The teaching only ever travelled companion -> Core, and only when the companion had
     * one. A rider who measured in Core - the half that ships the ruler and owns Android Auto -
     * left the other half framing the same panel as if there were no furniture on it at all.
     */
    @Test
    fun `before the fix one dashboard got two framings`() {
        val coreUsed = agreedScreenMargins(taughtHere = TAUGHT_IN_CORE, taughtElsewhere = null)!!
        // The companion could not ask, so all it had was the model profile's guess.
        val companionUsed = GENERIC_PROFILE_DEFAULT

        assertNotEquals(coreUsed, companionUsed)
        assertEquals(DisplayGeometry(680, 480), coreUsed.inset(panel))
        assertEquals(DisplayGeometry(800, 480), companionUsed.inset(panel))
        // 120 columns of dashboard drawn where the panel's own furniture sits.
        assertEquals(120, companionUsed.inset(panel).width - coreUsed.inset(panel).width)
    }

    /**
     * AFTER. The companion adopts what Core was taught, so both halves inset the panel by the
     * same furniture and the Android Auto viewport lands inside the area the Dashboard uses.
     */
    @Test
    fun `after the fix both halves frame it identically`() {
        val coreUsed = agreedScreenMargins(taughtHere = TAUGHT_IN_CORE, taughtElsewhere = null)!!
        val companionUsed = agreedScreenMargins(taughtHere = null, taughtElsewhere = TAUGHT_IN_CORE)!!

        assertEquals(coreUsed, companionUsed)
        assertEquals(dashboardArea(companionUsed), coreUsed.inset(panel))
        val viewport = androidAutoViewport(companionUsed)
        assertEquals(680, viewport.width)
        assertEquals(408, viewport.height)
    }

    /**
     * The asymmetry is deliberate: a value measured in this app travels to the other half at
     * every session start, so the other half's copy can only be older. Adopting over it would
     * make the pair argue every session instead of agreeing once.
     */
    @Test
    fun `a teaching made here still wins over the other half's`() {
        val here = TBoxScreenMargins(right = 40)
        assertEquals(here, agreedScreenMargins(taughtHere = here, taughtElsewhere = TAUGHT_IN_CORE))
    }

    /**
     * The trap this whole boundary is built around: four zeros are something a rider can measure
     * on a panel with no furniture, so "never taught" cannot be encoded as zeros. It has to stay
     * null the whole way across, or a default silently overwrites a measurement.
     */
    @Test
    fun `never taught survives the trip as null and zeros survive as a teaching`() {
        assertNull(encodeScreenMargins(null))
        assertNull(decodeScreenMargins(null))
        val zeros = TBoxScreenMargins.NONE
        assertEquals(zeros, decodeScreenMargins(encodeScreenMargins(zeros)))
        assertEquals(TAUGHT_IN_CORE, decodeScreenMargins(encodeScreenMargins(TAUGHT_IN_CORE)))
    }

    @Test
    fun `an unreadable answer is nothing taught, never a guess`() {
        assertNull(decodeScreenMargins(""))
        assertNull(decodeScreenMargins("0,0,0"))
        assertNull(decodeScreenMargins("0,0,0,x"))
        assertNull(decodeScreenMargins("0,0,0,-1"))
        assertNull(decodeScreenMargins("0,0,0,${TBoxScreenMargins.MAX + 1}"))
    }

    /** Neither half taught: the caller falls back to the model profile, not to a fake teaching. */
    @Test
    fun `nothing taught anywhere stays nothing taught`() {
        assertNull(agreedScreenMargins(taughtHere = null, taughtElsewhere = null))
    }

    /**
     * The trap adoption creates, and the reason provenance is recorded at all.
     *
     * An adopted copy is byte-for-byte a local teaching once written. Sent back at the next
     * session start - which is what this app does with every margin it holds - it would overwrite
     * whatever the rider measured in the other half since. The bug reads as "I fixed the framing
     * in MOTO-HUB and it came back", once per session, forever.
     */
    @Test
    fun `an adopted copy is never sent back to the half it came from`() {
        assertNull(screenMarginsToPush(stored = TAUGHT_IN_CORE, adoptedFromTheOtherHalf = true))
    }

    @Test
    fun `a measurement made here is sent`() {
        val here = TBoxScreenMargins(right = 40)
        assertEquals(here, screenMarginsToPush(stored = here, adoptedFromTheOtherHalf = false))
        assertNull(screenMarginsToPush(stored = null, adoptedFromTheOtherHalf = false))
    }

    /**
     * Adopting must not freeze either. Once the copy is marked as adopted it stops out-ranking
     * the other half, so a rider who re-measures over there gets their new value next session
     * instead of being outvoted by the copy this app took months ago.
     */
    @Test
    fun `a re-measurement in the other half wins over a copy adopted earlier`() {
        val adoptedEarlier = TAUGHT_IN_CORE
        val remeasuredThere = TBoxScreenMargins(right = 80)
        val taughtHere = screenMarginsToPush(adoptedEarlier, adoptedFromTheOtherHalf = true)

        assertEquals(remeasuredThere, agreedScreenMargins(taughtHere, remeasuredThere))
    }

    private companion object {
        /** Rider 87bc5a7c's Core-side teaching for EASYCONN_5G-1813BC. */
        val TAUGHT_IN_CORE = TBoxScreenMargins(right = 120)

        /** What GENERIC offers when nobody measured anything - the log's "profile default". */
        val GENERIC_PROFILE_DEFAULT = TBoxScreenMargins.NONE
    }
}
