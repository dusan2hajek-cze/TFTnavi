package io.motohub.android.feature.diagnostics.report

import android.app.ApplicationExitInfo
import io.motohub.android.feature.diagnostics.report.DiagnosticReportScheduler.CrashPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash prompt is the only path by which a report leaves the phone of a rider who never opted
 * in, so the two failures worth pinning are opposite ones: never asking about a crash that just
 * happened, and asking forever about one nobody answered.
 *
 * SharedPreferences needs a Context and this module has no Robolectric, so what is tested is the
 * decision, not the storage - which is why the decision was given a Context-free form.
 */
class CrashPromptPolicyTest {
    private val day = 24L * 60L * 60L * 1_000L
    private val now = 1_700_000_000_000L

    @Test
    fun `a crash on the previous run is always asked about`() {
        assertEquals(
            CrashPrompt.ASK_ABOUT_NEW_CRASH,
            DiagnosticReportScheduler.crashPromptDecision(previousCrash = true, storedConsentAt = 0L, nowMillis = now)
        )
    }

    @Test
    fun `a fresh crash outranks an older unanswered one`() {
        // Both true; the new crash must win, so the stored timestamp is refreshed rather than
        // leaving the prompt anchored to a crash that is about to expire.
        assertEquals(
            CrashPrompt.ASK_ABOUT_NEW_CRASH,
            DiagnosticReportScheduler.crashPromptDecision(
                previousCrash = true,
                storedConsentAt = now - 6 * day,
                nowMillis = now
            )
        )
    }

    @Test
    fun `a launch with no crash and nothing stored asks nothing`() {
        assertEquals(
            CrashPrompt.NONE,
            DiagnosticReportScheduler.crashPromptDecision(previousCrash = false, storedConsentAt = 0L, nowMillis = now)
        )
    }

    @Test
    fun `an unanswered crash is asked about again on the next launch`() {
        assertEquals(
            CrashPrompt.ASK_AGAIN,
            DiagnosticReportScheduler.crashPromptDecision(
                previousCrash = false,
                storedConsentAt = now - day,
                nowMillis = now
            )
        )
    }

    @Test
    fun `an unanswered crash stops being asked about after a week`() {
        assertEquals(
            CrashPrompt.EXPIRE,
            DiagnosticReportScheduler.crashPromptDecision(
                previousCrash = false,
                storedConsentAt = now - 8 * day,
                nowMillis = now
            )
        )
    }

    /**
     * The two deaths CrashRecovery structurally cannot see. If this ever goes green for a system
     * kill instead, a rider whose phone closed the app would be asked to send a log of an app that
     * was working - and told the opposite by SystemKillNotice on the same screen.
     */
    @Test
    fun `a native crash and an ANR count as app faults, a system kill does not`() {
        assertTrue(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_ANR))

        assertFalse(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertFalse(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_USER_REQUESTED))
        assertFalse(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_USER_STOPPED))
        assertFalse(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_OTHER))
        assertFalse(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_EXIT_SELF))
        assertFalse(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_PACKAGE_UPDATED))
    }

    /**
     * A Java crash reaches the prompt through CrashRecovery, which also put its stack trace in the
     * log. Android reports that same death as REASON_CRASH, so treating it as a fault here would
     * be the one duplicate this design has to avoid.
     */
    @Test
    fun `a java crash is left to CrashRecovery rather than counted twice`() {
        assertFalse(PreviousProcessFault.isAppFault(ApplicationExitInfo.REASON_CRASH))
    }
}
