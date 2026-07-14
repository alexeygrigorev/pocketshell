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

/**
 * Issue #1575: identity equality for [ConnectionTarget] that EXCLUDES the secret
 * `passphrase`. Backs [ConnectionTarget.equals]/`hashCode` (the data class delegates
 * here so the god-object VM file stays lean). The generated data-class equality
 * would compare `passphrase: CharArray?` by REFERENCE, so two targets built for the
 * SAME passphrase-protected host+session (distinct CharArray instances, same chars)
 * never compared equal — defeating the `connect()` same-session no-op dedupe and
 * forcing a spurious reconnect on every genuine re-entry of a passphrase host. The
 * dedupe must not depend on the secret, and the secret bytes are never
 * content-compared or logged here. Every non-secret field still participates, so
 * the dedupe semantics are otherwise byte-identical to the old data-class equality.
 */
internal fun connectionTargetIdentityEquals(target: ConnectionTarget, other: Any?): Boolean {
    if (target === other) return true
    if (other !is ConnectionTarget) return false
    return target.hostId == other.hostId &&
        target.hostName == other.hostName &&
        target.host == other.host &&
        target.port == other.port &&
        target.user == other.user &&
        target.keyPath == other.keyPath &&
        target.sessionName == other.sessionName &&
        target.startDirectory == other.startDirectory &&
        target.tmuxSessionId == other.tmuxSessionId &&
        target.sessionCreated == other.sessionCreated
}

internal fun connectionTargetIdentityHashCode(target: ConnectionTarget): Int {
    var result = target.hostId.hashCode()
    result = 31 * result + target.hostName.hashCode()
    result = 31 * result + target.host.hashCode()
    result = 31 * result + target.port
    result = 31 * result + target.user.hashCode()
    result = 31 * result + target.keyPath.hashCode()
    result = 31 * result + target.sessionName.hashCode()
    result = 31 * result + (target.startDirectory?.hashCode() ?: 0)
    result = 31 * result + (target.tmuxSessionId?.hashCode() ?: 0)
    result = 31 * result + (target.sessionCreated?.hashCode() ?: 0)
    return result
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
