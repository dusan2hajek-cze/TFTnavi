// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import io.motohub.android.session.ProjectionEventLog

/**
 * The crashes [io.motohub.android.session.CrashRecovery] cannot see.
 *
 * That handler is a Java `UncaughtExceptionHandler`, so it only ever runs for a Java exception. A
 * native crash takes the process down through a signal with no JVM left to call anything, and an
 * ANR is not an exception at all - in both cases MOTO-HUB simply stops existing and the next
 * launch has nothing to show for it. Those are exactly the deaths a rider cannot describe and a
 * log is the only account of, so they are the ones most worth asking about.
 *
 * Android keeps the record either way, in `getHistoricalProcessExitReasons`, which is where this
 * reads it from - the same source
 * [io.motohub.android.session.ProcessExitReport] uses for the rider-facing "stopped by your phone"
 * notice. It is deliberately a second, independent read rather than a hook into that object: this
 * is the ADVANCED-only diagnostics feature, and `ProcessExitReport` lives in the shared CORE half
 * where the collector does not exist. One extra query at startup costs less than a field CORE
 * would carry and never use.
 */
object PreviousProcessFault {
    /** Only the newest few matter; anything older has long since been asked about or expired. */
    private const val MAX_RECORDS = 5

    /**
     * A death Android attributes to a fault in the app, newer than [after].
     *
     * Returns the description to put in the log, or null when the previous process ended for any
     * other reason. [after] is the watermark of what has already been considered, so one native
     * crash is offered once rather than at every launch until the rider answers - re-asking is the
     * job of the stored consent request, which knows whether it was answered.
     */
    fun detect(context: Context, after: Long): Detection? {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return null
        val exits = runCatching {
            activityManager.getHistoricalProcessExitReasons(null, 0, MAX_RECORDS)
        }.getOrElse { failure ->
            ProjectionEventLog.debug(
                "SUPPORT",
                "Android would not say how the previous process ended: ${failure.message}."
            )
            return null
        }
        return exits
            .filter { it.timestamp > after && isAppFault(it.reason) }
            .maxByOrNull { it.timestamp }
            ?.let { Detection(it.timestamp, describe(it.reason)) }
    }

    data class Detection(val at: Long, val description: String)

    /**
     * Whether Android blames the app rather than the phone.
     *
     * `REASON_CRASH` is excluded on purpose: a Java crash already reaches the prompt through
     * `CrashRecovery`, which additionally wrote the stack trace into the log the report carries,
     * and counting it twice would only risk asking about one crash as if it were two.
     *
     * The system kills - low memory, the freezer, an OEM battery manager - are excluded too, and
     * that is a judgement rather than an oversight. The rider already gets
     * [io.motohub.android.ui.components.SystemKillNotice] for those, which says in as many words
     * that the phone stopped the app and nothing is broken. Asking for a fault report in the same
     * breath would contradict it, and the answer would be a log of an app that was working.
     */
    internal fun isAppFault(reason: Int): Boolean = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE,
        ApplicationExitInfo.REASON_ANR -> true
        else -> false
    }

    private fun describe(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "a native crash"
        ApplicationExitInfo.REASON_ANR -> "an ANR (the app stopped responding)"
        else -> "reason $reason"
    }
}
