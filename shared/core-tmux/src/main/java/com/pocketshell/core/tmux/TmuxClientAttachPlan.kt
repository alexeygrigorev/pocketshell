package com.pocketshell.core.tmux

import android.util.Log
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

// Issue #2387: [TmuxClient.connect]'s attach-plan resolution — split out of
// `TmuxClient.kt` (a pure move, same package, no call-site changes) so that
// file stays under the `check-file-size-hygiene.sh` 128 KiB threshold; also
// the D28-preferred shape — a cohesive sibling file rather than more growth
// on a god object (same rationale as `TmuxClientErrors.kt`).
private const val ISSUE_105_DIAG_TAG = "issue105-diag"

/**
 * Issue #2387: the sweep is ONE exec on the caller's warm lease — bounded so
 * a wedged/half-open transport can never hang [TmuxClient.connect] itself.
 * Before this sweep existed, the explicit "new session" intent
 * (`createIfMissing && !probeServerLiveness`) ran NO exec at all here, so it
 * could never be blocked by a probe; this bound preserves that promise. It
 * is also a strict improvement for the reattach-required paths, whose
 * pre-#2387 `has-session` preflight had no bound of its own and could hang
 * forever on a wedged transport.
 */
private const val SWEEP_TIMEOUT_MS = 5_000L

/**
 * Issue #666/#998/#2387: resolve what [TmuxClient.connect] should write to
 * the freshly-opened `-CC` shell, BEFORE any shell opens or byte is written —
 * a multi-socket sweep on a separate `exec` channel (never the control-mode
 * wire, never spawns tmux itself) — then either throw the #666/#998
 * reattach-refusal exceptions or return the exact command line to write
 * (without a trailing newline).
 *
 * ## Issue #2387 — why a sweep, and why on EVERY connect()
 *
 * A tmuxctl host runs one dedicated tmux SERVER per session
 * (`tmuxctl-<name>`), not one shared default server, so a bare
 * `has-session`/`new-session -A` (no `-S`) can only ever see the default
 * socket. Before this, attaching to a name that lives on a dedicated socket
 * found NOTHING on default and — because `new-session -A` is
 * attach-OR-create — silently MINTED a brand-new, empty, same-named session
 * on default instead of reaching the real one (the maintainer's reported
 * orphan). [TmuxSessionSocketLocator]'s sweep runs on EVERY connect(), not
 * just the reattach-flagged ones: the ordinary create-if-missing "open this
 * session" path (session-list taps, warm switches) is exactly how the orphan
 * was hit, since by the time this runs a session created moments earlier
 * through the app's own create path may already be sitting on its own
 * tmuxctl socket.
 *
 * Two DISTINCT intents, two DISTINCT commands — never a single
 * `new-session -A` guessing between them:
 *  - the sweep LOCATED the session (on its dedicated socket, or on default)
 *    -> ATTACH to that exact server. This can never mint a duplicate:
 *    `attach-session` fails loudly instead of silently creating when the
 *    name is not actually there.
 *  - the sweep found it nowhere, or degraded to [TmuxSessionLocation.Unknown]
 *    on a host the sweep could not run/parse (e.g. a foreign/old host whose
 *    shell injected banner noise ahead of the sweep's own output) -> for the
 *    explicit "new session" intent (`createIfMissing && !probeServerLiveness`),
 *    the pre-#2387 `new-session -A` on the DEFAULT socket, exactly as before:
 *    attach-or-create is the right behaviour for a genuinely fresh/first-ever
 *    session. For a REATTACH-REQUIRED intent (`!createIfMissing ||
 *    probeServerLiveness`), an unclassifiable [TmuxSessionLocation.Unknown]
 *    result is treated the SAME as the sweep exec throwing (see the `catch`
 *    block below): we cannot tell whether the session survived, so we refuse
 *    to create rather than risk silently resurrecting a killed session
 *    (#666/#998) — see the [TmuxSessionLocation.Unknown] guard below.
 */
internal suspend fun resolveTmuxAttachCommand(
    session: SshSession,
    resolvedSessionName: String,
    startDirectory: String?,
    createIfMissing: Boolean,
    probeServerLiveness: Boolean,
): String {
    val location = try {
        // Issue #2387: `withContext(Dispatchers.IO)` OUTSIDE `withTimeout` —
        // not the other way round. `withTimeout`'s cancellation timer runs on
        // its CURRENT coroutine context's `Delay`; entered before switching
        // dispatchers, that context is whatever the caller's ambient
        // dispatcher is (a virtual-time `TestDispatcher` under a
        // `kotlinx-coroutines-test` `runTest`, in every VM test that drives
        // [TmuxClient.connect] through a real `RealTmuxClient`), so the bound
        // would only fire when the TEST's virtual clock is manually advanced
        // past it — never on the real IO thread the exec actually runs on.
        // Switching to `Dispatchers.IO` FIRST makes the timer real-time on a
        // real dispatcher, exactly like every other bounded exec in this file
        // (`captureWithCursor`, the exec lane) already does.
        withContext(Dispatchers.IO) {
            withTimeout(SWEEP_TIMEOUT_MS) {
                TmuxSessionSocketLocator.parse(
                    session.exec(
                        TmuxSessionSocketLocator.locateCommand(
                            "'${escapeAttachSingleQuoted(TmuxTarget.session(resolvedSessionName))}'",
                        ),
                    ),
                )
            }
        }
    } catch (t: Throwable) {
        // Issue #666/#998: a reattach that EXPECTS the session to already
        // exist cannot proceed without knowing whether it is gone or the
        // whole server died — surface the failure exactly as the old
        // has-session-only preflight did (now also bounded, see
        // [SWEEP_TIMEOUT_MS] — a [TimeoutCancellationException] lands here
        // exactly like any other transport failure). The explicit "new
        // session" intent never depended on this probe before #2387 either,
        // so a sweep failure (including a timeout) there degrades silently
        // (below) rather than blocking a legitimate fresh create.
        if (!createIfMissing || probeServerLiveness) {
            throw TmuxClientException(
                "failed to preflight tmux has-session for '$resolvedSessionName': ${t.message}",
                t,
            )
        }
        TmuxSessionLocation.Unknown
    }
    if (location is TmuxSessionLocation.Unknown && (!createIfMissing || probeServerLiveness)) {
        // Issue #2387 review gap (round 2): a sweep exec that SUCCEEDS but
        // whose stdout does not match either known shape — e.g. a
        // foreign/old host whose shell injects banner/MOTD text ahead of the
        // sweep's own `printf`, per [TmuxSessionSocketLocator.parse]'s own
        // KDoc — must be treated exactly like the `catch (t: Throwable)`
        // block above treats an exec that THROWS: for a reattach-required
        // intent (`!createIfMissing || probeServerLiveness`, the exact
        // attach-only cold-restore / reconnect intents #666/#998 protect),
        // we cannot tell whether the session is still there, so we refuse to
        // fall through to `new-session -A` rather than silently resurrecting
        // a possibly-killed session (the #666/#998 bug, reopened under this
        // specific degraded condition). Before this fix, ONLY the
        // exec-throws case was guarded here — a successful-but-garbled exec
        // skipped straight past the (location is Absent) check below and
        // reached the unconditional `new-session -A` fallback at the bottom
        // of this function.
        Log.i(
            ISSUE_105_DIAG_TAG,
            "tmux-sweep-unclassified session=$resolvedSessionName " +
                "createIfMissing=$createIfMissing probeServerLiveness=$probeServerLiveness " +
                "— sweep exec succeeded but its output could not be classified, refusing to " +
                "recreate, dropping to list",
        )
        throw TmuxClientException(
            "failed to preflight tmux has-session for '$resolvedSessionName': " +
                "sweep result could not be classified (garbled/unparseable output)",
        )
    }
    if (location is TmuxSessionLocation.Absent) {
        // Issue #666 (attach-only cold-restore) + Issue #998 (reattach
        // server-death): the sweep checked EVERY socket and found the
        // session nowhere. Whenever we are reattaching to a session we
        // EXPECT to already exist — either an attach-only cold restore
        // (`!createIfMissing`) or a reconnect/lifecycle reattach
        // (`probeServerLiveness`) — that must NOT silently fall through to
        // creating a fresh one. We do NOT throw for the explicit user "new
        // session" intent (`createIfMissing && !probeServerLiveness`), which
        // legitimately wants a fresh server when none is running.
        if (!createIfMissing || probeServerLiveness) {
            // Issue #998: a dead SERVER (`no server running on <socket>`) is
            // categorically different from one gone SESSION (`can't find
            // session`). On a dead server EVERY session vanished, so a
            // `new-session -A` reattach would silently boot a fresh empty
            // server — the resurrection bug. Surface it as server-death so
            // the caller drops to the list and never recreates. We classify
            // server-death FIRST because it dominates: even on the
            // attach-only restore path a dead server is server-death, not a
            // single-session-ended. Issue #2387: this classification reads
            // the DEFAULT socket's own failure text — the sweep tried every
            // dedicated socket first, so "absent" here means genuinely
            // nowhere, not merely "not on default".
            if (isTmuxServerDeadStderr(location.detail)) {
                Log.i(
                    ISSUE_105_DIAG_TAG,
                    "tmux-server-dead session=$resolvedSessionName exit=${location.exitCode} " +
                        "stderr=${location.detail.trim()}",
                )
                throw TmuxServerDeadException()
            }
            // Server(s) alive but the TARGET session is gone. Issue #666
            // REOPEN (2026-07-06): a session that no longer exists at
            // reattach time ENDED — a reattach must NEVER recreate it.
            // Previously ONLY the attach-only cold-restore path
            // (`!createIfMissing`) refused to recreate here; the reattach
            // path (`createIfMissing && probeServerLiveness`:
            // LifecycleReattach / AutoReconnect / Reconnect /
            // NetworkReconnect) FELL THROUGH to `new-session -A`
            // (attach-OR-create) and silently resurrected the killed session
            // — the exact dogfood bug ("I removed it on the computer, but
            // the app created it again"). We hard-cut that branch (D22):
            // whenever the preflight ran (`!createIfMissing ||
            // probeServerLiveness`) and the specific session is gone
            // everywhere, throw [TmuxSessionNotFoundException] so the
            // caller drops to the list — identically for cold-restore AND
            // every reattach. The only path that legitimately
            // create-if-missing is the explicit user "new session" intent
            // (`createIfMissing && !probeServerLiveness`).
            Log.i(
                ISSUE_105_DIAG_TAG,
                "tmux-has-session-gone session=$resolvedSessionName exit=${location.exitCode} " +
                    "createIfMissing=$createIfMissing probeServerLiveness=$probeServerLiveness " +
                    "— refusing to recreate, dropping to list",
            )
            throw TmuxSessionNotFoundException(resolvedSessionName)
        }
    }
    val located = location as? TmuxSessionLocation.Located
    return if (located != null) {
        "${located.tmuxClientPrefix} -CC attach-session -t " +
            "'${escapeAttachSingleQuoted(TmuxTarget.session(resolvedSessionName))}'"
    } else {
        val resolvedStartDirectory = startDirectory?.trim().orEmpty()
        buildString {
            append("tmux -CC new-session -A -s '")
            append(escapeAttachSingleQuoted(resolvedSessionName))
            append("'")
            if (resolvedStartDirectory.isNotEmpty()) {
                append(" -c '")
                append(escapeAttachSingleQuoted(resolvedStartDirectory))
                append("'")
            }
        }
    }
}

private fun escapeAttachSingleQuoted(input: String): String =
    input.replace("'", "'\\''")
