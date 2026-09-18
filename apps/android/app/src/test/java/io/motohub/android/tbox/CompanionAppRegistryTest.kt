// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The registry's Context-bound half needs a real PackageManager, so what is checkable here is the
 * table itself - and that is where the bug lived: one hard-coded package, so a Zontes rider's
 * holder was invisible.
 */
class CompanionAppRegistryTest {
    @Test
    fun coversTheBrandsWhoseCompanionAppHasBeenObserved() {
        val packages = CompanionAppRegistry.known.map { it.packageName }
        assertTrue(packages.contains("com.cfmoto.cfmotointernational"))
        assertTrue(packages.contains("tayo.com.ZontesIntelligence"))
        assertTrue(packages.contains("net.easyconn.carman"))
    }

    /**
     * A package listed here but missing from the manifest's <queries> block is worse than not
     * listing it at all: getPackageInfo() then reports "not installed" for an app that is, and the
     * rider silently loses the only help this path offers.
     */
    @Test
    fun everyKnownPackageIsDeclaredForPackageVisibility() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        CompanionAppRegistry.known.forEach { app ->
            assertTrue(
                "${app.packageName} is missing from the manifest <queries> block",
                manifest.contains("<package android:name=\"${app.packageName}\" />")
            )
        }
    }

    @Test
    fun namesAreUniqueSoTheBannerCannotReadAmbiguously() {
        val packages = CompanionAppRegistry.known.map { it.packageName }
        assertEquals(packages.size, packages.toSet().size)
    }
}
