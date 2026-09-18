// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.Context
import io.motohub.android.feature.home.DiagnosticsOffer
import io.motohub.android.session.ProjectionEventLog

/**
 * The profile-trial dialog's offer to share a log.
 *
 * Nothing here is new machinery - it is the same consent-gated uploader the Settings screen and
 * the post-crash prompt already drive. What is new is the moment it is offered from: a rider who
 * has just been helped, about the one fact the collector cannot obtain any other way.
 */
class ProfileTrialDiagnosticsOffer private constructor(context: Context) : DiagnosticsOffer {
    private val appContext = context.applicationContext

    override val autoUploadEnabled: Boolean
        get() = DiagnosticReportSettings.autoUploadEnabled(appContext)

    override fun sendNow() {
        ProjectionEventLog.record(
            "SUPPORT",
            "Rider agreed to send a diagnostics report after a profile change fixed their dashboard."
        )
        DiagnosticReportScheduler.sendNow(appContext)
    }

    companion object {
        /**
         * Null in a build with no collector behind it - which is every build made from the public
         * repository, since the endpoint and key come from a private properties file.
         *
         * Returning null rather than an offer that quietly does nothing: the dialog hides the
         * whole section on null, and a checkbox that thanks a rider for helping and then discards
         * their answer is worse than not asking.
         */
        fun createOrNull(context: Context): ProfileTrialDiagnosticsOffer? =
            if (DiagnosticReportUploader.configured) ProfileTrialDiagnosticsOffer(context) else null
    }

    override fun enableAutoUpload() {
        // Only ever called from a checkbox the rider ticked themselves, in a dialog that says in
        // plain words what it is for. The switch it sets is the same one Settings shows, and
        // Settings remains where it is turned back off.
        ProjectionEventLog.record(
            "SUPPORT",
            "Rider turned on automatic diagnostics reports from the profile confirmation."
        )
        DiagnosticReportSettings.setAutoUploadEnabled(appContext, true)
    }
}
