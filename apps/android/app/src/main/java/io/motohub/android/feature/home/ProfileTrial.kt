// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import io.motohub.android.session.DashboardDeliveryReport
import io.motohub.android.tbox.ProfileOverride

/**
 * A profile the rider picked from [ProfileTrialScreen] and is now watching, with the pin it
 * replaced so the app can put things back exactly as they were.
 *
 * [previousKey] is the point of the whole type. Applying a profile means writing it down before
 * the session is built - it selects the transport, the encoder and the touch policy, so nothing
 * can be tried without it - and a rider who tries three profiles would otherwise be left pinned
 * to whichever they tried last, including if that one was worse than what they started with.
 */
data class PendingProfileTrial(
    val ssid: String,
    val override: ProfileOverride,
    val previousKey: String?
) {
    /** What the session will report running if this trial takes effect. */
    val expectedProfileKey: String? get() = override.resolve()?.key
}

/**
 * Whether a delivery verdict settles a trial the rider is watching, and how.
 *
 * A separate decision from "is this session healthy" because the trial adds two questions the
 * verdict alone cannot answer: is this the same motorcycle, and is this the profile we are
 * actually waiting on. Neither is hypothetical - a verdict can arrive from a session rebuilt by a
 * watchdog on the OLD profile while the trial's own session is still settling, and confirming a
 * trial on that would tell the rider their new profile works when nothing had tried it yet.
 */
object ProfileTrialPolicy {

    enum class Outcome {
        /** Nothing to say yet; keep waiting. */
        PENDING,

        /** The picture is landing on the profile the rider picked - worth asking to keep it. */
        CONFIRMED,

        /** This profile is no better. Back to the list, without pretending otherwise. */
        FAILED
    }

    fun outcome(trial: PendingProfileTrial, report: DashboardDeliveryReport?): Outcome {
        if (report == null) return Outcome.PENDING
        if (report.ssid != trial.ssid) return Outcome.PENDING
        // A verdict about a different profile is a verdict about a different question. The
        // commonest way to get one is a recovery that rebuilt the session before the write
        // landed; treating it as this trial's answer would be a coin flip either way.
        if (report.profileKey != trial.expectedProfileKey) return Outcome.PENDING
        return if (report.healthy) Outcome.CONFIRMED else Outcome.FAILED
    }
}
