// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.controls

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One gesture as it reached the phone, with the instant it arrived. */
data class HandlebarGestureEvent(
    val gesture: HandlebarGesture,
    val atElapsedRealtimeMillis: Long
)

/**
 * A live view of what the handlebar is actually sending, so the mapping screen can light up
 * the gesture the rider just performed.
 *
 * This exists because gesture names cannot be trusted to describe the rider's hand. Each dash
 * model wires its buttons to different Bluetooth commands - on the CFDL16 a HELD up/down rocker
 * arrives as a "next/previous track" command, i.e. wearing the label this app calls Left/Right
 * (road test 2026-07-29). Rather than hard-code one bike's quirks into the labels, the screen
 * lets the rider press a button and shows which slot answered: their own motorcycle explains
 * itself, whatever it is.
 */
object HandlebarGestureFeed {
    private val mutableLastGesture = MutableStateFlow<HandlebarGestureEvent?>(null)
    val lastGesture: StateFlow<HandlebarGestureEvent?> = mutableLastGesture.asStateFlow()

    private val mutableCaptureOnly = MutableStateFlow(false)

    /**
     * Observable so the half that ISN'T decoding the presses can mirror the promise across the
     * bridge. An Android Auto session's gestures are recognised in Core, and this flag is set by
     * a wizard running in the companion app: without something to watch, "observed, not obeyed"
     * was true in the process that wasn't listening and false in the one that was.
     */
    val captureOnly: StateFlow<Boolean> = mutableCaptureOnly.asStateFlow()

    /**
     * While teaching the handlebar, gestures are observed but NOT acted on: otherwise every
     * press the rider is asked to perform also switches a dashboard panel or moves the Android
     * Auto cursor, and the motorcycle jumps around under them mid-calibration.
     */
    fun setCaptureOnly(enabled: Boolean) {
        mutableCaptureOnly.value = enabled
    }

    fun isCaptureOnly(): Boolean = mutableCaptureOnly.value

    /** Called by [MediaButtonBridge] for every gesture it recognises, mapped or not. */
    fun publish(gesture: HandlebarGesture) {
        mutableLastGesture.value = HandlebarGestureEvent(gesture, SystemClock.elapsedRealtime())
    }

    fun clear() {
        mutableLastGesture.value = null
    }
}
