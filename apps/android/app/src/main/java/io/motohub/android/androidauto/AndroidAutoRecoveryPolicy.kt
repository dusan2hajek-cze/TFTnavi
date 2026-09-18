// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

/** Pure recovery-timing logic for a full Android Auto session — no AGPL dependency, kept in
 *  shared code (and tested from both flavors) even though only Core's AndroidAutoSessionService
 *  currently calls it. */
internal fun shouldAutoRecoverAndroidAuto(
    hasReachedStreaming: Boolean,
    enabled: Boolean
): Boolean = hasReachedStreaming && enabled

/**
 * Why Android Auto is not going to be reconnected, or null when it is.
 *
 * Split out of the service so the sentence a rider log will carry is decided somewhere a test can
 * read it. It exists at all because the silent version cost a full reading of rider 8d5a1631's log
 * (2026-08-26): the session ended, the log stopped, and nothing said whether reconnection had been
 * refused, attempted, or had failed without a trace.
 */
internal fun androidAutoRecoveryRefusal(hasReachedStreaming: Boolean, enabled: Boolean): String? =
    when {
        shouldAutoRecoverAndroidAuto(hasReachedStreaming, enabled) -> null
        // A session that never streamed is named first even when the switch is also off: there is
        // nothing to reconnect TO, and blaming the switch would send a rider to change a setting
        // that would not have helped.
        !hasReachedStreaming -> "this session never reached streaming."
        else -> "automatic reconnection is switched off in this app."
    }

/**
 * What Core should do with the auto-recovery field a companion app sent: the value to store, or
 * null to leave Core's own switch alone.
 *
 * The gate is the entire content of this decision. `false` reaches Core both from a companion that
 * means "do not reconnect" and from one that predates the field and means nothing at all, and only
 * [provided] separates them. Read without it, every old companion becomes a switch that quietly
 * turns reconnection off for a rider who turned it on in Core - which is the fault this field was
 * added to fix, reintroduced from the other side.
 */
internal fun companionAutoRecovery(provided: Boolean, value: Boolean): Boolean? =
    if (provided) value else null

internal fun isAndroidAutoWatchdogStalled(
    nowElapsed: Long,
    lastProgressElapsed: Long,
    thresholdMillis: Long
): Boolean = lastProgressElapsed > 0L && nowElapsed - lastProgressElapsed >= thresholdMillis
