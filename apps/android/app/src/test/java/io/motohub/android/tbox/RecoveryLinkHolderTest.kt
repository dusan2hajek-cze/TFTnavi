// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that lets a watchdog recovery reuse the network it is recovering.
 *
 * Covers the bookkeeping only, like [TBoxSessionRegistryClaimTest]: the registry around it needs a
 * Context and stays outside unit tests.
 */
class RecoveryLinkHolderTest {
    private val holder = RecoveryLinkHolder()
    private var released = false
    private val link: TBoxLink = TBoxLink.WifiDirect(
        bindIp = InetAddress.getByName("192.168.49.2") as Inet4Address,
        gatewayIp = InetAddress.getByName("192.168.49.1") as Inet4Address,
        leaveGroup = { released = true }
    )

    @Test
    fun `a link handed to the recovered session is never released`() {
        holder.retain(link)

        holder.handOver()

        assertNull("the new handle owns the group now", holder.takeUnclaimed())
        assertFalse("releasing here would tear down the group just adopted", released)
    }

    @Test
    fun `a recovery that never installed leaves the link to be released`() {
        holder.retain(link)

        val unclaimed = holder.takeUnclaimed()

        assertSame(link, unclaimed)
        unclaimed?.disconnect()
        assertTrue("nothing took the link over, so the group must go back", released)
    }

    @Test
    fun `the link is handed out once, so a second teardown cannot release it twice`() {
        holder.retain(link)

        holder.takeUnclaimed()

        assertNull(holder.takeUnclaimed())
    }

    @Test
    fun `an ordinary teardown has no link to release`() {
        assertNull(holder.takeUnclaimed())
    }

    @Test
    fun `only the newest recovery's link is kept`() {
        var secondReleased = false
        val second: TBoxLink = TBoxLink.WifiDirect(
            bindIp = InetAddress.getByName("192.168.49.3") as Inet4Address,
            gatewayIp = InetAddress.getByName("192.168.49.1") as Inet4Address,
            leaveGroup = { secondReleased = true }
        )
        holder.retain(link)

        holder.retain(second)

        assertSame(second, holder.takeUnclaimed())
        assertFalse(secondReleased)
    }
}
