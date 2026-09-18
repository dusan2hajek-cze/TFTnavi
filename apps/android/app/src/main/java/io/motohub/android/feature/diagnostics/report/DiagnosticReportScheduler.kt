// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.feature.diagnostics.report

import android.content.Context
import io.motohub.android.BuildConfig
import io.motohub.android.session.ProjectionEventLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Diagnostics shows under "Send diagnostics now". */
data class DiagnosticUploadStatus(
    val inProgress: Boolean = false,
    val lastUploadAt: Long = 0L,
    val lastReportId: String? = null,
    val lastError: String? = null,
    val pending: Boolean = false
) {
    fun describe(): String = when {
        inProgress -> "Sending…"
        lastError != null && lastUploadAt == 0L -> "Last attempt failed: $lastError"
        lastError != null -> "Last sent ${formatTime(lastUploadAt)}; latest attempt failed: $lastError"
        lastUploadAt > 0L -> "Last sent ${formatTime(lastUploadAt)}" + if (pending) " (retry pending)" else ""
        else -> "Never sent"
    }

    private fun formatTime(epochMillis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(epochMillis))
}

/**
 * Decides when a report goes out on its own and runs every upload, automatic or manual.
 *
 * Automatic uploads are throttled to what a support case actually needs: the first launch of a
 * new version, once a day otherwise, straight away after a crash, and a retry of anything that
 * could not leave the phone last time. A rider who turns the switch off in Diagnostics stops
 * all of it; "Send diagnostics now" still works, because that one is their own request.
 */
object DiagnosticReportScheduler {
    private const val LOG_SOURCE = "SUPPORT"
    private const val AUTO_UPLOAD_INTERVAL_MS = 24L * 60L * 60L * 1_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val uploadMutex = Mutex()
    private val mutableStatus = MutableStateFlow(DiagnosticUploadStatus())
    val status: StateFlow<DiagnosticUploadStatus> = mutableStatus
    private val mutableCrashConsent = MutableStateFlow(false)

    /**
     * True while a crash is waiting for an answer the rider has not given yet: the app came back
     * from a crash, automatic reports are off, and this build has a collector to send to. The
     * main activity draws [CrashDiagnosticsConsentDialog] on it.
     */
    val crashConsentRequired: StateFlow<Boolean> = mutableCrashConsent
    @Volatile private var startupAttempted = false

    fun refreshStatus(context: Context) {
        val appContext = context.applicationContext
        mutableStatus.value = mutableStatus.value.copy(
            lastUploadAt = DiagnosticReportSettings.lastUploadAt(appContext),
            lastReportId = DiagnosticReportSettings.lastReportId(appContext),
            lastError = DiagnosticReportSettings.lastError(appContext),
            pending = DiagnosticReportSettings.pending(appContext)
        )
    }

    /**
     * Called once per process from the main activity. [previousCrash] is what
     * [io.motohub.android.session.CrashRecovery] found at startup; a native crash or an ANR, which
     * that handler cannot see, is picked up here through [faultOfPreviousProcess]. However it
     * died, a crash report is the one case where waiting a day would lose the point.
     */
    fun onAppStarted(context: Context, previousCrash: Boolean) {
        if (startupAttempted) return
        startupAttempted = true
        val appContext = context.applicationContext
        refreshStatus(appContext)
        if (!DiagnosticReportUploader.configured) return
        val now = System.currentTimeMillis()
        val fault = faultOfPreviousProcess(appContext, previousCrash)
        if (!DiagnosticReportSettings.autoUploadEnabled(appContext)) {
            askAboutCrashIfNeeded(appContext, fault, now)
            return
        }
        DiagnosticReportSettings.clearCrashConsent(appContext)
        val reason = startupReason(appContext, fault, now) ?: return
        ProjectionEventLog.debug(LOG_SOURCE, "Automatic diagnostics upload scheduled: $reason.")
        scope.launch {
            upload(appContext, if (fault != null) DiagnosticReportTrigger.CRASH else DiagnosticReportTrigger.STARTUP)
        }
    }

    /**
     * How the previous process died, in one phrase, or null if it ended normally.
     *
     * Two sources, because no single one sees every crash. [previousCrash] is what
     * [io.motohub.android.session.CrashRecovery] recovered - a Java exception, whose stack trace is
     * already in the log this report will carry. [PreviousProcessFault] covers what that handler
     * structurally cannot see: a native crash or an ANR, which take the process down with no JVM
     * left to run anything. The second was the gap that made this worth extending - those are the
     * deaths a rider has no words for, so a log is the only account that will ever exist.
     *
     * The Java crash is checked first and short-circuits the query: it is the more informative of
     * the two, and the exit history would report the same death again as `REASON_CRASH`.
     */
    private fun faultOfPreviousProcess(context: Context, previousCrash: Boolean): String? {
        if (previousCrash) return "an unhandled exception"
        val detection = PreviousProcessFault.detect(context, DiagnosticReportSettings.lastFaultExitAt(context))
            ?: return null
        // Advanced as soon as it is seen, not when the rider answers: this watermark's only job is
        // to stop Android's exit history - which survives launches and app updates - from
        // rediscovering the same crash forever. Whether to ask again is the stored consent
        // request's decision, and it knows whether anybody answered.
        DiagnosticReportSettings.setLastFaultExitAt(context, detection.at)
        return detection.description
    }

    /**
     * The rider who never turned automatic reports on, asked once, at the only moment the answer
     * is easy to give: the launch straight after their app crashed.
     *
     * A crash report is the one thing support cannot reconstruct afterwards, and the switch under
     * Settings is not where anybody looks after a crash. Asking here is also what keeps the
     * feature honest: the report leaves on a yes, not on a default.
     *
     * The question outlives the launch that raised it. A rider who puts the phone away without
     * answering has not said no, so the flag is written down and the prompt returns next launch
     * until it is answered or [DiagnosticReportSettings.crashConsentPending] lets it expire.
     */
    private fun askAboutCrashIfNeeded(context: Context, fault: String?, nowMillis: Long) {
        val storedAt = DiagnosticReportSettings.crashConsentAt(context)
        when (crashPromptDecision(fault != null, storedAt, nowMillis)) {
            CrashPrompt.ASK_ABOUT_NEW_CRASH -> {
                DiagnosticReportSettings.setCrashConsentPendingAt(context, nowMillis)
                ProjectionEventLog.record(
                    LOG_SOURCE,
                    "Previous process ended in $fault and automatic reports are off; " +
                        "asking the rider whether to send one."
                )
                mutableCrashConsent.value = true
            }
            CrashPrompt.ASK_AGAIN -> {
                ProjectionEventLog.debug(LOG_SOURCE, "Crash report still unanswered; asking again.")
                mutableCrashConsent.value = true
            }
            CrashPrompt.EXPIRE -> {
                DiagnosticReportSettings.clearCrashConsent(context)
                ProjectionEventLog.debug(LOG_SOURCE, "Unanswered crash report expired; no longer asking.")
            }
            CrashPrompt.NONE -> Unit
        }
    }

    /** What [askAboutCrashIfNeeded] does with the crash prompt this launch. */
    internal enum class CrashPrompt { ASK_ABOUT_NEW_CRASH, ASK_AGAIN, EXPIRE, NONE }

    /**
     * The whole prompt policy, with no Context in it so it can be pinned by tests.
     *
     * [storedConsentAt] is the earlier crash nobody answered for: a fresh crash overwrites it, an
     * old enough one is dropped rather than carried forever - see the validity window on
     * [DiagnosticReportSettings.crashConsentPending].
     */
    internal fun crashPromptDecision(previousCrash: Boolean, storedConsentAt: Long, nowMillis: Long): CrashPrompt = when {
        previousCrash -> CrashPrompt.ASK_ABOUT_NEW_CRASH
        storedConsentAt <= 0L -> CrashPrompt.NONE
        DiagnosticReportSettings.withinCrashConsentWindow(storedConsentAt, nowMillis) -> CrashPrompt.ASK_AGAIN
        else -> CrashPrompt.EXPIRE
    }

    /**
     * "Send report" on the crash prompt. The rider's own decision, so it uploads whatever the
     * switch says - exactly like [sendNow] - and [alwaysSend] turns the switch on for next time.
     */
    fun onCrashReportConsented(context: Context, alwaysSend: Boolean) {
        val appContext = context.applicationContext
        mutableCrashConsent.value = false
        // Kept for the re-arm below: the crash's own time, so a report that keeps failing to go
        // out still expires seven days after the crash rather than seven days after each attempt.
        val crashAt = DiagnosticReportSettings.crashConsentAt(appContext)
        DiagnosticReportSettings.clearCrashConsent(appContext)
        if (alwaysSend) {
            DiagnosticReportSettings.setAutoUploadEnabled(appContext, true)
        }
        ProjectionEventLog.record(
            LOG_SOURCE,
            "Rider agreed to send the crash report; automatic reports from now on=$alwaysSend."
        )
        if (mutableStatus.value.inProgress) return
        scope.launch {
            val result = upload(appContext, DiagnosticReportTrigger.CRASH)
            // The likeliest moment for this yes is also the likeliest moment to have no route out:
            // the crash happened while riding, and the phone came back up on the dashboard's
            // Wi-Fi, which carries no Internet. Without the switch on, nothing would ever retry,
            // and a rider who agreed would silently lose the report they agreed to send - so the
            // request goes back on the pile and the next launch offers it again.
            if (result is DiagnosticUploadResult.Unreachable &&
                crashAt > 0L &&
                !DiagnosticReportSettings.autoUploadEnabled(appContext)
            ) {
                DiagnosticReportSettings.setCrashConsentPendingAt(appContext, crashAt)
                ProjectionEventLog.warning(
                    LOG_SOURCE,
                    "The agreed crash report could not leave the phone (${result.reason}); will offer it again next launch."
                )
            }
        }
    }

    /**
     * "Not now" on the crash prompt: an answer, so the question is closed. Nothing is sent and
     * the switch is left exactly as the rider had it - declining one report is not declining the
     * feature, and the reverse would be worse: it would make "no" mean something they did not say.
     */
    fun onCrashReportDeclined(context: Context) {
        val appContext = context.applicationContext
        mutableCrashConsent.value = false
        DiagnosticReportSettings.clearCrashConsent(appContext)
        ProjectionEventLog.record(LOG_SOURCE, "Rider declined to send the crash report.")
    }

    /**
     * The prompt closed without an answer. It goes away for this launch only: the stored request
     * is left alone, so the next launch asks again until it is answered or expires.
     */
    fun dismissCrashPromptForNow() {
        mutableCrashConsent.value = false
    }

    /** Retry hook for the activity's resume: only fires when a previous attempt is still owed. */
    fun retryIfPending(context: Context) {
        val appContext = context.applicationContext
        if (!DiagnosticReportUploader.configured) return
        if (!DiagnosticReportSettings.autoUploadEnabled(appContext)) return
        if (!DiagnosticReportSettings.pending(appContext)) return
        if (mutableStatus.value.inProgress) return
        scope.launch { upload(appContext, DiagnosticReportTrigger.STARTUP) }
    }

    /** The rider's own "Send diagnostics now": no throttle, no switch. */
    fun sendNow(context: Context) {
        val appContext = context.applicationContext
        if (mutableStatus.value.inProgress) return
        scope.launch { upload(appContext, DiagnosticReportTrigger.MANUAL) }
    }

    internal fun startupReason(context: Context, fault: String?, nowMillis: Long): String? {
        if (fault != null) return "previous process ended in $fault"
        if (DiagnosticReportSettings.pending(context)) return "previous upload still pending"
        val lastVersion = DiagnosticReportSettings.lastUploadVersionCode(context)
        if (lastVersion != BuildConfig.VERSION_CODE) return "first launch of ${BuildConfig.VERSION_NAME}"
        val lastAt = DiagnosticReportSettings.lastUploadAt(context)
        if (nowMillis - lastAt >= AUTO_UPLOAD_INTERVAL_MS) return "more than a day since the last report"
        return null
    }

    /**
     * Empties both logs once the collector has confirmed it holds them.
     *
     * The log is a fixed ring shared by every report: without this, each report repeats what
     * the previous one already delivered, and the entries that matter - what happened since -
     * are whatever fraction of the ring is left over. Cleared only on [DiagnosticUploadResult.Sent],
     * which the collector confirms with its body marker, so nothing is thrown away on a 200
     * that stored nothing; an upload that failed keeps every line for the retry.
     *
     * The window between assembling the report and this call (seconds, most of it the upload)
     * is cleared with it - the price of a clear over a per-line watermark, which the CORE half
     * cannot support: it arrives as formatted text over the bridge, not as events.
     */
    private suspend fun startFreshLog(
        context: Context,
        result: DiagnosticUploadResult.Sent,
        trigger: DiagnosticReportTrigger
    ) {
        val note = "Diagnostics report ${result.reportId.take(8)} sent " +
            "(${trigger.wireName}, ${result.logBytes / 1024} KiB); log cleared, recording restarts here."
        // A failure to reach the other half is not worth a warning: the ADVANCED companion
        // already records which halves it emptied, and this app's own log is cleared either way.
        // In CORE there is no other half and nothing to fail.
        runCatching { createDiagnosticsCompanion(context).clearLog(context, note) }
            .onFailure { failure ->
                ProjectionEventLog.warning(LOG_SOURCE, "$note (clear failed: ${failure.message})")
            }
    }

    /** Returns what the collector said, or null when the report could not even be assembled. */
    private suspend fun upload(context: Context, trigger: DiagnosticReportTrigger): DiagnosticUploadResult? = uploadMutex.withLock {
        mutableStatus.value = mutableStatus.value.copy(inProgress = true)
        try {
            val report = runCatching { DiagnosticReportBuilder.build(context, trigger) }
                .getOrElse { failure ->
                    ProjectionEventLog.warning(LOG_SOURCE, "Unable to assemble the diagnostics report.", failure)
                    DiagnosticReportSettings.recordFailure(context, "could not assemble the report", keepPending = false)
                    return@withLock null
                }
            when (val result = DiagnosticReportUploader.upload(context, report)) {
                is DiagnosticUploadResult.Sent -> {
                    DiagnosticReportSettings.recordSuccess(context, result.reportId, BuildConfig.VERSION_CODE, System.currentTimeMillis())
                    startFreshLog(context, result, trigger)
                    result
                }
                is DiagnosticUploadResult.Unreachable -> {
                    // "Pending" is a debt the automatic path owes itself, and only it ever pays:
                    // retryIfPending does nothing while the switch is off. A report the rider
                    // asked for by hand - "Send diagnostics now", or a one-off yes to the crash
                    // prompt - must not leave that debt behind, or it would be collected much
                    // later, by a switch they turned on for something else entirely.
                    val keepPending = trigger != DiagnosticReportTrigger.MANUAL &&
                        DiagnosticReportSettings.autoUploadEnabled(context)
                    DiagnosticReportSettings.recordFailure(context, result.reason, keepPending = keepPending)
                    ProjectionEventLog.warning(LOG_SOURCE, "Diagnostics report not sent, will retry: ${result.reason}")
                    result
                }
                is DiagnosticUploadResult.Rejected -> {
                    DiagnosticReportSettings.recordFailure(context, result.reason, keepPending = false)
                    ProjectionEventLog.warning(LOG_SOURCE, "Diagnostics report rejected: ${result.reason}")
                    result
                }
            }
        } finally {
            mutableStatus.value = mutableStatus.value.copy(inProgress = false)
            refreshStatus(context)
        }
    }
}
