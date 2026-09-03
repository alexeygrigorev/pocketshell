package com.pocketshell.core.transport

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
 * then the truth.
 *
 * Both knobs are constructor parameters purely so tests can drive the
 * exhaustion path without a five-second wall-clock wait; production always uses
 * the defaults.
 */
internal class ChannelBudget(
    val capacity: Int = MAX_CONCURRENT_CHANNELS,
    private val waitTimeoutMs: Long = CHANNEL_WAIT_TIMEOUT_MS,
) {
    init {
        require(capacity > 0) { "channel budget capacity must be positive, was $capacity" }
        require(waitTimeoutMs >= 0) { "channel wait timeout must not be negative, was $waitTimeoutMs" }
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

    internal companion object {
        /**
         * Concurrent channels this client will open on ONE connection.
         *
         * Eight, against OpenSSH's default `MaxSessions` of 10. The two-channel
         * headroom is not decoration:
         * - channel teardown is asynchronous and our own close is bounded
         *   (best-effort, 2s cap), so a permit can be released a moment before
         *   the server has finished retiring the channel it belonged to;
         * - the host may legitimately be carrying a channel we did not open
         *   (a `-CC` control channel owned elsewhere, a plain `ssh` session by
         *   the same user is separate, but a reconnect racing a grace close is
         *   not).
         * Sizing at the server's exact limit would make the very refusal this
         * budget exists to prevent reachable again on timing alone.
         */
        const val MAX_CONCURRENT_CHANNELS = 8

        /**
         * How long a caller waits for a busy budget to free a channel before
         * getting [ChannelBudgetExhaustedException]. Under `exec`'s 15s default
         * timeout on purpose: a queued caller's total latency stays bounded and
         * explainable.
         */
        const val CHANNEL_WAIT_TIMEOUT_MS = 5_000L
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
