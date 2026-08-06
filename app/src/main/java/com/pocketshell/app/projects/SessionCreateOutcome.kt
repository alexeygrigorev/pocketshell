package com.pocketshell.app.projects

/**
 * Issue #1928: what a [FolderListGateway.createSession] call ACTUALLY achieved.
 *
 * A create has two independent halves — make the tmux session, then (optionally)
 * launch the requested agent inside it with `send-keys`. Before this type the
 * gateway returned `Result<String>`, which can only express two states, so the
 * second half had nowhere to go: [SshFolderListGateway.createSessionOnSession]
 * ran the launch and **discarded its result**. A launch that exited non-zero
 * (the pane target vanished between create and send, the host's `pocketshell`
 * predates the `agent` subcommand, the bounded exec timed out on a degraded
 * link) still produced `Result.success(resolvedName)`, so the app told the user
 * "your Claude session is ready" and handed them an empty shell.
 *
 * The three states are now distinct and a caller cannot reach the resolved name
 * without deciding which one it is handling (see [fold]):
 *
 *  - **create failure** — `Result.failure`. Nothing exists on the host; the
 *    thrown message is the actionable reason (missing start directory, launch
 *    collision, tmux/systemd error).
 *  - **[Created]** — full success. The session exists and, when a start command
 *    was requested, it was typed into the new pane.
 *  - **[LaunchFailed]** — PARTIAL success. The tmux session exists and MUST be
 *    kept (issue #1928 non-goal: never delete a session just because the
 *    optional launch failed), but the requested agent did not start. The user
 *    has a plain shell under [sessionName] and needs to be told so.
 *
 * D22 hard cut: the `Result<String>` shape is gone, not deprecated. There is no
 * overload, no `sessionNameOrNull()` convenience, and no default branch — every
 * call site states what it does with a partial success or does not compile.
 */
sealed interface SessionCreateOutcome {

    /** The tmux session that exists on the host. Real in BOTH states. */
    val sessionName: String

    /** Session created; the requested start command (if any) was launched. */
    data class Created(override val sessionName: String) : SessionCreateOutcome

    /**
     * Session created, requested agent launch FAILED.
     *
     * [detail] is the host's own reason (tmux stderr, the #759 outdated-CLI
     * update hint, or the bounded-exec timeout), already trimmed. It never
     * carries the launch command itself, so a profile/API-key-bearing wrapper
     * line cannot leak into a banner or a log line.
     */
    data class LaunchFailed(
        override val sessionName: String,
        val detail: String,
    ) : SessionCreateOutcome
}

/**
 * Consume a [SessionCreateOutcome] by handling BOTH states.
 *
 * This exists so that "collapse partial success into full success" is not
 * something a caller can do by accident — the compiler requires both lambdas,
 * and neither branch can be reached without naming it. Prefer this over a
 * `when` + `.sessionName` read at the call sites the sweep in issue #1928
 * covers (folder tree, in-session, assistant, stale-session recovery).
 */
inline fun <T> SessionCreateOutcome.fold(
    onCreated: (sessionName: String) -> T,
    onLaunchFailed: (sessionName: String, detail: String) -> T,
): T = when (this) {
    is SessionCreateOutcome.Created -> onCreated(sessionName)
    is SessionCreateOutcome.LaunchFailed -> onLaunchFailed(sessionName, detail)
}

/**
 * The ONE user-facing sentence for a [SessionCreateOutcome.LaunchFailed] —
 * issue #1928.
 *
 * It has to say three things, because all three are actionable: the session
 * DOES exist (so the user does not create a second one), the agent did NOT
 * start (so they do not sit waiting for a prompt that never appears), and what
 * to do next. Shared by every surface so the folder tree, the in-session sheet,
 * the assistant and the stale-session recovery all report the same thing.
 */
fun sessionLaunchFailedMessage(sessionName: String, detail: String): String =
    "Session “$sessionName” was created, but the agent didn't start: " +
        "${detail.ifBlank { "the host gave no reason" }}. " +
        "Open the session and start the agent from there."
