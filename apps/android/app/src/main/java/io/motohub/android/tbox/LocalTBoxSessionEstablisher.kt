// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
// CORE flavor: the original local connect path (Wi-Fi join + hudlib EasyConn discovery). This is
// exactly what HubViewModel.connectAndDiscover() used to do inline — behaviour unchanged for CORE.
package io.motohub.android.tbox

import android.content.Context
import io.motohub.android.session.DashboardDeliveryMonitor
import io.motohub.android.session.MotorcycleProfile

/** Flavor factory resolved at compile time (a sibling exists in src/pro). */
fun createTBoxSessionEstablisher(context: Context): TBoxSessionEstablisher =
    LocalTBoxSessionEstablisher(context)

class LocalTBoxSessionEstablisher(private val context: Context) : TBoxSessionEstablisher {

    override val transport: TBoxTransport = SelectingTBoxTransport(context)
    override val networkConnector: TBoxNetworkConnector = TBoxNetworkConnectors.shared(context)

    /** The pipelines run in this process, so there is nothing to ask anybody. */
    override fun deliveryWarnings() = DashboardDeliveryMonitor.current
    private val capabilityStore = TBoxCapabilityStore(context)

    override suspend fun connectAndInstall(
        profile: MotorcycleProfile,
        onNetworkConnected: suspend () -> Unit,
        onNetworkError: (Throwable) -> Unit,
        onDiscoveryError: (Throwable) -> Unit
    ): Boolean {
        TBoxNetworkConnectors.acquire(context, TBoxSessionEstablisher.NETWORK_OWNER)
        // The lease survives a network failure on purpose: the specifier request outlives its
        // timeout (v1.1.17) and a retry joins the hunt already in progress.
        val connected = TBoxLinkResolver.connect(context, networkConnector, profile)
        val link = connected.getOrElse { onNetworkError(it); return false }
        onNetworkConnected()
        transport.configureProtocolProfile(
            TBoxModelProfile.resolve(
                profile.modelId,
                null,
                ProfileOverride.byKey(profile.profileOverrideKey)
            ),
            profile
        )
        val discovered = transport.discover(link, profile.modelId)
        val host = discovered.getOrElse {
            transport.stop()
            link.disconnect()
            TBoxSessionRegistry.clear()
            TBoxNetworkConnectors.release(TBoxSessionEstablisher.NETWORK_OWNER)
            onDiscoveryError(it)
            return false
        }
        capabilityStore.recordDiscovery(profile, host)
        TBoxSessionRegistry.install(
            TBoxSessionHandle(transport, host, networkConnector, profile, link)
        )
        return true
    }
}
