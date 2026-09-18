// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText

/**
 * What this edition can do with a diagnostics report, or null in an edition that cannot.
 *
 * A seam rather than a flavour check inside the dialog: the question "may I send this?" is the
 * same everywhere, and only the answer to "can this app send anything at all?" differs.
 */
interface DiagnosticsOffer {
    /** Whether reports already go out on their own, so the dialog does not offer what is on. */
    val autoUploadEnabled: Boolean

    /** Sends the current log once, now. */
    fun sendNow()

    /** Turns automatic uploads on from here onwards. */
    fun enableAutoUpload()
}

/**
 * Asked the moment a profile the rider picked is proven to work - and nowhere else.
 *
 * The timing is the substance of it. Riders are asked to share diagnostics when something is
 * broken, which is exactly when they are frustrated, in a hurry, and have least reason to trust
 * the app with anything. This asks in the one moment the app has just done something for them,
 * about the one fact the collector cannot obtain any other way: WHICH profile works on WHICH
 * dashboard. Rider 315e0af3 found that answer alone and it stayed on his phone; nobody with the
 * same motorcycle benefited.
 *
 * Keeping the profile and sharing the log are separate answers on purpose. Bundling them would
 * make "no thanks" cost the rider the fix they just found, which is not consent.
 */
@Composable
internal fun ProfileTrialConfirmation(
    trial: PendingProfileTrial,
    diagnostics: DiagnosticsOffer?,
    onKeep: (sendNow: Boolean, enableAutoUpload: Boolean) -> Unit,
    onDiscard: () -> Unit
) {
    var sendNow by remember(trial) { mutableStateOf(false) }
    var alwaysSend by remember(trial) { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = {
            // No dismiss-by-outside-tap: the pin is already written, so walking away silently is
            // the one outcome that leaves the rider with a setting they never agreed to.
        },
        title = { Text(motoHubText("That worked - keep it?")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    motoHubText(
                        "Your dashboard is now accepting the picture on the %1\$s profile. " +
                            "Keep using it for this motorcycle?",
                        motoHubText(trial.override.label)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (diagnostics != null) {
                    HorizontalDivider()
                    Text(
                        motoHubText(
                            "You have just found out something MOTO-HUB could not work out by " +
                                "itself: which profile this dashboard actually accepts. Sending " +
                                "your log shares that, so the next rider with your motorcycle " +
                                "gets it right the first time."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CheckboxRow(
                        checked = sendNow,
                        onCheckedChange = { sendNow = it },
                        label = motoHubText("Send my log now")
                    )
                    if (!diagnostics.autoUploadEnabled) {
                        CheckboxRow(
                            checked = alwaysSend,
                            onCheckedChange = { alwaysSend = it },
                            label = motoHubText("Send logs automatically from now on")
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onKeep(sendNow, alwaysSend) }) {
                Text(motoHubText("Keep it"))
            }
        },
        dismissButton = {
            TextButton(onClick = onDiscard) {
                Text(motoHubText("Undo"))
            }
        }
    )
}

@Composable
private fun CheckboxRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit, label: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
