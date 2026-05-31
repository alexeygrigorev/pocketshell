package com.pocketshell.app.assistant

import com.pocketshell.app.nav.AppDestination

/**
 * The session-bound seam the live session screen provides to
 * [AppAssistantActions] for terminal-mode actions and navigation
 * (issue #266).
 *
 * The big [com.pocketshell.app.session.SessionViewModel] /
 * [com.pocketshell.app.tmux.TmuxSessionViewModel] keep ownership of the
 * terminal byte path (`sendText` / `writeInputToPane`) and the live
 * connection metadata; the assistant only reaches them through this thin
 * interface so the agent loop stays decoupled from the 1000+-line session
 * view models and stays unit-testable.
 */
internal interface SessionActionBridge {

    /** The currently-connected host's saved label, or null when disconnected. */
    fun activeHostName(): String?

    /** The active terminal's working directory if known, else null. */
    fun activeCwd(): String?

    /** The active tmux session name if this is a tmux route, else null. */
    fun activeSessionName(): String?

    /** A short description of the current screen for `get_context`. */
    fun currentScreenLabel(): String

    /**
     * Send a literal [command] into the active terminal followed by Enter
     * (the `run_command` execution path). Mirrors the byte path chip taps
     * and snippet picks already use.
     */
    suspend fun sendCommand(command: String): Result<Unit>

    /** Request navigation to [destination] via the app's nav state machine. */
    fun navigate(destination: AppDestination)
}
