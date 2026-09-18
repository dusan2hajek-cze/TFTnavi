// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * Finding the dash when the *phone* hosts the network.
 *
 * In [io.motohub.android.session.TBoxConnectionMode.PHONE_HOTSPOT] every assumption the other two
 * transports rest on is inverted: there is no dash access point to join, no `Network` handed over
 * by `ConnectivityManager` for the tethering interface, and no fixed `.1` gateway to aim a probe
 * at - the phone is the gateway and the dash is just some DHCP client on the subnet.
 *
 * The address arithmetic lives here, apart from the sockets, because it is the half that can be
 * tested without a motorcycle. See `TBoxHotspotScanTest`.
 */
object TBoxHotspotScan {

    /**
     * Every IPv4 address on a network this phone is using as an **uplink** - its Wi-Fi, its
     * mobile link, a VPN. Excluding those is what stops a rider's home subnet from being swept.
     *
     * Networks Android marks `LOCAL_NETWORK` are deliberately kept out of the set, and that
     * exception is load-bearing. The original version assumed a hosted hotspot is never surfaced
     * to apps as a [android.net.Network] at all — true when it was written, and false now.
     * Measured on a OnePlus CPH2653 on 2026-08-09 with tethering on: Android reports the SoftAP
     * interface `wlan2` as a full `NetworkAgentInfo`, `LinkAddresses: [10.181.20.114/24]`,
     * carrying the newer `LOCAL_NETWORK` capability. Its address therefore landed in this set,
     * [tetheringSubnets] dropped the only correct interface, and every caller concluded no
     * hotspot was running — the group intercom called the hosting phone a guest, and the T-Box's
     * `PHONE_HOTSPOT` mode would have told the rider to turn on a hotspot that was already on.
     * `isHostedName` grants immunity too, but only to names it recognises, and `wlan2` is not
     * one of them.
     *
     * The first attempt at this filtered on `INTERNET` instead — "a network the phone hosts
     * gives it no internet" — and that was wrong in a way worth recording, because it looked
     * more principled than it was. Carrier IMS/MMS APNs have no `INTERNET` either, so
     * `rmnet_data3`, a 30-bit carrier link, stopped being excluded, became a candidate, and won
     * the ranking tie against `wlan2` purely by enumeration order. The host then bound its
     * listener to the cellular interface, where no guest could ever reach it. `LOCAL_NETWORK`
     * says what is actually meant; absence of internet merely correlates with it.
     *
     * Best-effort by design. If the query fails or comes back empty the scan simply runs
     * unfiltered, which is what it did before this existed.
     */
    // getAllNetworks() is deprecated with no synchronous replacement: the sanctioned API is a
    // registered NetworkCallback, which answers a question this code asks once, on demand, at the
    // start of a connect. activeNetwork alone is not enough - behind a VPN it *is* the VPN, and
    // the Wi-Fi whose subnet must not be swept stops being reported at all.
    @Suppress("DEPRECATION")
    fun addressesInUse(context: Context): Set<InetAddress> =
        runCatching {
            val connectivityManager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            connectivityManager.allNetworks
                .filterNot { network -> isLocalNetwork(connectivityManager, network) }
                .mapNotNull { network -> connectivityManager.getLinkProperties(network) }
                .flatMap { properties -> properties.linkAddresses }
                .map { linkAddress -> linkAddress.address }
                .filterIsInstance<Inet4Address>()
                .toSet()
        }.getOrDefault(emptySet())

    /**
     * Whether Android considers this a local network rather than one of the phone's uplinks.
     *
     * Guarded because the capability is recent (API 36). On a platform that does not know the
     * value `hasCapability` can reject it outright, and on one that never surfaces tethering as
     * a network the question does not arise: either way, "not local" is the answer that leaves
     * the old behaviour intact.
     */
    private fun isLocalNetwork(manager: ConnectivityManager, network: Network): Boolean =
        runCatching {
            manager.getNetworkCapabilities(network)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_LOCAL_NETWORK) == true
        }.getOrDefault(false)

    /**
     * Interface-name prefixes Android has used for the tethering/SoftAP side. The list is
     * corroboration, not a gate: [tetheringSubnets] accepts any interface that looks like a
     * hosted network, because vendors rename these freely and a missed name would cost the
     * whole feature. Names decide ordering, so the likeliest candidate is swept first - and
     * they decide one thing more, see [isHostedName].
     */
    private val LIKELY_AP_PREFIXES = listOf("ap", "swlan", "wlan1", "softap", "rndis", "tether")

    /**
     * Ranked below a real SoftAP interface but above an unrecognised name.
     *
     * A Wi-Fi Direct group is not a hotspot, yet it produces the same shape - the phone holding a
     * private /24 with the dash as the only other host - and several EasyConn dashes reach the
     * phone that way while the rider's hotspot is switched off entirely. Field log 2026-08-06
     * (OnePlus CPH2653, EASYCONN_5G-F3116E): three interfaces qualified at once (`p2p0`, `wlan0`,
     * `wlan2`), all tied at the bottom rank, so a stable sort left the choice to kernel
     * enumeration order - `p2p0` found the dash in 114ms, `wlan0` and `wlan2` swept 253 addresses
     * for 45s and found nothing. Same phone, same bike, opposite outcomes, decided by luck.
     */
    private val PEER_LINK_PREFIXES = listOf("p2p")

    /**
     * Whether a name is one Android uses for a network it *hosts*, as opposed to one it joined.
     *
     * This is the one place a name is more than a hint: [tetheringSubnets] refuses to drop such an
     * interface for being "in use". Excluding the phone's own uplink is what stops a rider's home
     * subnet from being swept, but if some OEM ever surfaced its SoftAP as a visible network too,
     * the same exclusion would delete the only correct answer. Immunity by name makes that
     * regression impossible rather than unlikely.
     *
     * Peer links earn the same immunity, and it took a contradiction to see why. Ranking `p2p0`
     * above an unknown name says a Wi-Fi Direct group is a plausible place to find the dash - it
     * is the reason [PEER_LINK_PREFIXES] exists at all, after that interface reached the dash in
     * 114ms. Letting the exclusion delete it anyway would leave the rank describing a candidate
     * that can no longer be chosen. Which way to resolve that is settled by who holds the subnet:
     * on a P2P group the phone is the group owner and the dash is its only client, so this is a
     * network the phone hosts in every sense the sweep cares about.
     */
    private fun isHostedName(interfaceName: String): Boolean {
        val lowered = interfaceName.lowercase()
        return LIKELY_AP_PREFIXES.any(lowered::startsWith) || PEER_LINK_PREFIXES.any(lowered::startsWith)
    }

    /** A hosted IPv4 subnet: the phone's own address on it, and how wide it is. */
    data class Subnet(val localAddress: Inet4Address, val prefixLength: Int, val interfaceName: String)

    /**
     * A snapshot of one interface, so enumeration can be tested without a device.
     * [addresses] pairs each address with its prefix length, matching `InterfaceAddress`.
     */
    data class InterfaceSnapshot(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val isPointToPoint: Boolean,
        val addresses: List<Pair<InetAddress, Int>>
    )

    /**
     * Picks the interfaces that look like a hotspot the phone is hosting.
     *
     * [excluding] is the address of the network the phone is *using* (its own Wi-Fi or mobile
     * link). Without it a phone that is both joined to a home network and hosting a hotspot would
     * have its home subnet swept too - slow, and it would probe strangers' devices.
     *
     * A /24 is the practical ceiling: Android hands out a /24 for tethering, and anything wider
     * would be a sweep of thousands of addresses that cannot finish inside a pairing attempt.
     */
    fun tetheringSubnets(
        interfaces: List<InterfaceSnapshot>,
        excluding: Set<InetAddress> = emptySet()
    ): List<Subnet> =
        interfaces
            .filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { candidate ->
                candidate.addresses.mapNotNull { (address, prefixLength) ->
                    val ipv4 = address as? Inet4Address ?: return@mapNotNull null
                    if (ipv4 in excluding && !isHostedName(candidate.name)) return@mapNotNull null
                    if (ipv4.isLoopbackAddress || ipv4.isLinkLocalAddress || ipv4.isAnyLocalAddress) {
                        return@mapNotNull null
                    }
                    // A hosted network is always private. Anything routable is the carrier's.
                    if (!ipv4.isSiteLocalAddress) return@mapNotNull null
                    if (prefixLength < 24 || prefixLength > 30) return@mapNotNull null
                    Subnet(ipv4, prefixLength, candidate.name)
                }
            }
            .sortedBy { subnet -> rank(subnet.interfaceName) }

    /**
     * SoftAP names first in the order of [LIKELY_AP_PREFIXES], then [PEER_LINK_PREFIXES], then
     * unknown names. Every rank is distinct so the sort is a decision rather than a tie broken by
     * enumeration order.
     */
    private fun rank(interfaceName: String): Int {
        val lowered = interfaceName.lowercase()
        val hosted = LIKELY_AP_PREFIXES.indexOfFirst(lowered::startsWith)
        if (hosted >= 0) return hosted
        val peer = PEER_LINK_PREFIXES.indexOfFirst(lowered::startsWith)
        if (peer >= 0) return LIKELY_AP_PREFIXES.size + peer
        return LIKELY_AP_PREFIXES.size + PEER_LINK_PREFIXES.size
    }

    /**
     * Every address a dash could hold on [subnet], excluding the network and broadcast addresses
     * and the phone's own. Ordered outward from the phone: Android's DHCP pool starts just above
     * the gateway, so the dash is overwhelmingly likely to be among the first few tried, and a
     * rider watching a progress line sees it resolve in a second rather than at address 200.
     */
    fun candidateHosts(subnet: Subnet): List<Inet4Address> {
        val local = subnet.localAddress.address
        val hostBits = 32 - subnet.prefixLength
        val size = 1 shl hostBits
        val localValue = local.fold(0) { accumulated, byte -> (accumulated shl 8) or (byte.toInt() and 0xFF) }
        val mask = if (hostBits == 32) 0 else (-1 shl hostBits)
        val network = localValue and mask
        val ordered = (1 until size - 1)
            .map { offset -> network + offset }
            .filter { candidate -> candidate != localValue }
            .sortedBy { candidate -> kotlin.math.abs(candidate - localValue) }
        return ordered.map { value ->
            InetAddress.getByAddress(
                byteArrayOf(
                    (value ushr 24).toByte(),
                    (value ushr 16).toByte(),
                    (value ushr 8).toByte(),
                    value.toByte()
                )
            ) as Inet4Address
        }
    }

    /**
     * One device the kernel has seen on a network this phone hosts. [oui] is the first three
     * octets of its hardware address - the vendor half - because a full MAC is redacted out of
     * every shared log, and "who joined" is a question the vendor half answers on its own.
     */
    data class Neighbour(val address: String, val oui: String, val interfaceName: String)

    /**
     * The devices currently on the hosted network, or **null when the phone will not say** -
     * which is a different answer from "none", and the difference is the entire point.
     *
     * A rider whose dash never appears has two unrelated problems and no log has been able to
     * tell them apart: either the dash never joined the hotspot at all (wrong Ssid, wrong
     * password, a band its radio cannot see, or it was never in that mode) or it joined and is
     * simply not answering on the port MOTO-HUB probes. One line naming the occupants of the
     * subnet separates those before the 253-address sweep even starts, and the sweep's own
     * silence has never been able to.
     *
     * `/proc/net/arp` is the only route to that answer an unprivileged app has - the
     * `TetheringManager` client callback is gated behind a system permission - and Android has
     * been tightening access to it since 10. Strictly best-effort, therefore: unreadable is
     * reported as unreadable, and nothing whatsoever is concluded from it.
     */
    fun neighbours(interfaceName: String? = null): List<Neighbour>? =
        runCatching { parseNeighbours(File(ARP_TABLE_PATH).readLines(), interfaceName) }.getOrNull()

    /** The parsing half, kept separate so it can be tested without a motorcycle or a hotspot. */
    internal fun parseNeighbours(lines: List<String>, interfaceName: String? = null): List<Neighbour>? =
        run {
            // Header only is a real answer ("nothing has joined"); no header at all means the
            // read was refused or emptied, and that is not evidence about the dash.
            if (lines.isEmpty()) return@run null
            lines.drop(1).mapNotNull { line ->
                val fields = line.trim().split(WHITESPACE)
                if (fields.size < 6) return@mapNotNull null
                val device = fields[5]
                if (interfaceName != null && !device.equals(interfaceName, ignoreCase = true)) {
                    return@mapNotNull null
                }
                val flags = fields[2].removePrefix("0x").toIntOrNull(16) ?: 0
                val hardware = fields[3]
                // ATF_COM (0x2) is what separates a neighbour from the record of an ARP request
                // that went unanswered - and the sweep manufactures those by the hundred.
                if (flags and 0x2 == 0 || hardware == EMPTY_HARDWARE_ADDRESS) return@mapNotNull null
                Neighbour(
                    address = fields[0],
                    oui = hardware.split(':').take(3).joinToString(":"),
                    interfaceName = device
                )
            }
        }

    /** [neighbours] as the single log line the connect path records. */
    fun describeNeighbours(interfaceName: String): String {
        val seen = neighbours(interfaceName)
        return when {
            seen == null ->
                "This phone will not say which devices are on $interfaceName (Android restricts " +
                    "the neighbour table), so nothing here proves whether the dash joined."
            seen.isEmpty() ->
                "Nothing has joined the network this phone is hosting on $interfaceName yet - the " +
                    "neighbour table is empty. A dash that never associates is a credentials or " +
                    "band problem, not a discovery one."
            else ->
                "${seen.size} device(s) joined the hosted network on $interfaceName: " +
                    seen.joinToString { "${it.address} (vendor ${it.oui})" } + "."
        }
    }

    private val WHITESPACE = Regex("\\s+")
    private const val EMPTY_HARDWARE_ADDRESS = "00:00:00:00:00:00"
    private const val ARP_TABLE_PATH = "/proc/net/arp"

    /** Live enumeration; [tetheringSubnets] holds the logic worth testing. */
    fun snapshotInterfaces(): List<InterfaceSnapshot> =
        runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().map { candidate ->
                InterfaceSnapshot(
                    name = candidate.name.orEmpty(),
                    isUp = runCatching { candidate.isUp }.getOrDefault(false),
                    isLoopback = runCatching { candidate.isLoopback }.getOrDefault(false),
                    isPointToPoint = runCatching { candidate.isPointToPoint }.getOrDefault(false),
                    addresses = candidate.interfaceAddresses.orEmpty().mapNotNull { interfaceAddress ->
                        interfaceAddress.address?.let { it to interfaceAddress.networkPrefixLength.toInt() }
                    }
                )
            }.toList()
        }.getOrDefault(emptyList())
}
