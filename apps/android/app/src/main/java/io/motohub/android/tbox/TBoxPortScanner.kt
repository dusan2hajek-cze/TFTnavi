// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import android.net.ConnectivityManager
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class TBoxPortStatus { OPEN, REFUSED, NO_RESPONSE }

data class TBoxPortScanEntry(val port: Int, val status: TBoxPortStatus, val detail: String?)

data class TBoxPortScanResult(val peerIp: String?, val entries: List<TBoxPortScanEntry>)

/**
 * Best-effort TCP probe of the T-Box AP's well-known EasyConn ports. Exists because a wake
 * probe to the OpenCfMoto/OpenMoto reference port (10930) can come back ECONNREFUSED on a T-Box
 * variant those projects never reverse-engineered - REFUSED there means the peer IP is reachable
 * and alive, just not listening on that specific port. This narrows down what port actually is
 * open, instead of guessing another one blind. Opens its own short-lived Wi-Fi connection,
 * independent of any active EasyConn session, and always disconnects when done.
 */
object TBoxPortScanner {
    // The only ports any reference EasyConn implementation (OpenCfMoto, OpenMoto, OpenCfLink)
    // documents - PXC ctrl/media (10920-10922) and the mDNS-respond probe (10930) - plus a
    // narrow neighborhood in case this firmware shifted the whole block.
    private val CANDIDATE_PORTS = (10915..10935).toList()
    private const val CONNECT_TIMEOUT_MS = 1_200

    /** This scanner's name in [TBoxNetworkConnectors]' interest ledger. */
    private const val OWNER = "port-scan"

    suspend fun scan(context: Context, profile: MotorcycleProfile): Result<TBoxPortScanResult> =
        withContext(Dispatchers.IO) {
            // A diagnostic must not steal the radio from a ride: refused outright when the shared
            // connector is working a different motorcycle on someone else's behalf. For the SAME
            // SSID, connect() below reuses the already-active network instead of re-joining.
            val connector = TBoxNetworkConnectors.tryAcquireForDiagnostics(context, OWNER, profile.ssid)
                ?: return@withContext Result.failure(
                    IllegalStateException(
                        "MOTO-HUB is connected to a different motorcycle right now. " +
                            "Disconnect it first, then scan."
                    )
                )
            var establishedLink: TBoxLink? = null
            val result = runCatching {
                val link = TBoxLinkResolver.connect(context, connector, profile).getOrThrow()
                establishedLink = link
                val peerIp = link.peerHint?.hostAddress ?: link.network?.let { network ->
                    val connectivityManager = context.applicationContext
                        .getSystemService(ConnectivityManager::class.java)
                    connectivityManager.getLinkProperties(network)?.let { properties ->
                        deriveTBoxPeerIpv4(
                            gateways = properties.routes
                                .filter { route -> route.isDefaultRoute }
                                .mapNotNull { route -> route.gateway },
                            dnsServers = properties.dnsServers,
                            localAddresses = properties.linkAddresses
                                .map { linkAddress -> linkAddress.address to linkAddress.prefixLength }
                        )
                    }?.hostAddress
                }
                if (peerIp == null) {
                    ProjectionEventLog.warning("DIAGNOSTICS", "Port scan: no usable peer IPv4 could be derived.")
                    TBoxPortScanResult(peerIp = null, entries = emptyList())
                } else {
                    probeAll(link, peerIp)
                }
            }
            // A Wi-Fi Direct link owns its P2P group directly (no ConnectivityManager request to
            // release), so the link must be disconnected too or the group survives the scan and
            // pollutes the next real connection as a stale formed group.
            establishedLink?.let { runCatching { it.disconnect() } }
            // Release, never disconnect: with a session live on the same SSID this is not the
            // last interest, and the scan ends without touching the ride's network.
            TBoxNetworkConnectors.release(OWNER)
            result
        }

    /**
     * Scans the dash of a link that is ALREADY established, asking Android for nothing.
     *
     * This is the shape the companion boundary needs. The scanner's own [scan] opens a
     * connection, which is right when nothing is connected and impossible when something is:
     * only one process can hold the T-Box network, and when that process is CORE the companion
     * has no socket to probe with. CORE runs this over the live session's link instead, so the
     * scan costs the ride nothing - no request, no re-join, no teardown - and the link stays
     * owned by whoever established it. Never disconnects [link] for the same reason.
     *
     * @param peerIp the dash's address, already derived by whoever owns the session.
     */
    suspend fun scanOverLink(link: TBoxLink, peerIp: String): TBoxPortScanResult =
        withContext(Dispatchers.IO) { probeAll(link, peerIp) }

    private suspend fun probeAll(link: TBoxLink, peerIp: String): TBoxPortScanResult = coroutineScope {
        ProjectionEventLog.record(
            "DIAGNOSTICS",
            "Port scan starting against $peerIp (${link.label}), " +
                "ports ${CANDIDATE_PORTS.first()}-${CANDIDATE_PORTS.last()}."
        )
        val entries = CANDIDATE_PORTS
            .map { port -> async { probe(link, peerIp, port) } }
            .awaitAll()
            .sortedBy { it.port }
        val open = entries.filter { it.status == TBoxPortStatus.OPEN }
        ProjectionEventLog.record(
            "DIAGNOSTICS",
            if (open.isEmpty()) {
                "Port scan complete: none of ${entries.size} candidate ports responded as open."
            } else {
                "Port scan complete: open=${open.joinToString { it.port.toString() }}."
            }
        )
        TBoxPortScanResult(peerIp, entries)
    }

    /**
     * The result as the JSON that crosses the companion bridge. Hand-built rather than reflected
     * so the two apps can be different versions of it: an unknown status decodes to NO_RESPONSE
     * and an unknown field is skipped, which is the same tolerance every other JSON on that
     * boundary already has.
     */
    fun encode(result: TBoxPortScanResult): String = JSONObject().apply {
        result.peerIp?.let { put("peerIp", it) }
        put(
            "ports",
            JSONArray().apply {
                result.entries.forEach { entry ->
                    put(
                        JSONObject().apply {
                            put("port", entry.port)
                            put("status", entry.status.name)
                            entry.detail?.let { put("detail", it) }
                        }
                    )
                }
            }
        )
    }.toString()

    /** [encode]'s inverse; null when the text is not the JSON this scanner writes. */
    fun decode(json: String): TBoxPortScanResult? = runCatching {
        val root = JSONObject(json)
        val ports = root.optJSONArray("ports") ?: JSONArray()
        val entries = (0 until ports.length()).mapNotNull { index ->
            val entry = ports.optJSONObject(index) ?: return@mapNotNull null
            TBoxPortScanEntry(
                port = entry.optInt("port", -1).takeIf { it >= 0 } ?: return@mapNotNull null,
                status = TBoxPortStatus.entries
                    .firstOrNull { it.name == entry.optString("status") }
                    ?: TBoxPortStatus.NO_RESPONSE,
                detail = entry.optString("detail").takeIf { it.isNotBlank() }
            )
        }
        TBoxPortScanResult(
            peerIp = root.optString("peerIp").takeIf { it.isNotBlank() },
            entries = entries
        )
    }.getOrNull()

    private fun probe(link: TBoxLink, host: String, port: Int): TBoxPortScanEntry = try {
        link.createSocket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        TBoxPortScanEntry(port, TBoxPortStatus.OPEN, null)
    } catch (failure: ConnectException) {
        TBoxPortScanEntry(port, TBoxPortStatus.REFUSED, failure.message)
    } catch (failure: SocketTimeoutException) {
        TBoxPortScanEntry(port, TBoxPortStatus.NO_RESPONSE, failure.message)
    } catch (failure: Exception) {
        TBoxPortScanEntry(port, TBoxPortStatus.NO_RESPONSE, failure.message)
    }
}
