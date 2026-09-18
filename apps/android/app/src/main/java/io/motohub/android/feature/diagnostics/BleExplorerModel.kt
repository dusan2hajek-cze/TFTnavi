// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics

import android.bluetooth.BluetoothGattCharacteristic
import java.util.UUID

/** One device as the scanner sees it. Rebuilt on every advertisement, keyed by address. */
data class BleScanEntry(
    val address: String,
    val name: String?,
    val rssi: Int,
    val connectable: Boolean,
    val serviceUuids: List<String>,
    val serviceData: List<String>,
    val manufacturer: List<String>,
    val txPower: Int?,
    val lastSeenElapsed: Long
) {
    val label: String get() = name?.takeIf { it.isNotBlank() } ?: "(unnamed)"
}

enum class BleLinkState { IDLE, CONNECTING, DISCOVERING, READY, DISCONNECTED }

/** One characteristic, flattened with everything the screen needs to act on it. */
data class BleCharacteristicNode(
    val serviceUuid: UUID,
    val uuid: UUID,
    val properties: Int,
    val descriptors: List<String>,
    val notifying: Boolean,
    val lastValue: ByteArray?
) {
    val canRead: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_READ != 0
    val canWrite: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0
    val canWriteNoResponse: Boolean
        get() = properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
    val canNotify: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
    val canIndicate: Boolean get() = properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

    /** READ/WRITE/WNR/NOTIFY/INDICATE and the rest, as the chips shown on the row. */
    fun propertyLabels(): List<String> = buildList {
        if (canRead) add("READ")
        if (canWrite) add("WRITE")
        if (canWriteNoResponse) add("WNR")
        if (canNotify) add("NOTIFY")
        if (canIndicate) add("INDICATE")
        if (properties and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
        if (properties and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED")
        if (properties and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("EXT")
    }

    // Generated equals/hashCode would compare lastValue by reference; the screen diffs these to
    // decide what to redraw, so the bytes have to be part of the comparison.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is BleCharacteristicNode) return false
        return serviceUuid == other.serviceUuid && uuid == other.uuid &&
            properties == other.properties && descriptors == other.descriptors &&
            notifying == other.notifying && lastValue.contentEquals(other.lastValue)
    }

    override fun hashCode(): Int {
        var result = serviceUuid.hashCode()
        result = 31 * result + uuid.hashCode()
        result = 31 * result + properties
        result = 31 * result + descriptors.hashCode()
        result = 31 * result + notifying.hashCode()
        result = 31 * result + (lastValue?.contentHashCode() ?: 0)
        return result
    }
}

data class BleServiceNode(
    val uuid: UUID,
    val primary: Boolean,
    val characteristics: List<BleCharacteristicNode>
)

/** One line of the live traffic view; also mirrored into the diagnostic log. */
data class BleTrafficLine(val atMillis: Long, val kind: Kind, val text: String) {
    enum class Kind { OUT, IN, INFO, FAILURE }
}

/**
 * Names for the UUIDs a rider is likely to meet, so the tree reads as something rather than as
 * thirty-two hex digits.
 *
 * Deliberately short: the whole point of this screen is devices nobody has a name for, and a long
 * table would imply an authority it does not have. The Bluetooth SIG's 16-bit numbers that show up
 * on real remotes, plus the two vendor services this app already knows about.
 */
object BleNames {
    private val SHORT = mapOf(
        "1800" to "Generic Access", "1801" to "Generic Attribute", "180a" to "Device Information",
        "180f" to "Battery", "180d" to "Heart Rate", "1812" to "HID over GATT",
        "1816" to "Cycling Speed and Cadence", "1802" to "Immediate Alert",
        "1803" to "Link Loss", "1804" to "Tx Power", "fe59" to "Nordic DFU",
        "2a00" to "Device Name", "2a19" to "Battery Level", "2a27" to "Hardware Revision",
        "2a28" to "Software Revision", "2a29" to "Manufacturer", "2a24" to "Model Number",
        "2902" to "Client Characteristic Configuration",
        "2a4d" to "HID Report", "2a4b" to "HID Report Map", "2a37" to "Heart Rate Measurement"
    )

    private const val NORDIC_UART = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    /** A readable name for [uuid], or null when nothing is known — never a guess. */
    fun of(uuid: String): String? {
        val lower = uuid.lowercase()
        if (lower == NORDIC_UART) return "Nordic UART"
        if (lower.startsWith("6e4000")) return "Nordic UART channel"
        if (lower.length >= 8) SHORT[lower.substring(4, 8)]?.let { return it }
        return null
    }

    /** "0000180a-… (Device Information)" or just the UUID when it is nobody's. */
    fun describe(uuid: UUID): String {
        val text = uuid.toString()
        return of(text)?.let { "$text  ($it)" } ?: text
    }

    /** The 16-bit form when the UUID is a standard one, for a compact row. */
    fun short(uuid: UUID): String {
        val text = uuid.toString().lowercase()
        val base = "-0000-1000-8000-00805f9b34fb"
        return if (text.endsWith(base) && text.startsWith("0000")) {
            "0x${text.substring(4, 8).uppercase()}"
        } else {
            text.take(8)
        }
    }
}

/** Hex both ways, because a rider debugging a remote types and reads nothing else. */
object BleHex {
    fun encode(bytes: ByteArray?): String =
        bytes?.joinToString(" ") { "%02X".format(it) } ?: ""

    /** The printable half of a payload, for protocols that carry text. */
    fun ascii(bytes: ByteArray?): String =
        bytes?.map { if (it in 32..126) it.toInt().toChar() else '.' }?.joinToString("") ?: ""

    /**
     * Parses "01 ff 0A", "01ff0a" or "0x01,0xFF" — anything a datasheet or a forum post might be
     * copied from. Null when it is not hex at all, so the screen can refuse rather than write
     * something the rider did not mean.
     */
    fun decode(text: String): ByteArray? {
        val cleaned = text.replace("0x", "", ignoreCase = true)
            .filter { !it.isWhitespace() && it != ',' && it != ':' && it != '-' }
        if (cleaned.isEmpty() || cleaned.length % 2 != 0) return null
        if (!cleaned.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(cleaned.length / 2) { i ->
            cleaned.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
