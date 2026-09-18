// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import io.motohub.android.session.MotorcycleProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The garage must recognise a network name the same way the connector does.
 *
 * TBoxNetworkConnector matches a scan result with `equalsIgnoreCase`. While the garage matched
 * exactly, one dash could become two motorcycles - and the second was created bare, so it resolved
 * to the GENERIC EasyConn profile, which on a ThinkerRide dash is broken rather than merely
 * approximate. Rider 2e3b10d2 carries both entries for one KOVE.
 */
class GarageSsidMatchTest {

    private val kove = MotorcycleProfile(
        ssid = "CQKY_54e8196f2",
        password = "secret",
        modelId = "THINKERRIDE",
        profileOverrideKey = "kove_800x"
    )
    private val other = MotorcycleProfile(ssid = "ZT_e0082100e5ff_3", password = "secret")
    private val garage = listOf(other, kove)

    @Test
    fun findsTheSameMotorcycleWhateverTheCase() {
        assertEquals(kove, garage.bySsidIgnoringCase("cqky_54e8196f2"))
        assertEquals(kove, garage.bySsidIgnoringCase("CQKY_54E8196F2"))
        assertEquals(kove, garage.bySsidIgnoringCase("CQKY_54e8196f2"))
    }

    /** Case is the only thing relaxed: a different network is still a different motorcycle. */
    @Test
    fun doesNotMatchADifferentNetwork() {
        assertNull(garage.bySsidIgnoringCase("CQKY_54e8196f3"))
        assertNull(garage.bySsidIgnoringCase("CQKY_54e8196f"))
        assertNull(garage.bySsidIgnoringCase(""))
    }

    @Test
    fun findsNothingInAnEmptyGarage() {
        assertNull(emptyList<MotorcycleProfile>().bySsidIgnoringCase("CQKY_54e8196f2"))
    }
}
