package com.pocketshell.app.projects

import com.pocketshell.app.bootstrap.UV_EXCLUDE_NEWER_FLAG
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Issue #947: run the host-side `pocketshell` upgrade over the EXISTING warm SSH
 * session (D21 — no new connection), so the host-version-mismatch banner can
 * offer a one-tap **Update** button instead of only printing the manual command.
 *
 * ## What it does
 *
 * In a SINGLE non-interactive `/bin/sh` exec it:
 *
 * 1. Augments `PATH` with the common per-user bin dirs (the
 *    [com.pocketshell.app.pocketshell.PocketshellCommand] problem: a
 *    non-interactive SSH exec misses `~/.local/bin`, where uv/pipx/pip drop
 *    their shims).
 * 2. PROBES which installer owns `pocketshell` and runs the matching upgrade:
 *    - `uv tool list` mentions `pocketshell` → `uv tool install --upgrade
 *      --exclude-newer 2099-12-31 pocketshell` (the #779/#1492 override that
 *      lifts uv's global `exclude-newer` cap for the WHOLE resolution so a
 *      sibling pin like `quse` past the cap cannot make the upgrade a silent
 *      no-op — mirrors [PayloadVersionCheck.UPDATE_COMMAND]).
 *    - else `pipx` present → `pipx upgrade pocketshell`.
 *    - else `pip`/`pip3` present → `pip install -U pocketshell`.
 *    - else exit `127` so the caller surfaces "no installer found".
 *
 * The probe-then-run lives entirely server-side in the one command so there is
 * exactly ONE exec round-trip (no extra latency, no second blocking read).
 *
 * ## Bounding (issues #944 / #939)
 *
 * The exec is bounded with [execBounded] (mirrors
 * [TreeRemoteSource.execTreeRpcBounded]): the upgrade runs in a child coroutine
 * and is awaited with [upgradeTimeoutMs]; on timeout the child is cancelled and
 * the session closed so a wedged installer can never pin the warm path forever
 * and the banner's spinner can never stick. An upgrade is heavier than a
 * `tree get`, so the default timeout is generous (a real `uv tool install`
 * downloads + builds).
 */
public class HostPocketshellUpgrade {

    /**
     * Bound on a single upgrade exec. An installer upgrade (uv resolve +
     * download + build) is much heavier than a `tree get`, so this is generous —
     * it is the last-line defence against a TRULY wedged installer, not a normal
     * completion deadline. Test-overridable.
     */
    internal var upgradeTimeoutMs: Long = UPGRADE_TIMEOUT_MS
    internal var execDispatcher: CoroutineDispatcher = Dispatchers.IO

    /** The outcome of a [run] call. */
    public sealed interface Result {
        /** The upgrade command exited 0. */
        public data object Success : Result

        /**
         * The upgrade did not succeed. [message] is a short, user-facing reason
         * built from the installer stderr/stdout (or a timeout / no-installer
         * note), suitable for the banner's failure line.
         */
        public data class Failure(val message: String) : Result
    }

    /**
     * Run the host upgrade over [session] (the warm lease's session). Never
     * throws except [CancellationException]; any transport/parse failure
     * degrades to a [Result.Failure] so the banner always leaves the running
     * state (no stuck spinner — #939).
     */
    public suspend fun run(session: SshSession): Result {
        val result = try {
            session.execBounded(UPGRADE_COMMAND)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return Result.Failure(t.message?.takeIf { it.isNotBlank() } ?: "Update failed.")
        }
            ?: return Result.Failure(
                "Update timed out after ${upgradeTimeoutMs / 1000}s. Try again, or run the command on the host.",
            )
        if (result.exitCode == 0) return Result.Success
        if (result.exitCode == NO_INSTALLER_EXIT) {
            return Result.Failure(
                "No uv / pipx / pip found on the host to upgrade pocketshell. " +
                    "Install one, or run the command on the host.",
            )
        }
        return Result.Failure(formatFailure(result))
    }

    private fun formatFailure(result: ExecResult): String {
        val detail = result.stderr.trim().ifBlank { result.stdout.trim() }
        val tail = detail.lines().filter { it.isNotBlank() }.takeLast(MAX_ERROR_LINES)
            .joinToString("\n")
            .take(MAX_ERROR_CHARS)
        return if (tail.isBlank()) {
            "Update failed (exit ${result.exitCode})."
        } else {
            "Update failed (exit ${result.exitCode}):\n$tail"
        }
    }

    private suspend fun SshSession.execBounded(command: String): ExecResult? =
        withContext(execDispatcher) {
            val deferred = async { exec(command) }
            withTimeoutOrNull(upgradeTimeoutMs) { deferred.await() }
                ?: run {
                    deferred.cancel()
                    withContext(NonCancellable) {
                        runCatching { close() }
                    }
                    null
                }
        }

    public companion object {
        /** 4 minutes: an installer download + build is slow; this only guards a true wedge. */
        internal const val UPGRADE_TIMEOUT_MS: Long = 240_000L

        /** Exit code the [UPGRADE_COMMAND] uses when no installer owns pocketshell. */
        internal const val NO_INSTALLER_EXIT: Int = 127

        private const val MAX_ERROR_LINES: Int = 6
        private const val MAX_ERROR_CHARS: Int = 600

        /**
         * The single `/bin/sh` command: augment PATH, probe the owning installer
         * (uv → pipx → pip), and run the matching upgrade. Exits 127 when none is
         * found. The uv arm mirrors [PayloadVersionCheck.UPDATE_COMMAND]
         * (the global [UV_EXCLUDE_NEWER_FLAG], issue #779 widened by #1492) so the
         * upgrade is never silently capped by the host's global uv cutoff — for
         * pocketshell OR any of its pinned siblings.
         */
        internal val UPGRADE_COMMAND: String = buildString {
            // PATH augmentation: same per-user bin dirs PocketshellCommand prepends
            // so uv/pipx/pip shims under ~/.local/bin etc. are discoverable on a
            // non-interactive exec.
            append("export PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$HOME/.pixi/bin:")
            append("\$HOME/bin:/usr/local/bin:\$PATH\"; ")
            // uv: only if uv exists AND its tool list actually owns pocketshell —
            // otherwise a host with uv-for-something-else but pipx-owned
            // pocketshell would wrongly try uv.
            append("if command -v uv >/dev/null 2>&1 && ")
            append("uv tool list 2>/dev/null | grep -qi '^pocketshell\\b'; then ")
            append("exec uv tool install --upgrade ")
            append("$UV_EXCLUDE_NEWER_FLAG pocketshell; ")
            // pipx next.
            append("elif command -v pipx >/dev/null 2>&1; then ")
            append("exec pipx upgrade pocketshell; ")
            // pip / pip3 last.
            append("elif command -v pip >/dev/null 2>&1; then ")
            append("exec pip install -U pocketshell; ")
            append("elif command -v pip3 >/dev/null 2>&1; then ")
            append("exec pip3 install -U pocketshell; ")
            // No installer at all.
            append("else exit 127; fi")
        }
    }
}
