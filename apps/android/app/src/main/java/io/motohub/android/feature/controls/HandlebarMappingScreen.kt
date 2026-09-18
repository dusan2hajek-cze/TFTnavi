// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import android.Manifest
import android.os.Build
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.motohub.android.androidauto.AndroidAutoRuntime
import io.motohub.android.androidauto.AndroidAutoRuntimeState
import io.motohub.android.data.MotorcycleProfileStore
import io.motohub.android.i18n.motoHubText
import io.motohub.android.ui.components.MotoHubDialogBody
import io.motohub.android.session.MotorcycleProfile
import io.motohub.android.session.ProjectionEventLog
import androidx.compose.foundation.layout.fillMaxSize
import io.motohub.android.ui.components.MonoLabel
import io.motohub.android.ui.components.MotoHubDetailScreen
import io.motohub.android.ui.components.ScreenSlideTransition
import io.motohub.android.ui.components.ToggleRow
import io.motohub.android.ui.theme.MotoHubFavorite
import io.motohub.android.ui.theme.MotoHubLive
import io.motohub.android.ui.theme.MotoHubManual
import io.motohub.android.ui.theme.MotoHubMirror
import kotlinx.coroutines.delay

/**
 * Handlebar mapping, organised the way a rider thinks about it: one card per BUTTON, with a
 * row for press, double press and hold.
 *
 * It used to list Bluetooth gesture names instead - and those do not line up with the buttons.
 * A held up rocker on a CFMOTO 700MT arrives as the "next track" command, which on a handlebar
 * with a real left button is that button instead. Guessing produced labels that were wrong on
 * one bike or the other, so nothing is guessed here: the volume steps and play/pause are the
 * only bindings assumed, everything else is learned from the rider.
 */
@Composable
fun HandlebarMappingScreen(
    onBack: () -> Unit,
    backLabel: String = motoHubText("‹ Controls"),
    // The handlebar's own on/off belongs on this screen rather than on the page that opens
    // it. Applying it to a running session is not something this screen can do on its own,
    // though - which
    // projection is live, and which side of the app owns its bridge, is known only to the
    // caller. Null means the caller has no session to apply it to, and no switch is shown.
    captureControl: ((Boolean) -> Boolean)? = null,
    captureDetail: (@Composable (Boolean) -> Unit)? = null
) {
    val context = LocalContext.current
    var revision by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<PhysicalPress?>(null) }
    var calibrating by remember { mutableStateOf(false) }
    var showTeachPrerequisiteDialog by remember { mutableStateOf(false) }
    // Set when the prerequisite dialog asked MainActivity to start a session, so the rider
    // doesn't have to notice it came up and tap "Teach my handlebar" a second time - the whole
    // point of asking was to get straight into teaching once there is something to capture.
    var awaitingSessionForTeach by remember { mutableStateOf(false) }
    val androidAutoState by AndroidAutoRuntime.state.collectAsState()
    LaunchedEffect(androidAutoState, awaitingSessionForTeach) {
        if (!awaitingSessionForTeach) return@LaunchedEffect
        // Streaming specifically, not just Preparing/ReceiverReady: MediaButtonBridge only
        // flips captureActive once video is actually flowing (see onVideoReady), so opening the
        // wizard any earlier would just look broken - presses would register nothing yet.
        if (androidAutoState is AndroidAutoRuntimeState.Streaming) {
            awaitingSessionForTeach = false
            calibrating = true
        }
    }
    val lastGesture by HandlebarGestureFeed.lastGesture.collectAsState()
    var litGesture by remember { mutableStateOf<HandlebarGesture?>(null) }
    LaunchedEffect(lastGesture?.atElapsedRealtimeMillis) {
        // The feed keeps its last event forever, so without the age check re-opening this
        // screen lights a button for a press the rider made minutes ago.
        //
        // Every path assigns the light, none of them returns leaving it as it was: the previous
        // run is cancelled the moment a newer event arrives, so a bare return would strand
        // whatever that run had switched on with nothing left to switch it off.
        val event = lastGesture
        val age = event?.let { SystemClock.elapsedRealtime() - it.atElapsedRealtimeMillis }
        if (event == null || age == null || age >= HIGHLIGHT_MILLIS) {
            litGesture = null
            return@LaunchedEffect
        }
        litGesture = event.gesture
        delay(HIGHLIGHT_MILLIS - age)
        litGesture = null
    }

    // Every level closes exactly one step; without these, back fell through to the caller.
    BackHandler(enabled = calibrating) { calibrating = false }
    BackHandler(enabled = editing != null) { editing = null }

    val pressBeingEdited = editing
    val editingGesture = pressBeingEdited?.let { HandlebarCalibration.gestureFor(context, it) }
    // A press with nothing taught for it yet cannot be edited - drop back to the list rather
    // than open a picker for a gesture that does not exist.
    if (pressBeingEdited != null && editingGesture == null) editing = null

    val screenState = when {
        calibrating -> HandlebarScreenState.Calibrating
        pressBeingEdited != null && editingGesture != null ->
            HandlebarScreenState.Editing(pressBeingEdited, editingGesture)
        else -> HandlebarScreenState.List
    }
    ScreenSlideTransition(
        screen = screenState,
        isBase = { it is HandlebarScreenState.List },
        modifier = Modifier.fillMaxSize()
    ) { shown ->
        when (shown) {
            HandlebarScreenState.Calibrating -> {
                // The pin has to be held for the whole wizard, not only while a step is on
                // screen: an AVRCP volume rocker is invisible without it, so the volume steps
                // looked dead and got skipped. Tied to the screen rather than to onDone so that
                // leaving with Back releases it too.
                DisposableEffect(Unit) {
                    MediaButtonBridge.setCalibrating(true)
                    onDispose { MediaButtonBridge.setCalibrating(false) }
                }
                HandlebarCalibrationScreen(
                    onDone = {
                        revision++
                        calibrating = false
                        // The taught set may have just changed whether volume presses exist on
                        // this handlebar — a live capture must re-decide the volume pin NOW, not
                        // at the next session (stale pin = phone volume keys acting as handlebar
                        // presses).
                        MediaButtonBridge.refreshVolumeGestureUse()
                    }
                )
            }
            is HandlebarScreenState.Editing -> HandlebarActionPicker(
                title = shown.press.label,
                current = HandlebarControlStore.action(context, shown.gesture),
                onPicked = { action ->
                    HandlebarControlStore.setAction(context, shown.gesture, action)
                    ProjectionEventLog.record(
                        "CONTROLS",
                        "Handlebar ${shown.press.id} (${shown.gesture.id}) -> ${action.id}"
                    )
                    revision++
                    editing = null
                },
                onBack = { editing = null }
            )
            HandlebarScreenState.List -> HandlebarListContent(
                captureControl = captureControl,
                captureDetail = captureDetail,
                context = context,
                backLabel = backLabel,
                onBack = onBack,
                revision = revision,
                litGesture = litGesture,
                onTeach = {
                    // The wizard only ever sees a press through a live capture (MediaButtonBridge
                    // captureActive), so with no Android Auto session at all there is nothing to
                    // teach from and it would just sit there looking broken. Asked before it
                    // opens rather than inside it, so the rider is never left guessing why
                    // nothing on the motorcycle does anything once it has.
                    val sessionRunning = androidAutoState is AndroidAutoRuntimeState.Streaming ||
                        androidAutoState is AndroidAutoRuntimeState.ReceiverReady ||
                        androidAutoState is AndroidAutoRuntimeState.Preparing
                    if (sessionRunning) calibrating = true else showTeachPrerequisiteDialog = true
                },
                onEdit = { editing = it },
                onReset = {
                    HandlebarControlStore.reset(context)
                    revision++
                    ProjectionEventLog.record("CONTROLS", "Handlebar mapping reset to defaults.")
                }
            )
        }
    }
    if (showTeachPrerequisiteDialog) {
        HandlebarTeachPrerequisiteDialog(
            motorcycles = remember { MotorcycleProfileStore(context).loadAll() },
            onConnect = { profile ->
                ProjectionEventLog.record(
                    "CONTROLS",
                    "Handlebar teach requested a connect to ${profile.ssid} to get a live session."
                )
                HandlebarTeachPrerequisiteRequest.publish(
                    HandlebarTeachPrerequisiteRequest.Choice.Connect(profile.id)
                )
                awaitingSessionForTeach = true
                showTeachPrerequisiteDialog = false
            },
            onPhoneOnly = {
                ProjectionEventLog.record(
                    "CONTROLS",
                    "Handlebar teach requested a phone-only Android Auto session."
                )
                HandlebarTeachPrerequisiteRequest.publish(HandlebarTeachPrerequisiteRequest.Choice.PhoneOnly)
                awaitingSessionForTeach = true
                showTeachPrerequisiteDialog = false
            },
            onDismiss = { showTeachPrerequisiteDialog = false }
        )
    }
}

/** The list's own destinations - a screen key for [ScreenSlideTransition], not shared state. */
private sealed interface HandlebarScreenState {
    data object List : HandlebarScreenState
    data object Calibrating : HandlebarScreenState
    data class Editing(val press: PhysicalPress, val gesture: HandlebarGesture) : HandlebarScreenState
}

@Composable
private fun HandlebarListContent(
    captureControl: ((Boolean) -> Boolean)?,
    captureDetail: (@Composable (Boolean) -> Unit)?,
    context: android.content.Context,
    backLabel: String,
    onBack: () -> Unit,
    revision: Int,
    litGesture: HandlebarGesture?,
    onTeach: () -> Unit,
    onEdit: (PhysicalPress) -> Unit,
    onReset: () -> Unit
) {
    var captureEnabled by remember { mutableStateOf(HandlebarControlStore.isEnabled(context)) }
    val handlebarTaught = HandlebarCalibration.isCalibrated(context)

    MotoHubDetailScreen(
        title = motoHubText("Handlebar"),
        backLabel = backLabel,
        onBack = onBack
    ) {
        val currentRevision = revision
        Text(
            motoHubText(
                "Your motorcycle's own handlebar drives MOTO-HUB. Teach it once, then give " +
                    "each button the action you want."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (!captureEnabled) {
            CaptureOffBanner(
                managedByCompanion = HandlebarControlStore.isManagedByCompanion(context),
                onEnable = captureControl?.let { control ->
                    {
                        captureEnabled = true
                        HandlebarControlStore.setEnabled(context, true)
                        control(true)
                    }
                }
            )
        }

        MonoLabel(motoHubText("MOTORCYCLE HANDLEBAR"))
        if (captureControl != null) {
            HandlebarCaptureRow(
                checked = captureEnabled,
                onCheckedChange = { value ->
                    captureEnabled = value
                    HandlebarControlStore.setEnabled(context, value)
                    captureControl(value)
                }
            )
            captureDetail?.invoke(captureEnabled)
        }
        BluetoothStatusCard()
        LiveCaptureBanner(litGesture)
        Button(
            onClick = onTeach,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                motoHubText(if (handlebarTaught) "Teach again" else "Teach my handlebar"),
                fontWeight = FontWeight.Bold
            )
        }
        HandlebarCalibration.presentButtons(context).forEach { button ->
            ButtonCard(
                button = button,
                revision = currentRevision,
                litGesture = litGesture,
                onEdit = onEdit
            )
        }
        HandlebarTimingSection()
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text(motoHubText("Reset actions to defaults"))
        }
    }
}

/** The motorcycle's handlebar on/off, on the page that lists it as one of the sources. */
@Composable
private fun HandlebarCaptureRow(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                (if (checked) MotoHubLive else Color(0xFF8C93A0)).copy(alpha = 0.10f),
                RoundedCornerShape(16.dp)
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                motoHubText("Handlebar controls"),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                motoHubText("Use the motorcycle buttons without looking down. The active projection receives the commands first."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/**
 * The master switch, said where it is felt.
 *
 * A rider could teach every button, watch this page light up as presses arrived, and never
 * see the one switch deciding that none of them would be performed.
 */
@Composable
private fun CaptureOffBanner(managedByCompanion: Boolean, onEnable: (() -> Unit)?) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MotoHubManual.copy(alpha = 0.10f)),
        border = BorderStroke(1.dp, MotoHubManual.copy(alpha = 0.45f)),
        shape = MaterialTheme.shapes.large
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                motoHubText("BUTTON PRESSES ARE OFF"),
                style = MaterialTheme.typography.labelMedium,
                color = MotoHubManual,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                motoHubText(
                    "Presses still arrive and still show up on this page - nothing is " +
                        "performed with them."
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (managedByCompanion) {
                Text(
                    motoHubText(
                        "MOTO-HUB ADVANCED owns this setting and writes its own over this one, " +
                            "so turn it on there rather than here."
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            } else if (onEnable != null) {
                OutlinedButton(
                    onClick = onEnable,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(motoHubText("Turn presses back on"))
                }
            }
        }
    }
}

/**
 * Asked before opening the teach wizard when no Android Auto session is running - see the
 * "Teach my handlebar" button above. Offers exactly the two ways this app can get a live
 * session: connect to a saved motorcycle's T-Box (with a choice of which, if more than one is
 * saved), or start Android Auto entirely on the phone with no T-Box at all. Neither is done
 * here directly - this screen has no session-state or permission-launcher access of its own,
 * so the choice is published for MainActivity to act on (see [HandlebarTeachPrerequisiteRequest]).
 */
@Composable
private fun HandlebarTeachPrerequisiteDialog(
    motorcycles: List<MotorcycleProfile>,
    onConnect: (MotorcycleProfile) -> Unit,
    onPhoneOnly: () -> Unit,
    onDismiss: () -> Unit
) {
    // Same AlertDialog shape as every other dialog in the app, body wrapped in MotoHubDialogBody
    // so a rider with several saved motorcycles - or a large system font - can still scroll to
    // the last option instead of having it clipped away.
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(motoHubText("Android Auto isn't running")) },
        text = {
            MotoHubDialogBody {
                Text(
                    motoHubText(
                        "Teaching the handlebar needs a live Android Auto session to capture " +
                            "presses. Connect to the motorcycle, or start it on this phone " +
                            "without a T-Box."
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                motorcycles.forEach { profile ->
                    OutlinedButton(
                        onClick = { onConnect(profile) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            motoHubText(
                                "Connect to %1\$s",
                                profile.displayName?.takeIf(String::isNotBlank) ?: profile.ssid
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onPhoneOnly) {
                Text(motoHubText("Start on this phone (no T-Box)"))
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text(motoHubText("Cancel"))
            }
        }
    )
}

/**
 * The buttons ride Bluetooth AVRCP, not the T-Box link: a phone that never paired to the
 * motorcycle receives nothing, and no amount of mapping can fix that. This card shows what is
 * connected RIGHT NOW (queried from the live audio profiles, never the bond list) so the rider
 * knows whether to blame the pairing before blaming the mapping. It re-queries on every resume,
 * so coming back from the system Bluetooth settings shows the fresh state.
 */
@Composable
private fun BluetoothStatusCard() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<BluetoothStatus.Status?>(null) }
    LaunchedEffect(refresh) {
        BluetoothStatus.query(context) { fresh -> status = fresh }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refresh++ }
    val current = status
    // A2DP/HEADSET "connected device" only means something for an AVRCP dash - a HID remote
    // pairs as a keyboard and never shows up in that list, so current.describe() would call an
    // actually-working HID remote "no audio device connected" (field report 2026-08-13). HID
    // mode has no per-device connection signal to show at all here; "ready" is the same
    // Bluetooth-on-and-permitted check that actually gates capture (see
    // BluetoothStatus.canReceiveHandlebarKeys).
    val hidMode = HandlebarControlStore.inputMode(context) == HandlebarInputMode.HID
    val connected = current?.connected == true
    val ready = if (hidMode) current?.enabled == true && current.permitted else connected
    val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        current?.permitted == false

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (ready) {
            MotoHubLive.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    Modifier.size(10.dp).background(
                        if (ready) MotoHubLive
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        CircleShape
                    )
                )
                Text(
                    motoHubText("Motorcycle Bluetooth"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                when {
                    current == null -> motoHubText("Checking Bluetooth…")
                    !hidMode -> current.describe()
                    !current.supported -> motoHubText("This phone has no Bluetooth.")
                    !current.enabled -> motoHubText("Bluetooth is off — turn it on, then pair the remote as a keyboard.")
                    !current.permitted -> motoHubText(
                        "Allow Bluetooth access so the app can read the remote's key presses."
                    )
                    else -> motoHubText(
                        "Bluetooth is on. A HID remote doesn't show up here as a connected " +
                            "device — if presses still aren't recognized, check it is paired " +
                            "in system Bluetooth settings and that Accessibility is turned on."
                    )
                },
                style = MaterialTheme.typography.bodyMedium
            )
            if (current != null && current.supported) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (needsPermission) {
                        Button(
                            onClick = { permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT) },
                            modifier = Modifier.weight(1f)
                        ) { Text(motoHubText("Allow Bluetooth")) }
                    }
                    OutlinedButton(
                        onClick = { BluetoothStatus.openBluetoothSettings(context) },
                        modifier = Modifier.weight(1f)
                    ) { Text(motoHubText("Bluetooth settings")) }
                }
            }
        }
    }
}

/** One physical button, with its three ways of being pressed. */
@Composable
private fun ButtonCard(
    button: HandlebarButton,
    revision: Int,
    litGesture: HandlebarGesture?,
    onEdit: (PhysicalPress) -> Unit
) {
    val context = LocalContext.current
    val accent = button.accent()
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.extraLarge,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier.size(42.dp).background(accent.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(button.glyph, style = MaterialTheme.typography.titleLarge, color = accent)
                }
                Text(
                    motoHubText(button.label),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            PressKind.entries.forEach { kind ->
                val press = PhysicalPress.entries.first { it.button == button && it.kind == kind }
                if (HandlebarCalibration.isMissing(context, press)) return@forEach
                val gesture = remember(press, revision) { HandlebarCalibration.gestureFor(context, press) }
                PressRow(
                    kind = kind,
                    action = gesture?.let { HandlebarControlStore.action(context, it) },
                    lit = gesture != null && gesture == litGesture,
                    onClick = { if (gesture != null) onEdit(press) }
                )
            }
        }
    }
}

/** A press / double / hold row: what it is, and what it does. */
@Composable
private fun PressRow(
    kind: PressKind,
    action: HandlebarAction?,
    lit: Boolean,
    onClick: () -> Unit
) {
    val highlight by animateFloatAsState(if (lit) 1f else 0f, label = "pressHighlight")
    val family = action?.family()
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = (highlight * 2).dp,
                color = MotoHubLive.copy(alpha = highlight),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(enabled = action != null, onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                motoHubText(kind.label),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = if (action == null) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (action == null || family == null) {
                // Honest about the gap: no motorcycle command is known for this press yet,
                // so the app cannot pretend to map it.
                Text(
                    motoHubText("not detected yet"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            } else {
                Surface(shape = RoundedCornerShape(9.dp), color = family.color.copy(alpha = 0.16f)) {
                    Text(
                        motoHubText(action.shortLabel()),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = family.color
                    )
                }
                Text("›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Asks for each press in turn and records which command the motorcycle answered with.
 * "I don't have it" hides that row for good, so the screen ends up describing this
 * handlebar and no other.
 */
@Composable
private fun HandlebarCalibrationScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(0) }
    var learned by remember { mutableStateOf(0) }
    val lastGesture by HandlebarGestureFeed.lastGesture.collectAsState()
    val press = PhysicalPress.entries.getOrNull(step)
    // The instant this step began. A gesture older than it belongs to a previous step - or to
    // before the screen even opened, which is how a stale feed value once got recorded as
    // "up_press = trackBack" with the rider's hand nowhere near the handlebar.
    var stepStartedAt by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    // Feedback for a gesture that arrived but didn't match what this step needs - shown instead
    // of silently accepting it (see the LaunchedEffect below) or silently ignoring it.
    var mismatchHint by remember { mutableStateOf<String?>(null) }
    // Feedback for a step that is skipped automatically because it can never be satisfied (a
    // volume-mapped button has no hold gesture at all) - shown instead of silently advancing
    // past it with nothing on screen to explain why (field report 2026-08-13).
    var skipNotice by remember { mutableStateOf<String?>(null) }

    /** The gesture recorded for this button's PRESS step, taught earlier in the fixed
     *  press/double/hold order - tells us which family (and so which double/hold siblings, if
     *  any) this physical button belongs to. */
    fun baseGestureFor(current: PhysicalPress): HandlebarGesture? = PhysicalPress.entries
        .firstOrNull { it.button == current.button && it.kind == PressKind.PRESS }
        ?.let { HandlebarCalibration.gestureFor(context, it) }

    LaunchedEffect(step) {
        stepStartedAt = SystemClock.elapsedRealtime()
        mismatchHint = null
        skipNotice = null
        val current = press ?: return@LaunchedEffect
        if (current.kind == PressKind.PRESS) return@LaunchedEffect
        val baseGesture = baseGestureFor(current)
        val expected = if (current.kind == PressKind.DOUBLE) {
            baseGesture?.doubleSibling()
        } else {
            baseGesture?.longSibling()
        }
        if (baseGesture != null && expected == null) {
            // Known upfront, before any press arrives: this button's family has no hold gesture
            // at all (volume never does - see HandlebarGesture.longSibling). Waiting for one
            // would hang forever, so this is announced and skipped immediately instead of
            // silently advancing past it on the next unrelated press.
            skipNotice = "This button has no hold - skipping automatically."
            ProjectionEventLog.record(
                "CONTROLS",
                "Skipped ${current.id}: ${baseGesture.id} has no hold sibling."
            )
            learned++
            delay(SKIP_NOTICE_MILLIS)
            step++
        }
    }

    // Gestures are observed, not obeyed, for as long as this screen is up.
    DisposableEffect(Unit) {
        HandlebarGestureFeed.setCaptureOnly(true)
        onDispose { HandlebarGestureFeed.setCaptureOnly(false) }
    }

    LaunchedEffect(lastGesture?.atElapsedRealtimeMillis, step) {
        val event = lastGesture ?: return@LaunchedEffect
        val current = press ?: return@LaunchedEffect
        if (event.atElapsedRealtimeMillis <= stepStartedAt) return@LaunchedEffect
        // The no-hold-exists case is announced and skipped by the step-change effect above,
        // before any press arrives - this only validates the shape of an actual incoming gesture.
        if (skipNotice != null) return@LaunchedEffect

        // The "press" step for this same physical button was always taught first (PhysicalPress
        // orders press/double/hold together per button) - its recorded gesture is this button's
        // family, and tells us which gesture id actually PROVES a double or a hold happened,
        // rather than accepting whatever fires next (field report 2026-08-13: a single tap
        // during the double/hold step silently overwrote both with the plain press).
        val baseGesture = baseGestureFor(current)
        val expected = when (current.kind) {
            PressKind.PRESS -> null
            PressKind.DOUBLE -> baseGesture?.doubleSibling()
            PressKind.HOLD -> baseGesture?.longSibling()
        }

        if (expected != null && event.gesture != expected) {
            mismatchHint = when (current.kind) {
                PressKind.DOUBLE -> "That was a single press - press twice quickly for double."
                PressKind.HOLD -> "That was a quick tap - press and hold a moment longer."
                PressKind.PRESS -> null
            }
            return@LaunchedEffect
        }

        mismatchHint = null
        HandlebarCalibration.record(context, current, event.gesture)
        ProjectionEventLog.record("CONTROLS", "Calibrated ${current.id} = ${event.gesture.id}")
        learned++
        delay(CALIBRATION_CONFIRM_MILLIS)
        step++
    }

    MotoHubDetailScreen(
        title = motoHubText("Teach my handlebar"),
        backLabel = motoHubText("‹ Handlebar"),
        onBack = onDone
    ) {
        if (press == null) {
            Text(
                motoHubText("Done - %1\$d presses learned.", learned),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                motoHubText("Every card now describes your handlebar. Buttons you marked as absent are gone."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp)
            ) { Text(motoHubText("Finish"), fontWeight = FontWeight.Bold) }
            return@MotoHubDetailScreen
        }

        MonoLabel(motoHubText("STEP %1\$d OF %2\$d", step + 1, PhysicalPress.entries.size))
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MotoHubLive.copy(alpha = 0.14f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    motoHubText("On the motorcycle, do this"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    motoHubText(press.label),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        mismatchHint?.let { hint ->
            Text(
                motoHubText(hint),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
        skipNotice?.let { notice ->
            Text(
                motoHubText(notice),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
        }
        Text(
            motoHubText(
                "Nothing happens? Some dashboards keep a button for themselves and send the " +
                    "phone nothing at all - on a CFMOTO CFDL16 that is exactly what a SHORT " +
                    "rocker press does. Mark it as absent and it stops taking up space."
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    HandlebarCalibration.recordMissing(context, press)
                    ProjectionEventLog.record(
                        "CONTROLS",
                        "Marked ${press.id} as absent from this handlebar."
                    )
                    step++
                },
                modifier = Modifier.weight(1f)
            ) { Text(motoHubText("Not on my bike")) }
            OutlinedButton(
                onClick = {
                    // Logged for the same reason the automatic skip above is: a wizard run to the
                    // end teaches nothing if every step was skipped, and a support case cannot
                    // tell that from a wizard whose presses never arrived. Support 0df154af read
                    // as the second and may well have been the first.
                    ProjectionEventLog.record("CONTROLS", "Skipped ${press.id}; rider tapped Skip.")
                    step++
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(motoHubText("Skip"))
            }
        }
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text(motoHubText("Stop here"))
        }
    }
}

/** Timing, where the gestures it governs live - not on a separate screen from them. */
@Composable
private fun HandlebarTimingSection() {
    val context = LocalContext.current
    var doubleTap by remember { mutableStateOf(HandlebarTimingPrefs.doubleTap(context)) }
    var hold by remember { mutableStateOf(HandlebarTimingPrefs.selectHold(context)) }
    var eager by remember { mutableStateOf(HandlebarTimingPrefs.eagerSingles(context)) }
    var holdsOn by remember { mutableStateOf(HandlebarTimingPrefs.holdsEnabled(context)) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MonoLabel(motoHubText("TIMING"))
        Text(
            motoHubText("How long the app waits before deciding a press was double, or held."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ToggleRow(
            title = motoHubText("Snappy singles"),
            description = motoHubText(
                "A single press acts immediately instead of waiting out the double-press " +
                    "window. A double still works - it just runs the single first."
            ),
            checked = eager,
            onCheckedChange = { value ->
                eager = value
                HandlebarTimingPrefs.setEagerSingles(context, value)
                ProjectionEventLog.record("CONTROLS", "Handlebar snappy singles set to $value.")
            }
        )
        ToggleRow(
            title = motoHubText("Hold gestures"),
            description = motoHubText(
                "Turn off if your handlebar needs a long physical press for every click - " +
                    "otherwise those presses are eaten as holds."
            ),
            checked = holdsOn,
            onCheckedChange = { value ->
                holdsOn = value
                HandlebarTimingPrefs.setHoldsEnabled(context, value)
                ProjectionEventLog.record("CONTROLS", "Handlebar hold gestures set to $value.")
            }
        )
        SegmentedChoice(
            title = motoHubText("Double press window"),
            options = DoubleTapDelay.entries.map { motoHubText(it.label) },
            selectedIndex = DoubleTapDelay.entries.indexOf(doubleTap),
            accent = MotoHubMirror,
            onSelected = { index ->
                doubleTap = DoubleTapDelay.entries[index]
                HandlebarTimingPrefs.setDoubleTap(context, doubleTap)
            }
        )
        SegmentedChoice(
            title = motoHubText("Hold time"),
            options = SelectHoldDelay.entries.map { motoHubText(it.label) },
            selectedIndex = SelectHoldDelay.entries.indexOf(hold),
            accent = MotoHubFavorite,
            onSelected = { index ->
                hold = SelectHoldDelay.entries[index]
                HandlebarTimingPrefs.setSelectHold(context, hold)
            }
        )
    }
}

/** A row of equal segments - every choice visible at once, unlike a dropdown. */
@Composable
private fun SegmentedChoice(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    accent: Color,
    onSelected: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                options.forEachIndexed { index, option ->
                    val selected = index == selectedIndex
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(9.dp),
                        color = if (selected) accent.copy(alpha = 0.9f) else Color.Transparent,
                        onClick = { onSelected(index) }
                    ) {
                        Box(Modifier.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                            Text(
                                option,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color(0xFF10151A) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Shows the most recent press the motorcycle sent, named as the rider taught it. */
@Composable
private fun LiveCaptureBanner(litGesture: HandlebarGesture?) {
    val context = LocalContext.current
    val active = litGesture != null
    val background by animateColorAsState(
        if (active) MotoHubLive.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        label = "captureBackground"
    )
    Surface(shape = RoundedCornerShape(16.dp), color = background, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            val dotColor by animateColorAsState(
                if (active) MotoHubLive else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                label = "captureDot"
            )
            Box(Modifier.size(10.dp).background(dotColor, CircleShape))
            Column(Modifier.weight(1f)) {
                Text(
                    motoHubText(if (active) "Received" else "Listening"),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    litGesture?.let { gesture ->
                        HandlebarCalibration.pressFor(context, gesture)?.label
                            ?: motoHubText("A button the app has not been taught yet")
                    } ?: motoHubText("Press a handlebar button"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/** Full-width action chooser: every option visible and grouped, instead of a cramped dropdown. */
@Composable
private fun HandlebarActionPicker(
    title: String,
    current: HandlebarAction,
    onPicked: (HandlebarAction) -> Unit,
    onBack: () -> Unit
) {
    MotoHubDetailScreen(
        title = motoHubText(title),
        backLabel = motoHubText("‹ Handlebar"),
        onBack = onBack
    ) {
        Text(
            motoHubText("Choose what this press does."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        ActionFamily.entries.forEach { family ->
            val actions = HandlebarAction.entries.filter { it.family() == family }
            if (actions.isEmpty()) return@forEach
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoLabel(motoHubText(family.title))
                if (family == ActionFamily.DASHBOARD) {
                    // Chosen from here it looks like any other action, and the rider finds out it
                    // is not one only by reading a log line. Field case, 2026-08-29: every front
                    // button of an Xbox pad mapped to these, in an Android Auto session, every
                    // press resolved and then dropped.
                    Text(
                        motoHubText(
                            "These drive the Ride Dashboard, so they do nothing unless it is " +
                                "running - an Android Auto session on its own is not enough."
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                actions.forEach { candidate ->
                    ActionOptionRow(
                        action = candidate,
                        family = family,
                        selected = candidate == current,
                        onClick = { onPicked(candidate) }
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ActionOptionRow(
    action: HandlebarAction,
    family: ActionFamily,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = if (selected) family.color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier.size(34.dp).background(family.color.copy(alpha = 0.16f), RoundedCornerShape(11.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(action.glyph(), style = MaterialTheme.typography.titleSmall, color = family.color)
            }
            Text(
                motoHubText(action.label),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
            )
            if (selected) {
                Text("✓", style = MaterialTheme.typography.titleMedium, color = family.color)
            }
        }
    }
}

private fun HandlebarButton.accent(): Color = when (this) {
    HandlebarButton.UP, HandlebarButton.DOWN -> MotoHubMirror
    HandlebarButton.LEFT, HandlebarButton.RIGHT -> MotoHubManual
    HandlebarButton.SELECT -> MotoHubLive
}

/** Action families, each with its own colour so a glance at the list groups itself. */
private enum class ActionFamily(val title: String, val color: Color) {
    CURSOR("MOVE THE CURSOR", MotoHubMirror),
    DASHBOARD("RIDE DASHBOARD", MotoHubFavorite),
    MUSIC("MUSIC", MotoHubMirror),
    SYSTEM("SYSTEM", MotoHubLive),
    NAVIGATION("NAVIGATE TO", MotoHubManual),
    OFF("OFF", Color(0xFF8C93A0))
}

private fun HandlebarAction.family(): ActionFamily = when (this) {
    HandlebarAction.NONE -> ActionFamily.OFF
    HandlebarAction.SCROLL_FORWARD, HandlebarAction.SCROLL_BACK,
    HandlebarAction.DPAD_UP, HandlebarAction.DPAD_DOWN,
    HandlebarAction.DPAD_LEFT, HandlebarAction.DPAD_RIGHT,
    HandlebarAction.SELECT -> ActionFamily.CURSOR
    HandlebarAction.BACK, HandlebarAction.HOME, HandlebarAction.ASSISTANT -> ActionFamily.SYSTEM
    HandlebarAction.NAV_1, HandlebarAction.NAV_2, HandlebarAction.NAV_3 -> ActionFamily.NAVIGATION
    HandlebarAction.DASH_NEXT_PANEL, HandlebarAction.DASH_FULLSCREEN_MAP,
    HandlebarAction.DASH_MAP_ZOOM,
    HandlebarAction.DASH_WIDGET_LEFT, HandlebarAction.DASH_WIDGET_RIGHT -> ActionFamily.DASHBOARD
    HandlebarAction.MEDIA_PLAY_PAUSE, HandlebarAction.MEDIA_NEXT, HandlebarAction.MEDIA_PREVIOUS,
    HandlebarAction.MEDIA_VOLUME_UP, HandlebarAction.MEDIA_VOLUME_DOWN -> ActionFamily.MUSIC
}

private fun HandlebarAction.glyph(): String = when (this) {
    HandlebarAction.NONE -> "–"
    HandlebarAction.SCROLL_FORWARD -> "↻"
    HandlebarAction.SCROLL_BACK -> "↺"
    HandlebarAction.DPAD_UP -> "▲"
    HandlebarAction.DPAD_DOWN -> "▼"
    HandlebarAction.DPAD_LEFT -> "◀"
    HandlebarAction.DPAD_RIGHT -> "▶"
    HandlebarAction.SELECT -> "◉"
    HandlebarAction.BACK -> "↩"
    HandlebarAction.HOME -> "⌂"
    HandlebarAction.ASSISTANT -> "🎙"
    HandlebarAction.NAV_1 -> "①"
    HandlebarAction.NAV_2 -> "②"
    HandlebarAction.NAV_3 -> "③"
    HandlebarAction.DASH_NEXT_PANEL -> "⊞"
    HandlebarAction.DASH_FULLSCREEN_MAP -> "⛶"
    HandlebarAction.DASH_MAP_ZOOM -> "◎"
    HandlebarAction.DASH_WIDGET_LEFT -> "◧"
    HandlebarAction.DASH_WIDGET_RIGHT -> "◨"
    HandlebarAction.MEDIA_PLAY_PAUSE -> "⏯"
    HandlebarAction.MEDIA_NEXT -> "⏭"
    HandlebarAction.MEDIA_PREVIOUS -> "⏮"
    HandlebarAction.MEDIA_VOLUME_UP -> "🔊"
    HandlebarAction.MEDIA_VOLUME_DOWN -> "🔉"
}

private fun HandlebarAction.shortLabel(): String = when (this) {
    HandlebarAction.NONE -> "Off"
    HandlebarAction.SCROLL_FORWARD -> "Scroll +"
    HandlebarAction.SCROLL_BACK -> "Scroll −"
    HandlebarAction.DPAD_UP -> "Up"
    HandlebarAction.DPAD_DOWN -> "Down"
    HandlebarAction.DPAD_LEFT -> "Left"
    HandlebarAction.DPAD_RIGHT -> "Right"
    HandlebarAction.SELECT -> "OK"
    HandlebarAction.BACK -> "Back"
    HandlebarAction.HOME -> "Home"
    HandlebarAction.ASSISTANT -> "Voice"
    HandlebarAction.NAV_1 -> "Place 1"
    HandlebarAction.NAV_2 -> "Place 2"
    HandlebarAction.NAV_3 -> "Place 3"
    HandlebarAction.DASH_NEXT_PANEL -> "Panel +"
    HandlebarAction.DASH_FULLSCREEN_MAP -> "Map full"
    HandlebarAction.DASH_MAP_ZOOM -> "Zoom me"
    HandlebarAction.DASH_WIDGET_LEFT -> "Widget L"
    HandlebarAction.DASH_WIDGET_RIGHT -> "Widget R"
    HandlebarAction.MEDIA_PLAY_PAUSE -> "Play"
    HandlebarAction.MEDIA_NEXT -> "Next"
    HandlebarAction.MEDIA_PREVIOUS -> "Previous"
    HandlebarAction.MEDIA_VOLUME_UP -> "Vol +"
    HandlebarAction.MEDIA_VOLUME_DOWN -> "Vol −"
}

private const val HIGHLIGHT_MILLIS = 1_400L
private const val CALIBRATION_CONFIRM_MILLIS = 700L
/** Longer than CALIBRATION_CONFIRM_MILLIS - this is an unrequested notice about a step the
 *  rider never got a chance to act on, not a confirmation of something they just did. */
private const val SKIP_NOTICE_MILLIS = 1800L
