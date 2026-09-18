// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics

import android.app.Application
import android.text.format.DateFormat
import androidx.lifecycle.AndroidViewModel
import io.motohub.android.session.ProjectionEventLog
import io.motohub.android.tbox.EcBtpClockLab
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ClockLabUiState(
    val running: Boolean = false,
    val lines: List<String> = emptyList()
)

/**
 * Drives one [EcBtpClockLab] run and mirrors its every line into both the on-screen list and the
 * application log under CLOCKLAB - the screen is for the rider standing next to the bike, the log
 * is for the shared report the experiment exists to produce.
 */
class ClockLabViewModel(application: Application) : AndroidViewModel(application) {
    private val mutableUiState = MutableStateFlow(ClockLabUiState())
    val uiState: StateFlow<ClockLabUiState> = mutableUiState.asStateFlow()

    private var lab: EcBtpClockLab? = null

    fun run() {
        if (mutableUiState.value.running) return
        mutableUiState.value = ClockLabUiState(running = true)
        ProjectionEventLog.record("CLOCKLAB", "Dash clock lab started by the rider.")
        val started = EcBtpClockLab(
            context = getApplication(),
            log = { message -> append(message) },
            onFinished = {
                lab = null
                mutableUiState.update { it.copy(running = false) }
            }
        )
        lab = started
        started.start()
    }

    fun stop() {
        val active = lab ?: return
        lab = null
        append("Clock lab stopped by the rider.")
        active.close()
        mutableUiState.update { it.copy(running = false) }
    }

    private fun append(message: String) {
        ProjectionEventLog.record("CLOCKLAB", message)
        val stamped = "${DateFormat.format("HH:mm:ss", System.currentTimeMillis())}  $message"
        mutableUiState.update { state ->
            state.copy(lines = (state.lines + stamped).takeLast(MAX_LINES))
        }
    }

    override fun onCleared() {
        lab?.close()
        lab = null
    }

    private companion object {
        /** A full run is a few hundred lines; the cap only guards against a chattering device. */
        const val MAX_LINES = 600
    }
}
