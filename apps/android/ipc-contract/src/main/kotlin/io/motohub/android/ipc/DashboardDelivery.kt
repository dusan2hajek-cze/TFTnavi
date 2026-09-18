// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

/**
 * Core's verdict on what a session is doing to the dashboard, as it crosses the bridge - see
 * `ITBoxTransportService.getDashboardDeliveryReport`.
 *
 * Lives in the contract module because both halves must agree on the wire format, and only one
 * of them can ever produce it.
 */
data class DashboardDelivery(
    val healthy: Boolean,
    val rejected: Int,
    val accepted: Int,
    val profileKey: String,
    val ssid: String
) {
    /** 0..1, the number the rider-facing sentence is built from. */
    val rejectedShare: Double
        get() = (rejected + accepted).let { if (it == 0) 0.0 else rejected.toDouble() / it }

    companion object {
        private const val SEPARATOR = '|'
        private const val FIELDS = 5
        private const val HEALTHY = "ok"
        private const val FAILING = "failing"

        /**
         * Null on anything that is not exactly this format.
         *
         * Strict on purpose. The only thing this value does is offer the rider a different
         * profile, and an offer assembled from a half-parsed string - a count that silently
         * became zero, a profile key that is really the tail of an SSID - is worse than the
         * silence that existed before the call. Refusing to guess costs one missed prompt.
         *
         * The limit matters: an SSID may contain the separator, so the name is whatever remains
         * after the fields that cannot.
         */
        fun parse(wire: String): DashboardDelivery? {
            val parts = wire.split(SEPARATOR, limit = FIELDS)
            if (parts.size != FIELDS) return null
            val healthy = when (parts[0]) {
                HEALTHY -> true
                FAILING -> false
                // Not defaulted either way. One arm of this drives "keep this profile?" and the
                // other drives "try a different one"; guessing between them would show a rider
                // the opposite of what happened.
                else -> return null
            }
            val rejected = parts[1].toIntOrNull() ?: return null
            val accepted = parts[2].toIntOrNull() ?: return null
            if (rejected < 0 || accepted < 0) return null
            val profileKey = parts[3].takeIf { it.isNotBlank() } ?: return null
            val ssid = parts[4].takeIf { it.isNotBlank() } ?: return null
            return DashboardDelivery(healthy, rejected, accepted, profileKey, ssid)
        }
    }
}
