// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What [SelectingTBoxTransport.activeProtocolProfile] is allowed to say about a session, which is
 * the only thing this app's own Android Auto and the companion app across the AIDL bridge have to
 * go on when the routing did not come from the saved motorcycle.
 *
 * Rider 315e0af3 is the reason this is pinned down by tests: a Moto Morini X-Cape 1200 that Core
 * named correctly in the log line above, encoded at the generic profile's 30 fps all-intra into a
 * Yunmo transport whose send window holds three frames.
 */
class RoutedProfilePublishingTest {

    /** The lookup [TBoxProtocolMemory]'s shortcut uses on both connect paths. */
    private fun shortcutProfileFor(family: TBoxTransportFamily): TBoxModelProfile? =
        TBoxModelProfile.entries.firstOrNull { it.transportFamily == family }

    @Test
    fun aSessionRoutedOffEasyConnPublishesTheProfileItIsRoutedTo() {
        TBoxModelProfile.entries
            .filter { it.transportFamily != TBoxTransportFamily.EASYCONN }
            .forEach { profile ->
                assertEquals(
                    "${profile.name} runs a wire the other side cannot arrive at on its own",
                    profile,
                    SelectingTBoxTransport.routedProfileToPublish(profile)
                )
            }
    }

    @Test
    fun anEasyConnSessionPublishesNothingAndLeavesTheAnswerToTheCaller() {
        // Null is not "unknown" here, it is "nothing to correct": the caller resolves EasyConn
        // profiles with the CLIENT_INFO capability scoring this side has no store for, so its
        // answer is the better one and must not be overwritten.
        TBoxModelProfile.entries
            .filter { it.transportFamily == TBoxTransportFamily.EASYCONN }
            .forEach { profile ->
                assertNull(
                    "${profile.name} must leave the capability-scored resolution alone",
                    SelectingTBoxTransport.routedProfileToPublish(profile)
                )
            }
    }

    @Test
    fun theGenericProfileIsNeverPublishedOverSomeoneElsesBetterGuess() {
        assertNull(SelectingTBoxTransport.routedProfileToPublish(TBoxModelProfile.GENERIC))
    }

    @Test
    fun theLearnedYunmoShortcutPublishesTheXCapeProfileRatherThanFallingBackToGeneric() {
        // The regression itself. From the second connect onwards the session is routed straight to
        // Yunmo at configure time and never reaches the fallback inside discover(), so this is the
        // only moment the profile can be published - and what it publishes has to be the X-Cape's
        // 10 fps and 2-second GOP, not the 30 fps all-intra of a profile nothing switched away
        // from.
        val shortcut = shortcutProfileFor(TBoxTransportFamily.YUNMO)!!
        val published = SelectingTBoxTransport.routedProfileToPublish(shortcut)
        assertNotEquals(TBoxModelProfile.GENERIC, published)
        assertEquals(shortcut, published)
        assertEquals(10, published!!.encoderFrameRate)
        assertEquals(2, published.encoderKeyframeIntervalSeconds)
    }

    @Test
    fun theYunmoShortcutPicksThePlainXCapeProfileAndNotOneOfItsExperiments() {
        // Declaration order in TBoxModelProfile is what decides this, so it is load-bearing:
        // the shortcut takes the first entry of the family and must never start pinning riders to
        // the mirror or JPEG variants, which exist to answer one question each and are reachable
        // only by a deliberate pin.
        assertEquals(TBoxModelProfile.MORINI_XCAPE_1200, shortcutProfileFor(TBoxTransportFamily.YUNMO))
    }
}
