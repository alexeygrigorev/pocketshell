package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionTarget
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.tmux.TmuxClient

/**
 * Issue #178: true when [previous] and [target] address the same SSH
 * endpoint (same host, port, user, key path). Same-host means the
 * underlying [com.pocketshell.core.ssh.SshSession] is reusable for an
 * in-channel tmux client swap. We deliberately exclude `passphrase` and
 * `sessionName` -- passphrase is only used for the initial key load (the live
 * session is already authenticated), and the session name is the THING we're
 * switching.
 */
internal fun isSameHost(previous: ConnectionTarget, target: ConnectionTarget): Boolean =
    previous.hostId == target.hostId &&
        previous.host == target.host &&
        previous.port == target.port &&
        previous.user == target.user &&
        previous.keyPath == target.keyPath

internal fun sameSessionIdentity(left: ConnectionTarget, right: ConnectionTarget): Boolean {
    if (!left.hasSameHostAndCredential(right)) return false
    val leftDurable = left.durableSessionKey()
    val rightDurable = right.durableSessionKey()
    if (leftDurable != null || rightDurable != null) {
        return leftDurable == rightDurable
    }
    return left.sessionName == right.sessionName &&
        left.startDirectory == right.startDirectory
}

internal fun ConnectionTarget.hasSameHostAndCredential(other: ConnectionTarget): Boolean =
    hostId == other.hostId &&
        host == other.host &&
        port == other.port &&
        user == other.user &&
        keyPath == other.keyPath

internal fun ConnectionTarget.durableSessionKey(): String? =
    durableTmuxSessionKey(hostId, tmuxSessionId, sessionCreated)

internal fun ConnectionTarget.toRuntimeKey(): TmuxRuntimeKey =
    TmuxRuntimeKey(
        hostId = hostId,
        hostname = host,
        port = port,
        username = user,
        keyPath = keyPath,
        sessionName = sessionName,
        durableSessionKey = durableSessionKey(),
    )

/**
 * Issue #1537 (option b): the parked-runtime health identity for this target.
 * Keyed on host + tmux session name so it aligns with the runtime cache's
 * per-session eviction grain (`removeSession`).
 */
internal fun ConnectionTarget.toHealthKey(): RuntimeHealthKey =
    RuntimeHealthKey(hostId = hostId, sessionName = sessionName)

internal fun TmuxRuntimeKey.toHealthKey(): RuntimeHealthKey =
    RuntimeHealthKey(hostId = hostId, sessionName = sessionName)

internal fun targetLogFields(target: ConnectionTarget): String = buildString {
    append("hostId=")
    append(target.hostId)
    append(" host=")
    append(target.host)
    append(" port=")
    append(target.port)
    append(" user=")
    append(target.user)
    append(" session=")
    append(target.sessionName)
    target.startDirectory?.let {
        append(" startDirectory=")
        append(it)
    }
}

/**
 * The [LeaseSessionTarget] for this connection, byte-identical to the lease
 * key the session screens use (so a borrow reuses the warm transport, not a
 * fresh handshake). Used by the #972 host connection-log mirror.
 */
internal fun ConnectionTarget.toLeaseSessionTarget(): LeaseSessionTarget =
    LeaseSessionTarget(
        hostId = hostId,
        hostname = host,
        port = port,
        username = user,
        keyPath = keyPath,
        passphrase = passphrase,
    )

internal fun ConnectionTarget.sessionCardsTargetKey(): String =
    sessionCardsTargetKey(
        hostId = hostId,
        host = host,
        port = port,
        user = user,
        keyPath = keyPath,
        sessionName = sessionName,
    )

// Issue #722: visibility widened from `private` to `internal` (no behavior
// change) so the characterization test seams currentRuntimeGuardForTest,
// reseedBlankVisiblePanesForTest, and armConnectedBlankWatchdogForTest can
// carry an opaque guard across the test boundary.
internal data class RuntimeRefreshGuard(
    val generation: Long,
    val target: ConnectionTarget,
    val client: TmuxClient,
)
