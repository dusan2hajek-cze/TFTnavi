// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/**
 * The four settings that decide what a dashboard actually receives on the wire.
 *
 * They used to be read straight off [TBoxModelProfile], which tied them to *identity*: to give a
 * dash a different frame format you had to invent a whole profile for it, and a profile carries a
 * panel size, an Android Auto preset and a touch policy as well. That is the right shape for a
 * dashboard somebody has held in their hands and measured. It is the wrong shape for the question
 * [TBoxWireLadder] asks - "does this firmware want the frame index or not?" - which is brand-neutral
 * and has four answers total.
 *
 * Splitting them out is what lets an unidentified dash be handed a different wire without also being
 * handed a Zontes panel geometry. A recognised profile still answers for itself: [TBoxModelProfile.wireConfig]
 * returns exactly what its own fields have always said, so nothing about a dash that streams fine today changes.
 */
data class TBoxWireConfig(
    /**
     * Whether the dash's own `supportExtendProtocol` byte may pick the frame format: 0 drops the
     * 4-byte frame index, 1 keeps it. False pins the index on regardless of what the dash says.
     */
    val allowsPlainVideoFraming: Boolean,
    /** Send PXC keepalives rather than waiting for the dash to start them. */
    val requiresProactivePxcHeartbeat: Boolean,
    /** GOP length in seconds for the TFT encoder; 0 is the all-intra stream every dash has had. */
    val encoderKeyframeIntervalSeconds: Int,
    /** Plain periodic IDRs instead of intra refresh, for decoders that mishandle the latter. */
    val encoderPlainGopWithoutIntraRefresh: Boolean
) {
    /** Compact form for logs and for the support report; stable enough to group riders by. */
    val signature: String
        get() = buildString {
            append(if (allowsPlainVideoFraming) "ext-decides" else "indexed")
            append('/')
            append(if (encoderKeyframeIntervalSeconds <= 0) "all-intra" else "gop${encoderKeyframeIntervalSeconds}s")
            if (encoderPlainGopWithoutIntraRefresh) append("+idr")
            if (!requiresProactivePxcHeartbeat) append("/no-beat")
        }
}

/** What a hand-written profile has always said about the wire, unchanged. */
val TBoxModelProfile.wireConfig: TBoxWireConfig
    get() = TBoxWireConfig(
        allowsPlainVideoFraming = allowsPlainVideoFraming,
        requiresProactivePxcHeartbeat = requiresProactivePxcHeartbeat,
        encoderKeyframeIntervalSeconds = encoderKeyframeIntervalSeconds,
        encoderPlainGopWithoutIntraRefresh = encoderPlainGopWithoutIntraRefresh
    )
