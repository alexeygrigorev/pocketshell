package com.pocketshell.app.assistant

/**
 * The seam between the provider-agnostic [AssistantAgentLoop] and the live
 * app surfaces (nav state machine, `FolderListGateway`, `HostDao`, tmux
 * `send-keys`/exec over SSH). Issue #266.
 *
 * Every method here corresponds to one or more tools in the catalog. The
 * loop never touches Hilt, Room, or SSH directly — it only calls this
 * interface — so the loop can be unit-tested end to end against a hand-built
 * fake that scripts tool outputs. Production wiring lives in
 * [com.pocketshell.app.assistant.AppAssistantActions], which bridges onto
 * the real surfaces.
 *
 * Methods that mutate remote / nav state ([runCommand], [createFile],
 * [startSession], [cloneRepo]) are only invoked by the loop AFTER the user
 * confirms the candidate via the confirm-or-correct gate. Inspect / nav
 * methods auto-run (D25).
 */
internal interface AssistantActions {

    // ---- Inspect (auto-run, read-only) ----------------------------------

    /**
     * Snapshot of the current screen / host / session / cwd. Resolves
     * "this folder", "this dir", "here", "it" for the model. Returns a
     * JSON-ish text block the model can read.
     */
    suspend fun getContext(): String

    /** `pocketshell`-free host list from [com.pocketshell.core.storage.dao.HostDao]. */
    suspend fun listHosts(): String

    /** tmux folders on [host] (session name → cwd grouping). */
    suspend fun listFolders(host: String): String

    /** tmux sessions on [host]. */
    suspend fun listSessions(host: String): String

    /** `ls -la` of [path] on the active host. */
    suspend fun listDirectory(path: String): String

    /** First N KiB of [path] on the active host. */
    suspend fun readFile(path: String): String

    /** `pocketshell repos list --json` on the active host. */
    suspend fun listRepos(): String

    // ---- Act — navigation (auto) ----------------------------------------

    /** Navigate to the folder list / a folder on [host] rooted at [path]. */
    suspend fun openFolder(host: String, path: String): String

    /** Attach to / open an existing tmux session by name. */
    suspend fun openSession(sessionName: String): String

    /** Navigate to a named app screen (hosts, settings, usage, …). */
    suspend fun openScreen(destination: String): String

    // ---- Act — mutating (confirm-gated) ---------------------------------

    /**
     * Create a tmux session in [cwd] on [host] launching [agent]
     * (claude|codex|opencode|shell). Returns the resolved session name on
     * success.
     */
    suspend fun startSession(host: String, cwd: String, agent: String): ActionResult

    /**
     * Run [command] in the active terminal via `send-keys` (also handles
     * "cd to …"). The safety gate has already passed by the time the loop
     * calls this.
     */
    suspend fun runCommand(command: String): ActionResult

    /** Create a file at [path] with [content] on the active host. */
    suspend fun createFile(path: String, content: String): ActionResult

    /**
     * `pocketshell repos clone <fullName>` on the active host, optionally
     * into [folder]. Returns the clone path on success. When `gh` is not
     * authenticated the result is a clear non-crashing message.
     */
    suspend fun cloneRepo(fullName: String, folder: String?): ActionResult
}

/**
 * The outcome of a mutating tool. [ok] drives the trace `result` field
 * (`ok` vs `error`) and lets the loop relay a clean message to the model.
 */
internal data class ActionResult(
    val ok: Boolean,
    val message: String,
) {
    companion object {
        fun ok(message: String): ActionResult = ActionResult(ok = true, message = message)
        fun error(message: String): ActionResult = ActionResult(ok = false, message = message)
    }
}
