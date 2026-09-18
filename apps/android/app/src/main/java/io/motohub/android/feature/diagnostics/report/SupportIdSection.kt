// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.i18n.motoHubText
import io.motohub.android.session.InstallationId
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.ui.components.MotoHubActionRow
import io.motohub.android.ui.components.MotoHubCardGroup
import io.motohub.android.ui.components.ToggleRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The top of Diagnostics: the Support ID a rider reads out when asking for help, the switch for
 * automatic reports, and the button that sends one right now.
 *
 * [loggingEnabled] is passed in rather than read here so this section recomposes when the master
 * switch below it is flipped: with no parameters at all Compose skips it, and the notice about a
 * frozen log would appear only on the next visit to the screen - by which time the rider has
 * already sent the report.
 */
@Composable
fun SupportIdSection(loggingEnabled: Boolean) {
    val context = LocalContext.current
    var supportId by remember { mutableStateOf<String?>(null) }
    var autoUpload by remember { mutableStateOf(DiagnosticReportSettings.autoUploadEnabled(context)) }
    var reviewingNotice by remember { mutableStateOf(false) }
    var readingPrivacyNotice by remember { mutableStateOf(false) }
    val status by DiagnosticReportScheduler.status.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        DiagnosticReportScheduler.refreshStatus(context)
        supportId = withContext(Dispatchers.IO) {
            val activeId = runCatching { MotorcycleProfileStore(context).load()?.id }.getOrNull()
            InstallationId.supportId(context, activeId)
        }
    }

    val shortId = supportId?.let(InstallationId::shortForm) ?: "…"
    // Read when the switch changes, not on every recomposition: the answer only moves while
    // logging is on, and while it is on this is not shown at all.
    val frozenAt = remember(loggingEnabled) {
        if (loggingEnabled) null else ProjectionEventLog.lastEntryAtMillis()
    }
    if (!loggingEnabled) LoggingOffNotice(frozenAt)
    MotoHubCardGroup {
        MotoHubActionRow(
            title = motoHubText("Support ID"),
            description = motoHubText("Tap to copy. Quote it when asking for help; it identifies this phone and motorcycle."),
            value = shortId,
            onClick = {
                val id = supportId ?: return@MotoHubActionRow
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(motoHubText("MOTO-HUB Support ID"), id)
                )
                Toast.makeText(context, motoHubText("Support ID copied"), Toast.LENGTH_SHORT).show()
            }
        )
        MotoHubActionRow(
            title = motoHubText("What gets sent"),
            description = motoHubText("Read what a report contains, then choose whether to send them"),
            onClick = { reviewingNotice = true }
        )
        // The long form behind the summary above, and the only place a rider can find out how to
        // have their reports deleted - which is why it sits here rather than behind the notice.
        MotoHubActionRow(
            title = motoHubText("How your data is handled"),
            description = motoHubText("Who holds your reports, how long they are kept, and how to have them deleted"),
            onClick = { readingPrivacyNotice = true }
        )
        MotoHubActionRow(
            title = motoHubText("Send diagnostics now"),
            // The frozen log is said here as well as in the notice above, because this is the row
            // the thumb is on when it decides. Still tappable: a report is the rider's to send,
            // and even a stale log carries the identity and version fields support asks for.
            description = when {
                !DiagnosticReportUploader.configured ->
                    motoHubText("This build has no collector configured")
                !loggingEnabled && frozenAt != null ->
                    motoHubText("Logging is off; the log would end at %1\$s", stamp(frozenAt))
                !loggingEnabled -> motoHubText("Logging is off; there is no log to send")
                else -> status.describe()
            },
            onClick = {
                if (!DiagnosticReportUploader.configured || status.inProgress) return@MotoHubActionRow
                ProjectionEventLog.record("SUPPORT", "Diagnostics report requested by the rider.")
                DiagnosticReportScheduler.sendNow(context)
            }
        )
    }
    // Answering here is a real answer: a rider who reads what would leave the phone and decides
    // either way must be able to say so at that moment, not be sent looking for a separate
    // switch. Closing it without answering leaves the setting exactly as it was.
    if (reviewingNotice) {
        DiagnosticReportNoticeDialog(
            onAccept = {
                reviewingNotice = false
                autoUpload = true
                DiagnosticReportSettings.setAutoUploadEnabled(context, true)
                ProjectionEventLog.record("SETTINGS", "Automatic diagnostics upload confirmed from the notice.")
            },
            onDecline = {
                reviewingNotice = false
                autoUpload = false
                DiagnosticReportSettings.setAutoUploadEnabled(context, false)
                DiagnosticReportSettings.setPending(context, false)
                ProjectionEventLog.record("SETTINGS", "Automatic diagnostics upload declined from the notice.")
            },
            onDismiss = { reviewingNotice = false }
        )
    }
    if (readingPrivacyNotice) {
        PrivacyNoticeDialog(onDismiss = { readingPrivacyNotice = false })
    }
    Spacer(Modifier.height(8.dp))
    ToggleRow(
        title = motoHubText("Send diagnostics automatically"),
        description = motoHubText(
            "Sends the report above to the developer at most once a day, after an update, or after a " +
                "crash - only over a connection with Internet access. Dashboard and phone models, app " +
                "versions and the application log; never passwords, positions or hardware addresses."
        ),
        checked = autoUpload,
        onCheckedChange = {
            autoUpload = it
            DiagnosticReportSettings.setAutoUploadEnabled(context, it)
            if (!it) DiagnosticReportSettings.setPending(context, false)
            ProjectionEventLog.record("SETTINGS", "Automatic diagnostics upload changed to enabled=$it.")
        }
    )
}

/**
 * Says that the log stopped, and when.
 *
 * Turning "Enable logging" off keeps everything recorded until then - the switch stops new
 * entries, it never erases what is already there - which is exactly what makes this worth
 * saying: what is left still reads like a complete log, and a report sent now carries it. The
 * switch is also per-app, so ADVANCED can be frozen while CORE keeps writing, and the two halves
 * of one report then describe two different weeks.
 *
 * Not styled as an error and offering nothing to tap: nothing is broken, this is a setting the
 * rider chose, and the switch that undoes it is a few rows below.
 */
@Composable
private fun LoggingOffNotice(frozenAtMillis: Long?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                motoHubText("LOGGING IS OFF"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                if (frozenAtMillis != null) {
                    motoHubText(
                        "Nothing has been recorded since %1\$s. What was logged before then is " +
                            "kept - turning logging off stops new entries, it never deletes the " +
                            "old ones.",
                        stamp(frozenAtMillis)
                    )
                } else {
                    motoHubText("Nothing has been recorded, and there is no earlier log either.")
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                motoHubText(
                    "A report sent now carries that log, so it says nothing about what the app " +
                        "is doing today. To report a problem, turn Enable logging back on, make " +
                        "it happen again, then send."
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/** A log entry's own timestamp, in the phone's locale: this is read against a rider's memory. */
private fun stamp(epochMillis: Long): String = STAMP_FORMAT.format(Date(epochMillis))

private val STAMP_FORMAT = SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
