// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MotoHubDialogBody

/**
 * The privacy notice for diagnostics reports: what is collected, who holds it, for how long, and
 * how a rider gets it deleted.
 *
 * Consent is the legal ground the whole feature stands on (nothing leaves the phone until someone
 * says yes), and consent is only worth anything if it is informed - which is what this screen is
 * for. It is reachable from both places a rider is ever asked: the crash prompt and
 * Settings ▸ Diagnostics.
 *
 * **English only, deliberately.** The two summaries that carry the actual decision -
 * [CrashDiagnosticsConsentDialog] and [DiagnosticReportNoticeDialog] - are translated into every
 * language the app ships; this is the long form behind them. Splitting a legal text across six
 * catalogues means six versions that drift apart, and a mistranslated retention period is worse
 * than an English one a rider can paste into a translator.
 *
 * Shown by both editions since 1.1.98. It was ADVANCED-only while the collector was: until then
 * the CORE flavour built with an empty `DIAGNOSTICS_ENDPOINT` and had nowhere to send a report.
 * A build made from the public repository still does, since the endpoint and key come from a
 * private properties file - so this notice describes what a RELEASED build does.
 */
@Composable
fun PrivacyNoticeDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Diagnostics privacy notice")) },
        text = {
            MotoHubDialogBody {
                Section(
                    "Who is responsible",
                    "MOTO-HUB is a non-commercial hobby project, not a company. Vincenzo " +
                        "Buonomano, its developer, is the data controller: he alone decides what " +
                        "happens to the data in a diagnostics report, and he is who you contact " +
                        "about anything on this page. Reach him on the MOTO-HUB Discord or " +
                        "through GitHub issues; both are linked under About."
                )
                Section(
                    "What is collected, and why",
                    "A report contains: your Support ID and a device identifier, your " +
                        "motorcycle's dashboard model and capabilities as the dashboard itself " +
                        "reports them, the network name (SSID) of the dashboard, your phone's " +
                        "make, model and Android version, the versions of Android Auto, MOTO-HUB " +
                        "the MOTO-HUB apps installed, your MOTO-HUB settings, and the application " +
                        "log, which includes any error or crash. It is used for one purpose: " +
                        "finding and fixing faults in MOTO-HUB."
                )
                Section(
                    "What is never collected",
                    "Your position or any route you have ridden, your dashboard password, " +
                        "hardware addresses (MAC or BSSID), the raw Android device identifier, " +
                        "your photos, contacts or accounts, and anything from the AI assistant's " +
                        "credentials. Your Support ID is a one-way hash: it identifies this " +
                        "installation across reports and cannot be turned back into your device."
                )
                Section(
                    "The legal basis is your consent",
                    "Nothing is ever sent unless you agree to it, either by answering the prompt " +
                        "after a crash or by turning automatic reports on yourself. You can " +
                        "withdraw that consent at any time by turning the switch off under " +
                        "Settings ▸ Diagnostics, and it takes effect immediately. Withdrawing it " +
                        "does not affect what was already sent, and MOTO-HUB works exactly the " +
                        "same either way. This is Article 6(1)(a) of the GDPR."
                )
                Section(
                    "Where reports go",
                    "To a server the developer runs himself, in Portugal, inside the European " +
                        "Union - not to an advertising network or an analytics company. Your " +
                        "data is never sold, never shared with third parties for their own " +
                        "purposes, and never used to profile you or to make any automated " +
                        "decision about you.\n\nMOTO-HUB also uses " +
                        "Sentry for crash telemetry. Sentry is operated by Functional Software, " +
                        "Inc., a United States company; MOTO-HUB uses its European region, so " +
                        "the data is stored in Germany. What it receives is your Support ID and " +
                        "the redacted error, and no personal data beyond that."
                )
                Section(
                    "How long they are kept",
                    "Reports are deleted automatically 90 days after they arrive. The five most " +
                        "recent reports from any one installation are kept beyond that, so a " +
                        "long-running fault can still be compared against its own history."
                )
                Section(
                    "Your rights",
                    "You can ask what is held about you, ask for it to be corrected, ask for it " +
                        "to be deleted, ask for a copy, or object to it being held at all. Quote " +
                        "your Support ID, shown at the top of Settings ▸ Diagnostics - without it " +
                        "there is no way to tell which reports are yours. Requests are answered " +
                        "within one month.\n\nIf you believe your data has been mishandled, you " +
                        "have the right to complain to a data protection authority - either the " +
                        "one in the EU country where you live or work, or CNPD, the Portuguese " +
                        "authority, which is the one this project answers to."
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text(motoHubText("Close")) }
        }
    )
}

@Composable
private fun Section(heading: String, body: String) {
    Text(
        text = heading,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
    Text(
        text = body,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
