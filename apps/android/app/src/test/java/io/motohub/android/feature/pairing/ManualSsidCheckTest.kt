// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The check that would have saved rider 168b97bb forty minutes - see [manualSsidVerdict].
 *
 * He typed `CFMOTO 6627` into manual pairing for a dash that broadcasts `CFMOTO6627`, on a phone
 * that already had a working profile for it and had seen it in a scan minutes earlier.
 */
class ManualSsidCheckTest {

    private val air = listOf("Sztik", "CFMOTO6627", "eduroam")

    @Test
    fun `the space rider 168b97bb typed is caught by the motorcycle he already had`() {
        assertEquals(
            ManualSsidVerdict.DidYouMean("CFMOTO6627", ManualSsidVerdict.DidYouMean.Source.SAVED_MOTORCYCLE),
            manualSsidVerdict("CFMOTO 6627", savedSsids = listOf("CFMOTO6627"), visibleSsids = air)
        )
    }

    @Test
    fun `a first pairing has no saved motorcycle, so the air answers instead`() {
        // The same typo on a phone with nothing saved yet: the scan is the only witness left, and
        // it is enough.
        assertEquals(
            ManualSsidVerdict.DidYouMean("CFMOTO6627", ManualSsidVerdict.DidYouMean.Source.ON_THE_AIR),
            manualSsidVerdict("CFMOTO 6627", savedSsids = emptyList(), visibleSsids = air)
        )
    }

    @Test
    fun `the other shapes a name collects when it is copied by eye`() {
        val saved = listOf("DIRECT-CF-CFMOTO-820082")
        for (typed in listOf(
            "DIRECT CF CFMOTO 820082",
            "DIRECT_CF_CFMOTO_820082",
            "DIRECT-CF-CFMOTO 820082",
            "DIRECTCFCFMOTO820082"
        )) {
            assertEquals(
                typed,
                ManualSsidVerdict.DidYouMean(saved[0], ManualSsidVerdict.DidYouMean.Source.SAVED_MOTORCYCLE),
                manualSsidVerdict(typed, savedSsids = saved, visibleSsids = emptyList())
            )
        }
    }

    @Test
    fun `trimming and case are settled before any of this, and stay settled`() {
        // "direct-cf-cfmoto-820082 " is the same name, and saveMotorcycle already keeps the
        // stored spelling for it. Reaching a suggestion here would ask a rider to confirm a name
        // that was never going to be stored differently - and questions nobody needs are how
        // riders learn to dismiss the one that matters.
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict(
                "direct-cf-cfmoto-820082 ",
                savedSsids = listOf("DIRECT-CF-CFMOTO-820082"),
                visibleSsids = emptyList()
            )
        )
    }

    @Test
    fun `a dash whose name really does contain a space can still be entered`() {
        // The point of not correcting anything by itself. Once that name is saved or on the air,
        // it is an exact match and nothing is asked - including on every later edit of it.
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict("MY BIKE 12", savedSsids = listOf("MY BIKE 12"), visibleSsids = emptyList())
        )
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict("MY BIKE 12", savedSsids = emptyList(), visibleSsids = listOf("MY BIKE 12"))
        )
    }

    @Test
    fun `an exact match outranks a near miss elsewhere`() {
        // Two dashes whose names differ only by noise is not a hypothesis worth breaking: the one
        // typed exactly is the one meant.
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict(
                "CFMOTO 6627",
                savedSsids = listOf("CFMOTO6627"),
                visibleSsids = listOf("CFMOTO 6627")
            )
        )
    }

    @Test
    fun `a dash this phone has never heard of is accepted in silence`() {
        // Why the screen exists: a dash that is switched off, or on a channel this phone's
        // regulatory domain forbids, is in no scan and in no profile. Rejecting it would close
        // the only door those riders have.
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict("VOGE-5G-9fab", savedSsids = listOf("CFMOTO6627"), visibleSsids = air)
        )
    }

    @Test
    fun `an empty scan says nothing, exactly as everywhere else in this app`() {
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict("CFMOTO 6627", savedSsids = emptyList(), visibleSsids = emptyList())
        )
    }

    @Test
    fun `case alone never reaches a suggestion, because saving already handles it`() {
        // bySsidIgnoringCase keeps the stored spelling for this, and a question about a name that
        // is about to be resolved anyway would only teach riders to dismiss the question.
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict("cfmoto6627", savedSsids = listOf("CFMOTO6627"), visibleSsids = emptyList())
        )
    }

    @Test
    fun `a name with nothing to compare on is not matched against every other one`() {
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict("---", savedSsids = listOf("___"), visibleSsids = listOf("   -"))
        )
    }

    @Test
    fun `an empty name is left to the emptiness check that already rejects it`() {
        assertEquals(
            ManualSsidVerdict.Accepted,
            manualSsidVerdict("   ", savedSsids = listOf("CFMOTO6627"), visibleSsids = air)
        )
    }
}
