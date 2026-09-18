// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.InetAddress

/**
 * Says whether a VPN is actually standing between this app and the dash - and refuses to say so
 * when it is not.
 *
 * This used to blame the VPN for any failure that happened while a VpnService-based app merely
 * existed on the phone, on the strength of a `TRANSPORT_VPN` network being present. A rider
 * running Tailscale was told to "disable Always-on VPN / Block connections without VPN" - two
 * settings he did not have on - for a failure whose real shape (an exit node capturing the
 * default route) that sentence never named. Presence is not causation, and a diagnosis that
 * sends a rider to the wrong settings screen is worse than no diagnosis.
 *
 * So the evidence is gathered instead of assumed. A VPN that applies to this app is visible in
 * `allNetworks`, and its [android.net.LinkProperties] - readable with ordinary ACCESS_NETWORK_STATE -
 * carry the routes it claims. If those routes cover the dash, the VPN wins the route to it and
 * the diagnosis is a fact; if they do not, the VPN is a bystander and this says nothing.
 */
internal object TBoxVpnDiagnostics {

    /**
     * What a VPN applying to this app is doing to the route towards the dash, as far as an
     * ordinary app can observe it.
     *
     * @param interfaceName the tunnel's interface (`tun0`), when Android exposes it.
     * @param capturesDefaultRoute the tunnel claims `0.0.0.0/0` - full-tunnel mode, which on
     *   Tailscale/Mullvad-style clients is what an "exit node" turns on. Everything not covered
     *   by a more specific route, including the dash, goes into the tunnel.
     * @param capturesDash one of the tunnel's routes matches the dash's own address. Stronger
     *   than [capturesDefaultRoute] and independent of it: a client with LAN access switched off
     *   can claim the private ranges without claiming everything.
     */
    data class VpnRouting(
        val interfaceName: String?,
        val capturesDefaultRoute: Boolean,
        val capturesDash: Boolean
    ) {
        /** The only state worth blaming: the dash's own address is inside the tunnel. */
        val capturesTBox: Boolean get() = capturesDash || capturesDefaultRoute

        val label: String get() = interfaceName?.takeIf { it.isNotBlank() } ?: "VPN"

        /** For the log: the facts, not the verdict. */
        fun describe(): String =
            "interface=$label, capturesDefaultRoute=$capturesDefaultRoute, capturesDashAddress=$capturesDash"
    }

    /**
     * The VPN that applies to this app right now, and what it claims about the route to
     * [dashAddress] (the dash's gateway on the Wi-Fi it just joined). Null when no VPN applies.
     *
     * Never throws: this runs inside a ConnectivityManager callback on the path that establishes
     * a ride, and a diagnostic has no business breaking the thing it is describing.
     */
    fun inspect(
        connectivityManager: ConnectivityManager,
        dashAddress: InetAddress?
    ): VpnRouting? = runCatching {
        @Suppress("DEPRECATION")
        val vpn = connectivityManager.allNetworks.firstOrNull { network ->
            connectivityManager.getNetworkCapabilities(network)
                ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        } ?: return@runCatching null
        val linkProperties = connectivityManager.getLinkProperties(vpn)
        val routes = linkProperties?.routes.orEmpty()
        VpnRouting(
            interfaceName = linkProperties?.interfaceName,
            capturesDefaultRoute = routes.any { it.isDefaultRoute },
            capturesDash = dashAddress != null && routes.any { route ->
                runCatching { route.destination.contains(dashAddress) }.getOrDefault(false)
            }
        )
    }.getOrNull()

    /**
     * Android refused something with a permission error, which is what a VPN in lockdown produces.
     * Kept separate from [inspect] because it is evidence of a different kind: the error itself,
     * rather than the routing table.
     */
    fun isVpnBindBlocked(error: Throwable?): Boolean {
        var current = error
        while (current != null) {
            val detail = listOfNotNull(current.message, current.cause?.message)
                .joinToString(" ")
                .lowercase()
            if ("eperm" in detail ||
                "operation not permitted" in detail ||
                "permission denied" in detail && "network" in detail
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * The rider-facing sentence for a failure a VPN demonstrably caused, or null when nothing
     * here points at one. [routing] may be null when the caller has no network to inspect yet -
     * the error alone can still carry the evidence.
     */
    fun userFacingMessage(error: Throwable?, routing: VpnRouting?): String? = when {
        routing?.capturesTBox == true -> blockingMessage(routing)
        // Routes say the tunnel is a bystander and Android still refused the socket with a
        // permission error. That combination has one cause, and it is not routing: see
        // [lockdownMessage]. Ordering matters - a tunnel that captures the dash is the stronger
        // explanation and keeps its own sentence even when the error is also EPERM.
        isVpnBindBlocked(error) -> lockdownMessage(routing)
        else -> null
    }

    /**
     * Names the tunnel and the three ways out of it. Deliberately does not say "Always-on VPN":
     * a full-tunnel exit node produces exactly this failure with always-on switched off, and
     * sending a rider to that toggle is how the previous message wasted an afternoon.
     */
    fun blockingMessage(routing: VpnRouting?): String {
        val tunnel = routing?.label?.let { " ($it)" }.orEmpty()
        return "$VPN_ROUTING_MARKER$tunnel is routing the motorcycle's network into its tunnel, so " +
            "Android will not let MOTO-HUB reach the dash. Turn the VPN off while you ride, switch " +
            "off its exit node / full-tunnel mode, or turn on its \"allow local network access\" " +
            "option, then retry."
    }

    /**
     * The rider-facing sentence for a VPN in LOCKDOWN - Android's "Block connections without VPN"
     * (Always-on VPN's second switch), or the equivalent inside an ad-blocker/filtering VPN.
     *
     * A different fault from [blockingMessage], with a different fix, and it has to be told apart
     * from it because the routing evidence actively points the other way. Lockdown is enforced
     * per UID in netd, not in the tunnel's routing table: the VPN can claim nothing at all -
     * `capturesDefaultRoute=false, capturesDashAddress=false` - and Android will still refuse to
     * bind ANY socket of this app to ANY other network. The dash lives on that Wi-Fi and nowhere
     * else, so there is no network left to fall back to; the ride simply cannot start.
     *
     * EPERM is what makes this knowable. A full-tunnel VPN that swallows the dash's subnet
     * produces unreachable hosts and timeouts, never a permission error - the kernel refuses the
     * BIND, before a packet exists. So an EPERM on a bind to the dash's own network is the
     * signature of lockdown and of practically nothing else.
     *
     * Vincenzo's own OnePlus, 2026-08-26 (installation cc23165f, 1.1.98): four connects in 53
     * seconds, Android granting the Wi-Fi every time at -29 dBm with EasyConn answering mDNS on
     * :10930, and every socket after it dying with
     * `Binding socket to network 245 failed: EPERM`. What he was shown was that sentence and a
     * stack trace. The routing check had already looked at the tunnel, found it innocent - it
     * was - and thrown the diagnosis away.
     */
    fun lockdownMessage(routing: VpnRouting?): String {
        val tunnel = routing?.label?.let { " ($it)" }.orEmpty()
        return "$VPN_ROUTING_MARKER$tunnel is set to block connections that do not go through " +
            "it, so Android will not let MOTO-HUB use the motorcycle's Wi-Fi at all - the dash " +
            "is only reachable there. Turn off \"Block connections without VPN\" for it in " +
            "Android's VPN settings, or turn the VPN off while you ride, then retry."
    }

    /**
     * Whether [message] is one of ours from above. Used by the error banner to keep help meant for
     * a busy EasyConn session off a failure that never got near one - see
     * [io.motohub.android.session.HubSessionState.offerOfficialAppHelp].
     */
    fun isVpnRoutingMessage(message: String?): Boolean =
        message?.contains(VPN_ROUTING_MARKER, ignoreCase = true) == true

    /** Opening words of every message above, so [isVpnRoutingMessage] has something exact to match. */
    private const val VPN_ROUTING_MARKER = "A VPN on this phone"
}
