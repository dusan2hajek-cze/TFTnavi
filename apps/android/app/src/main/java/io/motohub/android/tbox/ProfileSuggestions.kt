// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

/**
 * Which profiles to put in front of a rider whose dashboard is connected and not painting, and
 * in what order.
 *
 * The list this replaces is the flat one in the Garage: nineteen entries in declaration order,
 * real motorcycles mixed with one-question experiments, no indication which of them could
 * possibly apply. Rider 315e0af3 found it by accident after two days and picked correctly on the
 * first try - which says the entries are good and the presentation was the whole problem.
 */
object ProfileSuggestions {

    /** Why an entry is being offered, so the screen can say it rather than just imply an order. */
    enum class Reason {
        /**
         * Speaks the wire this session is already speaking. The strongest thing that can be said
         * here: the transport is proven, so only the settings on top of it are in question.
         */
        SAME_WIRE,

        /** What the dashboard's own model id or CLIENT_INFO points at. */
        IDENTIFIED,

        /** Neutral defaults - the way back when detection has landed somewhere wrong. */
        NEUTRAL,

        /** A guess written for one dashboard's open question; may do nothing at all. */
        EXPERIMENT,

        /** Everything else, kept reachable rather than hidden. */
        OTHER
    }

    data class Suggestion(val override: ProfileOverride, val reason: Reason)

    /**
     * @param activeProfileKey what the session is really running, from
     *   `SelectingTBoxTransport.activeProtocolProfile` or its across-the-bridge equivalent. This
     *   is the field that makes the ordering worth anything: until it was published for a session
     *   routed by remembered transport family, a Yunmo dash reported itself as the generic
     *   EasyConn profile and this would have ranked the whole EasyConn catalogue above the three
     *   profiles that could actually work.
     * @param currentKey the rider's own pin, excluded from the offers - proposing what is already
     *   selected is how a screen tells a rider it has not understood their problem.
     */
    fun forFailingSession(
        activeProfileKey: String?,
        currentKey: String?,
        modelId: String?,
        capabilities: TBoxCapabilities?
    ): List<Suggestion> {
        val current = ProfileOverride.byKey(currentKey)
        val active = TBoxModelProfile.byKey(activeProfileKey)
        val activeFamily = active?.transportFamily
        val identified = TBoxModelProfile.resolve(modelId, capabilities, null)
            .takeIf { it != TBoxModelProfile.GENERIC }

        return ProfileOverride.entries
            .filter { it.riderSelectable }
            .filterNot { it == ProfileOverride.AUTO }
            .filterNot { it == current }
            // Never the profile the failing session is already running, however well it scores.
            // Detection reaching the right answer by itself is the common case, and when that
            // answer is the one not working, repeating it back is the screen admitting nothing.
            .filterNot { active != null && it.resolve() == active }
            .map { override -> Suggestion(override, reasonFor(override, activeFamily, identified)) }
            // Two keys, and the order of the two is the whole lesson of the X-Cape.
            //
            // Speaking the session's own wire comes FIRST, ahead of what kind of entry it is,
            // because a profile from another family cannot work here whatever else recommends it.
            // Ranking by kind alone put the neutral profile - EasyConn, and precisely what was
            // already failing - above the two remaining Yunmo entries on a Yunmo dash, one of
            // which is the JPEG profile rider 315e0af3 actually settled on.
            //
            // A stable sort, so entries sharing both keys keep the declaration order of the
            // profile table, which already groups a dashboard's variants together.
            .sortedWith(compareBy({ if (speaksWire(it.override, activeFamily)) 0 else 1 }, { rank(it.reason) }))
    }

    /**
     * Whether this entry drives the transport the session is already on.
     *
     * Unknown family (an older Core, or a profile key this build does not have) answers false for
     * everything, which flattens the first key and leaves the ordering to [Reason] alone - a
     * worse list, never an empty or a wrong one.
     */
    private fun speaksWire(override: ProfileOverride, activeFamily: TBoxTransportFamily?): Boolean =
        activeFamily != null && override.resolve()?.transportFamily == activeFamily

    private fun reasonFor(
        override: ProfileOverride,
        activeFamily: TBoxTransportFamily?,
        identified: TBoxModelProfile?
    ): Reason {
        val profile = override.resolve()
        return when {
            override == ProfileOverride.GENERIC -> Reason.NEUTRAL
            profile != null && profile == identified -> Reason.IDENTIFIED
            // Checked before SAME_WIRE on purpose: an entry written to answer one open question
            // is a guess even when it speaks the right wire, and the screen has to keep saying
            // so. The sort above is what lifts it past off-wire entries; this only names it.
            override.experimental -> Reason.EXPERIMENT
            speaksWire(override, activeFamily) -> Reason.SAME_WIRE
            else -> Reason.OTHER
        }
    }

    /**
     * Experiments sit below the neutral profile and above the rest of the catalogue - not at the
     * very bottom, because on a dashboard nobody has identified they are frequently the only
     * entries anyone wrote for it, and not higher, because they are guesses and are labelled as
     * such. A rider whose dash is already identified never reaches them.
     */
    private fun rank(reason: Reason): Int = when (reason) {
        Reason.IDENTIFIED -> 0
        Reason.SAME_WIRE -> 1
        Reason.NEUTRAL -> 2
        Reason.EXPERIMENT -> 3
        Reason.OTHER -> 4
    }
}
