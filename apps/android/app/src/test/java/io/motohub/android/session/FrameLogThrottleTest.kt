package io.motohub.android.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The frame-counter line used to fire every 120 frames — four seconds at 30 fps — and filled
 * 555 of the 1500 slots in rider a9fb623a's report while the buffer was evicting the history
 * the report was raised about.
 */
class FrameLogThrottleTest {

    @Test
    fun `the first frame of a session always logs, with no rate to report yet`() {
        val throttle = FrameLogThrottle()

        assertEquals("", throttle.rateSuffixIfDue(frameCount = 1L, nowUptimeMillis = START))
    }

    @Test
    fun `stays quiet for a whole minute of streaming`() {
        val throttle = FrameLogThrottle()
        throttle.rateSuffixIfDue(1L, START)

        // A minute at 30 fps is 1800 frames; the old rule logged fifteen times over this span.
        for (frame in 2L..1_800L) {
            val now = START + frame * 1_000L / 30L
            if (now - START >= FRAME_LOG_INTERVAL_MILLIS) break
            assertNull(
                "frame $frame at ${now - START}ms should not have logged",
                throttle.rateSuffixIfDue(frame, now)
            )
        }
    }

    @Test
    fun `logs again once the interval has elapsed`() {
        val throttle = FrameLogThrottle()
        throttle.rateSuffixIfDue(1L, START)

        assertNotNull(throttle.rateSuffixIfDue(1_801L, START + FRAME_LOG_INTERVAL_MILLIS))
    }

    @Test
    fun `the line carries the rate measured since the previous one`() {
        val throttle = FrameLogThrottle()
        throttle.rateSuffixIfDue(1L, START)

        val suffix = throttle.rateSuffixIfDue(1_801L, START + 60_000L)

        assertEquals(" (30.0 fps over 60s)", suffix)
    }

    /** The interval restarts from the line just emitted, not from the session start. */
    @Test
    fun `the interval is measured from the previous line`() {
        val throttle = FrameLogThrottle()
        throttle.rateSuffixIfDue(1L, START)
        throttle.rateSuffixIfDue(1_801L, START + 60_000L)

        assertNull(throttle.rateSuffixIfDue(2_700L, START + 90_000L))
        assertNotNull(throttle.rateSuffixIfDue(3_601L, START + 120_000L))
    }

    /**
     * A dashboard delivering half the frames it aimed for must say so plainly — that is the
     * whole reason the line survives at all rather than being demoted to DEBUG.
     */
    @Test
    fun `a starved pipeline reports its real rate`() {
        val throttle = FrameLogThrottle()
        throttle.rateSuffixIfDue(1L, START)

        assertEquals(" (12.5 fps over 60s)", throttle.rateSuffixIfDue(751L, START + 60_000L))
    }

    @Test
    fun `an uptime that did not advance cannot produce a rate`() {
        assertEquals("", frameRateSuffix(frames = 100L, elapsedMillis = 0L))
    }

    @Test
    fun `a counter that went backwards cannot produce a rate`() {
        assertEquals("", frameRateSuffix(frames = -1L, elapsedMillis = 60_000L))
    }

    private companion object {
        /** Deliberately large: SystemClock.elapsedRealtime() on a phone that has been up a while. */
        const val START = 9_000_000L
    }
}
