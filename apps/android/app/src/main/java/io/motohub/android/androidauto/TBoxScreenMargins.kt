// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.androidauto

import android.content.Context
import android.content.SharedPreferences
import io.motohub.android.session.MotorcycleProfile

/** Physical TFT pixels reserved by motorcycle-owned UI around the projection area. */
data class TBoxScreenMargins(
    val top: Int = 0,
    val bottom: Int = 0,
    val left: Int = 0,
    val right: Int = 0
) {
    init {
        require(top >= 0 && bottom >= 0 && left >= 0 && right >= 0) {
            "Screen margins cannot be negative"
        }
        require(top <= MAX && bottom <= MAX && left <= MAX && right <= MAX) {
            "Screen margins exceed the supported limit"
        }
    }

    fun inset(geometry: DisplayGeometry): DisplayGeometry = DisplayGeometry(
        width = (geometry.width - left - right).coerceAtLeast(1),
        height = (geometry.height - top - bottom).coerceAtLeast(1)
    )

    companion object {
        const val MAX = 200
        val NONE = TBoxScreenMargins()
    }
}

/**
 * [TBoxScreenMarginsStore.loadStored]'s decision, without a Context so it can be tested: the four
 * edges as they came out of the preferences, with [TBoxScreenMarginsStore.UNSET] standing for
 * "no value stored". Null unless ALL FOUR are present - a half-written record is not a teaching,
 * and filling its gaps with zeros is how a stored zero and a missing value become the same answer
 * on the companion boundary, which is exactly what must not happen there.
 */
internal fun storedMargins(edges: List<Int>): TBoxScreenMargins? {
    if (edges.size != 4 || edges.any { it == TBoxScreenMarginsStore.UNSET }) return null
    return TBoxScreenMargins(top = edges[0], bottom = edges[1], left = edges[2], right = edges[3])
}

/**
 * The wire form margins take across the companion boundary: four edges, in [storedMargins]'s
 * order, or null for "the rider never taught this one".
 *
 * Null has to survive the trip intact. Four zeros are a legitimate teaching - a rider CAN measure
 * a dash with no furniture - so encoding "nothing stored" as zeros would let one half quietly
 * overwrite the other half's calibration with a value nobody ever entered.
 */
fun encodeScreenMargins(margins: TBoxScreenMargins?): String? = margins?.let {
    "${it.top},${it.bottom},${it.left},${it.right}"
}

/**
 * Which margins one half of the pair should frame a motorcycle with, given what each half was
 * taught. No Android in it, so the rule the two halves have to share can be checked rather than
 * assumed - and it is a rule with a history of being got wrong in one direction at a time.
 *
 * [taughtHere] wins because it is the more recent teaching by construction: a value measured in
 * this app travels to the other half at every session start, so the only way the other half's
 * differs is that this app was taught afterwards. [taughtElsewhere] then fills the gap, which is
 * the case that went unhandled and framed one dashboard two ways.
 *
 * Null out means neither half was ever taught - the caller then falls back to the model profile's
 * default, which [TBoxScreenMarginsStore.load] already does at read time. Not folded in here on
 * purpose: a default returned from this function would be indistinguishable from a measurement,
 * and telling those two apart is the entire job of this boundary. Four zeros are a teaching a
 * rider can genuinely make on a panel with no furniture.
 */
fun agreedScreenMargins(
    taughtHere: TBoxScreenMargins?,
    taughtElsewhere: TBoxScreenMargins?
): TBoxScreenMargins? = taughtHere ?: taughtElsewhere

/**
 * What this app may send the other half: a measurement made HERE, and never a copy of the other
 * half's own.
 *
 * Adoption without this would turn a one-time gap-fill into a permanent clobber. The adopted copy
 * looks exactly like a local teaching to [TBoxScreenMarginsStore.loadStored], so it would be
 * pushed back at every session start - and the moment the rider re-measured in the OTHER app,
 * their new value would survive until the next session and then be overwritten by the stale copy
 * this app adopted months earlier. The bug would look like "I fixed the framing and it came back".
 */
internal fun screenMarginsToPush(
    stored: TBoxScreenMargins?,
    adoptedFromTheOtherHalf: Boolean
): TBoxScreenMargins? = stored?.takeUnless { adoptedFromTheOtherHalf }

/** Reads [encodeScreenMargins] back. Anything unreadable is "nothing stored", never a guess. */
fun decodeScreenMargins(raw: String?): TBoxScreenMargins? {
    val parts = raw?.split(',') ?: return null
    if (parts.size != 4) return null
    val edges = parts.map { it.trim().toIntOrNull() ?: return null }
    if (edges.any { it < 0 || it > TBoxScreenMargins.MAX }) return null
    return TBoxScreenMargins(top = edges[0], bottom = edges[1], left = edges[2], right = edges[3])
}

/** Stores margins per motorcycle so one bike's panel furniture never affects another's. */
class TBoxScreenMarginsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    fun load(
        profile: MotorcycleProfile,
        defaults: TBoxScreenMargins = TBoxScreenMargins.NONE
    ): TBoxScreenMargins = TBoxScreenMargins(
        top = preferences.getInt(key(profile.ssid, "top"), defaults.top),
        bottom = preferences.getInt(key(profile.ssid, "bottom"), defaults.bottom),
        left = preferences.getInt(key(profile.ssid, "left"), defaults.left),
        right = preferences.getInt(key(profile.ssid, "right"), defaults.right)
    )

    /**
     * A rider's own measurement, made in this app. Clears the adopted marker: whatever this store
     * held before, from here on this is a local teaching and may travel to the other half.
     */
    fun save(profile: MotorcycleProfile, margins: TBoxScreenMargins) {
        preferences.edit()
            .putInt(key(profile.ssid, "top"), margins.top)
            .putInt(key(profile.ssid, "bottom"), margins.bottom)
            .putInt(key(profile.ssid, "left"), margins.left)
            .putInt(key(profile.ssid, "right"), margins.right)
            .remove(key(profile.ssid, ADOPTED))
            .apply()
    }

    /**
     * What this store actually holds for [profile], or null when the rider has never taught it.
     *
     * [load] cannot answer that question: it folds "nothing saved" into the model profile's
     * defaults, which is right for compositing and wrong for handing the value to the other half
     * of the pair. Four zeros returned because nothing was ever taught would overwrite, on the
     * companion boundary, a calibration the rider did in the other app - and zero margins are a
     * legitimate answer a rider can also teach, so the two cases cannot be told apart afterwards.
     */
    fun loadStored(profile: MotorcycleProfile): TBoxScreenMargins? =
        storedMargins(EDGES.map { edge -> preferences.getInt(key(profile.ssid, edge), UNSET) })

    fun reset(profile: MotorcycleProfile) {
        preferences.edit()
            .remove(key(profile.ssid, "top"))
            .remove(key(profile.ssid, "bottom"))
            .remove(key(profile.ssid, "left"))
            .remove(key(profile.ssid, "right"))
            .remove(key(profile.ssid, ADOPTED))
            .apply()
    }

    /**
     * Notifies [listener] whenever [save] or [reset] changes any margin for any SSID -
     * callers should use [belongsToMotorcycle] to filter for the SSID they care about. Lets
     * an active projection session apply a margin change picked in
     * [io.motohub.android.feature.garage.MotorcycleDetailsScreen] without restarting.
     */
    fun addListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun removeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        preferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    /** True if the preference [key] reported by [addListener] belongs to [ssid]. */
    fun belongsToMotorcycle(key: String?, ssid: String): Boolean = key?.startsWith("$ssid:") == true

    /**
     * [loadStored] for a caller that has only the network name - the companion boundary, where
     * the other half's profile id means nothing but the SSID is the same string on both sides.
     */
    fun loadStoredBySsid(ssid: String): TBoxScreenMargins? =
        storedMargins(EDGES.map { edge -> preferences.getInt(key(ssid, edge), UNSET) })

    /**
     * Takes the other half's teaching, keyed by network name, and remembers that it came from
     * there. The marker is what [loadTaughtHere] reads, and it is the difference between filling
     * a gap once and clobbering the other half's ruler for good - see [screenMarginsToPush].
     */
    fun saveAdopted(ssid: String, margins: TBoxScreenMargins) {
        preferences.edit()
            .putInt(key(ssid, "top"), margins.top)
            .putInt(key(ssid, "bottom"), margins.bottom)
            .putInt(key(ssid, "left"), margins.left)
            .putInt(key(ssid, "right"), margins.right)
            .putBoolean(key(ssid, ADOPTED), true)
            .apply()
    }

    /**
     * What a rider measured IN THIS APP, or null when this store holds nothing - or holds only a
     * copy adopted from the other half.
     *
     * [loadStored] cannot answer that: an adopted copy is byte-for-byte a local teaching once
     * written, and the two have to be told apart at exactly one place - the boundary where a
     * value is about to be sent back.
     */
    fun loadTaughtHere(profile: MotorcycleProfile): TBoxScreenMargins? = screenMarginsToPush(
        stored = loadStored(profile),
        adoptedFromTheOtherHalf = preferences.getBoolean(key(profile.ssid, ADOPTED), false)
    )

    private fun key(ssid: String, edge: String): String = "$ssid:$edge"

    internal companion object {
        private const val PREFERENCES_NAME = "tbox_screen_margins"

        /** Order matters: [loadStored] reads the edges positionally, and so does [storedMargins]. */
        private val EDGES = listOf("top", "bottom", "left", "right")

        /**
         * Provenance flag beside the four edges: true when this value was adopted from the other
         * half rather than measured here. Not one of [EDGES] on purpose - it is a boolean, and a
         * positional read of the edges must never see it.
         */
        private const val ADOPTED = "adopted"

        /** Outside the 0..[TBoxScreenMargins.MAX] range a saved margin can occupy. */
        const val UNSET = -1
    }
}
