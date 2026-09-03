package com.pocketshell.next.terminal

/**
 * The WHOLE reconnect policy (rewrite task U-7, plan §C.3).
 *
 * A fixed ladder of waits and a give-up point. That is all it is, and the
 * shortness is the design, not a stub: the pre-rewrite client answered the same
 * question with episode budgets, storm classes, jitter, liveness probes and a
 * connection journal, and the diagnosis doc's §3.4 finding is that none of that
 * machinery ever made a reconnect land sooner — it made the failure modes
 * impossible to reason about. If a future change wants jitter, an episode
 * budget or a storm classifier here, that finding is the answer.
 *
 * Pure and stateless on purpose: the attempt counter lives in the caller
 * ([SessionViewModel]), so "how long do we wait" can be unit-tested without a
 * transport, a clock or a coroutine, and resetting the ladder (a user tapping
 * Retry, or the app coming back to the foreground) is a caller-side `attempt =
 * 0` rather than a state transition in here.
 */
class ReconnectController(
    private val ladderMs: List<Long> = listOf(0L, 1_000L, 2_000L, 5_000L, 10_000L),
) {

    /** What to do about attempt number `n`. */
    sealed interface Decision {

        /** Wait [delayMs], then make attempt [attempt]. */
        data class RetryAfter(val delayMs: Long, val attempt: Int) : Decision

        /** The ladder is exhausted: stop, and leave the user a manual retry. */
        data object GiveUp : Decision
    }

    /**
     * The decision for the [attempt]-th reconnect of one episode (0-based).
     *
     * The first rung is 0 ms because the overwhelmingly common drop is a blip
     * the very next dial rides through; making the user watch a countdown for
     * that one would be latency this class invented.
     */
    fun decide(attempt: Int): Decision =
        if (attempt < ladderMs.size) Decision.RetryAfter(ladderMs[attempt], attempt)
        else Decision.GiveUp
}
