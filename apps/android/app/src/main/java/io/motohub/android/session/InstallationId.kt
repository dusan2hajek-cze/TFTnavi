// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.session

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * The identifiers a rider can read out to support so their reports and crash events can be
 * found again: one for the phone, one for the phone + the motorcycle it is currently paired
 * with.
 *
 * Neither is a hardware serial. The dashboards never transmit a VIN, the phone's serial has
 * been inaccessible to apps since Android 10, and a random UUID would change on every
 * reinstall - which is exactly when a rider is most likely to be asking for help. Both ids are
 * therefore derived from `Settings.Secure.ANDROID_ID`, which Android scopes to the signing key
 * and the user: CORE and ADVANCED are signed with the same key, so both editions compute the
 * same value, it survives reinstalls, and it only changes on a factory reset. The raw
 * ANDROID_ID itself is never stored or sent; only a one-way hash leaves the phone.
 */
object InstallationId {
    private const val DEVICE_SALT = "moto-hub/device/v1"
    private const val SUPPORT_SALT = "moto-hub/support/v1"
    private const val NO_MOTORCYCLE = "no-motorcycle"

    /** Identifies the phone + app signing key, independently of which motorcycle is active. */
    fun deviceId(context: Context): String = derive(DEVICE_SALT, androidId(context))

    /**
     * The "Support ID" shown in Diagnostics: the phone combined with the active motorcycle
     * profile, so one rider with two bikes shows up as two installations and a report can be
     * traced to the bike it came from. Changes when the rider switches or re-pairs a bike; the
     * [deviceId] travels alongside it so the two still correlate.
     */
    fun supportId(context: Context, activeMotorcycleProfileId: String?): String =
        derive(SUPPORT_SALT, androidId(context) + "|" + (activeMotorcycleProfileId ?: NO_MOTORCYCLE))

    /** `A1B2-C3D4-E5F6`: the first 12 hex digits, grouped so a rider can read them over chat. */
    fun shortForm(id: String): String =
        id.replace("-", "").take(12).uppercase(Locale.US).chunked(4).joinToString("-")

    @SuppressLint("HardwareIds")
    private fun androidId(context: Context): String =
        runCatching {
            Settings.Secure.getString(context.applicationContext.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "unknown-android-id"

    /** A name-based UUID (RFC 4122 version 5 layout) over SHA-256 of salt + material. */
    internal fun derive(salt: String, material: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$salt:$material".toByteArray(Charsets.UTF_8))
        digest[6] = ((digest[6].toInt() and 0x0f) or 0x50).toByte()
        digest[8] = ((digest[8].toInt() and 0x3f) or 0x80).toByte()
        var high = 0L
        var low = 0L
        for (index in 0 until 8) high = (high shl 8) or (digest[index].toLong() and 0xff)
        for (index in 8 until 16) low = (low shl 8) or (digest[index].toLong() and 0xff)
        return UUID(high, low).toString()
    }
}
