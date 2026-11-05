package ru.nedotify.app.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/**
 * One wall-clock budget for a whole resolution cascade.
 *
 * A cascade that gives every attempt its own timeout is not a cascade: the
 * user waits for the sum of the timeouts. The budget here is therefore
 * **wall-clock**: it is computed once, and every attempt is bounded by what
 * is left of it. An attempt that overruns the budget is cancelled and the
 * cascade reports the deadline, not the attempt's own error.
 *
 * The budget is measured with a **monotonic** clock. A wall clock can be
 * stepped by an NTP correction: the step may make the remaining budget jump
 * to zero, and the cascade would end silently, in the middle of a working
 * provider. `System.nanoTime()` cannot be stepped.
 */
class ResolutionCascade(budgetMillis: Long, private val now: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val deadline = now() + budgetMillis

    init { require(budgetMillis > 0) { "A resolution budget must be positive" } }

    /** The milliseconds that are left of the budget. */
    fun remaining(): Long = (deadline - now()).coerceAtLeast(0L)

    /**
     * Runs the attempts in order under the one budget.
     *
     * An attempt returns `null` when it cannot serve the track (the identity
     * gate did not admit its provider); an attempt that *fails* hands the
     * cascade its error, and the cascade continues with the next attempt. The
     * first error is the one that is reported when no attempt succeeds.
     */
    suspend fun <T> resolve(attempts: List<suspend (remaining: Long) -> T?>): T {
        var firstError: Exception? = null
        for (attempt in attempts) {
            val left = remaining()
            if (left <= 0L) break
            try {
                val result = attempt(left)
                if (result != null) return result
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (firstError == null) firstError = error }
        }
        throw firstError ?: SourceFailure("Источник не ответил вовремя. Повторите попытку")
    }
}

/**
 * Runs one attempt under the remaining budget.
 *
 * The deadline is converted into the app's own failure: the caller's
 * cancellation keeps its meaning, only the budget's expiry is translated.
 */
internal suspend fun <T> withinBudget(remaining: Long, message: String, block: suspend () -> T): T {
    currentCoroutineContext().ensureActive()
    return try {
        withTimeout(remaining) { block() }
    } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw SourceFailure(message)
    }
}
