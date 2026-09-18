// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.Context
import android.content.SharedPreferences

/** What the collector remembers between launches: the rider's choice and the last upload. */
object DiagnosticReportSettings {
    private const val PREFERENCES_NAME = "diagnostic_reports"
    private const val KEY_AUTO_UPLOAD = "auto_upload"
    private const val KEY_LAST_UPLOAD_AT = "last_upload_at"
    private const val KEY_LAST_UPLOAD_VERSION_CODE = "last_upload_version_code"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_PENDING = "pending"
    private const val KEY_LAST_REPORT_ID = "last_report_id"
    private const val KEY_CRASH_CONSENT_AT = "crash_consent_at"
    private const val KEY_LAST_FAULT_EXIT_AT = "last_fault_exit_at"

    /**
     * How long a crash keeps the right to interrupt a rider for an answer.
     *
     * The prompt survives a launch where nobody answered it - closing the app is not "no" - but
     * not indefinitely: the report it would send is drawn from a fixed-size log ring, so a week
     * of riding later there is nothing of that crash left to send and the question is only noise.
     */
    private const val CRASH_CONSENT_VALIDITY_MS = 7L * 24L * 60L * 60L * 1_000L

    /**
     * **Off by default.** Nothing leaves the phone until a rider turns this on themselves under
     * Settings > Diagnostics, which is also why no notice interrupts the first launch any more:
     * there is nothing to warn about until someone opts in, and the switch's own description and
     * the "What gets sent" notice are where that decision is made.
     */
    fun autoUploadEnabled(context: Context): Boolean = preferences(context).getBoolean(KEY_AUTO_UPLOAD, false)

    fun setAutoUploadEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(KEY_AUTO_UPLOAD, enabled).apply()
    }

    fun lastUploadAt(context: Context): Long = preferences(context).getLong(KEY_LAST_UPLOAD_AT, 0L)

    fun lastUploadVersionCode(context: Context): Int = preferences(context).getInt(KEY_LAST_UPLOAD_VERSION_CODE, 0)

    fun lastError(context: Context): String? = preferences(context).getString(KEY_LAST_ERROR, null)

    fun lastReportId(context: Context): String? = preferences(context).getString(KEY_LAST_REPORT_ID, null)

    /** A startup upload that could not go out (no Internet, server down) and should be retried. */
    fun pending(context: Context): Boolean = preferences(context).getBoolean(KEY_PENDING, false)

    fun setPending(context: Context, pending: Boolean) {
        preferences(context).edit().putBoolean(KEY_PENDING, pending).apply()
    }

    /**
     * A crash whose report is waiting for the rider's answer, and when it happened.
     *
     * Only ever set for a rider who has *not* turned automatic reports on: with the switch on,
     * the crash report goes out on its own and there is nothing to ask.
     */
    fun crashConsentAt(context: Context): Long = preferences(context).getLong(KEY_CRASH_CONSENT_AT, 0L)

    fun crashConsentPending(context: Context, nowMillis: Long): Boolean =
        crashConsentAt(context).let { it > 0L && withinCrashConsentWindow(it, nowMillis) }

    /** Context-free half of the check, so the prompt policy can be tested without Android. */
    internal fun withinCrashConsentWindow(consentAt: Long, nowMillis: Long): Boolean =
        nowMillis - consentAt < CRASH_CONSENT_VALIDITY_MS

    fun setCrashConsentPendingAt(context: Context, nowMillis: Long) {
        preferences(context).edit().putLong(KEY_CRASH_CONSENT_AT, nowMillis).apply()
    }

    /** Answered, expired, or made moot by the switch being turned on: stop asking. */
    fun clearCrashConsent(context: Context) {
        preferences(context).edit().remove(KEY_CRASH_CONSENT_AT).apply()
    }

    /**
     * How far [PreviousProcessFault] has already looked. Without it, Android's exit history - which
     * outlives the process, the launch and even the app update - would report the same native
     * crash at every launch forever.
     */
    fun lastFaultExitAt(context: Context): Long = preferences(context).getLong(KEY_LAST_FAULT_EXIT_AT, 0L)

    fun setLastFaultExitAt(context: Context, epochMillis: Long) {
        preferences(context).edit().putLong(KEY_LAST_FAULT_EXIT_AT, epochMillis).apply()
    }

    fun recordSuccess(context: Context, reportId: String, versionCode: Int, nowMillis: Long) {
        preferences(context).edit()
            .putLong(KEY_LAST_UPLOAD_AT, nowMillis)
            .putInt(KEY_LAST_UPLOAD_VERSION_CODE, versionCode)
            .putString(KEY_LAST_REPORT_ID, reportId)
            .remove(KEY_LAST_ERROR)
            .putBoolean(KEY_PENDING, false)
            .apply()
    }

    fun recordFailure(context: Context, error: String, keepPending: Boolean) {
        preferences(context).edit()
            .putString(KEY_LAST_ERROR, error.take(300))
            .putBoolean(KEY_PENDING, keepPending)
            .apply()
    }

    private fun preferences(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
}
