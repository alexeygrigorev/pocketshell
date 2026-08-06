package com.pocketshell.app.tmux

import com.pocketshell.app.projects.SessionCreateOutcome
import com.pocketshell.app.projects.fold
import com.pocketshell.app.projects.sessionLaunchFailedMessage

/**
 * Issue #1928: the in-session "+ New session" surface's reading of a
 * [SessionCreateOutcome].
 *
 * It lives OUT of [TmuxSessionViewModel] on purpose. The VM is the D28
 * god-object under a downward-only line/byte ratchet
 * (`scripts/check-connection-vm-ratchet.sh`), so the three-state accounting the
 * gateway now returns is expressed here and the VM keeps only the two lambdas
 * that are genuinely its own — the success log/attach and its existing
 * `reportSessionCreateFailure` breadcrumb.
 *
 * The split it enforces is the whole point of the issue:
 *
 *  - [onCreated] — full success. The session exists AND the requested agent
 *    started, so attaching the user to it is honest.
 *  - [onProblem] — everything else, with a `reason` token for the log and the
 *    user-facing `message`. It covers BOTH a create failure (nothing on the
 *    host, `reason = gateway_failure`, the throwable carried through) and issue
 *    #1928's partial success (`reason = launch_failed`, the session exists and
 *    is KEPT, the agent did not start). A partial success must never reach
 *    [onCreated]: attaching would drop the user into a plain shell they asked
 *    to be a Claude/Codex session, which is exactly the lie this issue is about.
 */
internal inline fun Result<SessionCreateOutcome>.reportSessionCreate(
    onCreated: (sessionName: String) -> Unit,
    onProblem: (reason: String, message: String, error: Throwable?) -> Unit,
): Unit = fold(
    onSuccess = { outcome ->
        outcome.fold(
            onCreated = onCreated,
            onLaunchFailed = { name, detail ->
                onProblem("launch_failed", sessionLaunchFailedMessage(name, detail), null)
            },
        )
    },
    onFailure = { error ->
        onProblem(
            "gateway_failure",
            error.message?.takeIf { it.isNotBlank() } ?: "Couldn't create the session.",
            error,
        )
    },
)
