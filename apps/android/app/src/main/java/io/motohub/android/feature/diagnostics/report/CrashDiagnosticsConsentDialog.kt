// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MotoHubDialogBody

/**
 * Asked once, on the launch after MOTO-HUB crashed, of a rider who never turned automatic
 * diagnostics on.
 *
 * The switch under Settings ▸ Diagnostics is off until someone opts in, which is the right
 * default and also means the one report worth having - the crash that just happened - is the one
 * that never leaves. This is the missing half of that: the question at the moment it costs
 * nothing to answer, with the same list of what would be sent that the settings notice carries,
 * so the answer is informed wherever it is given.
 *
 * [onSend] carries [alwaysSend] rather than a second dialog: a rider who is happy to send this
 * one is usually happy to send the next, and being asked every time is its own annoyance.
 * Declining sends nothing and changes no setting - see
 * [DiagnosticReportScheduler.onCrashReportDeclined].
 *
 * Unlike the safety disclaimer this dialog is dismissible. Refusing to take an answer would make
 * it a toll gate on a rider who opened the app to get somewhere, and an unanswered question
 * returns on the next launch anyway.
 */
@Composable
fun CrashDiagnosticsConsentDialog(
    alwaysSend: Boolean,
    onAlwaysSendChanged: (Boolean) -> Unit,
    onSend: () -> Unit,
    onDecline: () -> Unit,
    onOpenPrivacyNotice: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("MOTO-HUB closed unexpectedly")) },
        text = {
            MotoHubDialogBody {
                Text(
                    motoHubText(
                        "The last time you used MOTO-HUB it stopped because of an error. Sending " +
                            "the diagnostics report is what lets the developer find out why."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    motoHubText(
                        "The report contains your motorcycle's dashboard model, your phone model, " +
                            "the Android and Android Auto versions, the MOTO-HUB versions installed, and the " +
                            "application log including the error. Passwords, positions and hardware " +
                            "addresses are never included."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onAlwaysSendChanged(!alwaysSend) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = alwaysSend, onCheckedChange = onAlwaysSendChanged)
                    Text(
                        motoHubText("Send reports automatically from now on"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                // Consent is the legal ground this report travels on, so the full account of
                // what happens to it has to be reachable from the question itself, not from a
                // settings page the rider would have to go looking for afterwards.
                TextButton(
                    onClick = onOpenPrivacyNotice,
                    contentPadding = PaddingValues(0.dp)
                ) {
                    Text(
                        motoHubText("How your data is handled"),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSend) { Text(motoHubText("Send report")) }
        },
        dismissButton = {
            OutlinedButton(onClick = onDecline) { Text(motoHubText("Not now")) }
        }
    )
}
