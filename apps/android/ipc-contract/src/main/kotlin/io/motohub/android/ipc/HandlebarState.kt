// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ipc

/**
 * How one package's handlebar is configured right now, as it crosses the bridge - see
 * `ITBoxTransportService.getHandlebarStateJson`.
 *
 * Per-package, and therefore two answers, for the same reason the Bluetooth grant is
 * (see [IpcBridgeContract.CONTRACT_VERSION_CORE_BLUETOOTH]): an Android Auto session's handlebar
 * is decoded in Core, while the screens that configure it are in the companion app. A report that
 * printed the companion's own settings under "handlebar" would be describing the half where
 * nothing is decoded - which is how a rider can be told his handlebar is taught and in HID mode
 * while the process that reads his presses is on AVRCP with nothing taught at all.
 *
 * The radio is deliberately absent: an adapter is one radio and either process reads the same
 * answer, so it is collected locally rather than carried.
 *
 * Lives in the contract module because both halves must agree on the wire format, and only one
 * of them can ever produce it.
 */
data class HandlebarState(
    /** [HandlebarInputMode]'s id, as stored: "avrcp" or "hid". */
    val inputMode: String,
    /** Whether this package would capture presses at all for the active motorcycle. */
    val captureEnabled: Boolean,
    /** Whether any physical press has been taught for the active motorcycle. */
    val calibrated: Boolean,
    /** Whether a companion app has taken over this package's handlebar configuration. */
    val managedByCompanion: Boolean,
    /** Whether this package's Accessibility service - the only way HID keys arrive - is granted. */
    val hidServiceEnabled: Boolean
) {
    fun encode(): String = listOf(
        inputMode,
        captureEnabled.wire(),
        calibrated.wire(),
        managedByCompanion.wire(),
        hidServiceEnabled.wire()
    ).joinToString(SEPARATOR.toString())

    companion object {
        private const val SEPARATOR = '|'
        private const val FIELDS = 5
        private const val TRUE = "1"
        private const val FALSE = "0"

        private fun Boolean.wire(): String = if (this) TRUE else FALSE

        /**
         * Null on anything that is not exactly this format.
         *
         * Strict like [DashboardDelivery.parse], and for a stronger reason: every field here is
         * read as a fact about the rider's phone when someone works a support case. A half-parsed
         * state that silently defaulted "calibrated" to false would send an investigation after a
         * teaching wizard that had run perfectly.
         */
        fun parse(wire: String): HandlebarState? {
            val parts = wire.split(SEPARATOR)
            if (parts.size != FIELDS) return null
            val mode = parts[0].takeIf { it.isNotBlank() } ?: return null
            fun flag(raw: String): Boolean? = when (raw) {
                TRUE -> true
                FALSE -> false
                else -> null
            }
            return HandlebarState(
                inputMode = mode,
                captureEnabled = flag(parts[1]) ?: return null,
                calibrated = flag(parts[2]) ?: return null,
                managedByCompanion = flag(parts[3]) ?: return null,
                hidServiceEnabled = flag(parts[4]) ?: return null
            )
        }
    }
}
