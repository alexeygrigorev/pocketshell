package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.tmux.TmuxRead
import kotlinx.coroutines.CancellationException

/**
 * Issue #2378: the host's tmux sessions do NOT all live on one socket, so the
 * session-create path may not reason about them as if they did.
 *
 * ## The state this exists for
 *
 * tmuxctl (the host-side session manager PocketShell creates through since
 * #726) runs **one tmux server per session**, each on its own dedicated socket
 * `$TMUX_TMPDIR/tmux-<uid>/tmuxctl-<session>`; only legacy/foreign sessions sit
 * on the literal `default` socket. A bare `tmux has-session` / `tmux
 * list-sessions` — no `-S` — therefore answers about ONE socket out of many,
 * and reports a name as free while a live session of exactly that name is
 * sitting on its own server two socket files away.
 *
 * That is what the maintainer hit: creating `git-pocketshell` while a live
 * tmuxctl-managed `git-pocketshell` already existed on
 * `/tmp/tmux-1000/tmuxctl-git-pocketshell`. The default-socket probe said
 * "free", nothing disambiguated the name, `tmuxctl create-detached` no-oped
 * (it IS cross-socket aware, so it saw the existing session), and the launch's
 * `tmux send-keys` — default socket again — failed with
 * `no server running on /tmp/tmux-1000/default` even though a server plainly
 * existed. One wrong assumption, two user-visible symptoms.
 *
 * ## What this object provides
 *
 * Two small POSIX-sh probes plus their parsers, so the gateway can ask the host
 * about **every** socket in one exec each:
 *
 *  - [liveSessionNamesCommand] — every live session name on every socket, so
 *    the `-2`/`-3` disambiguation walk ([nextFreeSessionName]) sees tmuxctl's
 *    per-session servers, not just the default one.
 *  - [sessionSocketCommand] — WHICH socket currently holds a given session, so
 *    a post-create `send-keys` targets the server the session is actually on.
 *
 * Both are fail-safe by construction: a host whose shell cannot run them
 * answers [SessionSocket.Unknown] / an empty name set, and the caller degrades
 * to exactly the pre-#2378 default-socket behaviour rather than blocking a
 * create.
 *
 * Aplexer-managed sessions are not tmux servers at all, so they are invisible
 * to both probes; the gateway unions the `pocketshell sessions list`
 * enumerator (tmuxctl + aplexer) into the taken-name set for that half of the
 * class.
 */
internal object TmuxSocketSweep {

    /**
     * The ceiling on the `-2`/`-3`… walk in [nextFreeSessionName]. A directory
     * with 200 live sessions is not a real state; the bound exists only so a
     * pathological host cannot spin the walk forever. On hitting it the walk
     * returns the requested base and the create falls back to its normal
     * idempotent behaviour.
     */
    const val MAX_SESSION_NAME_SUFFIX: Int = 200

    /**
     * Printed by [sessionSocketCommand] when the sweep ran to completion and no
     * socket holds the session. Distinguishing "swept, genuinely nowhere" from
     * "could not sweep" is what lets the launch report an accurate reason
     * instead of the misleading default-socket `no server running`.
     */
    const val NO_SOCKET_SENTINEL: String = "-"

    /**
     * Printed by [sessionSocketCommand] when the session is on the client's
     * DEFAULT socket, i.e. a bare `tmux` already targets it. Emitted as a token
     * rather than a path so the caller never has to assume the exec
     * environment's `TMUX_TMPDIR` resolves the same way tmux's own default does.
     */
    const val DEFAULT_SOCKET_TOKEN: String = "default"

    /**
     * The directory tmux keeps its server sockets in, as a shell expression:
     * `$TMUX_TMPDIR` when set (tmux's own override), else `/tmp`, then
     * `tmux-<uid>`. Same rule tmux itself and tmuxctl's `socket_for()` apply,
     * so the glob below sees exactly the sockets they create.
     */
    private const val SOCKET_DIR_EXPR: String = "\"\${TMUX_TMPDIR:-/tmp}/tmux-\$(id -u)\""

    /**
     * Every live session name on every tmux socket, one per line.
     *
     * The glob covers `default` AND every `tmuxctl-<session>` per-session
     * socket. A stale socket file whose server is gone just prints its error to
     * `/dev/null` and contributes nothing. The trailing bare `list-sessions` is
     * belt-and-braces: on a host where the glob resolves to nothing (a
     * `TMUX_TMPDIR` the exec environment cannot expand, a first-ever server),
     * the command still returns at least the default socket's names, i.e. never
     * less than the pre-#2378 answer.
     *
     * `exit 0` is deliberate: an empty listing is a valid answer ("no sessions
     * anywhere"), so the caller distinguishes "ran, nothing taken" from "could
     * not run" by the exit code rather than by the empty stdout.
     */
    fun liveSessionNamesCommand(): String =
        "for __ps_sock in $SOCKET_DIR_EXPR/*; do " +
            "[ -S \"\$__ps_sock\" ] || continue; " +
            "${TmuxRead.CLIENT} -S \"\$__ps_sock\" list-sessions -F '#{session_name}' 2>/dev/null; " +
            "done; " +
            "${TmuxRead.CLIENT} list-sessions -F '#{session_name}' 2>/dev/null; " +
            "exit 0"

    /**
     * Which socket currently holds [quotedName] (already shell-quoted by the
     * caller). Prints one line: the socket path, [DEFAULT_SOCKET_TOKEN], or
     * [NO_SOCKET_SENTINEL].
     *
     * Dedicated sockets are probed FIRST and the default one last, mirroring
     * tmuxctl's own `locate_session()` precedence: when a name exists on both
     * (the exact orphan state #2378 reports), the session the host's manager
     * considers authoritative is the one on its dedicated server, and that is
     * the one a launch must be typed into.
     *
     * `-t "=<name>"` is tmux's EXACT session match. Without the `=`, tmux falls
     * back to prefix then fnmatch matching, so probing `foo` while `foo-2`
     * exists would answer "taken" and point the launch at the neighbour (#1820).
     */
    fun sessionSocketCommand(quotedName: String): String =
        "__ps_want=$quotedName; " +
            "for __ps_sock in $SOCKET_DIR_EXPR/*; do " +
            "case \"\$__ps_sock\" in */$DEFAULT_SOCKET_TOKEN) continue ;; esac; " +
            "[ -S \"\$__ps_sock\" ] || continue; " +
            "if tmux -S \"\$__ps_sock\" has-session -t \"=\$__ps_want\" 2>/dev/null; then " +
            "printf '%s\\n' \"\$__ps_sock\"; exit 0; fi; " +
            "done; " +
            "if tmux has-session -t \"=\$__ps_want\" 2>/dev/null; then " +
            "printf '%s\\n' '$DEFAULT_SOCKET_TOKEN'; exit 0; fi; " +
            "printf '%s\\n' '$NO_SOCKET_SENTINEL'; exit 0"

    /**
     * Run [liveSessionNamesCommand] over [exec] and parse it. Best-effort: any
     * transport/shell failure answers an empty set, which the caller reads as
     * "nothing known to be taken" and degrades to the requested base name — a
     * create is never blocked by this probe.
     *
     * [exec] must apply the caller's PATH wrapping and bounded-read policy.
     */
    suspend fun liveSessionNames(exec: suspend (String) -> ExecResult): Set<String> {
        val sweep = try {
            exec(liveSessionNamesCommand())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return emptySet()
        }
        return parseLiveSessionNames(sweep.stdout, sweep.exitCode)
    }

    /**
     * Run [sessionSocketCommand] for [quotedName] (already shell-quoted) over
     * [exec] and parse it. Never throws except on cancellation: an unusable
     * host answers [SessionSocket.Unknown], never [SessionSocket.Absent], so a
     * failed sweep can not be mistaken for "the session is gone".
     */
    suspend fun locateSession(
        exec: suspend (String) -> ExecResult,
        quotedName: String,
    ): SessionSocket {
        val probe = try {
            exec(sessionSocketCommand(quotedName))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return SessionSocket.Unknown
        }
        return parseSessionSocket(probe.stdout, probe.exitCode)
    }

    /**
     * True when [output] is a tmux client saying it found no server — either
     * the plain `no server running on <socket>` or the connect-error form a
     * stale socket file produces.
     */
    fun isServerAbsentOutput(output: String): Boolean =
        output.contains("no server running", ignoreCase = true) ||
            (
                output.contains("error connecting to", ignoreCase = true) &&
                    output.contains("tmux-", ignoreCase = true) &&
                    output.contains("No such file or directory", ignoreCase = true)
                )

    /**
     * The launch-failure reason for a session the sweep proved is on NO tmux
     * server on this host.
     *
     * tmux's own words for that state — `no server running on
     * /tmp/tmux-1000/default` — are the misleading half of the #2378 report:
     * they name one socket out of many and read as "this host has no tmux"
     * while several servers are running. The host's raw reason is appended only
     * when it says something else; a server-absent message is dropped rather
     * than repeated, since it is precisely the sentence that misled.
     */
    fun launchTargetMissingDetail(sessionName: String, hostReason: String): String {
        val detail = "the session “$sessionName” isn't on any tmux server on this host " +
            "(every tmux socket was checked), so there was nowhere to type the launch"
        return if (hostReason.isBlank() || isServerAbsentOutput(hostReason)) {
            detail
        } else {
            "$detail; the host said: $hostReason"
        }
    }

    /** Parse [liveSessionNamesCommand]'s output into the taken-name set. */
    fun parseLiveSessionNames(stdout: String, exitCode: Int): Set<String> {
        if (exitCode != 0) return emptySet()
        return stdout.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Parse [sessionSocketCommand]'s answer. Anything unrecognised — a shell
     * that could not run the sweep, a login-shell banner, a non-zero exit — is
     * [SessionSocket.Unknown], which the caller treats as "fall back to the
     * pre-#2378 default-socket behaviour", never as "the session is missing".
     */
    fun parseSessionSocket(stdout: String, exitCode: Int): SessionSocket {
        if (exitCode != 0) return SessionSocket.Unknown
        val answer = stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: return SessionSocket.Unknown
        return when {
            answer == NO_SOCKET_SENTINEL -> SessionSocket.Absent
            answer == DEFAULT_SOCKET_TOKEN -> SessionSocket.Located(socket = null)
            answer.startsWith("/") -> SessionSocket.Located(socket = answer)
            else -> SessionSocket.Unknown
        }
    }

    /**
     * The smallest free name in the `<base>`, `<base>-2`, `<base>-3`… sequence
     * given the [taken] set — the #1820 disambiguation convention, now decided
     * against every socket's names rather than one socket's.
     */
    fun nextFreeSessionName(base: String, taken: Set<String>): String {
        if (base !in taken) return base
        for (suffix in 2..MAX_SESSION_NAME_SUFFIX) {
            val candidate = "$base-$suffix"
            if (candidate !in taken) return candidate
        }
        return base
    }
}

/**
 * Issue #1820: ask the HOST for a free session name, on the very session that
 * is about to create it — never from a client-side list, which is seconds stale
 * and sometimes simply WRONG.
 *
 * Issue #2378 (hard cut, D22): the "is this name taken?" question now unions
 * EVERY session source the host has. The old host-side walk asked a bare
 * `tmux has-session` — the DEFAULT socket only — and so called
 * `git-pocketshell` free while the maintainer's live `git-pocketshell` sat on
 * its own tmuxctl server, skipping the `-2` disambiguation entirely. The two
 * sources are [TmuxSocketSweep.liveSessionNames] (every socket's tmux
 * sessions) and [enumeratedNames], the `pocketshell sessions list` enumerator,
 * which also carries APLEXER rows — not tmux servers at all, so invisible to
 * the socket sweep.
 *
 * The `<base>`/`<base>-2`/`<base>-3`… walk itself is
 * [TmuxSocketSweep.nextFreeSessionName]; deciding it client-side rather than in
 * a remote shell loop costs no extra round trip (one exec either way) and makes
 * the union directly testable.
 *
 * Fail-safe by design: when both halves fail the taken set is empty and the
 * requested base comes back — the pre-#1820 behaviour. A create must never be
 * BLOCKED by the uniqueness probe itself; for a LAUNCH the #976 collision guard
 * still refuses rather than mistyping into a live pane.
 */
internal suspend fun resolveFreeSessionNameOnHost(
    requestedName: String,
    exec: suspend (String) -> ExecResult,
    enumeratedNames: suspend () -> Set<String>,
): String {
    val taken = TmuxSocketSweep.liveSessionNames(exec) + enumeratedNames()
    if (taken.isEmpty()) return requestedName
    return TmuxSocketSweep.nextFreeSessionName(requestedName, taken)
}

/**
 * Issue #2378: where a tmux session lives, as far as the host can tell.
 *
 * The three states are deliberately distinct — collapsing [Absent] and
 * [Unknown] is the bug this type exists to prevent, because "the sweep found
 * nothing" and "the sweep could not run" call for opposite behaviour (report an
 * accurate failure vs. degrade to the legacy default-socket path).
 */
internal sealed interface SessionSocket {

    /**
     * The session is on this server. [socket] is the explicit socket path, or
     * `null` when it is the client's default socket (a bare `tmux` reaches it).
     */
    data class Located(val socket: String?) : SessionSocket {
        /** `tmux` client prefix that targets exactly this server. */
        val tmuxClient: String
            get() = socket?.let { "tmux -S '${it.replace("'", "'\"'\"'")}'" } ?: "tmux"
    }

    /** The sweep completed and NO socket on the host holds the session. */
    data object Absent : SessionSocket

    /** The sweep could not run/parse; the caller must not conclude anything. */
    data object Unknown : SessionSocket
}
