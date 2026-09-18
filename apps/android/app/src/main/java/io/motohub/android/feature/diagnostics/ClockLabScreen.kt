// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.motohub.android.i18n.motoHubText
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.ThinkerRideGate
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubBackground
import io.motohub.android.ui.components.MotoHubHeader

/**
 * The Zontes clock-reset experiment bench. One button, one live log: EcBtpClockLab does the
 * Bluetooth work and every line lands both here and in the shared application log.
 */
@Composable
fun ClockLabScreen(
    state: ClockLabUiState,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onBack: () -> Unit
) {
    BackHandler(onBack = onBack)

    // The first field run burned three attempts on the missing "Nearby devices" grant: the lab
    // only logged where to find it and the rider spent minutes in system settings. Ask in place.
    val context = LocalContext.current
    val blePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        ProjectionEventLog.record(
            "CLOCKLAB",
            "Bluetooth permission results: " +
                grants.entries.joinToString { "${it.key.substringAfterLast('.')}=${it.value}" } + "."
        )
        if (grants.values.all { it }) onRun()
    }
    val runWithPermissions: () -> Unit = {
        if (ThinkerRideGate.hasBlePermissions(context)) {
            onRun()
        } else {
            blePermissionLauncher.launch(ThinkerRideGate.blePermissions)
        }
    }

    MotoHubBackground(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            MotoHubHeader(
                modifier = Modifier.fillMaxWidth(),
                trailing = { TextButton(onClick = onBack) { Text(motoHubText("Close")) } }
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel(motoHubText("SYSTEM LAB"))
                Text(motoHubText("Dash clock lab"), style = MaterialTheme.typography.headlineMedium)
                Text(
                    motoHubText(
                        "For dashes that reset their clock at every ignition cycle (Zontes and " +
                            "Voge above all). With the dash powered on, the lab connects over Bluetooth, " +
                            "listens, then pushes the time in five different shapes and records " +
                            "every byte. Ride, cycle the ignition, and share the application log: " +
                            "it will say which shape the dash accepted."
                    ),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Button(
                onClick = if (state.running) onStop else runWithPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (state.running) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    contentColor = if (state.running) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    }
                )
            ) {
                if (state.running) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onError,
                        strokeWidth = 2.dp,
                        modifier = Modifier
                            .size(20.dp)
                            .padding(end = 4.dp)
                    )
                }
                Text(
                    if (state.running) motoHubText("Stop") else motoHubText("Run clock experiments"),
                    fontWeight = FontWeight.Bold
                )
            }

            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MonoLabel(motoHubText("LIVE LOG"))
                    if (state.lines.isEmpty()) {
                        Text(
                            motoHubText("Nothing yet. Power the dash on, then run the experiments."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        state.lines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
