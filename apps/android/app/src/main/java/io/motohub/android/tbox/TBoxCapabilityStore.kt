// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.MotorcycleProfile
import org.json.JSONObject

data class TBoxCapabilitySnapshot(
    val profileId: String,
    val ssid: String,
    val host: TBoxHost? = null,
    val discoveredAtEpochMillis: Long? = null,
    val capabilities: TBoxCapabilities? = null,
    val capabilitiesObservedAtEpochMillis: Long? = null
)

/** Persists only whitelisted, non-secret T-Box metadata for each motorcycle profile. */
class TBoxCapabilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun recordDiscovery(
        profile: MotorcycleProfile,
        host: TBoxHost,
        observedAtEpochMillis: Long = System.currentTimeMillis()
    ) {
        save(
            load(profile)?.copy(
                ssid = profile.ssid,
                host = host,
                discoveredAtEpochMillis = observedAtEpochMillis
            ) ?: TBoxCapabilitySnapshot(
                profileId = profile.id,
                ssid = profile.ssid,
                host = host,
                discoveredAtEpochMillis = observedAtEpochMillis
            )
        )
    }

    @Synchronized
    fun recordCapabilities(
        profile: MotorcycleProfile,
        capabilities: TBoxCapabilities,
        observedAtEpochMillis: Long = System.currentTimeMillis()
    ) {
        save(
            load(profile)?.copy(
                ssid = profile.ssid,
                capabilities = capabilities,
                capabilitiesObservedAtEpochMillis = observedAtEpochMillis
            ) ?: TBoxCapabilitySnapshot(
                profileId = profile.id,
                ssid = profile.ssid,
                capabilities = capabilities,
                capabilitiesObservedAtEpochMillis = observedAtEpochMillis
            )
        )
    }

    /**
     * One motorcycle's CLIENT_INFO capabilities as the JSON this store persists, addressed by
     * profile id rather than by [MotorcycleProfile] so the other side of the AIDL bridge - which
     * has an id and nothing else - can ask for them.
     *
     * The raw JSON rather than a parcel of fields, for the same reason the wire ladder crosses
     * that way: both apps compile this file, so what one encodes the other decodes, and a
     * capability gaining a field needs no contract change.
     */
    fun encodedCapabilities(profileId: String): String? =
        preferences.getString(key(profileId), null)
            ?.let { serialized -> runCatching { decode(JSONObject(serialized)) }.getOrNull() }
            ?.capabilities
            ?.let { capabilities -> encodeCapabilities(capabilities).toString() }

    /**
     * Stores capabilities that arrived as [encodedCapabilities] text from another process.
     * Returns what was stored, or null when the text carries nothing this build recognises.
     *
     * An all-null decode is refused rather than saved: every field here is optional, so a
     * truncated or foreign object decodes to an empty snapshot instead of throwing, and saving
     * that would replace a real snapshot with an empty one that still counts as "capabilities
     * known" everywhere downstream.
     */
    @Synchronized
    fun recordEncodedCapabilities(
        profile: MotorcycleProfile,
        json: String,
        observedAtEpochMillis: Long = System.currentTimeMillis()
    ): TBoxCapabilities? {
        val decoded = runCatching { decodeCapabilities(JSONObject(json)) }.getOrNull()
            ?.takeIf { it != TBoxCapabilities() }
            ?: return null
        recordCapabilities(profile, decoded, observedAtEpochMillis)
        return decoded
    }

    fun load(profile: MotorcycleProfile): TBoxCapabilitySnapshot? =
        preferences.getString(key(profile.id), null)
            ?.let { serialized -> runCatching { decode(JSONObject(serialized)) }.getOrNull() }

    fun delete(profileId: String) {
        preferences.edit().remove(key(profileId)).apply()
    }

    private fun save(snapshot: TBoxCapabilitySnapshot) {
        preferences.edit().putString(key(snapshot.profileId), encode(snapshot).toString()).apply()
    }

    private fun encode(snapshot: TBoxCapabilitySnapshot): JSONObject = JSONObject().apply {
        put("profileId", snapshot.profileId)
        put("ssid", snapshot.ssid)
        snapshot.host?.let { host ->
            put("host", JSONObject().apply {
                put("ipAddress", host.ipAddress)
                put("port", host.port)
                put("packageName", host.packageName)
            })
        }
        putNullable("discoveredAt", snapshot.discoveredAtEpochMillis)
        snapshot.capabilities?.let { put("capabilities", encodeCapabilities(it)) }
        putNullable("capabilitiesObservedAt", snapshot.capabilitiesObservedAtEpochMillis)
    }

    private fun decode(json: JSONObject): TBoxCapabilitySnapshot = TBoxCapabilitySnapshot(
        profileId = json.getString("profileId"),
        ssid = json.getString("ssid"),
        host = json.optJSONObject("host")?.let { host ->
            TBoxHost(
                ipAddress = host.getString("ipAddress"),
                port = host.getInt("port"),
                packageName = host.getString("packageName")
            )
        },
        discoveredAtEpochMillis = json.optionalLong("discoveredAt"),
        capabilities = json.optJSONObject("capabilities")?.let(::decodeCapabilities),
        capabilitiesObservedAtEpochMillis = json.optionalLong("capabilitiesObservedAt")
    )

    private fun key(profileId: String) = "profile:$profileId"

    private companion object {
        const val PREFERENCES_NAME = "tbox_capabilities"
    }
}
