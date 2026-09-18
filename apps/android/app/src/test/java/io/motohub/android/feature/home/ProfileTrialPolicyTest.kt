// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.home

import io.motohub.android.feature.home.ProfileTrialPolicy.Outcome
import io.motohub.android.session.DashboardDeliveryReport
import io.motohub.android.tbox.ProfileOverride
import org.junit.Assert.assertEquals
import org.junit.Test

class ProfileTrialPolicyTest {

    private val trial = PendingProfileTrial(
        ssid = "ML155083",
        override = ProfileOverride.MORINI_XCAPE_1200,
        previousKey = null
    )

    private fun report(
        ssid: String = "ML155083",
        profileKey: String = "morini_xcape_1200",
        healthy: Boolean = true,
        rejected: Int = 9,
        accepted: Int = 140
    ) = DashboardDeliveryReport(ssid, rejected, accepted, profileKey, healthy)

    @Test
    fun aHealthySessionOnThePickedProfileSettlesIt() {
        assertEquals(Outcome.CONFIRMED, ProfileTrialPolicy.outcome(trial, report()))
    }

    @Test
    fun theSameProfileStillRefusingThePictureIsAFailedTrial() {
        assertEquals(
            Outcome.FAILED,
            ProfileTrialPolicy.outcome(trial, report(healthy = false, rejected = 132, accepted = 18))
        )
    }

    @Test
    fun nothingIsConcludedUntilAVerdictArrives() {
        assertEquals(Outcome.PENDING, ProfileTrialPolicy.outcome(trial, null))
    }

    @Test
    fun aVerdictAboutTheOldProfileIsNotThisTrialsAnswer() {
        // The realistic way to get one: a watchdog rebuilt the session on the old profile while
        // the trial's own session was still settling. Confirming on that would tell the rider
        // their new profile works before anything had tried it.
        assertEquals(Outcome.PENDING, ProfileTrialPolicy.outcome(trial, report(profileKey = "generic")))
        assertEquals(
            Outcome.PENDING,
            ProfileTrialPolicy.outcome(trial, report(profileKey = "generic", healthy = false))
        )
    }

    @Test
    fun aVerdictAboutAnotherMotorcycleIsIgnored() {
        assertEquals(Outcome.PENDING, ProfileTrialPolicy.outcome(trial, report(ssid = "OtherBike")))
    }

    @Test
    fun aTrialOfTheNeutralProfileIsSettledLikeAnyOther() {
        // GENERIC is the one entry that pins *less*, and it still has a profile key of its own -
        // so "back to neutral" is a trial the rider can be asked to keep, not a special case.
        val neutral = trial.copy(override = ProfileOverride.GENERIC, previousKey = "morini_xcape_1200")
        assertEquals("generic", neutral.expectedProfileKey)
        assertEquals(
            Outcome.CONFIRMED,
            ProfileTrialPolicy.outcome(neutral, report(profileKey = "generic"))
        )
    }
}
