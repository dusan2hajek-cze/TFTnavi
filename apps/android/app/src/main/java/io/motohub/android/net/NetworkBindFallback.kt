// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import io.motohub.android.session.ProjectionEventLog
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import javax.net.SocketFactory

/**
 * Recovers Internet access when Android refuses to bind a socket to the cellular network.
 *
 * A VPN in lockdown mode ("Block connections without VPN") makes every
 * `Network.bindSocket()`/`Network.openConnection()` on a non-VPN network fail with EPERM -
 * instantly, and for as long as the lockdown is active. Riders running ad-blockers that install
 * as a local VPN (AdGuard, NetGuard, ...) or real VPNs (Tailscale, ...) hit this: the dashboard
 * video streamed fine while every tile, weather and Overpass request died on the bind, leaving
 * an empty grid where the map should be.
 *
 * The recovery is to route the request through the VPN itself - the one network the app is still
 * allowed to use, and one that reaches the Internet over the same cellular data the bind was
 * aiming for. When no VPN network can be found, fall back to an unbound request on the process
 * default network as a last resort.
 */
internal object NetworkBindFallback {

    /** True when [failure] (or any of its causes) is Android refusing a socket-to-network bind. */
    fun isBindRefusal(failure: Throwable): Boolean {
        var current: Throwable? = failure
        var hops = 0
        while (current != null && hops < MAX_CAUSE_HOPS) {
            if (current is ErrnoException && current.errno == OsConstants.EPERM) return true
            val message = current.message.orEmpty()
            if (message.contains("Binding socket to network") && message.contains("EPERM")) return true
            current = current.cause
            hops++
        }
        return false
    }

    /**
     * The network to retry on after a bind refusal: the VPN when one is up, else null - which
     * every caller translates to an unbound request on the process default network.
     */
    fun fallbackNetwork(context: Context): Network? {
        val connectivityManager =
            context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return null
        return runCatching {
            connectivityManager.allNetworks
                .asSequence()
                .mapNotNull { network ->
                    connectivityManager.getNetworkCapabilities(network)?.let { network to it }
                }
                .filter { (_, capabilities) ->
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                }
                .sortedByDescending { (_, capabilities) ->
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
                .map { (network, _) -> network }
                .firstOrNull()
        }.getOrNull()
    }

    /** Log the refusal once per window so a full ride does not flood the diagnostics file. */
    fun noteBindRefusal() {
        val now = SystemClock.elapsedRealtime()
        val previous = lastLogMillis.get()
        if (previous != Long.MIN_VALUE && now - previous < LOG_INTERVAL_MILLIS) return
        if (lastLogMillis.compareAndSet(previous, now)) {
            ProjectionEventLog.warning(
                "NETWORK",
                "Android refused to bind to the cellular network - a VPN with 'Block connections " +
                    "without VPN' does this. Routing map and nav requests through the VPN instead."
            )
        }
    }

    private val lastLogMillis = AtomicLong(Long.MIN_VALUE)
    private const val LOG_INTERVAL_MILLIS = 5 * 60_000L
    private const val MAX_CAUSE_HOPS = 8
}

/**
 * A [SocketFactory] that pins sockets to the network from [networkProvider] and retries through
 * [NetworkBindFallback.fallbackNetwork] when the bind is refused. OkHttp layers TLS on top via
 * its own SSLSocketFactory, so plain sockets are all this ever has to produce.
 */
internal class BindFallbackSocketFactory(
    context: Context,
    private val networkProvider: () -> Network?
) : SocketFactory() {
    private val applicationContext = context.applicationContext

    private fun <T> attempt(block: (SocketFactory) -> T): T {
        val pinned = networkProvider()?.socketFactory ?: getDefault()
        return try {
            block(pinned)
        } catch (failure: IOException) {
            if (!NetworkBindFallback.isBindRefusal(failure)) throw failure
            NetworkBindFallback.noteBindRefusal()
            block(NetworkBindFallback.fallbackNetwork(applicationContext)?.socketFactory ?: getDefault())
        }
    }

    override fun createSocket(): Socket = attempt { it.createSocket() }

    override fun createSocket(host: String?, port: Int): Socket =
        attempt { it.createSocket(host, port) }

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        attempt { it.createSocket(host, port, localHost, localPort) }

    override fun createSocket(address: InetAddress?, port: Int): Socket =
        attempt { it.createSocket(address, port) }

    override fun createSocket(
        address: InetAddress?,
        port: Int,
        localAddress: InetAddress?,
        localPort: Int
    ): Socket = attempt { it.createSocket(address, port, localAddress, localPort) }
}
