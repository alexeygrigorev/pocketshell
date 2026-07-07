package com.pocketshell.core.connection

/**
 * Slice S3 (epic #1331, issue #1326): the TYPED reason a session connect/attach
 * ended in the honest failure surface. This is the single failure payload the
 * view state ([SessionSurfaceState.Failed]) carries — it replaces the raw
 * `"error: ${class}: ${message}"` string the #1321 screenshot leaked. The screen
 * maps a [FailureReason] to ONE calm, curated sentence at render time; the raw
 * exception never reaches the UI (it stays in the diagnostic logs).
 *
 * Producing the reason is [classifyFailure]'s job — the SINGLE `Throwable →
 * FailureReason` classifier that supersedes the VM's `nonRetryableReason` /
 * `isNonRetryableConnectFailure` string table. Keeping the reason a closed type
 * (not a free string) is what makes the pill / surface / band derive one coherent
 * failure screen instead of three independently-worded ones.
 */
sealed interface FailureReason {
    /** sshj `UserAuthException` — the key/credentials were rejected. */
    data object AuthFailed : FailureReason

    /** `java.net.UnknownHostException` — DNS could not resolve the host. */
    data object HostUnresolved : FailureReason

    /** The tmux server died — all sessions ended (`TmuxServerDeadException`). */
    data object ServerRestarted : FailureReason

    /** The target tmux session no longer exists on the host (killed elsewhere). */
    data object SessionEnded : FailureReason

    /** The configured private key file was not found. */
    data object KeyMissing : FailureReason

    /**
     * A generic "could not reach / lost the connection" failure. [retryable] is
     * true for a transient transport drop (a closed-transport / preflight failure,
     * a socket tear-down) that another dial can plausibly fix; false when the
     * auto-reconnect ladder has genuinely EXHAUSTED and only a manual retry remains.
     */
    data class Unreachable(val retryable: Boolean) : FailureReason
}

/**
 * Whether AUTO-retry can plausibly recover from this reason. Only a transient
 * [FailureReason.Unreachable] with `retryable == true` is auto-retryable; every
 * config-level reason (auth, host, key) and the exhausted/dead cases are not (the
 * user must act — fix the key, recreate the session, tap Reconnect). This mirrors
 * the old `isNonRetryableConnectFailure` boolean, now derived from the typed reason.
 */
val FailureReason.retryable: Boolean
    get() = when (this) {
        is FailureReason.Unreachable -> retryable
        FailureReason.AuthFailed,
        FailureReason.HostUnresolved,
        FailureReason.ServerRestarted,
        FailureReason.SessionEnded,
        FailureReason.KeyMissing,
        -> false
    }

/**
 * Simple class names of connect-failure causes that retrying cannot fix. Ported
 * verbatim from `TmuxSessionViewModel.NON_RETRYABLE_FAILURE_CLASS_NAMES` (#440,
 * #998) — matched against the cause chain by [classifyFailure]. Matched on the
 * simple name so this module need not depend on sshj / core-tmux exception types.
 */
private val NON_RETRYABLE_FAILURE_CLASS_NAMES: Set<String> = setOf(
    "UserAuthException",
    "UnknownHostException",
    "TmuxServerDeadException",
)

/** Substring identifying the "private key file not found" IOException (#440). */
private const val MISSING_KEY_MESSAGE_FRAGMENT: String = "Private key file not found"

/**
 * Substrings identifying a "the target tmux session is gone" failure surfaced as
 * a generic exception message (the host still answers, but `has-session` reports
 * the session no longer exists). Kept narrow so a transient socket tear-down is
 * NOT misread as a permanent SessionEnded.
 */
private val SESSION_ENDED_MESSAGE_FRAGMENTS: List<String> = listOf(
    "no such session",
    "session not found",
    "can't find session",
    "session ended",
)

/**
 * The SINGLE `Throwable → FailureReason` classifier (issue #1326, S3). Walks the
 * cause chain (guarding against cycles) and maps the first recognised cause to a
 * typed reason:
 *
 *  - `UserAuthException` → [FailureReason.AuthFailed]
 *  - `UnknownHostException` → [FailureReason.HostUnresolved]
 *  - `TmuxServerDeadException` → [FailureReason.ServerRestarted]
 *  - a "private key file not found" message → [FailureReason.KeyMissing]
 *  - a "session gone" message → [FailureReason.SessionEnded]
 *  - any other non-retryable class → [FailureReason.Unreachable]`(retryable = false)`
 *  - anything else (incl. a null cause, and the closed-transport / has-session
 *    preflight case the #1321 screenshot leaked) → [FailureReason.Unreachable]`(retryable = true)`
 *
 * The default is the CALM, retryable case — a closed-transport drop is exactly the
 * recoverable "Tap Reconnect" state, never a scary raw exception.
 */
fun classifyFailure(cause: Throwable?): FailureReason {
    var current: Throwable? = cause
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        when (current.javaClass.simpleName) {
            "UserAuthException" -> return FailureReason.AuthFailed
            "UnknownHostException" -> return FailureReason.HostUnresolved
            "TmuxServerDeadException" -> return FailureReason.ServerRestarted
        }
        val message = current.message
        if (message != null) {
            if (message.contains(MISSING_KEY_MESSAGE_FRAGMENT, ignoreCase = true)) {
                return FailureReason.KeyMissing
            }
            if (SESSION_ENDED_MESSAGE_FRAGMENTS.any { message.contains(it, ignoreCase = true) }) {
                return FailureReason.SessionEnded
            }
        }
        if (current.javaClass.simpleName in NON_RETRYABLE_FAILURE_CLASS_NAMES) {
            return FailureReason.Unreachable(retryable = false)
        }
        current = current.cause
    }
    return FailureReason.Unreachable(retryable = true)
}
