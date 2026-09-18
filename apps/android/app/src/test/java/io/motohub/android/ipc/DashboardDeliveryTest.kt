// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardDeliveryTest {

    @Test
    fun itReadsWhatCoreWrites() {
        val parsed = DashboardDelivery.parse("failing|132|18|generic|ML155083")
        assertNotNull(parsed)
        assertFalse(parsed!!.healthy)
        assertEquals(132, parsed.rejected)
        assertEquals(18, parsed.accepted)
        assertEquals("generic", parsed.profileKey)
        assertEquals("ML155083", parsed.ssid)
        assertEquals(0.88, parsed.rejectedShare, 0.01)
    }

    @Test
    fun aNetworkNameMayContainTheSeparatorAndStillArriveWhole() {
        // The reason the name goes last. Nothing stops a dashboard from broadcasting this.
        val parsed = DashboardDelivery.parse("failing|100|10|morini_xcape_1200|Vince|s X-Cape|1200")
        assertNotNull(parsed)
        assertEquals("Vince|s X-Cape|1200", parsed!!.ssid)
        assertEquals("morini_xcape_1200", parsed.profileKey)
        assertEquals(100, parsed.rejected)
    }

    @Test
    fun anythingItCannotReadExactlyIsNoAnswerAtAll() {
        // This value only ever produces an offer to the rider; a half-parsed one would produce a
        // confident sentence built on a count that silently became zero.
        listOf(
            "",
            "failing|132|18|generic",
            "failing|132|18",
            "132|18|generic|ML155083",
            "maybe|132|18|generic|ML155083",
            "|132|18|generic|ML155083",
            "failing||18|generic|ML155083",
            "failing|132|x|generic|ML155083",
            "failing|132|18||ML155083",
            "failing|132|18|generic|",
            "failing|-1|18|generic|ML155083",
            "failing|132|-4|generic|ML155083",
            "not a report at all"
        ).forEach { wire ->
            assertNull("\"$wire\" must not parse", DashboardDelivery.parse(wire))
        }
    }

    @Test
    fun aHealthySessionsNumbersCarryNoAlarm() {
        // The same dash on the right profile. Core has already decided; parsing carries the
        // verdict across rather than re-deriving it from the numbers.
        val parsed = DashboardDelivery.parse("ok|9|140|morini_xcape_1200|ML155083")!!
        assertEquals(0.06, parsed.rejectedShare, 0.01)
        assertTrue(parsed.healthy)
    }

    @Test
    fun aReportWithNoFramesAtAllDoesNotDivideByZero() {
        val parsed = DashboardDelivery.parse("ok|0|0|generic|ML155083")!!
        assertEquals(0.0, parsed.rejectedShare, 0.0)
    }
}
