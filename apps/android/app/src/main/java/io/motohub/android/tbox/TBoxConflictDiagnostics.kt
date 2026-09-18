// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/** Detects the characteristic local-port failure produced when another EasyConn app is active. */
internal object TBoxConflictDiagnostics {
    /**
     * What a rider is told when nothing is known about which app is holding the ports - either no
     * companion app is installed under a package [CompanionAppRegistry] knows, or the caller had
     * no Context to ask. Naming one brand here would be worse than naming none: it sends riders of
     * every other brand after an app they do not have.
     */
    const val PORT_CONFLICT_MESSAGE =
        "Another EasyConn session is already using the local connection ports (10920-10922). " +
            "Stop any active MOTO-HUB session, or force-stop your motorcycle's own companion app " +
            "(CFMOTO, Zontes Smart, Carbit Ride and the equivalents other brands ship), then " +
            "retry the connection."

    /** The same message, naming the companion app actually installed on this phone. */
    fun portConflictMessage(companionAppName: String?): String =
        if (companionAppName.isNullOrBlank()) {
            PORT_CONFLICT_MESSAGE
        } else {
            "Another EasyConn session is already using the local connection ports " +
                "(10920-10922). Stop any active MOTO-HUB session, or force-stop $companionAppName " +
                "from its App info page, then retry the connection."
        }

    fun isPortConflict(message: String?): Boolean {
        val detail = message.orEmpty().lowercase()
        if (detail.isBlank()) return false
        val mentionsLinkPort = listOf("10920", "10921", "10922").any(detail::contains)
        return "address already in use" in detail ||
            "port already in use" in detail ||
            (mentionsLinkPort && ("bind" in detail || "listen" in detail || "held" in detail))
    }

    fun userFacingMessage(message: String, companionAppName: String? = null): String =
        if (isPortConflict(message)) portConflictMessage(companionAppName) else message
}
