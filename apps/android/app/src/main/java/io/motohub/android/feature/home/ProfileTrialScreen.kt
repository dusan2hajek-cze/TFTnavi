// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText
import io.motohub.android.session.DashboardDeliveryReport
import io.motohub.android.tbox.ProfileOverride
import io.motohub.android.tbox.ProfileSuggestions
import io.motohub.android.ui.components.MotoHubDetailScreen
import io.motohub.android.ui.components.MotoHubRadioRow
import kotlin.math.roundToInt

/**
 * The screen a rider reaches from "the dashboard is not showing this" - the thing rider 315e0af3
 * needed and found by accident, two days late, in the Garage.
 *
 * It is not the Garage's override list with a different title. That one is a flat nineteen
 * entries in table order, real motorcycles interleaved with one-question experiments, and no way
 * to tell which could possibly apply to the dash in front of you. Here the ordering carries the
 * app's own evidence (see [ProfileSuggestions]) and every row says why it is being offered, so
 * the rider is choosing between explanations rather than guessing from a menu.
 */
@Composable
internal fun ProfileTrialScreen(
    warning: DashboardDeliveryReport,
    suggestions: List<ProfileSuggestions.Suggestion>,
    onTryProfile: (ProfileOverride) -> Unit,
    onBack: () -> Unit
) {
    MotoHubDetailScreen(
        title = motoHubText("Try another profile"),
        backLabel = motoHubText("‹ Back"),
        onBack = onBack
    ) {
        EvidenceCard(warning)
        Text(
            motoHubText(
                "The connection to your motorcycle is fine - the dashboard is simply refusing " +
                    "the picture in the format it is being sent. A different profile changes " +
                    "that format. Picking one reconnects straight away, and if the dashboard " +
                    "starts showing it, MOTO-HUB will ask whether to keep it."
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp, bottom = 12.dp)
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { suggestion ->
                MotoHubRadioRow(
                    title = motoHubText(suggestion.override.label),
                    description = describe(suggestion),
                    // Nothing is selected here: this screen is a list of things to TRY, and a
                    // radio that looks already-answered invites the rider to close it again.
                    selected = false,
                    onClick = { onTryProfile(suggestion.override) }
                )
            }
        }
    }
}

/**
 * The strip that opens [ProfileTrialScreen], shown above whatever tab the rider is on.
 *
 * Above the tabs on purpose: the rider it is for is not looking at the connection screen. Their
 * dashboard says READY, so they are somewhere else in the app - or on the bike, glancing down -
 * wondering why the TFT is frozen. A notice filed under the screen that claims everything is
 * fine is a notice nobody reads.
 */
@Composable
internal fun DeliveryWarningBanner(onOpen: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                motoHubText("YOUR DASHBOARD IS NOT SHOWING THIS"),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                motoHubText("Connected, but the picture is being refused. Tap to try another profile."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

/**
 * The two numbers the verdict was reached on, shown rather than summarised.
 *
 * A rider who has been told "it should work" by an app that was wrong deserves to see what the
 * app is actually looking at - and these numbers are legible without any of the vocabulary
 * underneath them.
 */
@Composable
private fun EvidenceCard(warning: DashboardDeliveryReport) {
    val percent = (warning.rejectedShare * 100).roundToInt()
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    motoHubText("CONNECTED, NOT DISPLAYING"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                motoHubText(
                    "Your dashboard refused %1\$d%% of the picture it was sent (%2\$d frames out of %3\$d).",
                    percent,
                    warning.rejected,
                    warning.rejected + warning.accepted
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun describe(suggestion: ProfileSuggestions.Suggestion): String {
    val note = when (suggestion.reason) {
        ProfileSuggestions.Reason.IDENTIFIED ->
            motoHubText("This is what your dashboard reports itself to be.")
        ProfileSuggestions.Reason.SAME_WIRE ->
            motoHubText("Speaks the same protocol your motorcycle is already using.")
        ProfileSuggestions.Reason.NEUTRAL ->
            motoHubText("Plain settings, no assumptions - a good thing to fall back to.")
        ProfileSuggestions.Reason.EXPERIMENT ->
            motoHubText("An experiment for one dashboard's open question. It may do nothing.")
        ProfileSuggestions.Reason.OTHER -> ""
    }
    return listOf(motoHubText(suggestion.override.description), note)
        .filter { it.isNotBlank() }
        .joinToString(" ")
}
