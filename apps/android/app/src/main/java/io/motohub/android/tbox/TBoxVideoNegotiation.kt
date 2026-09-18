// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import io.motohub.android.encoding.EncoderProfile
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

enum class TBoxVideoAreaSource {
    LIVE,
    SAVED,
    FALLBACK
}

data class TBoxVideoConfiguration(
    val rawArea: TBoxEvent.VideoArea,
    val encoderProfile: EncoderProfile,
    val source: TBoxVideoAreaSource
)

/**
 * Starts EasyConn while already listening for the runtime TFT capture dimensions.
 *
 * [videoAreaTimeoutMillis] bounds ONLY the wait for the live [TBoxEvent.VideoArea]; it does not
 * and cannot bound [start], which blocks in the native session until Go's own
 * `StartupTimeoutSec` gives up. The name it used to carry - `timeoutMillis` - read like the
 * handshake budget and was quoted as one: rider 94b0a3da (2026-08-30) waited 25.037 s on a dash
 * that never answered, in a call site that believed it had capped him at 10 s. The real budget is
 * `RIDE_DAEMON_STARTUP_TIMEOUT_SEC`, and the "Starting EasyConn handshake" line names it.
 */
suspend fun TBoxTransport.negotiateVideoConfiguration(
    host: TBoxHost,
    savedArea: TBoxEvent.VideoArea?,
    videoAreaTimeoutMillis: Long,
    fallbackArea: TBoxEvent.VideoArea? = null
): Result<TBoxVideoConfiguration> = coroutineScope {
    val liveArea = async(start = CoroutineStart.UNDISPATCHED) {
        withTimeoutOrNull(videoAreaTimeoutMillis) {
            events.filterIsInstance<TBoxEvent.VideoArea>().first()
        }
    }
    val startResult = start(host)
    startResult.exceptionOrNull()?.let { failure ->
        liveArea.cancel()
        return@coroutineScope Result.failure(failure)
    }

    selectVideoConfiguration(liveArea.await(), savedArea, fallbackArea)
}

internal fun selectVideoConfiguration(
    liveArea: TBoxEvent.VideoArea?,
    savedArea: TBoxEvent.VideoArea?,
    fallbackArea: TBoxEvent.VideoArea? = null
): Result<TBoxVideoConfiguration> {
    val selected = when {
        liveArea != null -> liveArea to if (liveArea.isFallback) {
            TBoxVideoAreaSource.FALLBACK
        } else {
            TBoxVideoAreaSource.LIVE
        }
        savedArea != null -> savedArea to TBoxVideoAreaSource.SAVED
        fallbackArea != null -> fallbackArea.copy(isFallback = true) to TBoxVideoAreaSource.FALLBACK
        else -> null
    } ?: return Result.failure(
        IllegalStateException(
            "The T-Box did not provide a valid video area and no saved or fallback geometry is available."
        )
    )
    val area = selected.first
    return runCatching {
        TBoxVideoConfiguration(
            rawArea = area,
            encoderProfile = EncoderProfile.forTBoxArea(area.width, area.height),
            source = selected.second
        )
    }
}
