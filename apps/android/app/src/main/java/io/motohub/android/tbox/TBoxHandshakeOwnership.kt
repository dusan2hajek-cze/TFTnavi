// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/**
 * The line to log instead of ending the session, when a transport failure arrives while the
 * EasyConn handshake that caused it is still running - or null when nothing is in flight and the
 * failure really is the session ending.
 *
 * One native startup timeout produces TWO reactions on this side: `MobileSession` reports
 * `OnStopped`, which reaches the transport-event collector, and that same timeout is the return
 * value of the `start()` call the collector's own caller is still sitting inside. Ungated, the
 * collector wins the race and ends the whole session - taking with it the re-discover-and-retry
 * the caller was about to run, which is the only second chance either streaming mode has.
 *
 * Rider 94b0a3da's log (2026-08-30, CORE 1.1.102) shows the entire thing inside 175 ms:
 * "Re-discovering the T-Box before retrying" at 11:25:24.485, that retry's own wake probe answered
 * with "Software caused connection abort" at .629, and the Wi-Fi Direct group released at .651.
 * The retry had never once run to completion on that path.
 *
 * It lives here rather than beside either mode because BOTH have the window, for the same reason:
 * each installs its transport-event collector before the first handshake and creates its encoder
 * after it. Android Auto's collector reaches `fail()` directly; the Ride Dashboard's reaches
 * `requestRecovery`, whose "nothing is running yet" branch is `fail()` too - a different route to
 * the identical teardown, which is exactly how it stayed hidden.
 *
 * Nothing is lost by staying quiet here: while the handshake is in flight there is no stream to
 * lose, and whichever way it ends is reported by the call itself.
 *
 * @param sessionName what the rider would call the thing that is NOT being ended, in the middle of
 *   a sentence ("Android Auto", "the Ride Dashboard").
 */
internal fun tBoxFailureOwnedByHandshake(
    handshakeInFlight: Boolean,
    sessionName: String,
    message: String
): String? =
    if (handshakeInFlight) {
        "Not ending $sessionName: the EasyConn handshake that reported this is still running and " +
            "owns the outcome - $message"
    } else {
        null
    }
