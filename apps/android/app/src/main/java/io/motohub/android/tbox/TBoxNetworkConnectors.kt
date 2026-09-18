// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// Who owns the one TBoxNetworkConnector - and with it the one Wi-Fi request - this process has.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.ProjectionEventLog

/**
 * Which owners currently need the T-Box network request kept alive.
 *
 * Pure bookkeeping so the rule that actually broke in the field - "an owner going away must not
 * tear down what another owner is still streaming over" - runs in a plain JVM test. Same
 * rationale, and deliberately the same shape, as [SessionConsumers] one level up: that class
 * refcounts *modes* on a session, this one refcounts *owners* on the network request underneath
 * it.
 *
 * Stricter than [SessionConsumers] in one place: [releaseIsLast] answers true only when the
 * departing owner actually held an interest. A release that was never matched by an acquire is
 * a caller bug, and letting it drop the network would turn that bug into a mid-ride disconnect.
 */
internal class NetworkInterestLedger {
    private val owners = linkedSetOf<String>()

    /** @return true when [owner] was not already holding an interest. Idempotent. */
    fun acquire(owner: String): Boolean = owners.add(owner)

    /**
     * Drops [owner]'s interest. True only when [owner] genuinely held one and nobody is left -
     * the one case where the network request itself should be released.
     */
    fun releaseIsLast(owner: String): Boolean = owners.remove(owner) && owners.isEmpty()

    fun isHeldByOthers(owner: String): Boolean = owners.any { it != owner }

    fun describe(): String = owners.joinToString()
}

/**
 * The process-wide owner of the one [TBoxNetworkConnector] this process is allowed to have.
 *
 * Four sites used to construct their own connector - the Hub UI, the AIDL bridge, the session
 * establisher and the port scanner - and each brought its own exclusive `WifiNetworkSpecifier`
 * request. Two of those for the same SSID do not queue, they fight: each grant drops the other's
 * network, and releasing either tears down the association the other is streaming over. The
 * connector has detected this for a month ("2 T-Box network connectors now hold a Wi-Fi request
 * at the same time... This is a MOTO-HUB fault, not the dash") without being able to prevent it,
 * and two riders paid for it: the EASYCONN log of 2026-07-31 (a live Android Auto session plus
 * an AIDL connect - networks granted and lost within a second, thirteen times, until the
 * dashboard died), and rider a9fb623a on 2026-08-04, whose closing MainActivity released its own
 * request and took the AP down under the companion session that was streaming over it.
 *
 * The fix is ownership, not another guard: exactly one connector exists per process, so a second
 * request is impossible by construction, and the process-global state the instances used to fight
 * over ([android.net.ConnectivityManager.bindProcessToNetwork], the SSID-keyed
 * [TBoxRequestGiveUpAlarm]) has a single writer again. `liveRequesters` inside the connector
 * stays as a tripwire: if its ERROR line ever appears again, this invariant has been broken.
 *
 * The only operation that needs coordination on a shared instance is teardown -
 * [TBoxNetworkConnector.connect] already resolves same-SSID reuse, joining a pending hunt, and
 * the switch to a different bike internally, under its own lock. So owners hold a *lease*:
 * [acquire] registers interest, [release] disconnects only when the last interest is gone. The
 * session registry holds a lease of its own while a session is installed (see
 * [TBoxSessionRegistry.install]), which is what lets a connector outlive the ViewModel that
 * created it without anyone having to skip a disconnect and hope.
 *
 * Lock ordering: [TBoxSessionRegistry]'s monitor may be held while calling in here (the registry
 * acquires and releases the session lease); this object never calls the registry. One direction,
 * no deadlock.
 */
object TBoxNetworkConnectors {
    private val lock = Any()
    private var connector: TBoxNetworkConnector? = null
    private val ledger = NetworkInterestLedger()

    /** The registry's own interest, held from install to clear. */
    private const val SESSION_OWNER = "session"

    /**
     * The process's connector, created on first use and held forever. For read-only uses -
     * [TBoxNetworkConnector.events], [TBoxNetworkConnector.currentNetwork], process rebinding,
     * diagnostics - and for `connect()` itself once a lease is held. Never disconnect it
     * directly: that is what [release] is for.
     */
    fun shared(context: Context): TBoxNetworkConnector = synchronized(lock) {
        connector ?: TBoxNetworkConnector(context.applicationContext).also { connector = it }
    }

    /**
     * Registers [owner]'s interest in the network request and returns the shared connector.
     * Idempotent per owner, so a caller that acquires on every attempt (auto-connect fires on
     * every resume) holds one interest, not a growing pile.
     */
    fun acquire(context: Context, owner: String): TBoxNetworkConnector = synchronized(lock) {
        ledger.acquire(owner)
        shared(context)
    }

    /**
     * A short-lived diagnostic's acquire: refused (null) when another owner holds the request and
     * this connector is not the one on [ssid] - the port scanner must not steal the radio from a
     * ride in progress. Granted when the connector is already on (or hunting) the same SSID,
     * where [TBoxNetworkConnector.connect] reuses the active network, and granted when nobody
     * holds an interest at all.
     *
     * A refusal here does NOT mean another motorcycle. It used to say so, and in the companion
     * app it said so every single time: there the Wi-Fi is joined inside CORE, so this process's
     * connector never has an active profile and [TBoxNetworkConnector.isHuntingFor] can only
     * answer false - the one rider connected to their one motorcycle was told MOTO-HUB was busy
     * with a different one (field log 7efdfa33, 2026-08-25). The caller that owns the CORE link
     * asks CORE to scan instead; what is left here is the local case, and the log now names the
     * holder and the SSID rather than guessing at a second bike.
     */
    fun tryAcquireForDiagnostics(context: Context, owner: String, ssid: String): TBoxNetworkConnector? =
        synchronized(lock) {
            val shared = shared(context)
            if (ledger.isHeldByOthers(owner) && !shared.isHuntingFor(ssid)) {
                ProjectionEventLog.record(
                    "NETWORK",
                    "$owner refused the T-Box network: ${ledger.describe()} holds it and this " +
                        "process is not on $ssid."
                )
                return null
            }
            ledger.acquire(owner)
            shared
        }

    /**
     * Who currently holds the request, in the ledger's own words, for a log line that needs to say
     * WHY a join happened rather than only that it did.
     *
     * In the CORE/ADVANCED pair every specifier request is submitted by CORE - the rider tapping
     * Connect in ADVANCED reaches Android through the same code - so a log cannot otherwise tell
     * the two apart, and "ADVANCED is slower than CORE to connect" had nothing to be weighed
     * against (tester report, 2026-08-25). "aidl-bridge" among the holders is the companion's
     * fingerprint; "hub-ui" is this app's own screen.
     *
     * Never call this while holding [TBoxNetworkConnector]'s request lock: [release] takes this
     * object's lock and then that one, so the reverse order would close the cycle.
     */
    fun describeOwners(): String = synchronized(lock) { ledger.describe() }

    /**
     * [owner] no longer needs the network. Disconnects the connector only when this was the last
     * interest - an owner going away can never tear down what another owner is streaming over.
     * Idempotent, and a release never matched by an acquire is a logged no-op rather than a
     * teardown.
     */
    fun release(owner: String): Unit = synchronized(lock) {
        if (ledger.releaseIsLast(owner)) {
            connector?.disconnect()
        } else {
            val holders = ledger.describe()
            if (holders.isNotEmpty()) {
                ProjectionEventLog.debug(
                    "NETWORK",
                    "T-Box network kept after $owner released it: still needed by $holders."
                )
            }
        }
    }

    /**
     * [TBoxSessionRegistry.install]'s hook: the installed session holds its own interest, so the
     * request survives every individual owner - including the ViewModel whose `onCleared` used to
     * have to skip its disconnect while a stream ran. No-op for a connector this object does not
     * manage, which keeps handles built directly in tests inert.
     */
    internal fun adoptForSession(handleConnector: TBoxNetworkConnector): Unit = synchronized(lock) {
        if (handleConnector === connector) ledger.acquire(SESSION_OWNER)
    }

    /** [TBoxSessionRegistry.clear]'s hook; see [adoptForSession]. */
    internal fun releaseSession() = release(SESSION_OWNER)
}
