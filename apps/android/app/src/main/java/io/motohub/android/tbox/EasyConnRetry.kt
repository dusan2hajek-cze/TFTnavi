// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal data class EasyConnRetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMillis: Long = 750L,
    val maximumDelayMillis: Long = 2_000L,
    val backoffMultiplier: Int = 2,
    /**
     * Wall-clock ceiling on the whole run, counted from the first attempt.
     *
     * An attempt count alone stopped being a budget once the native handshake moved inside this
     * loop: three cheap `connection refused` attempts cost ~2 s, three that each burn the native
     * startup timeout cost 75 s, and a rider stares at the same "Connecting" either way. Default
     * is unbounded, which is the pre-existing behaviour for every caller that does not opt in.
     */
    val totalBudgetMillis: Long = Long.MAX_VALUE
) {
    init {
        require(maxAttempts > 0)
        require(initialDelayMillis >= 0)
        require(maximumDelayMillis >= initialDelayMillis)
        require(backoffMultiplier >= 1)
        require(totalBudgetMillis > 0)
    }
}

/**
 * @param onBudgetSpent called instead of [onRetry] when a retry is refused because
 *   [EasyConnRetryPolicy.totalBudgetMillis] is gone. It exists so a rider log can tell that case
 *   apart from "the failure was permanent" and from "the attempts ran out" - three different
 *   reasons that otherwise all read as the same failure with nothing after it.
 * @param elapsedMillis monotonic clock, injectable so the budget is testable without sleeping.
 */
internal suspend fun <T> retryEasyConnStart(
    policy: EasyConnRetryPolicy,
    shouldRetry: (Throwable) -> Boolean,
    onRetry: (failedAttempt: Int, delayMillis: Long, failure: Throwable) -> Unit = { _, _, _ -> },
    onBudgetSpent: (failedAttempt: Int, elapsedMillis: Long, failure: Throwable) -> Unit = { _, _, _ -> },
    sleeper: suspend (Long) -> Unit = { delay(it) },
    elapsedMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    operation: suspend (attempt: Int) -> T
): T {
    var retryDelayMillis = policy.initialDelayMillis
    val startedAt = elapsedMillis()
    for (attempt in 1..policy.maxAttempts) {
        try {
            return operation(attempt)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            if (attempt == policy.maxAttempts || !shouldRetry(failure)) throw failure
            // Subtraction, not `elapsed + delay >= budget`: the default budget is Long.MAX_VALUE
            // and the sum would overflow into a negative number that always allows the retry.
            val spent = elapsedMillis() - startedAt
            if (policy.totalBudgetMillis - spent <= retryDelayMillis) {
                onBudgetSpent(attempt, spent, failure)
                throw failure
            }
            onRetry(attempt, retryDelayMillis, failure)
            sleeper(retryDelayMillis)
            retryDelayMillis = (retryDelayMillis * policy.backoffMultiplier)
                .coerceAtMost(policy.maximumDelayMillis)
        }
    }
    error("EasyConn retry loop completed without a result")
}

internal fun isTransientEasyConnFailure(failure: Throwable): Boolean {
    if (failure is CancellationException) return false

    val failureChain = generateSequence(failure) { it.cause }.toList()
    val detail = failureChain
        .mapNotNull(Throwable::message)
        .joinToString(" ")
        .lowercase()

    if (PERMANENT_FAILURE_MARKERS.any(detail::contains)) return false
    if (failureChain.any { it is IOException }) return true
    return TRANSIENT_FAILURE_MARKERS.any(detail::contains)
}

private val PERMANENT_FAILURE_MARKERS = listOf(
    "already running",
    "host is not set",
    "mux source is not set",
    "invalid ec init socket descriptor",
    "unable to adopt ec init socket",
    "unable to release ec init descriptor",
    "start reverse server"
)

private val TRANSIENT_FAILURE_MARKERS = listOf(
    "context deadline exceeded",
    "i/o timeout",
    "timed out",
    "timeout",
    "connection refused",
    "connection reset",
    "connection closed",
    "broken pipe",
    "no route to host",
    "network is unreachable",
    "failed to decode response",
    "no response",
    "initialize easyconn stream",
    "unsuccessful ec response"
)
