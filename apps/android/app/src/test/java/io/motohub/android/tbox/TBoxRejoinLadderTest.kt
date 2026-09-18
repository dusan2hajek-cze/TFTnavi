// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import org.junit.Assert.assertEquals
import org.junit.Test

class TBoxRejoinLadderTest {
    private fun step(attempt: Int, elapsedMillis: Long): TBoxRejoinStep = nextTBoxRejoinStep(
        attempt = attempt,
        elapsedMillis = elapsedMillis,
        budgetMillis = BUDGET,
        firstDelayMillis = FIRST,
        baseDelayMillis = BASE,
        maxDelayMillis = MAX
    )

    @Test
    fun `the first attempt retries almost immediately`() {
        assertEquals(TBoxRejoinStep.WaitThenRetry(FIRST), step(attempt = 1, elapsedMillis = 0L))
    }

    @Test
    fun `later attempts back off and then hold at the cap`() {
        assertEquals(TBoxRejoinStep.WaitThenRetry(2_500L), step(attempt = 2, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitThenRetry(5_000L), step(attempt = 3, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitThenRetry(MAX), step(attempt = 7, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitThenRetry(MAX), step(attempt = 40, elapsedMillis = 0L))
    }

    @Test
    fun `the ladder surrenders once the budget is spent`() {
        assertEquals(TBoxRejoinStep.WaitThenRetry(MAX), step(attempt = 9, elapsedMillis = BUDGET - 1))
        assertEquals(TBoxRejoinStep.GiveUp, step(attempt = 9, elapsedMillis = BUDGET))
        assertEquals(TBoxRejoinStep.GiveUp, step(attempt = 9, elapsedMillis = BUDGET * 4))
    }

    /**
     * A rider's diagnostics recorded 39 attempts over nineteen minutes against a bike that was
     * simply switched off, still holding an exclusive Wi-Fi request and fighting the rider's own
     * reconnect. Whatever the attempt count, elapsed time alone has to end the ladder.
     */
    @Test
    fun `no attempt count can outlast the budget`() {
        (1..500).forEach { attempt ->
            assertEquals(TBoxRejoinStep.GiveUp, step(attempt = attempt, elapsedMillis = BUDGET))
        }
    }

    /** A round entered with this attempt's backoff already served. */
    private fun servedStep(
        attempt: Int,
        elapsedMillis: Long,
        submissionWouldBeRefused: Boolean
    ): TBoxRejoinStep = nextTBoxRejoinStep(
        attempt = attempt,
        elapsedMillis = elapsedMillis,
        budgetMillis = BUDGET,
        firstDelayMillis = FIRST,
        baseDelayMillis = BASE,
        maxDelayMillis = MAX,
        submissionWouldBeRefused = submissionWouldBeRefused,
        backgroundPollMillis = POLL,
        backoffElapsed = true
    )

    @Test
    fun `a served backoff submits instead of waiting again`() {
        assertEquals(TBoxRejoinStep.SubmitNow, servedStep(1, 0L, submissionWouldBeRefused = false))
        assertEquals(TBoxRejoinStep.SubmitNow, servedStep(4, 30_000L, submissionWouldBeRefused = false))
    }

    /**
     * Rider 4d8a4c5b, 2026-08-26. The ladder read the process importance, found the app in the
     * foreground, announced "back in the foreground; resuming", slept ten seconds - and submitted
     * on the far side of that sleep, from the background it had fallen into meanwhile. Android
     * refused it in 19ms and it counted as the fifth and last attempt.
     *
     * The reading that authorises a submission is taken in the round that submits, so the answer
     * here has to be to wait, however much backoff has already been served.
     */
    @Test
    fun `a process that fell to the background during its backoff does not submit anyway`() {
        assertEquals(
            TBoxRejoinStep.WaitForForeground(POLL),
            servedStep(5, 150_000L, submissionWouldBeRefused = true)
        )
    }

    /**
     * ...and the backoff it already served is not served again once the rider does open the app:
     * making them sit through it a second time would punish the one action being waited for.
     */
    @Test
    fun `coming back to the foreground submits without redoing the backoff`() {
        assertEquals(
            TBoxRejoinStep.WaitForForeground(POLL),
            servedStep(5, 150_000L, submissionWouldBeRefused = true)
        )
        assertEquals(
            TBoxRejoinStep.SubmitNow,
            servedStep(5, 152_000L, submissionWouldBeRefused = false)
        )
    }

    /** The budget still ends the ladder, whatever has been served. */
    @Test
    fun `a served backoff does not outlast the budget`() {
        assertEquals(TBoxRejoinStep.GiveUp, servedStep(2, BUDGET, submissionWouldBeRefused = false))
        assertEquals(TBoxRejoinStep.GiveUp, servedStep(2, BUDGET, submissionWouldBeRefused = true))
    }

    private fun backgroundStep(attempt: Int, elapsedMillis: Long): TBoxRejoinStep =
        nextTBoxRejoinStep(
            attempt = attempt,
            elapsedMillis = elapsedMillis,
            budgetMillis = BUDGET,
            firstDelayMillis = FIRST,
            baseDelayMillis = BASE,
            maxDelayMillis = MAX,
            submissionWouldBeRefused = true,
            backgroundPollMillis = POLL
        )

    /**
     * Support 87bc5a7c: a session teardown destroys its foreground service 261ms before the
     * ladder's first submission, so every request was refused by Android in 11-28ms without the
     * AP ever being looked for - four attempts spent, three minutes burnt, the radio never asked.
     */
    @Test
    fun `a request Android would refuse waits instead of becoming an attempt`() {
        assertEquals(TBoxRejoinStep.WaitForForeground(POLL), backgroundStep(attempt = 1, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitForForeground(POLL), backgroundStep(attempt = 1, elapsedMillis = 60_000L))
    }

    /**
     * The attempt counter is the ladder's memory of what it actually asked. Waiting must not
     * advance it, or a rider who opens the app after two minutes inherits a backed-off delay
     * earned by refusals that never left the process.
     */
    @Test
    fun `waiting never advances the backoff`() {
        assertEquals(TBoxRejoinStep.WaitForForeground(POLL), backgroundStep(attempt = 9, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitThenRetry(FIRST), step(attempt = 1, elapsedMillis = 0L))
    }

    /**
     * A phone that stays in the rider's pocket for the whole budget still has to let go: an
     * exclusive WifiNetworkSpecifier request held past the deadline takes the radio away from
     * the rider's own reconnect, which is the very thing the wait is trying to enable.
     */
    @Test
    fun `the budget ends the ladder even while it is waiting for the foreground`() {
        assertEquals(TBoxRejoinStep.GiveUp, backgroundStep(attempt = 1, elapsedMillis = BUDGET))
        assertEquals(TBoxRejoinStep.GiveUp, backgroundStep(attempt = 1, elapsedMillis = BUDGET * 4))
    }

    /** A foreground process is unaffected: the ordinary ladder is exactly as it was. */
    @Test
    fun `a foreground process still retries on the old schedule`() {
        assertEquals(TBoxRejoinStep.WaitThenRetry(FIRST), step(attempt = 1, elapsedMillis = 0L))
        assertEquals(TBoxRejoinStep.WaitThenRetry(5_000L), step(attempt = 3, elapsedMillis = 0L))
    }

    private companion object {
        const val BUDGET = 180_000L
        const val FIRST = 300L
        const val BASE = 2_500L
        const val MAX = 15_000L
        const val POLL = 2_000L
    }
}
