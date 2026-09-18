// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TBoxHotspotScanTest {

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address

    private fun snapshot(
        name: String,
        address: String,
        prefixLength: Int = 24,
        isUp: Boolean = true,
        isLoopback: Boolean = false,
        isPointToPoint: Boolean = false
    ) = TBoxHotspotScan.InterfaceSnapshot(
        name = name,
        isUp = isUp,
        isLoopback = isLoopback,
        isPointToPoint = isPointToPoint,
        addresses = listOf(ipv4(address) as InetAddress to prefixLength)
    )

    @Test
    fun findsTheTetheringSubnetAndIgnoresTheNetworkThePhoneIsUsing() {
        // The realistic shape: the rider's phone is on their home Wi-Fi AND hosting the hotspot
        // the dash joins. Sweeping the home subnet would be slow and would probe other people's
        // devices, so the joined network's own address is excluded by the caller.
        val subnets = TBoxHotspotScan.tetheringSubnets(
            interfaces = listOf(
                snapshot("lo", "127.0.0.1", isLoopback = true),
                snapshot("wlan0", "192.168.1.34"),
                snapshot("ap0", "192.168.43.1")
            ),
            excluding = setOf(ipv4("192.168.1.34"))
        )

        assertEquals(1, subnets.size)
        assertEquals(ipv4("192.168.43.1"), subnets.first().localAddress)
        assertEquals("ap0", subnets.first().interfaceName)
    }

    @Test
    fun prefersTheInterfaceThatLooksLikeAHostedNetwork() {
        // Unknown names are still accepted - vendors rename these - but ranked last, so the
        // likeliest candidate is swept first rather than after 254 wasted connects.
        val subnets = TBoxHotspotScan.tetheringSubnets(
            listOf(
                snapshot("eth7", "10.5.5.1"),
                snapshot("swlan0", "192.168.43.1")
            )
        )

        assertEquals(listOf("swlan0", "eth7"), subnets.map { it.interfaceName })
    }

    @Test
    fun ranksAPeerToPeerLinkAboveAnUnknownNameAndBelowARealSoftAp() {
        // The 2026-08-06 field log: p2p0, wlan0 and wlan2 all qualified at once and all tied at
        // the bottom rank, so the winner was whatever the kernel enumerated first - p2p0 reached
        // the dash in 114ms, the other two swept 253 addresses for 45s and found nothing. Every
        // rank has to be distinct or the sort decides nothing.
        val subnets = TBoxHotspotScan.tetheringSubnets(
            listOf(
                snapshot("wlan0", "192.168.1.34"),
                snapshot("p2p0", "192.168.49.1"),
                snapshot("wlan2", "192.168.2.7"),
                snapshot("ap0", "192.168.43.1")
            )
        )

        assertEquals(listOf("ap0", "p2p0", "wlan0", "wlan2"), subnets.map { it.interfaceName })
    }

    @Test
    fun keepsAHostedInterfaceEvenWhenItLooksLikeANetworkThePhoneIsUsing() {
        // The exclusion exists to drop the phone's uplink, never a plausible SoftAP. If an OEM
        // ever surfaced its own tethering interface as a visible network, excluding by address
        // alone would delete the only correct answer - so a hosted NAME overrides the exclusion.
        val subnets = TBoxHotspotScan.tetheringSubnets(
            interfaces = listOf(
                snapshot("wlan0", "192.168.1.34"),
                snapshot("ap0", "192.168.43.1")
            ),
            excluding = setOf(ipv4("192.168.1.34"), ipv4("192.168.43.1"))
        )

        assertEquals(listOf("ap0"), subnets.map { it.interfaceName })
    }

    @Test
    fun keepsAPeerLinkEvenWhenTheStackReportsItAsANetworkInUse() {
        // Ranking p2p0 above an unknown name says a Wi-Fi Direct group is a plausible place to
        // find the dash - it is why PEER_LINK_PREFIXES exists, after that interface reached the
        // dash in 114ms while wlan0 and wlan2 swept 253 addresses for nothing. An exclusion that
        // deleted it anyway would leave the rank describing a candidate that can never be chosen.
        // On a P2P group the phone is the group owner and the dash its only client, so this is a
        // network the phone hosts.
        val subnets = TBoxHotspotScan.tetheringSubnets(
            interfaces = listOf(snapshot("p2p0", "192.168.49.1")),
            excluding = setOf(ipv4("192.168.49.1"))
        )

        assertEquals(listOf("p2p0"), subnets.map { it.interfaceName })
    }

    @Test
    fun dropsAnUnrecognisedInterfaceThatIsActuallyANetworkThePhoneIsUsing() {
        // The immunity is still narrow: it is granted by NAME, to the shapes Android uses for a
        // network it hosts. An OEM name nobody recognises, reported by the stack as in use, is the
        // rider's own link - the wlan2 of the 2026-08-06 log - and sweeping it wastes 45s.
        val subnets = TBoxHotspotScan.tetheringSubnets(
            interfaces = listOf(snapshot("wlan2", "192.168.2.7")),
            excluding = setOf(ipv4("192.168.2.7"))
        )

        assertTrue(subnets.map { it.interfaceName }.toString(), subnets.isEmpty())
    }

    @Test
    fun rejectsInterfacesThatCannotBeAHostedNetwork() {
        val subnets = TBoxHotspotScan.tetheringSubnets(
            listOf(
                snapshot("ap0", "192.168.43.1", isUp = false),
                snapshot("wlan1", "169.254.4.4"),
                snapshot("rmnet0", "100.65.1.20", isPointToPoint = true),
                // Routable address: that is the carrier's, never a hotspot.
                snapshot("eth0", "93.184.216.34"),
                // Wider than a /24 would be thousands of addresses; not sweepable.
                snapshot("ap1", "10.0.0.1", prefixLength = 16)
            )
        )

        assertTrue(subnets.map { it.interfaceName }.toString(), subnets.isEmpty())
    }

    @Test
    fun enumeratesEveryHostExceptTheNetworkBroadcastAndThePhoneItself() {
        val hosts = TBoxHotspotScan.candidateHosts(
            TBoxHotspotScan.Subnet(ipv4("192.168.43.1"), prefixLength = 24, interfaceName = "ap0")
        )

        assertEquals(253, hosts.size)
        assertTrue(ipv4("192.168.43.0") !in hosts)
        assertTrue(ipv4("192.168.43.255") !in hosts)
        assertTrue(ipv4("192.168.43.1") !in hosts)
        assertTrue(ipv4("192.168.43.2") in hosts)
        assertTrue(ipv4("192.168.43.254") in hosts)
    }

    @Test
    fun triesAddressesNearestThePhoneFirst() {
        // Android's DHCP pool starts just above the gateway, so the dash is almost always in the
        // first handful. Sweeping in numeric order would find it just as reliably but far slower.
        val hosts = TBoxHotspotScan.candidateHosts(
            TBoxHotspotScan.Subnet(ipv4("192.168.43.1"), prefixLength = 24, interfaceName = "ap0")
        )

        assertEquals(ipv4("192.168.43.2"), hosts.first())
        assertTrue(hosts.take(6).contains(ipv4("192.168.43.3")))
        assertTrue(hosts.indexOf(ipv4("192.168.43.200")) > 100)
    }

    @Test
    fun handlesASmallSubnetWithoutRunningOffTheEnd() {
        val hosts = TBoxHotspotScan.candidateHosts(
            TBoxHotspotScan.Subnet(ipv4("192.168.49.1"), prefixLength = 29, interfaceName = "ap0")
        )

        // /29 is 8 addresses: minus network, broadcast and the phone itself.
        assertEquals(5, hosts.size)
        assertTrue(hosts.all { it.hostAddress!!.startsWith("192.168.49.") })
    }

    @Test
    fun readsTheDashOutOfTheNeighbourTableAndIgnoresTheRidersOtherNetworks() {
        val neighbours = TBoxHotspotScan.parseNeighbours(
            lines = listOf(
                "IP address       HW type     Flags       HW address            Mask     Device",
                "192.168.43.37    0x1         0x2         3c:2e:f5:11:22:33     *        ap_br_swlan0",
                // The rider's home network, on the interface the phone joined: not on the hotspot.
                "192.168.1.1      0x1         0x2         aa:bb:cc:dd:ee:ff     *        wlan0"
            ),
            interfaceName = "ap_br_swlan0"
        )

        assertEquals(1, neighbours?.size)
        assertEquals("192.168.43.37", neighbours?.first()?.address)
        // The vendor half only: a full hardware address is redacted out of every shared log.
        assertEquals("3c:2e:f5", neighbours?.first()?.oui)
    }

    @Test
    fun doesNotCountAnUnansweredArpRequestAsADeviceThatJoined() {
        // Exactly what the 253-address sweep manufactures: incomplete entries, no hardware
        // address behind them. Counting those would report a full hotspot every time.
        val neighbours = TBoxHotspotScan.parseNeighbours(
            lines = listOf(
                "IP address       HW type     Flags       HW address            Mask     Device",
                "192.168.43.9     0x1         0x0         00:00:00:00:00:00     *        ap_br_swlan0"
            ),
            interfaceName = "ap_br_swlan0"
        )

        assertEquals(emptyList<TBoxHotspotScan.Neighbour>(), neighbours)
    }

    @Test
    fun tellsAnUnreadableTableApartFromAnEmptyOne() {
        // Android has been restricting /proc/net/arp since 10. "Cannot say" must never be
        // reported as "nothing has joined": they point at opposite problems.
        assertEquals(null, TBoxHotspotScan.parseNeighbours(lines = emptyList()))
        assertEquals(
            emptyList<TBoxHotspotScan.Neighbour>(),
            TBoxHotspotScan.parseNeighbours(
                lines = listOf("IP address       HW type     Flags       HW address            Mask     Device")
            )
        )
    }
}
