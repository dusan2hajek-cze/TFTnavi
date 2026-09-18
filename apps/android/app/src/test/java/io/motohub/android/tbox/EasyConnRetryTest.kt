// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class EasyConnRetryTest {
    @Test
    fun `returns immediately when the first attempt succeeds`() = runBlocking {
        val attempts = mutableListOf<Int>()

        val result = retryEasyConnStart(
            policy = policy(),
            shouldRetry = { true },
            sleeper = { fail("A successful first attempt must not sleep") }
        ) { attempt ->
            attempts += attempt
            "ready"
        }

        assertEquals("ready", result)
        assertEquals(listOf(1), attempts)
    }

    @Test
    fun `retries transient failures with bounded exponential delays`() = runBlocking {
        val attempts = mutableListOf<Int>()
        val delays = mutableListOf<Long>()
        val retryAttempts = mutableListOf<Int>()

        val result = retryEasyConnStart(
            policy = policy(),
            shouldRetry = ::isTransientEasyConnFailure,
            onRetry = { failedAttempt, _, _ -> retryAttempts += failedAttempt },
            sleeper = delays::add
        ) { attempt ->
            attempts += attempt
            if (attempt < 3) throw SocketTimeoutException("T-Box did not answer")
            "ready"
        }

        assertEquals("ready", result)
        assertEquals(listOf(1, 2, 3), attempts)
        assertEquals(listOf(1, 2), retryAttempts)
        assertEquals(listOf(100L, 200L), delays)
    }

    @Test
    fun `stops after the configured attempt budget`() = runBlocking {
        val expected = IOException("connection reset")
        var attempts = 0

        try {
            retryEasyConnStart(
                policy = policy(),
                shouldRetry = ::isTransientEasyConnFailure,
                sleeper = { }
            ) {
                attempts++
                throw expected
            }
            fail("The final failure must be returned")
        } catch (failure: Throwable) {
            assertSame(expected, failure)
        }

        assertEquals(3, attempts)
    }

    @Test
    fun `does not retry permanent configuration failures`() = runBlocking {
        val expected = IllegalStateException("host is not set or has not been found")
        var attempts = 0

        try {
            retryEasyConnStart(
                policy = policy(),
                shouldRetry = ::isTransientEasyConnFailure,
                sleeper = { fail("A permanent failure must not sleep") }
            ) {
                attempts++
                throw expected
            }
            fail("The permanent failure must be returned")
        } catch (failure: Throwable) {
            assertSame(expected, failure)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `propagates cancellation without retrying`() = runBlocking {
        val expected = CancellationException("cancelled by user")
        var attempts = 0

        try {
            retryEasyConnStart(
                policy = policy(),
                shouldRetry = { true },
                sleeper = { fail("Cancellation must not sleep") }
            ) {
                attempts++
                throw expected
            }
            fail("Cancellation must be propagated")
        } catch (failure: Throwable) {
            assertSame(expected, failure)
        }

        assertEquals(1, attempts)
    }

    @Test
    fun `classifies Go timeout messages but rejects permanent startup errors`() {
        assertTrue(isTransientEasyConnFailure(Exception("context deadline exceeded")))
        assertTrue(isTransientEasyConnFailure(Exception("initialize EasyConn stream: no response")))
        assertTrue(isTransientEasyConnFailure(IOException("network unreachable")))
        assertFalse(isTransientEasyConnFailure(Exception("already running")))
        assertFalse(isTransientEasyConnFailure(Exception("start reverse server 1: bind failed")))
        assertFalse(isTransientEasyConnFailure(IOException("start reverse server 1: bind failed")))
        assertFalse(isTransientEasyConnFailure(CancellationException("cancelled")))
    }

    /**
     * The shape rider 94b0a3da hit: one attempt burns the whole native startup budget. Re-dialling
     * the same silent session here would only double his wait, so the budget must refuse it - and
     * must say so, because "gave up" and "was never allowed to try" read identically in a log.
     */
    @Test
    fun `an attempt that spends the whole budget is not retried and says why`() = runBlocking {
        var attempts = 0
        var clock = 0L
        val budgetSpent = mutableListOf<Pair<Int, Long>>()

        try {
            retryEasyConnStart(
                policy = policy(totalBudgetMillis = 25_000),
                shouldRetry = ::isTransientEasyConnFailure,
                onRetry = { _, _, _ -> fail("There is no time left for another attempt") },
                onBudgetSpent = { attempt, spent, _ -> budgetSpent += attempt to spent },
                sleeper = { fail("A refused retry must not sleep") },
                elapsedMillis = { clock }
            ) {
                attempts++
                clock += 25_037L
                throw IllegalStateException("context deadline exceeded")
            }
            fail("The failure must be returned")
        } catch (failure: Throwable) {
            assertEquals("context deadline exceeded", failure.message)
        }

        assertEquals(1, attempts)
        assertEquals(listOf(1 to 25_037L), budgetSpent)
    }

    /**
     * The other half of the same change: a handshake that fails FAST is exactly what the retry was
     * written for, and the budget must not get in its way.
     */
    @Test
    fun `fast failures still use every attempt within the same budget`() = runBlocking {
        var attempts = 0
        var clock = 0L

        val result = retryEasyConnStart(
            policy = policy(totalBudgetMillis = 25_000),
            shouldRetry = ::isTransientEasyConnFailure,
            onBudgetSpent = { _, _, _ -> fail("There is plenty of budget left") },
            sleeper = { clock += it },
            elapsedMillis = { clock }
        ) { attempt ->
            attempts++
            clock += 12L
            if (attempt < 3) throw IllegalStateException("unsuccessful ec response") else "ready"
        }

        assertEquals("ready", result)
        assertEquals(3, attempts)
    }

    /** The default policy must stay exactly as unbounded as it was before the budget existed. */
    @Test
    fun `an unset budget never refuses a retry`() = runBlocking {
        var attempts = 0
        val clock = Long.MAX_VALUE / 2

        try {
            retryEasyConnStart(
                policy = EasyConnRetryPolicy(initialDelayMillis = 0, maximumDelayMillis = 0),
                shouldRetry = ::isTransientEasyConnFailure,
                onBudgetSpent = { _, _, _ -> fail("An unset budget cannot be spent") },
                sleeper = { },
                elapsedMillis = { clock }
            ) {
                attempts++
                throw IOException("connection reset")
            }
            fail("The final failure must be returned")
        } catch (_: IOException) {
        }

        assertEquals(3, attempts)
    }

    private fun policy(totalBudgetMillis: Long = Long.MAX_VALUE) = EasyConnRetryPolicy(
        maxAttempts = 3,
        initialDelayMillis = 100,
        maximumDelayMillis = 250,
        backoffMultiplier = 2,
        totalBudgetMillis = totalBudgetMillis
    )
}
