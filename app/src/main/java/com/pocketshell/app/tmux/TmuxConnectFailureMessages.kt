package com.pocketshell.app.tmux

import com.pocketshell.core.connection.FailureReason
import com.pocketshell.core.connection.classifyFailure
import com.pocketshell.core.connection.retryable

internal class TmuxAttachPanesReadyException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/**
 * Issue #440: true when [cause] describes a connect failure that will never
 * succeed by waiting and retrying.
 *
 * Issue #1326 (S3): keep this as a thin adapter over the shared
 * `:shared:core-connection` classifier so the VM has one source of truth for
 * retryability.
 */
internal fun isNonRetryableConnectFailure(cause: Throwable?): Boolean =
    !classifyFailure(cause).retryable

/**
 * Issue #440/#1326: a short, user-facing reason for a non-retryable connect
 * failure, used in the failed connection band that replaces the backoff loop.
 */
internal fun nonRetryableReason(cause: Throwable?): String =
    when (classifyFailure(cause)) {
        FailureReason.AuthFailed -> "authentication failed"
        FailureReason.HostUnresolved -> "host could not be resolved"
        FailureReason.ServerRestarted -> "the tmux server restarted — all sessions ended"
        FailureReason.SessionEnded -> "this session ended"
        FailureReason.KeyMissing -> "private key file not found"
        is FailureReason.Unreachable -> "connection cannot be retried"
    }

internal fun connectFailureMessage(t: Throwable, sessionName: String): String =
    if (t is TmuxAttachPanesReadyException) {
        val message = t.message ?: "Timed out waiting for tmux panes from $sessionName."
        if ("Tap Reconnect" in message) message else "$message Tap Reconnect to retry."
    } else {
        // Issue #1322: fold general connect failures into a calm user-facing prompt.
        // Raw exception detail remains available in diagnostic logs.
        "Connection lost. Tap Reconnect to retry."
    }
