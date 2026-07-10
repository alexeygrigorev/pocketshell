package com.pocketshell.app.tmux

internal object TmuxSessionFailureClassifiers {
    fun isStaleChannelSymptom(cause: Throwable?): Boolean =
        isChannelOpenFailure(cause) ||
            isTmuxCommandTimeout(cause) ||
            isTmuxEofWriteFailure(cause) ||
            isTransportDisconnected(cause) ||
            isControlChannelClosed(cause) ||
            isTransportClosed(cause)

    /**
     * Issue #1328 (S5, #1321 §1b): true when [cause] is the "transport is closed"
     * shape a beyond-grace reconnect PREFLIGHT (`tmux has-session`) hits when the
     * reused warm lease's SSH transport was silently torn down while backgrounded.
     * It is TRANSIENT — the fix (evict the poisoned lease + dial a FRESH transport)
     * heals the same session, so it must NOT hard-`Failed`/"Tap Reconnect" on the
     * first preflight (the beyond-grace break the maintainer hit). Matched on
     * message text (walking the cause chain) so the app module needs no sshj dep.
     */
    private fun isTransportClosed(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                message.contains("transport is closed", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #685 (Bug B): true when [cause] is the "control channel closed"
     * variant seen on a beyond-grace foreground reattach. The pooled SSH
     * transport died while backgrounded, so the reattach's `list-panes`
     * round-trip races the now-dead `-CC` control channel and the reader reports
     * `control channel closed before response` / `control channel closed
     * mid-command` (see [com.pocketshell.core.tmux.TmuxClient]). The older
     * stale-channel matchers ("open failed" / EOF-write / command-timeout /
     * transport-disconnect) did not cover this shape.
     */
    private fun isControlChannelClosed(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                message.contains("control channel closed", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #665 / #636: true when [cause] is the transport-DEAD variant of the
     * stale-lease symptom. The pooled SSH transport silently died (sshj's
     * `isConnected` lies until its keepalive trips), so the attach's `tmux -CC`
     * spawn fails not with an "open failed" channel error but with a
     * `net.schmizz.sshj.transport.TransportException` carrying disconnect reason
     * `BY_APPLICATION` ("Disconnected"). On the attach/switch path that surfaces
     * as `TmuxClientException("failed to spawn tmux -CC: Disconnected", <that
     * TransportException>)` (see [com.pocketshell.core.tmux.TmuxClient]).
     *
     * Scoped tightly to the attach/spawn failure path so a deliberate
     * user-initiated disconnect is not auto-recovered: a `TransportException`
     * alone is not enough; it must be wrapped by the `tmux -CC` spawn failure
     * matched on the "failed to spawn tmux -CC" message, or be a sshj
     * `TransportException` whose disconnect reason is `BY_APPLICATION`, which is
     * the dead-transport-during-attach shape.
     *
     * Matched on class simple name + message text so the app module need not
     * import the sshj transport hierarchy, walking the cause chain so a deeper
     * wrap still matches.
     */
    private fun isTransportDisconnected(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        var sawSpawnFailure = false
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                message.contains("failed to spawn tmux -CC", ignoreCase = true)
            ) {
                sawSpawnFailure = true
            }
            if (isTransportDisconnectException(current)) {
                return true
            }
            // `failed to spawn tmux -CC: Disconnected` carries the disconnect
            // reason inline in the spawn-failure message even when the wrapped
            // TransportException's own message is bare; treat that as the
            // dead-transport attach symptom too.
            if (sawSpawnFailure &&
                message != null &&
                message.contains("Disconnected", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * True when [throwable] is a sshj `TransportException`-family error reporting
     * a `BY_APPLICATION` / "Disconnected" teardown: the dead-transport shape an
     * attach hits when the pooled lease silently expired. Matched without
     * importing the sshj type: the class simple name plus the reason/message
     * text the exception carries.
     */
    private fun isTransportDisconnectException(throwable: Throwable): Boolean {
        if (throwable.javaClass.simpleName != "TransportException") return false
        // sshj's TransportException exposes a `getDisconnectReason()`; its name /
        // the exception message is `BY_APPLICATION` / "Disconnected" for the
        // application-initiated teardown that an attach over a silently-dead
        // lease surfaces. Read it reflectively to avoid an sshj compile-time dep.
        val reasonName = runCatching {
            throwable.javaClass.getMethod("getDisconnectReason").invoke(throwable)?.toString()
        }.getOrNull()
        if (reasonName != null && reasonName.contains("BY_APPLICATION", ignoreCase = true)) {
            return true
        }
        val message = throwable.message
        return message != null &&
            (
                message.contains("BY_APPLICATION", ignoreCase = true) ||
                    message.contains("Disconnected", ignoreCase = true)
                )
    }

    private fun isTmuxEofWriteFailure(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (
                message != null &&
                message.contains("failed to write tmux command", ignoreCase = true) &&
                (
                    message.contains("EOF", ignoreCase = true) ||
                        message.contains("closed", ignoreCase = true) ||
                        message.contains("broken pipe", ignoreCase = true)
                    )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isTmuxCommandTimeout(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (
                message != null &&
                message.contains("tmux", ignoreCase = true) &&
                message.contains("command", ignoreCase = true) &&
                message.contains("timed out", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #465: true when [cause] is a channel/shell "open failed" against an
     * otherwise-live SSH transport. This is the case where the pooled connection
     * must be evicted, not merely released back to the pool, so the next
     * reconnect opens a fresh transport. A transport stuck refusing new channels
     * never self-heals on its own because it still reports `isConnected`, so the
     * lease pool would keep handing it back.
     *
     * Matched on message text rather than an exception type because the failure
     * surfaces as a [com.pocketshell.core.tmux.TmuxClientException] wrapping the
     * sshj `ConnectionException` whose message is the bare "open failed" string.
     * We walk the cause chain so a deeper wrap still matches.
     */
    private fun isChannelOpenFailure(cause: Throwable?): Boolean {
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
}
