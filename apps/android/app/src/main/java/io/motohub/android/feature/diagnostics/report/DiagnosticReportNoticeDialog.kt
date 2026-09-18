// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MotoHubDialogBody

/**
 * What a diagnostics report contains, and the two answers to it. Reached from
 * Settings ▸ Diagnostics ▸ "What gets sent" - automatic reports are off until a rider says
 * otherwise, so nothing about this interrupts a first launch.
 *
 * [onDismiss] exists because the dialog used to treat a tap outside as consent
 * (`onDismissRequest = onAccept`). That was defensible while the feature was on by default and
 * this was the notice about it; on an opt-in feature it would turn an idle tap into "yes, send
 * my logs". Dismissing now changes nothing.
 */
@Composable
fun DiagnosticReportNoticeDialog(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Help improve MOTO-HUB")) },
        text = {
            MotoHubDialogBody {
                Text(
                    motoHubText(
                        "MOTO-HUB can send a diagnostics report to the developer: your " +
                            "motorcycle's dashboard model, your phone model, the Android, Android Auto, " +
                            "MOTO-HUB versions installed, and the application log. Passwords, positions " +
                            "and hardware addresses are never included."
                    )
                )
                Text(
                    motoHubText(
                        "Reports go out at most once a day, after an update, or after a crash, and only " +
                            "over a connection with Internet access. Your Support ID is shown under " +
                            "Settings ▸ Diagnostics, where this can be turned on or off at any time."
                    )
                )
            }
        },
        confirmButton = {
            Button(onClick = onAccept) { Text(motoHubText("Send reports")) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) { Text(motoHubText("Not now")) }
        }
    )
}
