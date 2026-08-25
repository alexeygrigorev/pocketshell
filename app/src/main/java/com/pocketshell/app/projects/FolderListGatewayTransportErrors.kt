package com.pocketshell.app.projects

/**
 * Issue #680: the family of transient probe failures that must HEAL +
 * RETRY on a fresh lease rather than surface a persistent "not connected"
 * error. Mirrors
 * [com.pocketshell.app.tmux.TmuxSessionViewModel.isStaleChannelSymptom].
 *
 *  - [isChannelOpenFailure]: live transport refuses the exec channel.
 *  - [isTransportDisconnected]: sshj `TransportException` / `BY_APPLICATION`
 *    teardown of a silently-dead pooled transport.
 *  - [isSessionNotConnected]: `ensureConnected()` threw because the pooled
 *    lease's `isConnected` flipped false between acquire and exec — the
 *    exact false-negative #680 surfaced.
 */
internal fun isStaleChannelSymptom(cause: Throwable?): Boolean =
    isChannelOpenFailure(cause) ||
        isTransportDisconnected(cause) ||
        isSessionNotConnected(cause) ||
        isTransportEofDrop(cause) ||
        isWedgedReadTimeout(cause)

/**
 * Issue #1641: a bounded-exec timeout is a stale-channel symptom, so the
 * heal path — `evictIdle` + retry once on a fresh lease — owns the recovery
 * that the deleted `close()` used to do unsafely.
 *
 * This is the LOAD-BEARING negative half of #1641. Removing the close
 * without this would over-guard: a genuinely dead transport would never be
 * discarded, every poll would re-grab the corpse, and the tree would stop
 * recovering at all — strictly worse than the storm. Routing it through
 * `evictIdle` instead of `close()` keeps recovery while making it
 * refcount-aware: a transport an ACTIVE session VM still holds is left
 * alone (#758) and healed by that VM's own stale-lease path, while an idle
 * corpse no consumer holds is still discarded so the next poll re-dials.
 *
 * Mirrors [com.pocketshell.app.sessions.LeaseSessionExec.isStaleChannelSymptom],
 * which already treats its wedged-read/block timeouts this way.
 */
internal fun isWedgedReadTimeout(cause: Throwable?): Boolean {
    var current: Throwable? = cause
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is FolderListExecTimeoutException) return true
        current = current.cause
    }
    return false
}

/**
 * Issue #711: true when [cause] is the transient transport-EOF family that
 * the dogfood report surfaced as a scary raw-command band — a pooled SSH
 * transport that died MID-EXEC, so sshj reports `Broken transport;
 * encountered EOF` (or a bare `encountered EOF`, a `broken pipe`, or a
 * `Failed to open exec channel for <command>` that wraps that EOF). This is
 * a TRANSIENT drop (the tree self-recovered on the next refresh), so it must
 * heal + retry on a fresh lease like every other [isStaleChannelSymptom],
 * NOT escape as a persistent error carrying the raw enumeration command.
 *
 * Matched on message text (walking the cause chain) so the app module need
 * not depend on the core/sshj exception hierarchy.
 */
internal fun isTransportEofDrop(cause: Throwable?): Boolean {
    var current: Throwable? = cause
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        val message = current.message
        if (message != null &&
            (
                message.contains("encountered EOF", ignoreCase = true) ||
                    message.contains("Broken transport", ignoreCase = true) ||
                    message.contains("broken pipe", ignoreCase = true) ||
                    message.contains("Failed to open exec channel", ignoreCase = true) ||
                    message.contains("channel closed", ignoreCase = true) ||
                    message.contains("control channel closed", ignoreCase = true)
                )
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Issue #680: true when [cause] is the "SSH session is not connected" probe
 * failure — `RealSshSession.ensureConnected()` throwing because the pooled
 * lease's `isConnected` (sshj `client.isConnected && isAuthenticated`)
 * flipped false between the lease acquire and the exec. This is the
 * transient stale-channel symptom the folder refresh surfaced as a scary
 * persistent banner; it must heal + retry on a fresh lease, not surface a
 * false "not connected" error while the host is actually connectable.
 *
 * Matched on message text (walking the cause chain) so the app module need
 * not depend on the core SSH exception hierarchy. Also covers the lower-
 * level "transport endpoint is not connected" socket text.
 */
internal fun isSessionNotConnected(cause: Throwable?): Boolean {
    var current: Throwable? = cause
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        val message = current.message
        if (message != null &&
            (
                message.contains("SSH session is not connected", ignoreCase = true) ||
                    message.contains("transport endpoint is not connected", ignoreCase = true)
                )
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Issue #465: true when [cause] is a channel/shell "open failed" against an
 * otherwise-live SSH transport — the case where the pooled connection must
 * be evicted so the next probe opens a fresh transport instead of reusing
 * the half-dead one forever.
 */
internal fun isChannelOpenFailure(cause: Throwable?): Boolean {
    var current: Throwable? = cause
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        val message = current.message
        if (message != null &&
            (
                message.contains("open failed", ignoreCase = true) ||
                    message.contains("failed to open SSH shell", ignoreCase = true)
                )
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Issue #665 / #636: true when [cause] is the transport-DEAD variant — the
 * pooled SSH transport silently died, so the folder-tree probe's exec fails
 * not with an "open failed" channel error but with a sshj
 * `net.schmizz.sshj.transport.TransportException` carrying disconnect reason
 * `BY_APPLICATION` ("Disconnected"). Same gap as
 * [com.pocketshell.app.tmux.TmuxSessionViewModel.isStaleChannelSymptom]:
 * without this the dead lease is released back (not evicted), the next poll
 * re-grabs the corpse, and the host-detail "open failed" dead-end never
 * recovers. Evicting it makes the next poll / Retry open a fresh transport.
 *
 * Matched on class simple name + reason/message text (no sshj compile-time
 * dep), walking the cause chain.
 */
internal fun isTransportDisconnected(cause: Throwable?): Boolean {
    var current: Throwable? = cause
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        if (current.javaClass.simpleName == "TransportException") {
            val reasonName = runCatching {
                current!!.javaClass.getMethod("getDisconnectReason").invoke(current)?.toString()
            }.getOrNull()
            if (reasonName != null && reasonName.contains("BY_APPLICATION", ignoreCase = true)) {
                return true
            }
            val message = current.message
            if (message != null &&
                (
                    message.contains("BY_APPLICATION", ignoreCase = true) ||
                        message.contains("Disconnected", ignoreCase = true)
                    )
            ) {
                return true
            }
        }
        current = current.cause
    }
    return false
}
