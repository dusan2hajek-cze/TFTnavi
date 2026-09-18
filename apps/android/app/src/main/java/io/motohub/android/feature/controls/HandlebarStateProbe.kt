// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import android.content.Context
import io.motohub.android.ipc.HandlebarState

/**
 * This process's handlebar configuration, as one value.
 *
 * Read live from the same stores the bridges read, and scoped to the active motorcycle exactly as
 * they are - a handlebar answer that averaged over the garage would be true of no bike. Two
 * callers want it: the diagnostics report, and the bridge that hands it to a companion app which
 * cannot see any of this (see `ITBoxTransportService.getHandlebarState`).
 *
 * Cheap enough for a binder thread: preferences and one Settings.Secure read, no proxies and no
 * waiting. The Bluetooth radio is deliberately not here - it is one radio, and whoever asks can
 * read it themselves.
 */
fun currentHandlebarState(context: Context): HandlebarState = HandlebarState(
    inputMode = HandlebarControlStore.inputMode(context).id,
    captureEnabled = HandlebarControlStore.isEnabled(context),
    calibrated = HandlebarCalibration.isCalibrated(context),
    managedByCompanion = HandlebarControlStore.isManagedByCompanion(context),
    hidServiceEnabled = HandlebarHidCaptureService.isEnabled(context)
)
