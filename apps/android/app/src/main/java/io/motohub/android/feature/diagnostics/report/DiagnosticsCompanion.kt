// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.Context
import io.motohub.android.ipc.HandlebarState
import io.motohub.android.session.MotorcycleProfile

/**
 * Everything a diagnostics report needs that this edition cannot look up in its own process.
 *
 * The report itself is the same document in both editions - the same fields, the same collector,
 * the same consent gate. What differs is only where two of its inputs live:
 *
 *  - **the log.** ADVANCED runs the UI while CORE runs the transport, so a report from ADVANCED
 *    that carried only ADVANCED's own log would describe the half where nothing happens. It
 *    fetches CORE's over the bridge and staples the two together. CORE has the whole story in
 *    its own process and simply exports it.
 *  - **the wire ladder.** Walked and persisted in CORE. ADVANCED has to ask - and until it did,
 *    every report from every rider said "rung 0, no fingerprint", which is a true statement
 *    about ADVANCED's untouched copy of CORE's preferences and a false one about the motorcycle.
 *
 * Behind an interface rather than a `BuildConfig.IS_PRO` branch inside the builder because the
 * ADVANCED implementation reaches for the AIDL bridge, and the CORE flavour must not compile a
 * reference to a bridge it is the far end of.
 */
/**
 * Which halves hold BLUETOOTH_CONNECT, which is a per-package answer and therefore two answers.
 *
 * Null means "not this edition's to say" - CORE cannot see ADVANCED's grant at all, and ADVANCED
 * gets null for CORE when the bridge is unreachable or predates the call. Absent is not denied,
 * and the collector has to be able to tell them apart: a report that guessed would have sent
 * every CORE-only rider a warning about an app they do not have.
 */
data class HandlebarBluetoothGrants(val advanced: Boolean?, val core: Boolean?)

/**
 * How each half's handlebar is configured, which is a per-package answer for the same reason the
 * grant above is. Null means "not this edition's to say", never "nothing configured".
 */
data class HandlebarStates(val advanced: HandlebarState?, val core: HandlebarState?)

interface DiagnosticsCompanion {

    /**
     * The complete log to attach - both halves in ADVANCED, this process's own in CORE.
     */
    suspend fun exportLog(context: Context): String

    /**
     * Empties the log this edition just sent, leaving [note] as the first line of the new one.
     * Returns false when some part of it could not be cleared.
     *
     * A report is a ring buffer's worth of history, so a rider who sends two reports an hour
     * apart would otherwise send the same problem twice and neither would say which was which.
     */
    suspend fun clearLog(context: Context, note: String): Boolean

    /**
     * Where the wire search stands, keyed by motorcycle profile id. Empty when nothing is known.
     */
    suspend fun wireLadders(context: Context, profiles: List<MotorcycleProfile>): Map<String, String>

    /**
     * The Bluetooth grant of each half. The third input that does not live where the report is
     * written, and the one that cost the most before it was here.
     *
     * An Android Auto session's handlebar is decoded in CORE, so CORE's grant decides whether a
     * press can arrive - and ADVANCED, checking its own, reported a handlebar that was ready
     * while CORE logged "capture skipped: Bluetooth is off or unavailable to this app" in every
     * session. Rider 315e0af3 sent seven reports across three days, each of them containing that
     * line and none of them containing the fact that explains it.
     */
    suspend fun handlebarBluetoothGrants(context: Context): HandlebarBluetoothGrants

    /**
     * The handlebar configuration of each half - input protocol, capture, teaching, and whether
     * the Accessibility service HID needs is granted.
     *
     * The grant above says a press can arrive; this says what happens to it. Both had to cross
     * for the same reason: support 0df154af sent a report from ADVANCED, mid Android Auto
     * session, after switching input protocol three times - and everything under "handlebar"
     * would have described ADVANCED's own stores, while the presses were being decoded in CORE
     * against CORE's.
     */
    suspend fun handlebarStates(context: Context): HandlebarStates
}

// createDiagnosticsCompanion(context) is defined once per flavour - src/core and src/pro - the
// same arrangement createTBoxSessionEstablisher() uses next door.
