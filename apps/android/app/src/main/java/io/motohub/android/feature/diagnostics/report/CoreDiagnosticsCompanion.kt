// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.Context
import io.motohub.android.feature.controls.BluetoothStatus
import io.motohub.android.feature.controls.currentHandlebarState
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.TBoxWireLadder

fun createDiagnosticsCompanion(context: Context): DiagnosticsCompanion =
    CoreDiagnosticsCompanion(context.applicationContext)

/**
 * This edition has nothing to ask anybody: the transport, the wire ladder and the screen are all
 * in this process.
 *
 * Which is why the report is worth having here at all. A CORE-only rider is not a rider with less
 * to say - they are a rider whose whole session fits in one log, with no bridge in the middle and
 * no second app's version to reconcile. When their dashboard turns out to speak a wire nobody has
 * catalogued, that is exactly the report the wire ladder wants.
 */
private class CoreDiagnosticsCompanion(private val appContext: Context) : DiagnosticsCompanion {

    override suspend fun exportLog(context: Context): String = ProjectionEventLog.exportText()

    override suspend fun clearLog(context: Context, note: String): Boolean {
        ProjectionEventLog.clear(note)
        return true
    }

    /**
     * Read straight from the store, which is the same place the bridge reads it from when the
     * companion app asks - so both editions report the ladder from one source of truth.
     *
     * Keyed by profile id in the returned map because that is what the report indexes by, while
     * the STORE is keyed by network name: one physical dashboard outlives any number of garage
     * entries, and a ladder filed under a re-scanned UUID is a ladder nobody finds again.
     */
    override suspend fun wireLadders(
        context: Context,
        profiles: List<MotorcycleProfile>
    ): Map<String, String> = profiles.mapNotNull { profile ->
        TBoxWireLadder.storedProgress(appContext, TBoxWireLadder.storageKey(profile))
            ?.let { profile.id to it }
    }.toMap()

    /**
     * One process, one answer: this edition IS the half whose Bluetooth grant an Android Auto
     * handlebar depends on. ADVANCED is null rather than false - a CORE-only rider has no
     * ADVANCED, and reporting "denied" for an app that is not installed is worse than silence.
     */
    override suspend fun handlebarBluetoothGrants(context: Context) = HandlebarBluetoothGrants(
        advanced = null,
        core = BluetoothStatus.hasConnectPermission(appContext)
    )

    /** Same one-process answer, from the stores an Android Auto session actually reads. */
    override suspend fun handlebarStates(context: Context) = HandlebarStates(
        advanced = null,
        core = runCatching { currentHandlebarState(appContext) }.getOrNull()
    )
}
