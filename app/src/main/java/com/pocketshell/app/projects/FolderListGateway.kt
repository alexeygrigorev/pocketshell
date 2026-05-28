package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.agents.AgentDetector
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.AgentLogCandidate
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
 * `agentKind` is the LIVE detection state — issue #171 round 2. The
 * gateway runs an [AgentDetector]-driven pipeline (same shell-side
 * candidate enumeration as `AgentConversationRepository.detect`) per
 * session cwd, then merges the per-pane TTY-scoped process scan from
 * `tmux list-panes -a` to confirm the agent is actually running on the
 * session's active pane. Sessions without a detection match render as
 * [SessionAgentKind.Shell] — the spike's locked default for plain tmux
 * panes.
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
 *  - Agent detection probe (issue #171 round 2): host-wide JSONL
 *    candidate enumeration matching `AgentConversationRepository`'s
 *    detection command, plus a `ps -ef | grep ...` scan whose results
 *    are filtered per active-pane TTY to confirm the agent is live.
 *    Sessions whose cwd has no recent agent JSONL stay on
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

    private val agentDetector = AgentDetector()

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

                    // Issue #171 round 2: per-session agent detection via
                    // AgentDetector + JSONL candidate enumeration. The
                    // host-wide candidate scan runs once per probe, then
                    // we classify each session by matching candidates to
                    // its cwd and confirming with a per-pane TTY-scoped
                    // process line. Sessions without a match stay on
                    // SessionAgentKind.Shell (the default).
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
                val quotedCommand = shellQuote(startCommand)
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
     * Run [AgentDetector] against every session's cwd in a single SSH
     * round-trip. The detection command enumerates JSONL candidates
     * under each engine's conventional log directory across every
     * cwd we care about; the result is parsed per row and mapped back
     * to [SessionAgentKind].
     *
     * A session is promoted to an agent kind only when:
     *
     *  1. A recent (within 5 minutes) JSONL candidate exists under the
     *     engine's expected path-hint for the session's cwd, AND
     *  2. The engine's command name appears in the session's
     *     active-pane TTY-scoped process scan.
     *
     * Rule 2 follows the same `requireProcessMatch = true` discipline
     * `AgentConversationRepository.detectForPane` uses for per-pane
     * detection — a JSONL log written by an agent in a sibling pane
     * (same cwd) must NOT light up the badge on a plain-shell pane.
     */
    private suspend fun detectAgentKinds(
        session: com.pocketshell.core.ssh.SshSession,
        rows: List<FolderSessionRow>,
        paneRows: Map<String, ActivePaneRow>,
    ): Map<String, SessionAgentKind> {
        // Collect the unique cwds we want to probe. Sessions without a
        // known cwd skip detection entirely (no anchor for the engine
        // path-hint filter).
        val cwds = rows.mapNotNull { row ->
            val cwd = row.cwd?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            row.sessionName to cwd
        }
        if (cwds.isEmpty()) return emptyMap()

        val uniqueCwds = cwds.map { it.second }.distinct()
        val candidates = enumerateAgentCandidates(session, uniqueCwds)
        if (candidates.isEmpty()) return emptyMap()

        // One ps scan per host probe — TTY-filtered downstream.
        val processLines = runCatching {
            session.exec(
                "ps -eo pid,tty,comm,args 2>/dev/null | " +
                    "grep -E 'claude|codex|opencode' | grep -v grep || true",
            ).stdout.lines()
        }.getOrDefault(emptyList())

        val nowMillis = System.currentTimeMillis()
        val result = mutableMapOf<String, SessionAgentKind>()
        for ((sessionName, cwd) in cwds) {
            val sessionCandidates = candidates.filter { it.cwd == cwd }
            if (sessionCandidates.isEmpty()) continue
            val paneTty = paneRows[sessionName]?.tty?.removePrefix("/dev/")?.takeIf { it.isNotBlank() }
            val paneCommand = paneRows[sessionName]?.command.orEmpty()

            // Filter process lines to this pane's TTY when available so
            // a sibling pane's agent doesn't bleed across. Each `ps`
            // line of `pid,tty,comm,args` has the TTY as the second
            // whitespace token; we match it as a prefix of `pts/<n>`.
            val ttyFiltered = if (paneTty != null) {
                processLines.filter { line ->
                    val tokens = line.trim().split(Regex("\\s+"), limit = 3)
                    tokens.size >= 2 && tokens[1] == paneTty
                }
            } else {
                processLines
            }
            val mergedProcessLines = if (paneCommand.isBlank()) ttyFiltered else ttyFiltered + paneCommand

            val detection = agentDetector.detect(
                cwd = cwd,
                nowMillis = nowMillis,
                candidates = sessionCandidates,
                processLines = mergedProcessLines,
                requireProcessMatch = true,
            )
            if (detection != null) {
                result[sessionName] = when (detection.agent) {
                    AgentKind.ClaudeCode -> SessionAgentKind.Claude
                    AgentKind.Codex -> SessionAgentKind.Codex
                    AgentKind.OpenCode -> SessionAgentKind.OpenCode
                }
            }
        }
        return result
    }

    private suspend fun enumerateAgentCandidates(
        session: com.pocketshell.core.ssh.SshSession,
        cwds: List<String>,
    ): List<AgentLogCandidate> {
        val script = buildCandidateEnumScript(cwds)
        val result = runCatching { session.exec(script) }.getOrNull() ?: return emptyList()
        if (result.exitCode != 0 && result.stdout.isBlank()) return emptyList()
        return result.stdout
            .lineSequence()
            .mapNotNull(::parseCandidate)
            .toList()
    }

    /**
     * Build a shell script that enumerates JSONL candidates for every
     * supported engine across every requested cwd. Mirrors
     * [com.pocketshell.app.session.AgentConversationRepository.detectionCommand]
     * but emits rows for multiple cwds in a single SSH round-trip.
     *
     * Output rows are pipe-delimited: `agent|epoch_seconds|cwd|path`,
     * matching the upstream candidate parser.
     */
    private fun buildCandidateEnumScript(cwds: List<String>): String {
        val sb = StringBuilder()
        for (cwd in cwds) {
            val claudeEnc = agentDetector.encodeClaudeCwd(cwd)
            val quotedCwd = shellQuote(cwd)
            val quotedClaudeDir = shellQuote("\$HOME/.claude/projects/$claudeEnc")
            // Claude: cwd-encoded per-project dir.
            sb.append(
                """
                find $quotedClaudeDir -maxdepth 1 -type f -name '*.jsonl' -mmin -5 -print 2>/dev/null | while IFS= read -r f; do
                  mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
                  printf 'claude|%s|%s|%s\n' "${'$'}mtime" $quotedCwd "${'$'}f"
                done
                """.trimIndent(),
            ).append('\n')
        }
        // Codex + OpenCode: shared scan once per probe — the detector's
        // path-hint filter would route any matching candidate to every
        // listed cwd anyway, so we emit one row per (cwd, file). To keep
        // the script simple and the SSH round-trip cheap, the script
        // emits one canonical row per file, and the caller's per-cwd
        // filter (candidates.filter { it.cwd == cwd }) needs the rows to
        // be tagged with EACH cwd we want to inspect. We achieve that by
        // a nested loop in shell.
        val cwdList = cwds.joinToString(" ") { shellQuote(it) }
        sb.append(
            """
            codex_dir="${'$'}HOME/.codex/sessions"
            opencode_dir="${'$'}HOME/.local/share/opencode"
            for c in $cwdList; do
              find "${'$'}codex_dir" -type f -name '*.jsonl' -mmin -5 -print 2>/dev/null | while IFS= read -r f; do
                mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
                printf 'codex|%s|%s|%s\n' "${'$'}mtime" "${'$'}c" "${'$'}f"
              done
              find "${'$'}opencode_dir" -maxdepth 1 -type f -name '*.jsonl' -mmin -5 -print 2>/dev/null | while IFS= read -r f; do
                mtime=${'$'}(stat -c '%Y' "${'$'}f" 2>/dev/null) || continue
                printf 'opencode|%s|%s|%s\n' "${'$'}mtime" "${'$'}c" "${'$'}f"
              done
            done
            """.trimIndent(),
        )
        return sb.toString()
    }

    private fun parseCandidate(line: String): AgentLogCandidate? {
        val parts = line.split("|", limit = 4)
        if (parts.size != 4) return null
        val agent = when (parts[0]) {
            "claude" -> AgentKind.ClaudeCode
            "codex" -> AgentKind.Codex
            "opencode" -> AgentKind.OpenCode
            else -> return null
        }
        val seconds = parts[1].toDoubleOrNull() ?: return null
        val cwd = parts[2]
        val path = parts[3]
        return AgentLogCandidate(
            agent = agent,
            path = path,
            modifiedAtMillis = (seconds * 1000).toLong(),
            sessionId = path.substringAfterLast('/').substringBeforeLast('.'),
            cwd = cwd,
        )
    }

    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    /** Active-pane row carrying the per-session signals we use beyond cwd. */
    internal data class ActivePaneRow(
        val sessionName: String,
        val cwd: String?,
        val tty: String?,
        val command: String?,
    )

    internal companion object {
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
