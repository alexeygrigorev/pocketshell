package com.pocketshell.core.transport

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The failure a caller sees when a host's concurrent-channel budget is full and
 * no channel freed up within the bounded wait (issue #2120).
 *
 * A distinct type (still an [IOException], so ordinary error handling keeps
 * working) so the UI can say something true and actionable instead of relaying
 * sshj's raw `open failed`, which is what the server's channel refusal actually
 * looks like at the transport layer and which tells a user nothing.
 */
class ChannelBudgetExhaustedException(
    val operation: String,
    val capacity: Int,
    val waitedMs: Long,
) : IOException(
    "Too many concurrent operations on this host: all $capacity channels are in use " +
        "(waited ${waitedMs}ms for one while starting \"$operation\"). Try again in a moment.",
)

/**
 * The failure a caller sees when the HOST itself kept refusing a new channel for
 * the whole retry window — its own concurrent-session limit is full, not ours.
 *
 * Distinct from [ChannelBudgetExhaustedException] because the two say different
 * true things: that one means *we* are already running as many channels as we
 * allow ourselves, this one means the server said no. Both are [IOException]s
 * carrying a renderable message, so neither ever reaches a user as sshj's bare
 * `open failed`.
 */
class HostChannelLimitException(
    val operation: String,
    val retriedForMs: Long,
    cause: Throwable?,
) : IOException(
    "This host is at its own limit for concurrent SSH channels: it refused a new one " +
        "for \"$operation\" and kept refusing for ${retriedForMs}ms. " +
        "Close a session on this host, or try again in a moment.",
    cause,
)

/**
 * The per-connection concurrent-channel budget (rewrite design §3.1: "a channel
 * budget constant instead of a lease manager").
 *
 * ## Why this exists — issue #2120
 *
 * OpenSSH's `MaxSessions` caps the number of concurrently open **session**
 * channels on ONE SSH connection, and its default is 10. PocketShell's locked
 * architecture (D28, "ONE TRANSPORT") shares exactly one connection per host
 * across every surface, so every exec, PTY and SFTP channel counts against that
 * single budget of 10. On 2026-08-13 the maintainer had 14 sessions on one host
 * and session-create died with sshj's raw `open failed` — the server refusing
 * the 11th channel. Nothing in the client bounded how many channels it asked
 * for, so the app got *less* usable the more sessions were open, and the
 * failure landed on the one action that could have recovered it.
 *
 * This class bounds the ask. Every channel-opening path on
 * [RealHostConnection] takes a permit BEFORE the open request goes out and
 * holds it for the life of the channel, so the client never asks a server for
 * channel [MAX_CONCURRENT_CHANNELS] + 1.
 *
 * ## Behaviour when full
 *
 * Waiting, not failing, is the default: most channels here are short-lived
 * execs (detection, tree reconcile, RPC), so a caller that arrives during a
 * burst almost always gets a permit within milliseconds. Only when nothing
 * frees up inside [CHANNEL_WAIT_TIMEOUT_MS] does the caller get a
 * [ChannelBudgetExhaustedException] — an honest, typed, renderable message
 * rather than a confusing crash. There is deliberately no retry ladder here
 * (the deleted reconnect-storm machinery is not coming back): one bounded wait,
 * then the truth. [openRetryingHostRefusal] below keeps that shape exactly —
 * one more bounded wait for a different scarce resource, then the truth — not
 * an escalating ladder.
 *
 * ## Why bounding the ask is not enough on its own
 *
 * A permit is released when OUR side of a channel is finished, which is strictly
 * earlier than when the SERVER retires the session slot that channel occupied.
 * OpenSSH frees a session in its channel garbage-collection pass, after it has
 * processed our `CHANNEL_CLOSE`; several packets read in one go are all
 * dispatched before that pass runs. So a burst that closes a generation of
 * channels and immediately opens the next can present the server with up to
 * `2 x capacity` live sessions at once, and the client cannot observe any of
 * this — there is no "slot freed" message to wait for.
 *
 * That is why [openRetryingHostRefusal] exists: the budget bounds the ask, and
 * the residual, unobservable server-side lag is absorbed as a short wait instead
 * of surfacing as sshj's raw `open failed`.
 *
 * Every knob is a constructor parameter purely so tests can drive the exhaustion
 * and refusal paths without wall-clock waits; production always uses the
 * defaults.
 */
internal class ChannelBudget(
    val capacity: Int = MAX_CONCURRENT_CHANNELS,
    private val waitTimeoutMs: Long = CHANNEL_WAIT_TIMEOUT_MS,
    private val openRetryWindowMs: Long = OPEN_RETRY_WINDOW_MS,
    private val openRetryInitialDelayMs: Long = OPEN_RETRY_INITIAL_DELAY_MS,
    private val openRetryMaxDelayMs: Long = OPEN_RETRY_MAX_DELAY_MS,
) {
    init {
        require(capacity > 0) { "channel budget capacity must be positive, was $capacity" }
        require(waitTimeoutMs >= 0) { "channel wait timeout must not be negative, was $waitTimeoutMs" }
        require(openRetryWindowMs >= 0) {
            "channel open retry window must not be negative, was $openRetryWindowMs"
        }
        require(openRetryInitialDelayMs > 0) {
            "channel open retry delay must be positive, was $openRetryInitialDelayMs"
        }
        require(openRetryMaxDelayMs >= openRetryInitialDelayMs) {
            "max retry delay $openRetryMaxDelayMs must not be below the initial " +
                "$openRetryInitialDelayMs"
        }
    }

    private val permits = Semaphore(capacity)

    /** Permits currently free. Test/diagnostic view; never a decision input. */
    val available: Int get() = permits.availablePermits

    /**
     * Takes one permit for [operation], waiting up to [waitTimeoutMs] for one to
     * free up. Throws [ChannelBudgetExhaustedException] if none does.
     *
     * The caller owns the returned permit and MUST release it when its channel
     * is gone — a missed release silently shrinks the budget and recreates
     * #2120 one channel at a time.
     */
    suspend fun acquire(operation: String): ChannelPermit {
        // kotlinx's Semaphore.acquire is cancellation-atomic: a cancelled (here,
        // timed-out) waiter never keeps a permit, so the timeout cannot leak one.
        val acquired = withTimeoutOrNull(waitTimeoutMs) {
            permits.acquire()
            true
        }
        if (acquired == null) {
            throw ChannelBudgetExhaustedException(operation, capacity, waitTimeoutMs)
        }
        return ChannelPermit(permits)
    }

    /** Scoped [acquire] for a channel whose whole life fits in one call (exec). */
    suspend fun <T> withPermit(operation: String, block: suspend () -> T): T {
        val permit = acquire(operation)
        try {
            return block()
        } finally {
            permit.release()
        }
    }

    /**
     * Runs [open] — one channel-open attempt — retrying while the host answers
     * with a [ChannelRefusedException], for at most [openRetryWindowMs].
     *
     * The caller keeps its permit across the retries on purpose. A refusal here
     * does not mean we are over our own budget (we are not: we hold a permit);
     * it means the server has not yet retired sessions we already finished with,
     * which resolves on its very next garbage-collection pass. Waiting a few
     * milliseconds and asking again is the honest response, and it is bounded —
     * one window, no escalation, no reconnect, nothing resembling the deleted
     * storm machinery. When the window is spent the caller gets a typed
     * [HostChannelLimitException], never sshj's raw `open failed`.
     *
     * The window is deliberately shorter than [CHANNEL_WAIT_TIMEOUT_MS], so a
     * caller that queued for a permit AND then hit a busy server still finishes
     * inside `exec`'s 15s default timeout.
     */
    suspend fun <T> openRetryingHostRefusal(operation: String, open: suspend () -> T): T {
        var lastRefusal: ChannelRefusedException = try {
            return open()
        } catch (refusal: ChannelRefusedException) {
            refusal
        }

        // Holds the channel across the timeout boundary: `open()` is blocking, so
        // it can complete just as the window expires. Reading the box AFTER
        // withTimeoutOrNull means such a channel is returned, never leaked.
        var opened: OpenedChannel<T>? = null
        withTimeoutOrNull(openRetryWindowMs) {
            var backoffMs = openRetryInitialDelayMs
            while (opened == null) {
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(openRetryMaxDelayMs)
                try {
                    opened = OpenedChannel(open())
                } catch (refusal: ChannelRefusedException) {
                    lastRefusal = refusal
                }
            }
        }

        val settled = opened ?: throw HostChannelLimitException(
            operation = operation,
            retriedForMs = openRetryWindowMs,
            cause = lastRefusal,
        )
        return settled.value
    }

    /** Box so a legitimately `null` channel value is not read as "not opened". */
    private class OpenedChannel<T>(val value: T)

    internal companion object {
        /**
         * Concurrent channels this client will open on ONE connection.
         *
         * Eight, against OpenSSH's default `MaxSessions` of 10. The two-channel
         * headroom covers a host legitimately carrying a channel we did not open
         * (a `-CC` control channel owned elsewhere, or a reconnect racing a grace
         * close). Sizing at the server's exact limit would make the very refusal
         * this budget exists to prevent reachable on timing alone.
         *
         * It does NOT, and cannot, cover the release/retire skew described in the
         * class docs. Measured against the `tests/docker/Dockerfile.ssh` fixture
         * (OpenSSH, `MaxSessions` at its default 10), 60 rounds of 16 concurrent
         * execs on one connection, counting sshd's own "no more sessions":
         *
         * | capacity | 8  | 7  | 6  | 5 | 4 | 3 |
         * |----------|----|----|----|---|---|---|
         * | refusals | 45 | 15 | 13 | 0 | 0 | 0 |
         *
         * The cliff sits exactly at `MaxSessions / 2`, which is the arithmetic of
         * a whole generation of just-released channels still holding server slots
         * while the next generation opens. Sizing down to 5 would buy that
         * headroom by re-creating the product failure #2120 is about: the budget
         * is shared with long-lived PTYs, so five open sessions would leave zero
         * channels for exec and the app would again get *less* usable the more
         * sessions are open. The capacity therefore stays at 8 and the skew is
         * absorbed by [openRetryingHostRefusal] instead.
         */
        const val MAX_CONCURRENT_CHANNELS = 8

        /**
         * How long a caller waits for a busy budget to free a channel before
         * getting [ChannelBudgetExhaustedException]. Under `exec`'s 15s default
         * timeout on purpose: a queued caller's total latency stays bounded and
         * explainable.
         */
        const val CHANNEL_WAIT_TIMEOUT_MS = 5_000L

        /**
         * How long [openRetryingHostRefusal] keeps re-asking a host that refused
         * a channel. Two seconds: the server-side retire it waits on completes in
         * the sshd event loop iteration right after our `CHANNEL_CLOSE`, so the
         * first retry almost always wins, while a host that is *genuinely* full
         * fails fast enough to stay inside `exec`'s 15s budget even after a full
         * [CHANNEL_WAIT_TIMEOUT_MS] queue wait.
         */
        const val OPEN_RETRY_WINDOW_MS = 2_000L

        /** First retry pause; doubles up to [OPEN_RETRY_MAX_DELAY_MS]. */
        const val OPEN_RETRY_INITIAL_DELAY_MS = 20L

        /** Ceiling on the doubling, so a full host is still re-probed ~5x/second. */
        const val OPEN_RETRY_MAX_DELAY_MS = 200L
    }
}

/**
 * One taken permit. [release] is idempotent, because several lifecycle events
 * can legitimately end the same channel (a PTY that both exits remotely and is
 * then closed locally) and a double release would inflate the budget past the
 * server's real limit.
 */
internal class ChannelPermit(private val permits: Semaphore) {
    private val released = AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) {
            permits.release()
        }
    }
}
