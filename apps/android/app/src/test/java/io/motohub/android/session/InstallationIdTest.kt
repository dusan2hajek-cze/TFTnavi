// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallationIdTest {
    @Test
    fun `the same material always derives the same id`() {
        assertEquals(
            InstallationId.derive("salt", "abc|profile-1"),
            InstallationId.derive("salt", "abc|profile-1")
        )
    }

    @Test
    fun `salt, phone and motorcycle each change the id`() {
        val base = InstallationId.derive("salt", "abc|profile-1")
        assertNotEquals(base, InstallationId.derive("other", "abc|profile-1"))
        assertNotEquals(base, InstallationId.derive("salt", "abd|profile-1"))
        assertNotEquals(base, InstallationId.derive("salt", "abc|profile-2"))
    }

    @Test
    fun `the id has the version-5 uuid layout`() {
        val id = InstallationId.derive("salt", "material")
        assertTrue(id, Regex("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id))
    }

    @Test
    fun `the short form is twelve grouped upper-case hex digits`() {
        assertEquals("A1B2-C3D4-E5F6", InstallationId.shortForm("a1b2c3d4-e5f6-5789-8abc-def012345678"))
    }
}
