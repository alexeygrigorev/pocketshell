package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject

/**
 * One row returned by [FolderListGateway.listSessionsWithFolder] — the
 * minimal data shape the folder-grouping logic needs.
 *
 * `cwd` is the active pane's `pane_current_path` when available, falling
 * back to the session's `session_path` if the pane probe failed or the
 * session has no active pane. Both can be null (very old tmux, or a
 * session created without `-c`) — the view model surfaces those under
 * an "Untracked" group.
 *
 * `agentKind` is the LIVE detection state. Issue #252: the gateway
 * delegates to the exact same detector the Conversation view uses
 * ([com.pocketshell.app.session.AgentConversationRepository]), via the
 * batched [com.pocketshell.app.session.AgentConversationRepository.detectForPanes]
 * — every session is classified from a CONSTANT 2 host-wide SSH
 * round-trips (one candidate enumeration across all cwds, one host-wide
 * `ps`), scoped per pane by cwd + TTY + foreground command from
 * `tmux list-panes -a`. That guarantees the session-list chip and the
 * Conversation tab agree by construction (they previously drifted: the
 * list kept a forked candidate-enumeration heuristic that predated
 * #183/#186/#236 and so labelled live Claude Code / Codex / OpenCode
 * sessions `Shell`) WITHOUT the ~2N sequential round-trips a per-session
 * `detectForPane` loop would cost on a multi-session list. Sessions
 * without a detection match render as [SessionAgentKind.Shell] — the
 * locked default for plain tmux panes.
 */
data class FolderSessionRow(
    val sessionName: String,
    val lastActivity: Long?,
    val attached: Boolean,
    val cwd: String?,
    val agentKind: SessionAgentKind = SessionAgentKind.Shell,
)

/**
 * Result of a single folder-list probe against one host. Mirrors the
 * shape of [com.pocketshell.app.sessions.HostTmuxSessionListResult] so
 * the view model can render the same "Loading / Ready / Failed /
 * ConnectError" affordances as the existing host picker, but with
 * `cwd`-bearing rows.
 */
sealed interface FolderListResult {
    data class Sessions(val rows: List<FolderSessionRow>) : FolderListResult
    data object ToolUnavailable : FolderListResult
    data class Failed(val message: String) : FolderListResult
    data class ConnectFailed(val cause: Throwable) : FolderListResult
}

/**
 * Gateway used by [FolderListViewModel] to fetch session rows with
 * `pane_current_path` / `session_path` metadata.
 *
 * Kept separate from
 * [com.pocketshell.app.sessions.HostTmuxSessionsGateway] so issue #171
 * lands without touching the picker-sheet wire shape. The picker
 * gateway's existing call sites (dashboard, share-target paste-to-
 * session, deep links) stay on the cwd-blind contract; the folder
 * screen owns the cwd-aware probe end-to-end.
 *
 * Wire shape (per host poll):
 *
 *  - `tmux list-sessions -F '#{session_name}\t#{session_created}\t
 *    #{session_activity}\t#{session_attached}\t#{session_path}'`
 *  - `tmux list-panes -a -F '#{session_name}\t#{pane_active}\t
 *    #{pane_current_path}\t#{pane_tty}\t#{pane_current_command}'` so
 *    the active pane's cwd + TTY + foreground command supersede
 *    `session_path` when they disagree. Per the spike:
 *    `pane_current_path` is the primary signal, `session_path` is the
 *    fallback.
 *  - Agent detection probe (issue #252): one batched
 *    `AgentConversationRepository.detectForPanes` call for the whole
 *    list — a CONSTANT 2 host-wide round-trips (candidate enumeration
 *    across every cwd + one host-wide `ps`), each session then classified
 *    in-memory scoped to its active pane's cwd, TTY, and foreground
 *    command. This is the identical detector the Conversation view uses,
 *    so the session-list chip and the Conversation tab can never
 *    disagree, and the load does not scale with the session count.
 *    Sessions whose active pane has no live agent stay on
 *    [SessionAgentKind.Shell].
 *
 * If any of the secondary probes fail (no active panes, exec error)
 * the gateway falls back to the `session_path` value alone — the folder
 * grouping degrades gracefully rather than going blank.
 */
interface FolderListGateway {
    suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): FolderListResult

    /**
     * Create a new tmux session in [cwd] and optionally launch
     * [startCommand] inside it via `send-keys`. Used by the
     * [SessionTypePickerSheet] confirm path so an "Agent" choice
     * auto-runs the chosen CLI as the new pane's first command.
     *
     * Returns the resolved session name (sometimes munged by tmux when
     * the requested name collides) or null on failure.
     */
    suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
    ): Result<String>
}

class SshFolderListGateway @Inject constructor() : FolderListGateway {

    // Issue #252: reuse the SAME detection logic the Conversation view
    // uses instead of maintaining a forked candidate-enumeration +
    // process-scan heuristic here. The list path previously hard-coded a
    // stale copy of the detection shell that predated #183 (Codex/OpenCode
    // candidate enumeration), #186 (per-pane TTY-scoped process scan),
    // OpenCode SQLite detection, and #236 (120-minute freshness window).
    // That drift is exactly why a live Claude Code (and Codex/OpenCode)
    // session classified as `Shell` in the list while the Conversation
    // view rendered it correctly. Delegating to the batched
    // [AgentConversationRepository.detectForPanes] keeps the two paths in
    // lock-step by construction while collapsing the list-load to a
    // constant 2 host-wide SSH round-trips (vs. ~2N sequential ones).
    private val agentRepository = AgentConversationRepository()

    override suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): FolderListResult {
        val session = SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrElse { error ->
            return FolderListResult.ConnectFailed(error)
        }

        return try {
            val listSessions = session.exec(pathAware(LIST_SESSIONS_COMMAND))
            when {
                listSessions.exitCode == 127 ||
                    listSessions.stderr.contains("not found", ignoreCase = true) ->
                    FolderListResult.ToolUnavailable
                listSessions.stderr.contains("no server running", ignoreCase = true) ->
                    FolderListResult.Sessions(emptyList())
                listSessions.exitCode != 0 ->
                    FolderListResult.Failed(
                        listSessions.stderr.ifBlank { listSessions.stdout }
                            .ifBlank { "tmux exited ${listSessions.exitCode}" },
                    )
                else -> {
                    val baseRows = parseListSessionsRows(listSessions.stdout)
                    val paneRows = runCatching {
                        val listPanes = session.exec(pathAware(LIST_PANES_COMMAND))
                        if (listPanes.exitCode == 0) parseActivePaneRows(listPanes.stdout) else emptyMap()
                    }.getOrDefault(emptyMap())

                    // Merge active-pane data into each session row first.
                    val merged = baseRows.map { row ->
                        val pane = paneRows[row.sessionName]
                        val cwd = pane?.cwd ?: row.cwd
                        row.copy(cwd = cwd)
                    }

                    // Issue #252: per-session agent detection delegated to
                    // the Conversation view's detector
                    // (AgentConversationRepository.detectForPane) so the
                    // list chip and the Conversation tab agree. Each
                    // session is probed with its active pane's cwd, TTY,
                    // and foreground command. Sessions without a live
                    // agent stay on SessionAgentKind.Shell (the default).
                    val agentKinds = runCatching {
                        detectAgentKinds(
                            session = session,
                            rows = merged,
                            paneRows = paneRows,
                        )
                    }.getOrDefault(emptyMap())

                    val annotated = merged.map { row ->
                        row.copy(agentKind = agentKinds[row.sessionName] ?: SessionAgentKind.Shell)
                    }
                    FolderListResult.Sessions(annotated)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FolderListResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        } finally {
            session.close()
        }
    }

    override suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
    ): Result<String> {
        val session = SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrElse { return Result.failure(it) }

        return try {
            val quotedName = shellQuote(sessionName)
            val quotedCwd = shellQuote(cwd)
            // -A so an existing session with the same name attaches
            //    rather than failing (idempotent for the user — they
            //    can re-pick "Create" without seeing an error).
            // -d so the session is detached on the server (the app
            //    will attach via tmux -CC after navigation).
            val createResult = session.exec(
                pathAware("tmux new-session -A -d -s $quotedName -c $quotedCwd"),
            )
            if (createResult.exitCode != 0 && createResult.stderr.isNotBlank()) {
                return Result.failure(RuntimeException(createResult.stderr.trim()))
            }
            // Launch the start command via send-keys if requested. tmux
            // 's `send-keys ... Enter` sequence pipes the literal
            // command followed by a carriage return — same shape used
            // by the existing voice + planner paths.
            if (startCommand != null) {
                // Issue #263: auto-export the folder's .env / .envrc vars
                // into the new pane's shell BEFORE the agent CLI starts, so
                // the agent inherits them. The values are pulled via command
                // substitution (`eval "$(...)"`) so only the literal
                // `eval "$(pocketshell env export ...)"` text is echoed into
                // the pane — secret values never appear in the visible
                // terminal. Degrades gracefully: if `pocketshell` is missing
                // the substitution errors to a no-op (command-not-found is
                // swallowed inside `$()`), and if no env files exist the
                // export prints nothing — either way the agent still starts.
                val payload = composeStartCommand(cwd, startCommand)
                val quotedCommand = shellQuote(payload)
                session.exec(
                    pathAware("tmux send-keys -t $quotedName $quotedCommand Enter"),
                )
            }
            Result.success(sessionName)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            session.close()
        }
    }

    /**
     * Classify every session's active pane by delegating to the SAME
     * detector the Conversation view uses
     * ([AgentConversationRepository]). Issue #252: the list path used to
     * keep its own forked candidate-enumeration + process scan that
     * drifted out of sync with the conversation detector — it predated
     * #183 (Codex/OpenCode candidate enumeration), #186 (per-pane
     * TTY-scoped scan), OpenCode SQLite detection, and #236 (120-minute
     * freshness window). The drift is why a live Claude Code session
     * showed the Conversation tab yet was labelled `Shell` in the list.
     *
     * Latency follow-up (same issue): the per-session
     * [AgentConversationRepository.detectForPane] call costs 2 SSH
     * round-trips each, so an N-session list incurred ~2N SEQUENTIAL
     * round-trips per load — a regression the maintainer flagged. This
     * now uses the BATCHED [AgentConversationRepository.detectForPanes],
     * which runs a CONSTANT 2 host-wide round-trips (one candidate
     * enumeration across all cwds, one host-wide `ps`) and classifies
     * each pane in-memory with the SAME [AgentDetector] + the same
     * per-cwd / per-TTY `requireProcessMatch = true` discipline. So the
     * list and the Conversation tab still agree by construction, but the
     * load no longer scales with the session count.
     *
     * Each session is probed with its active pane's `pane_current_path`
     * (cwd), `pane_tty`, and `pane_current_command`. Sessions whose
     * active pane has no TTY, no cwd, or no live agent stay on
     * [SessionAgentKind.Shell].
     */
    private suspend fun detectAgentKinds(
        session: com.pocketshell.core.ssh.SshSession,
        rows: List<FolderSessionRow>,
        paneRows: Map<String, ActivePaneRow>,
    ): Map<String, SessionAgentKind> {
        val probes = rows.mapNotNull { row ->
            val cwd = row.cwd?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val pane = paneRows[row.sessionName]
            // No active-pane TTY ⇒ no per-pane attribution (same rule the
            // Conversation view enforces via the detector's blank-TTY
            // guard). Without a TTY there is no way to scope the process
            // scan to this session's pane.
            val paneTty = pane?.tty?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AgentConversationRepository.PaneProbe(
                key = row.sessionName,
                cwd = cwd,
                paneTty = paneTty,
                paneCommand = pane.command.orEmpty(),
            )
        }
        if (probes.isEmpty()) return emptyMap()

        val detections = agentRepository.detectForPanes(
            session = session,
            panes = probes,
        )
        return detections.mapValues { (_, detection) ->
            when (detection.agent) {
                AgentKind.ClaudeCode -> SessionAgentKind.Claude
                AgentKind.Codex -> SessionAgentKind.Codex
                AgentKind.OpenCode -> SessionAgentKind.OpenCode
            }
        }
    }

    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)

    private fun shellQuote(value: String): String = shellQuoteValue(value)

    /** Active-pane row carrying the per-session signals we use beyond cwd. */
    internal data class ActivePaneRow(
        val sessionName: String,
        val cwd: String?,
        val tty: String?,
        val command: String?,
    )

    internal companion object {
        /**
         * Single-quote a value for safe interpolation into a POSIX shell
         * command (`'...'` with embedded single quotes escaped as
         * `'\''`). Used both for the `tmux send-keys` argument and the
         * `--dir` path inside the env-export prelude (issue #263), so a
         * folder path containing spaces, quotes, `;`, `$()`, etc. cannot
         * break out of its argument.
         */
        internal fun shellQuoteValue(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        /**
         * Issue #263: compose the literal command typed into a freshly
         * created pane. When [startCommand] is present, prepend an
         * env-export prelude so the folder's `.env` / `.envrc` variables
         * are live before the agent CLI runs:
         *
         * ```
         * eval "$(pocketshell env export --dir '<cwd>')"; <startCommand>
         * ```
         *
         * `<cwd>` is shell-quoted so a hostile or unusual folder path
         * cannot inject shell. The values are merged in via command
         * substitution, so only this literal text is echoed into the
         * visible terminal — the secret values are not. The prelude
         * degrades to a no-op when `pocketshell` is absent (the inner
         * command-not-found error is swallowed inside `$()`) or when the
         * folder has no env files (export prints nothing), so the agent
         * always launches.
         */
        internal fun composeStartCommand(cwd: String, startCommand: String): String {
            val quotedDir = shellQuoteValue(cwd)
            return "eval \"\$(pocketshell env export --dir $quotedDir)\"; $startCommand"
        }

        // tmux's `-F` format spec replaces tab bytes (0x09) in the
        // rendered output with `_` so a multi-field row delimited by
        // real tabs is mangled into a single column. The existing
        // dashboard wire shape (`SessionsDashboardViewModel.LIST_SESSIONS_COMMAND`)
        // dodges the same hazard by using `::` as a separator — tmux's
        // session names disallow colons (per tmux(1)'s "NAMES, WINDOWS,
        // AND PANES" section), so the delimiter is unambiguous on the
        // session-name column. Paths can technically contain colons on
        // exotic filesystems, but tmux's session_path is always the
        // realpath of an absolute directory — colons inside path
        // components are exceedingly rare and we accept the trade-off
        // (the path is the last column, so a stray `::` inside it would
        // be parsed verbatim including the colons; degraded but not
        // wrong).
        const val FIELD_SEP: String = "::"

        const val LIST_SESSIONS_COMMAND: String =
            "tmux list-sessions -F " +
                "'#{session_name}$FIELD_SEP#{session_created}$FIELD_SEP" +
                "#{session_activity}$FIELD_SEP#{session_attached}$FIELD_SEP#{session_path}'"

        const val LIST_PANES_COMMAND: String =
            "tmux list-panes -a -F " +
                "'#{session_name}$FIELD_SEP#{pane_active}$FIELD_SEP" +
                "#{pane_current_path}$FIELD_SEP#{pane_tty}$FIELD_SEP#{pane_current_command}'"

        /**
         * Parse the tab-delimited `list-sessions` output into
         * [FolderSessionRow]s. Each line carries five fields:
         * `session_name`, `session_created`, `session_activity`,
         * `session_attached`, `session_path`. Blank cwd surfaces as
         * `null` so the view model can route the row to the "Untracked"
         * group.
         */
        internal fun parseListSessionsRows(stdout: String): List<FolderSessionRow> =
            stdout.lineSequence()
                .mapNotNull(::parseRow)
                .toList()

        private fun parseRow(line: String): FolderSessionRow? {
            if (line.isBlank()) return null
            // limit=5 so a path containing the rare `::` literal still
            // parses (the rightmost column absorbs any trailing
            // separators).
            val parts = line.split(FIELD_SEP, limit = 5)
            if (parts.size < 4) return null
            val name = parts[0].trim()
            if (name.isEmpty()) return null
            val sessionPath = if (parts.size >= 5) parts[4].trim().ifBlank { null } else null
            return FolderSessionRow(
                sessionName = name,
                lastActivity = parts[2].trim().toLongOrNull(),
                attached = (parts[3].trim().toLongOrNull() ?: 0L) > 0L,
                cwd = sessionPath,
                // Default to Shell; the gateway will override this for
                // sessions where the agent detection probe finds a match.
                agentKind = SessionAgentKind.Shell,
            )
        }

        /**
         * Parse `list-panes -a` output into a map from session name to
         * its active pane's metadata. Sessions whose active pane row is
         * missing fall back to the session-level `session_path` from
         * `list-sessions`.
         */
        internal fun parseActivePaneRows(stdout: String): Map<String, ActivePaneRow> {
            val result = mutableMapOf<String, ActivePaneRow>()
            for (line in stdout.lineSequence()) {
                if (line.isBlank()) continue
                val parts = line.split(FIELD_SEP, limit = 5)
                if (parts.size < 3) continue
                val name = parts[0].trim()
                if (name.isEmpty()) continue
                val active = (parts[1].trim().toLongOrNull() ?: 0L) > 0L
                if (!active) continue
                val cwd = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
                val tty = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                val command = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() }
                result[name] = ActivePaneRow(
                    sessionName = name,
                    cwd = cwd,
                    tty = tty,
                    command = command,
                )
            }
            return result
        }
    }
}
