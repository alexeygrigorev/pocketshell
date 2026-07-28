package com.pocketshell.core.tmux

// Issue #1820: the [TmuxClient] failure/teardown vocabulary — the exception
// types the connect preflight throws, the tmux "server is gone" classifiers,
// and the disconnect/overflow events. Split out of `TmuxClient.kt` (a pure
// move, same package, no call-site changes) so that file stays under the
// `check-file-size-hygiene.sh` 128 KiB threshold; it is also the D28-preferred
// shape — a cohesive sibling file rather than more growth on a god object.
/**
 * Thrown by [TmuxClient] for transport- and protocol-level failures
 * (SSH shell teardown, client close while waiting for a response, etc.).
 */
public class TmuxClientException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)

/**
 * Issue #666: thrown by [TmuxClient.connect] when an attach-only connect
 * (`createIfMissing == false`, e.g. a foreground cold-restore to the last
 * session) finds that the target tmux session no longer exists on the
 * server — it was killed elsewhere while the app was backgrounded.
 *
 * On this restore path the session must NOT be recreated. Callers catch
 * this to surface "that session ended" and drop the user to the host /
 * session list instead of silently resurrecting a session via
 * `new-session -A`.
 */
public class TmuxSessionNotFoundException(
    public val sessionName: String,
    message: String = "tmux session '$sessionName' no longer exists",
) : RuntimeException(message)

/**
 * Issue #998: thrown by [TmuxClient.connect] when a reattach to an
 * expected-existing session finds that the remote tmux *server* itself is
 * gone — the host rebooted, OOM-killed tmux, or someone ran `kill-server`.
 * The `tmux has-session` / `list-sessions` preflight reports
 * `no server running on <socket>` (exit ≠ 0) in this case.
 *
 * This is distinct from [TmuxSessionNotFoundException] (one session ended but
 * the server is still up): a dead server means EVERY session vanished. It must
 * NOT be resurrected via `new-session -A`, which on a dead server would boot a
 * brand-new empty server+session and silently strand the user in a blank
 * "Connected" shell with their work gone. Callers catch this to surface
 * "the tmux server restarted — all sessions ended" and drop the user to the
 * host/session list instead of the silent resurrection.
 */
public class TmuxServerDeadException(
    message: String = "tmux server is no longer running",
) : RuntimeException(message)

/**
 * Issue #998: the stderr signature tmux prints to both `has-session` and
 * `list-sessions` when the control socket has no server behind it (the host
 * rebooted / OOM / `kill-server`). Matched case-insensitively so a future tmux
 * wording tweak around the same phrase still classifies as server-death.
 */
internal const val TMUX_NO_SERVER_RUNNING_SIGNATURE: String = "no server running"

/**
 * Issue #998: returns true when [stderr] carries the tmux "server is gone"
 * signature. Centralised so the reattach preflight, the reader-loop classifier,
 * and the host session-list gateway all agree on what "server-death" means.
 */
internal fun isTmuxServerDeadStderr(stderr: String?): Boolean =
    stderr?.contains(TMUX_NO_SERVER_RUNNING_SIGNATURE, ignoreCase = true) == true

/**
 * Issue #998: true when a `%exit` control event's reason announces the SERVER
 * shutting down (`%exit server exited`), as opposed to a plain client `%exit`.
 * tmux emits the bare `%exit` for an ordinary client detach and
 * `%exit server exited` when the server itself is going away. We only treat the
 * latter as server-death.
 */
internal fun isTmuxServerExitReason(reason: String?): Boolean =
    reason?.contains("server exited", ignoreCase = true) == true

public data class TmuxOutputBacklogOverflow(
    val paneId: String,
    val droppedEvents: Int,
)

public data class TmuxDisconnectEvent(
    val reason: TmuxDisconnectReason,
    val source: String,
    val intent: String,
    val commandKind: String? = null,
    val timeoutMode: String? = null,
    val exceptionClass: String? = null,
    val message: String? = null,
)

public enum class TmuxDisconnectReason(public val logValue: String) {
    ExplicitClose("explicit_close"),
    ExplicitDetach("explicit_detach"),
    ReaderEof("reader_eof"),
    ReaderException("reader_exception"),
    CommandTimeout("command_timeout"),
    // Issue #998: the remote tmux SERVER announced shutdown in-band
    // (`%exit server exited`) before the channel EOFed — host reboot / OOM /
    // `kill-server`. The reconnect path treats this as server-death (drop to
    // the list) rather than a transport blip (silent `new-session -A`).
    ServerExited("server_exited"),
    Unknown("unknown"),
}
