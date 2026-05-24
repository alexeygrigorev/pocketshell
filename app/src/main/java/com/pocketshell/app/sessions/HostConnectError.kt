package com.pocketshell.app.sessions

import com.pocketshell.core.ssh.SshException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * User-facing breakdown of a failed SSH connect attempt.
 *
 * Issue #109: the connect-failure sheet previously rendered the raw
 * `SshException` message, which exposes implementation detail and reads
 * as a stack trace ("connect failed: SSH connect to user@host:22 failed:
 * ConnectException: failed to connect to /127.0.0.1 (port 22) from ...
 * after 30000ms: isConnected failed: ECONNREFUSED (Connection refused)").
 * The sheet shows [HostConnectErrorSummary.title] as the body line plus
 * the host coordinates from the caller, and tucks [details] behind a
 * "Show details" disclosure so a bug report still includes the
 * underlying exception text.
 */
data class HostConnectErrorSummary(
    val reason: HostConnectErrorReason,
    /**
     * Short user-facing sentence ending with a period — e.g. "Connection
     * refused.". Designed to be appended to "Couldn't reach
     * <user>@<host>:<port>." so the sheet reads as one thought.
     */
    val shortReason: String,
    /**
     * Full underlying exception text including the deepest cause message.
     * Surfaced only behind the "Show details" disclosure. Never blank —
     * falls back to the exception class name when no message is present.
     */
    val details: String,
)

/**
 * The narrow set of failure shapes the UI distinguishes by short reason.
 * Anything else falls into [Unknown], which still shows the short
 * "Unable to connect." text plus the full [HostConnectErrorSummary.details]
 * disclosure so bug reports keep the original exception.
 */
enum class HostConnectErrorReason {
    /** TCP refused by remote — port closed, daemon down, firewall reset. */
    ConnectionRefused,

    /** DNS failure, including emulator-side DNS issues. */
    UnknownHost,

    /** TCP connect timed out (no response within `timeoutMs`). */
    TimedOut,

    /** SSH key was rejected by the remote (`UserAuthException`). */
    AuthFailed,

    /** Anything else — the sheet keeps the full details for a bug report. */
    Unknown,
}

/**
 * Compose the body line the sheet shows for a given coordinate set.
 *
 * Format: `Couldn't reach <user>@<host>:<port>. <shortReason>`.
 * Used by the connect-error sheet body so the user sees the WHO (which
 * host) and the WHY (short reason) in one line.
 */
fun formatHostConnectErrorBody(
    user: String,
    host: String,
    port: Int,
    summary: HostConnectErrorSummary,
): String = "Couldn't reach $user@$host:$port. ${summary.shortReason}"

/**
 * Walk the [throwable] cause chain and classify it into a
 * [HostConnectErrorSummary].
 *
 * The classifier prefers cause-chain class matches because the top-level
 * [SshException] wraps the real failure (sshj wraps everything into its
 * own hierarchy or `IOException`). When no specific match is found the
 * classifier checks for an `ECONNREFUSED` / `UnknownHostException`
 * substring so we still recognise the failure even if the underlying
 * exception is reported via a different type. Fallback is
 * [HostConnectErrorReason.Unknown] with the original message preserved
 * for the details disclosure.
 */
fun summarizeConnectError(throwable: Throwable): HostConnectErrorSummary {
    val chain = throwable.causeChain()
    val joinedMessages = chain.joinToString(separator = " | ") {
        it.message.orEmpty()
    }
    val reason = when {
        chain.any { it is UnknownHostException } ||
            joinedMessages.contains("UnknownHostException", ignoreCase = true) ->
            HostConnectErrorReason.UnknownHost

        chain.any { it is SocketTimeoutException } ||
            joinedMessages.contains("timed out", ignoreCase = true) ||
            joinedMessages.contains("timeout", ignoreCase = true) ->
            HostConnectErrorReason.TimedOut

        chain.any { it is ConnectException } ||
            joinedMessages.contains("ECONNREFUSED", ignoreCase = true) ||
            joinedMessages.contains("Connection refused", ignoreCase = true) ->
            HostConnectErrorReason.ConnectionRefused

        chain.any { isSshUserAuthFailure(it) } ->
            HostConnectErrorReason.AuthFailed

        else -> HostConnectErrorReason.Unknown
    }
    val shortReason = when (reason) {
        HostConnectErrorReason.ConnectionRefused -> "Connection refused."
        HostConnectErrorReason.UnknownHost -> "Host not found."
        HostConnectErrorReason.TimedOut -> "Connection timed out."
        HostConnectErrorReason.AuthFailed -> "SSH key rejected."
        HostConnectErrorReason.Unknown -> "Unable to connect."
    }
    return HostConnectErrorSummary(
        reason = reason,
        shortReason = shortReason,
        details = formatDetails(chain),
    )
}

/**
 * `core-ssh` flattens every sshj failure into [SshException], so
 * `UserAuthException` arrives wrapped. The check matches by class name so
 * we don't need to import the sshj type (it lives in `core-ssh`'s runtime
 * graph but the `app` module already depends on it transitively).
 */
private fun isSshUserAuthFailure(t: Throwable): Boolean {
    val name = t.javaClass.name
    return name == "net.schmizz.sshj.userauth.UserAuthException" ||
        name.endsWith(".UserAuthException")
}

private fun Throwable.causeChain(): List<Throwable> {
    val chain = mutableListOf<Throwable>()
    var cursor: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (cursor != null && seen.add(cursor)) {
        chain += cursor
        cursor = cursor.cause
    }
    return chain
}

private fun formatDetails(chain: List<Throwable>): String {
    if (chain.isEmpty()) return "unknown error"
    return chain.joinToString(separator = "\nCaused by: ") { t ->
        val message = t.message?.takeIf { it.isNotBlank() }
        if (message != null) "${t.javaClass.name}: $message" else t.javaClass.name
    }
}

/**
 * Convenience overload that pre-folds [SshException] wrapping. Useful
 * when callers already discarded the wrapper.
 */
@Suppress("unused")
fun summarizeConnectError(error: SshException): HostConnectErrorSummary =
    summarizeConnectError(error as Throwable)
