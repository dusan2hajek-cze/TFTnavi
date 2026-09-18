// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentryDiagnosticBackoffTest {
    @Test
    fun reportsTheFirstOccurrence() {
        // A report that happens once must never be swallowed: it is the only copy there is.
        assertTrue(isDiagnosticBackoffMilestone(1))
    }

    @Test
    fun reportsThePowersOfTwo() {
        listOf(1, 2, 4, 8, 16, 32, 64, 1024).forEach { occurrence ->
            assertTrue("occurrence $occurrence should be reported", isDiagnosticBackoffMilestone(occurrence))
        }
    }

    @Test
    fun swallowsEverythingBetween() {
        listOf(3, 5, 6, 7, 9, 15, 17, 63, 100).forEach { occurrence ->
            assertFalse("occurrence $occurrence should be swallowed", isDiagnosticBackoffMilestone(occurrence))
        }
    }

    @Test
    fun aRideLongRetryStormCostsSevenEvents() {
        // What the fleet actually does: "T-Box AP connection failed" once per retry, all ride.
        // Before the backoff this was 100 events from one phone, and 8163 across the riders.
        val reported = (1..100).count(::isDiagnosticBackoffMilestone)
        assertEquals(7, reported)
    }

    @Test
    fun ignoresNonPositiveCounts() {
        // Defensive: an overflowed or unset counter must not open the gate.
        assertFalse(isDiagnosticBackoffMilestone(0))
        assertFalse(isDiagnosticBackoffMilestone(-8))
    }
}
