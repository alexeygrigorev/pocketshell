package com.pocketshell.core.tmux

import com.pocketshell.core.ssh.ExecResult

/**
 * Issue #2387: [TmuxClient.connect] must know WHICH tmux socket a session
 * actually lives on before it decides whether to attach or create — a bare
 * `tmux -CC new-session -A -s '<name>'` only ever reaches the DEFAULT socket
 * (`$TMUX_TMPDIR/tmux-<uid>/default`), while a tmuxctl-managed host runs one
 * dedicated tmux server PER session, on `tmuxctl-<name>`.
 *
 * Without this, `new-session -A` against a name that only exists on a
 * dedicated socket finds nothing on the default one and — because `-A` means
 * attach-OR-create — happily MINTS a brand-new, empty, same-named session on
 * the default socket instead of failing or reaching the real one. That is
 * the maintainer's reported orphan (a same-named `git-pocketshell` appearing
 * on the default socket while the real session lived on
 * `tmuxctl-git-pocketshell`).
 *
 * This is the `core-tmux` twin of the app-layer
 * `com.pocketshell.app.projects.TmuxSocketSweep` introduced by #2378 for the
 * session-CREATE path — `core-tmux` is a lower module and cannot depend on
 * `app`, so the same shell-probe shape is ported here rather than shared
 * directly. Both walk every socket in the `$TMUX_TMPDIR/tmux-<uid>` directory
 * (skipping `default`, tried last) with an EXACT `has-session -t '=<name>'`
 * per #1820.
 *
 * One remote round trip either way: the whole sweep — every dedicated
 * socket, then the default one — runs as a single POSIX-sh script over one
 * [com.pocketshell.core.ssh.SshSession.exec] call.
 */
internal object TmuxSessionSocketLocator {

    /** Stdout line prefix for "found it" — followed by the socket path or [DEFAULT_SOCKET_TOKEN]. */
    const val LOCATED_PREFIX: String = "LOCATED "

    /** Stdout line prefix for "checked every socket, nowhere" — followed by the default socket's raw error text. */
    const val ABSENT_PREFIX: String = "ABSENT"

    /** Printed instead of a path when the session is on the client's default socket. */
    const val DEFAULT_SOCKET_TOKEN: String = "default"

    private const val SOCKET_DIR_EXPR: String = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""

    /**
     * Build the sweep command for the EXACT session target [quotedTarget]
     * (already shell-quoted by the caller, e.g. `'=name'`).
     *
     * Dedicated sockets are probed first (mirroring tmuxctl's own
     * `locate_session()` precedence — see the app-layer sibling's KDoc), the
     * default socket last. The default probe's stderr is captured (not
     * discarded) so an [ABSENT] result still carries tmux's own wording,
     * which [isTmuxServerDeadStderr] classifies as dead-server vs
     * session-gone exactly as the pre-#2387 default-socket-only preflight
     * did.
     */
    fun locateCommand(quotedTarget: String): String =
        "for __ps_sock in $SOCKET_DIR_EXPR/*; do " +
            "case \"\$__ps_sock\" in */$DEFAULT_SOCKET_TOKEN) continue ;; esac; " +
            "[ -S \"\$__ps_sock\" ] || continue; " +
            "if tmux -S \"\$__ps_sock\" has-session -t $quotedTarget 2>/dev/null; then " +
            "printf '${LOCATED_PREFIX}%s\\n' \"\$__ps_sock\"; exit 0; " +
            "fi; " +
            "done; " +
            "__ps_err=\$(tmux has-session -t $quotedTarget 2>&1 >/dev/null); __ps_rc=\$?; " +
            "if [ \"\$__ps_rc\" -eq 0 ]; then " +
            "printf '${LOCATED_PREFIX}${DEFAULT_SOCKET_TOKEN}\\n'; exit 0; " +
            "fi; " +
            "printf '${ABSENT_PREFIX} %s\\n' \"\$__ps_err\"; exit \"\$__ps_rc\""

    /**
     * Parse [locateCommand]'s [ExecResult]. Anything that does not match the
     * known shapes is [TmuxSessionLocation.Unknown] — a foreign/old host
     * whose shell injected banner noise or otherwise could not run the
     * sweep — so the caller degrades rather than mis-classifies.
     */
    fun parse(result: ExecResult): TmuxSessionLocation {
        val firstLine = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }
            ?: return TmuxSessionLocation.Unknown
        return when {
            firstLine.startsWith(LOCATED_PREFIX) -> {
                val answer = firstLine.removePrefix(LOCATED_PREFIX).trim()
                when {
                    answer == DEFAULT_SOCKET_TOKEN -> TmuxSessionLocation.Located(socket = null)
                    answer.startsWith("/") -> TmuxSessionLocation.Located(socket = answer)
                    else -> TmuxSessionLocation.Unknown
                }
            }
            firstLine == ABSENT_PREFIX || firstLine.startsWith("$ABSENT_PREFIX ") -> {
                // Take everything from the first ABSENT marker onward (not just
                // its own line) so a multi-line default-socket error message is
                // preserved for [isTmuxServerDeadStderr].
                val fromMarker = result.stdout.substringAfter(ABSENT_PREFIX, missingDelimiterValue = "")
                TmuxSessionLocation.Absent(exitCode = result.exitCode, detail = fromMarker.trim())
            }
            else -> TmuxSessionLocation.Unknown
        }
    }
}

/**
 * Issue #2387: where a tmux session lives, as far as a fresh sweep of every
 * socket on the host can tell.
 *
 * The three states are deliberately distinct — see the app-layer sibling
 * `com.pocketshell.app.projects.SessionSocket`'s KDoc for why collapsing
 * [Absent] and [Unknown] would be wrong: "the sweep found nothing" and "the
 * sweep could not run" call for opposite behaviour.
 */
internal sealed interface TmuxSessionLocation {

    /**
     * The session is on this server. [socket] is the explicit socket path,
     * or `null` for the client's default socket (a bare `tmux` reaches it).
     */
    data class Located(val socket: String?) : TmuxSessionLocation {
        /** `tmux [-S '<socket>']` prefix that targets exactly this server. */
        val tmuxClientPrefix: String
            get() = socket?.let { "tmux -S '${it.replace("'", "'\"'\"'")}'" } ?: "tmux"
    }

    /**
     * The sweep completed and NO socket on the host holds the session.
     * [detail] is the default socket's raw `has-session` failure text (for
     * [isTmuxServerDeadStderr] classification); [exitCode] is that probe's
     * exit code.
     */
    data class Absent(val exitCode: Int, val detail: String) : TmuxSessionLocation

    /**
     * The sweep could not run/parse; the caller must not conclude anything.
     *
     * Issue #2387 review gap (round 2): "must not conclude anything" cuts
     * BOTH ways. [resolveTmuxAttachCommand] treats [Unknown] identically to
     * the sweep exec THROWING — for a reattach-required intent
     * (`!createIfMissing || probeServerLiveness`) it refuses to create
     * (the #666/#998 classification), and only the explicit "new session"
     * intent degrades to the pre-#2387 default-socket `new-session -A`. A
     * garbled/unparseable sweep on a reattach-required connect is NOT
     * license to silently mint a fresh session.
     */
    data object Unknown : TmuxSessionLocation
}
