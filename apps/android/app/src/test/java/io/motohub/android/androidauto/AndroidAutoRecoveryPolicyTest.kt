// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAutoRecoveryPolicyTest {
    @Test
    fun `recovery requires both an enabled preference and prior streaming`() {
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = false, enabled = false))
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = false, enabled = true))
        assertFalse(shouldAutoRecoverAndroidAuto(hasReachedStreaming = true, enabled = false))
        assertTrue(shouldAutoRecoverAndroidAuto(hasReachedStreaming = true, enabled = true))
    }

    @Test
    fun `the refusal names the switch once a session has actually streamed`() {
        assertEquals(
            "automatic reconnection is switched off in this app.",
            androidAutoRecoveryRefusal(hasReachedStreaming = true, enabled = false)
        )
    }

    /**
     * Both reasons are true here. The one that is worth telling a rider is the one they could have
     * acted on, and a session that never streamed had nothing to come back to.
     */
    @Test
    fun `a session that never streamed says so rather than blaming the switch`() {
        assertEquals(
            "this session never reached streaming.",
            androidAutoRecoveryRefusal(hasReachedStreaming = false, enabled = false)
        )
        assertEquals(
            "this session never reached streaming.",
            androidAutoRecoveryRefusal(hasReachedStreaming = false, enabled = true)
        )
    }

    @Test
    fun `there is no refusal to report when recovery is going to run`() {
        assertNull(androidAutoRecoveryRefusal(hasReachedStreaming = true, enabled = true))
    }

    /**
     * The guard that carries the whole mirror: a companion built before the field sends `false`,
     * which is also the value meaning "do not reconnect". Reading it would switch recovery off for
     * a rider who enabled it in Core - the same class of fault the field exists to fix, arriving
     * from the other direction.
     */
    @Test
    fun `a companion that predates the field leaves Core's own switch alone`() {
        assertNull(companionAutoRecovery(provided = false, value = false))
        assertNull(companionAutoRecovery(provided = false, value = true))
    }

    @Test
    fun `a companion that carries the field decides for the session Core runs`() {
        assertEquals(false, companionAutoRecovery(provided = true, value = false))
        assertEquals(true, companionAutoRecovery(provided = true, value = true))
    }

    @Test
    fun `watchdog waits until the complete stall threshold`() {
        assertFalse(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 19_999L,
                lastProgressElapsed = 10_000L,
                thresholdMillis = 10_000L
            )
        )
        assertTrue(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 20_000L,
                lastProgressElapsed = 10_000L,
                thresholdMillis = 10_000L
            )
        )
    }

    @Test
    fun `watchdog ignores an uninitialized progress clock`() {
        assertFalse(
            isAndroidAutoWatchdogStalled(
                nowElapsed = 30_000L,
                lastProgressElapsed = 0L,
                thresholdMillis = 10_000L
            )
        )
    }
}
