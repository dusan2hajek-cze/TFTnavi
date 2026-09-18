// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.pairing

/**
 * What a hand-typed network name is worth checking against before it becomes a motorcycle.
 *
 * Support 168b97bb (samsung SM-S948B, 2026-09-06) is the whole reason. He typed his dash's name
 * into manual pairing with a space in it - `CFMOTO 6627` for a dash that broadcasts `CFMOTO6627` -
 * and MOTO-HUB accepted it, saved it as a SECOND motorcycle, and then spent forty minutes
 * submitting WifiNetworkSpecifier requests for a network that does not exist. Every one of them
 * failed the same way:
 *
 *     Submitting WifiNetworkSpecifier request for CFMOTO 6627 ...
 *     Wi-Fi setup timed out after 30000ms with no network granted
 *     IllegalStateException: The phone never joined CFMOTO 6627
 *
 * and every one of them told him the dash was not broadcasting and to rescan the QR code. He did
 * rescan it, twice, at 11:31:44 and 11:31:49 - and it changed nothing, because the QR wrote the
 * right name into a profile the failing connect was not using.
 *
 * The app had three independent ways to know the name was wrong. A working profile for that dash
 * was already saved. A QR code carrying its real name had already been decoded. And the network
 * itself had been seen in a scan minutes earlier. None of them was consulted.
 *
 * [bySsidIgnoringCase] already handles the one kind of retyping that used to be handled - a rider
 * entering the same name in another case. This is the rest of the noise: spaces, dashes,
 * underscores and dots, which is what a name read off a dashboard photograph or a manual actually
 * collects.
 */
sealed interface ManualSsidVerdict {
    /** Nothing to say. The name is taken exactly as typed. */
    data object Accepted : ManualSsidVerdict

    /**
     * The typed name is the same as [candidate] once the noise is removed, and [candidate] is a
     * name this phone has evidence for.
     *
     * A question rather than a correction. Silently rewriting what a rider typed would be the
     * same mistake in the other direction: some dashboards really do have a space in their SSID,
     * and this screen exists precisely for the ones MOTO-HUB knows nothing about.
     */
    data class DidYouMean(val candidate: String, val source: Source) : ManualSsidVerdict {
        enum class Source {
            /** A motorcycle already saved on this phone - the strongest evidence there is. */
            SAVED_MOTORCYCLE,

            /** A network in the phone's latest Wi-Fi scan. */
            ON_THE_AIR
        }
    }
}

/**
 * Everything that is not a letter or a digit, which is exactly the class of character a rider
 * adds or drops when copying a name by eye: spaces, dashes, underscores, dots.
 *
 * Case goes with it. That makes this strictly wider than [bySsidIgnoringCase]'s comparison, so a
 * name that already matches a saved motorcycle exactly can never reach a suggestion.
 */
private fun ssidNoiseKey(ssid: String): String =
    ssid.filter { it.isLetterOrDigit() }.lowercase()

/**
 * @param typed what the rider entered, already trimmed.
 * @param savedSsids the SSIDs of motorcycles already on this phone, however they were paired.
 * @param visibleSsids the SSIDs in the phone's latest Wi-Fi scan, or empty when there is no usable
 *   scan. Empty must mean "nothing to say", never "the dash is not there" - a phone that handed
 *   back no scan is describing itself, not the air, and that mistake is already documented at
 *   [io.motohub.android.tbox.TBoxNetworkConnector.logVisibleApSnapshot].
 *
 * Never rejects. A dash that is switched off, or on a channel this phone's regulatory domain
 * forbids, is in no scan and in no profile, and is the ordinary reason to be on this screen at
 * all - the screen's own KDoc says it does not discover or guess credentials. So an unrecognised
 * name is accepted in silence; only a name that looks like a near miss of something this phone
 * has actual evidence for is worth a question.
 */
fun manualSsidVerdict(
    typed: String,
    savedSsids: List<String>,
    visibleSsids: List<String>
): ManualSsidVerdict {
    val target = typed.trim()
    if (target.isEmpty()) return ManualSsidVerdict.Accepted
    // An exact match, in any case, is the rider naming something we already know. Checked against
    // both sources before any near miss is considered, so a dash whose real SSID genuinely
    // contains a space can always be entered.
    if (savedSsids.any { it.equals(target, ignoreCase = true) }) return ManualSsidVerdict.Accepted
    if (visibleSsids.any { it.equals(target, ignoreCase = true) }) return ManualSsidVerdict.Accepted

    val key = ssidNoiseKey(target)
    // A name made only of punctuation has no key to match on, and matching every other such name
    // would be noise rather than evidence.
    if (key.isEmpty()) return ManualSsidVerdict.Accepted

    // Saved first. A motorcycle this phone has connected with is better evidence than a network it
    // can currently see, and it is the one the rider is about to end up with two of.
    savedSsids.firstOrNull { ssidNoiseKey(it) == key }?.let {
        return ManualSsidVerdict.DidYouMean(it, ManualSsidVerdict.DidYouMean.Source.SAVED_MOTORCYCLE)
    }
    visibleSsids.firstOrNull { ssidNoiseKey(it) == key }?.let {
        return ManualSsidVerdict.DidYouMean(it, ManualSsidVerdict.DidYouMean.Source.ON_THE_AIR)
    }
    return ManualSsidVerdict.Accepted
}
