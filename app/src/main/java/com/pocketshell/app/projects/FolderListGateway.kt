package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.repos.ReposListResult
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.remoteStartDirectoryExists
import com.pocketshell.app.sessions.startDirectoryMissingMessage
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.portfwd.PortScanner
import com.pocketshell.core.portfwd.RemotePort
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
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
    val windows: List<FolderSessionWindowRow> = emptyList(),
)

/**
 * Compact per-window metadata for a tmux session. The folder list uses
 * one active pane per tmux window to expose enough identity for
 * multi-window sessions without becoming a window manager.
 */
data class FolderSessionWindowRow(
    val sessionName: String,
    val index: Int?,
    val name: String?,
    val active: Boolean,
    val cwd: String?,
    val tty: String?,
    val command: String?,
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
    data class Sessions(
        val rows: List<FolderSessionRow>,
        val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
        val discoveredPorts: List<RemotePort> = emptyList(),
    ) : FolderListResult
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
 *  - `tmux list-panes -a -F '#{session_name}\t#{window_index}\t
 *    #{window_name}\t#{window_active}\t#{pane_active}\t
 *    #{pane_current_path}\t#{pane_tty}\t#{pane_current_command}'` so
 *    the active window's active-pane cwd + TTY + foreground command
 *    supersede `session_path` when they disagree, while every window's
 *    active pane remains available for compact metadata.
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
        watchedRoots: List<ProjectRootEntity> = emptyList(),
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

    suspend fun createEmptyProject(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        parentPath: String,
        folderName: String,
    ): Result<String>

    suspend fun importFile(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        folderPath: String,
        payload: FolderImportPayload,
    ): Result<String>
}

data class FolderImportPayload(
    val remoteName: String,
    val length: Long?,
    val openStream: () -> InputStream?,
)

class SshFolderListGateway @Inject constructor(
    private val reposRemoteSource: ReposRemoteSource,
    private val activeTmuxClients: ActiveTmuxClients,
    private val sshLeaseManager: SshLeaseManager = SshLeaseManager(
        connector = SshLeaseConnector { target ->
            com.pocketshell.core.ssh.DefaultSshLeaseConnector().connect(target)
        },
    ),
    private val sessionListParser: HostTmuxSessionListParser = HostTmuxSessionListParser(),
) : FolderListGateway {

    constructor() : this(
        ReposRemoteSource(ReposJsonParser()),
        ActiveTmuxClients(),
        SshLeaseManager(
            connector = SshLeaseConnector { target ->
                com.pocketshell.core.ssh.DefaultSshLeaseConnector().connect(target)
            },
        ),
        HostTmuxSessionListParser(),
    )

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
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult {
        if (watchedRoots.isEmpty()) {
            listSessionsWithFolderFromLiveClient(host, keyPath)?.let { return it }
        }

        return try {
            withLeaseSession(
                host = host,
                keyPath = keyPath,
                passphrase = passphrase,
            ) { session ->
                val listSessions = session.exec(pathAware(LIST_SESSIONS_COMMAND))
                listSessionsFromNativeOrPocketshell(session, host, watchedRoots, listSessions)
            }.fold(
                onSuccess = { it },
                onFailure = { error -> FolderListResult.ConnectFailed(error) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FolderListResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        }
    }

    private suspend fun <T> withLeaseSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        block: suspend (SshSession) -> T,
    ): Result<T> {
        val lease = try {
            sshLeaseManager.acquire(host.toSshLeaseTarget(keyPath, passphrase))
                .getOrElse { return Result.failure(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        return try {
            Result.success(block(lease.session))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Result.failure(t)
        } finally {
            withContext(NonCancellable) {
                lease.release()
            }
        }
    }

    private fun HostEntity.toSshLeaseTarget(
        keyPath: String,
        passphrase: CharArray?,
    ): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = "$id:$keyPath",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

    internal suspend fun listSessionsFromNativeOrPocketshell(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
        listSessions: ExecResult,
    ): FolderListResult {
        return when {
            listSessions.exitCode == 127 ||
                listSessions.stderr.contains("not found", ignoreCase = true) ->
                listSessionsWithFolderFromPocketshell(session, host, watchedRoots)
                    ?: FolderListResult.ToolUnavailable
            listSessions.isTmuxServerAbsent() ->
                listSessionsWithFolderFromPocketshell(session, host, watchedRoots)
                    ?: sessionsWithWatchedRootExpansion(
                        session = session,
                        host = host,
                        watchedRoots = watchedRoots,
                        rows = emptyList(),
                    )
            listSessions.exitCode != 0 ->
                listSessionsWithFolderFromPocketshell(session, host, watchedRoots)
                    ?: FolderListResult.Failed(
                        listSessions.stderr.ifBlank { listSessions.stdout }
                            .ifBlank { "tmux exited ${listSessions.exitCode}" },
                    )
            else -> {
                val baseRows = parseListSessionsRows(listSessions.stdout)
                val windowRows = runCatching {
                    val listPanes = session.exec(pathAware(LIST_PANES_COMMAND))
                    if (listPanes.exitCode == 0) parseSessionWindowRows(listPanes.stdout) else emptyList()
                }.getOrDefault(emptyList())
                val paneRows = activePaneRowsBySession(windowRows)
                val windowsBySession = windowRows.groupBy { it.sessionName }

                // Merge active-pane data into each session row first.
                val merged = baseRows.map { row ->
                    val pane = paneRows[row.sessionName]
                    val cwd = pane?.cwd ?: row.cwd
                    row.copy(cwd = cwd, windows = windowsBySession[row.sessionName].orEmpty())
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
                    )
                }.getOrDefault(FolderAgentDetection())

                val annotated = merged.map { row ->
                    val windows = row.windows.map { window ->
                        val key = WindowProbeKey(row.sessionName, window.index)
                        window.copy(agentKind = agentKinds.windowKinds[key] ?: SessionAgentKind.Shell)
                    }
                    row.copy(
                        agentKind = agentKinds.sessionKinds[row.sessionName] ?: SessionAgentKind.Shell,
                        windows = windows,
                    )
                }
                sessionsWithWatchedRootExpansion(
                    session = session,
                    host = host,
                    watchedRoots = watchedRoots,
                    rows = annotated,
                )
            }
        }
    }

    private suspend fun listSessionsWithFolderFromPocketshell(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult.Sessions? {
        val pocketshell = session.exec(pathAware(POCKETSHELL_SESSIONS_COMMAND))
        if (pocketshell.exitCode != 0) {
            if (pocketshell.isTmuxServerAbsent()) {
                return sessionsWithWatchedRootExpansion(
                    session = session,
                    host = host,
                    watchedRoots = watchedRoots,
                    rows = emptyList(),
                )
            }
            return null
        }
        return sessionsWithWatchedRootExpansion(
            session = session,
            host = host,
            watchedRoots = watchedRoots,
            rows = parsePocketshellSessionsRows(pocketshell.stdout, sessionListParser),
        )
    }

    private fun ExecResult.isTmuxServerAbsent(): Boolean {
        val output = "$stdout\n$stderr"
        return output.contains("no server running", ignoreCase = true) ||
            (
                output.contains("error connecting to", ignoreCase = true) &&
                    output.contains("tmux-", ignoreCase = true) &&
                    output.contains("No such file or directory", ignoreCase = true)
                )
    }

    private suspend fun sessionsWithWatchedRootExpansion(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
        rows: List<FolderSessionRow>,
    ): FolderListResult.Sessions {
        val expansion = expandWatchedRootProjects(
            session = session,
            host = host,
            watchedRoots = watchedRoots,
        )
        val discoveredPorts = runCatching { PortScanner.scan(session) }.getOrDefault(emptyList())
        return FolderListResult.Sessions(
            rows = rows,
            projectFoldersByRoot = expansion.projectFoldersByRoot,
            historyProjectFoldersByRoot = expansion.historyProjectFoldersByRoot,
            resolvedWatchedRootPaths = expansion.resolvedWatchedRootPaths,
            discoveredPorts = discoveredPorts,
        )
    }

    private suspend fun expandWatchedRootProjects(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
    ): WatchedRootProjectExpansion {
        if (watchedRoots.isEmpty()) return WatchedRootProjectExpansion()
        val namespace = "${host.id}:${host.username}@${host.hostname}:${host.port}"
        val rootPaths = watchedRoots
            .mapNotNull { it.path.trim().takeIf { path -> path.isNotEmpty() } }
            .distinct()
        val remoteHome = if (rootPaths.any(::usesHomeShortcut)) remoteHomeDirectory(session) else null

        val projectFoldersByRoot = mutableMapOf<String, List<String>>()
        val historyProjectFoldersByRoot = mutableMapOf<String, List<String>>()
        val resolvedWatchedRootPaths = mutableMapOf<String, String>()
        val historyPaths = listProjectHistoryFromPocketshellLogs(session)
        for (rootPath in rootPaths) {
            val resolvedRootPath = expandRemoteHomeShortcut(rootPath, remoteHome)
            resolvedWatchedRootPaths[rootPath] = resolvedRootPath
            val paths = when (
                val result = reposRemoteSource.listLocalRoot(
                    session = session,
                    root = resolvedRootPath,
                    cacheNamespace = namespace,
                )
            ) {
                is ReposListResult.Success -> result.repos.mapNotNull { repo ->
                    repo.local?.path?.trim()?.takeIf { it.isNotEmpty() }
                }
                ReposListResult.ToolMissing,
                is ReposListResult.Failed,
                -> emptyList()
            }
            projectFoldersByRoot[rootPath] = paths.distinct()
            historyProjectFoldersByRoot[rootPath] = historyPaths
                .filter { pathWithinRoot(it, resolvedRootPath) }
                .map { projectPathUnderRoot(it, resolvedRootPath) }
                .distinct()
        }
        return WatchedRootProjectExpansion(
            projectFoldersByRoot = projectFoldersByRoot,
            historyProjectFoldersByRoot = historyProjectFoldersByRoot,
            resolvedWatchedRootPaths = resolvedWatchedRootPaths,
        )
    }

    private suspend fun listProjectHistoryFromPocketshellLogs(session: SshSession): List<String> {
        val result = session.exec(pathAware(POCKETSHELL_PROJECT_HISTORY_COMMAND))
        if (result.exitCode != 0 || result.isPocketshellLogsMissing()) return emptyList()
        return parsePocketshellProjectHistory(result.stdout)
    }

    private suspend fun remoteHomeDirectory(session: SshSession): String? {
        val result = session.exec(pathAware("printf '%s\\n' \"\$HOME\""))
        if (result.exitCode != 0) return null
        return result.stdout.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
    }

    private fun usesHomeShortcut(path: String): Boolean =
        path == "~" || path.startsWith("~/")

    private fun expandRemoteHomeShortcut(path: String, remoteHome: String?): String {
        val clean = path.trim().trimEnd('/').ifBlank { path.trim() }
        val home = remoteHome?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        return when {
            home == null -> clean
            clean == "~" -> home
            clean.startsWith("~/") -> home + "/" + clean.removePrefix("~/")
            else -> clean
        }
    }

    private fun pathWithinRoot(path: String, root: String): Boolean {
        val cleanPath = canonicalRemotePath(path)
        val cleanRoot = canonicalRemotePath(root)
        return cleanPath == cleanRoot || cleanPath.startsWith(cleanRoot.trimEnd('/') + "/")
    }

    private fun projectPathUnderRoot(path: String, root: String): String {
        val cleanPath = canonicalRemotePath(path)
        val cleanRoot = canonicalRemotePath(root)
        if (cleanPath == cleanRoot) return cleanRoot
        val prefix = cleanRoot.trimEnd('/') + "/"
        val child = cleanPath.removePrefix(prefix).substringBefore('/').ifBlank { return cleanRoot }
        return prefix + child
    }

    private fun canonicalRemotePath(path: String): String {
        val clean = path.trim().trimEnd('/')
        return clean.ifEmpty { "/" }
    }

    override suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
    ): Result<String> {
        return withLeaseSession(host, keyPath, passphrase) { session ->
            if (!remoteStartDirectoryExists(session, cwd)) {
                throw RuntimeException(
                    startDirectoryMissingMessage(
                        sessionName = sessionName,
                        startDirectory = cwd,
                    ),
                )
            }
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
                throw RuntimeException(createResult.stderr.trim())
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
            sessionName
        }
    }

    override suspend fun createEmptyProject(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        parentPath: String,
        folderName: String,
    ): Result<String> {
        val safeName = normaliseProjectFolderName(folderName)
            ?: return Result.failure(IllegalArgumentException("Enter a project folder name."))
        val child = childPath(parentPath, safeName)
        return withLeaseSession(host, keyPath, passphrase) { session ->
            val result = session.exec(pathAware("mkdir -p -- ${shellQuoteRemotePath(child)}"))
            if (result.exitCode == 0) {
                resolveRemoteDirectory(session, child).getOrDefault(child)
            } else {
                throw RuntimeException(result.stderr.ifBlank { result.stdout }.trim())
            }
        }
    }

    override suspend fun importFile(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        folderPath: String,
        payload: FolderImportPayload,
    ): Result<String> {
        return withLeaseSession(host, keyPath, passphrase) { session ->
            val mkdir = session.exec(pathAware("mkdir -p -- ${shellQuoteRemotePath(folderPath)}"))
            if (mkdir.exitCode != 0) {
                throw RuntimeException(mkdir.stderr.ifBlank { mkdir.stdout }.trim())
            }
            val resolvedFolderPath = resolveRemoteDirectory(session, folderPath)
                .getOrThrow()
            val remotePath = childPath(resolvedFolderPath, payload.remoteName)
            val input = payload.openStream()
                ?: throw RuntimeException("Couldn't read selected file.")
            input.use { stream ->
                session.uploadStream(
                    input = stream,
                    length = payload.length ?: -1L,
                    name = payload.remoteName,
                    remotePath = remotePath,
                )
            }
            remotePath
        }
    }

    /**
     * Classify every session window's active pane by delegating to the
     * SAME detector the Conversation view uses. This keeps issue #252's
     * constant host-wide probe count while letting the project tree show
     * compact per-window agent hints for multi-window sessions.
     */
    private suspend fun detectAgentKinds(
        session: com.pocketshell.core.ssh.SshSession,
        rows: List<FolderSessionRow>,
    ): FolderAgentDetection {
        val probeKeys = mutableMapOf<String, WindowProbeKey>()
        val probes = rows.flatMap { row ->
            row.windows.mapNotNull { window ->
                val cwd = window.cwd?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val paneTty = window.tty?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val key = WindowProbeKey(row.sessionName, window.index)
                val probeKey = key.asProbeKey()
                probeKeys[probeKey] = key
                AgentConversationRepository.PaneProbe(
                    key = probeKey,
                    cwd = cwd,
                    paneTty = paneTty,
                    paneCommand = window.command.orEmpty(),
                )
            }
        }
        if (probes.isEmpty()) return FolderAgentDetection()

        val detections = agentRepository.detectForPanes(
            session = session,
            panes = probes,
        )
        val windowKinds = detections.mapNotNull { (probeKey, detection) ->
            val key = probeKeys[probeKey] ?: return@mapNotNull null
            key to detection.agent.toSessionAgentKind()
        }.toMap()
        val sessionKinds = rows.associate { row ->
            val activeWindowKind = row.windows
                .firstOrNull { it.active }
                ?.let { windowKinds[WindowProbeKey(row.sessionName, it.index)] }
            val anyAgentKind = row.windows
                .asSequence()
                .mapNotNull { windowKinds[WindowProbeKey(row.sessionName, it.index)] }
                .firstOrNull { it != SessionAgentKind.Shell }
            row.sessionName to (activeWindowKind ?: anyAgentKind ?: SessionAgentKind.Shell)
        }
        return FolderAgentDetection(
            sessionKinds = sessionKinds,
            windowKinds = windowKinds,
        )
    }

    private fun AgentKind.toSessionAgentKind(): SessionAgentKind =
        when (this) {
            AgentKind.ClaudeCode -> SessionAgentKind.Claude
            AgentKind.Codex -> SessionAgentKind.Codex
            AgentKind.OpenCode -> SessionAgentKind.OpenCode
        }

    private data class FolderAgentDetection(
        val sessionKinds: Map<String, SessionAgentKind> = emptyMap(),
        val windowKinds: Map<WindowProbeKey, SessionAgentKind> = emptyMap(),
    )

    private data class WindowProbeKey(
        val sessionName: String,
        val windowIndex: Int?,
    ) {
        fun asProbeKey(): String = "$sessionName$FIELD_SEP${windowIndex ?: "active"}"
    }

    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)

    private fun shellQuote(value: String): String = shellQuoteValue(value)

    private suspend fun listSessionsWithFolderFromLiveClient(
        host: HostEntity,
        keyPath: String,
    ): FolderListResult? {
        val entry = activeTmuxClients.clients.value[host.id]
            ?.takeIf { it.matches(host, keyPath) }
            ?.takeUnless { it.client.disconnected.value }
            ?: return null
        return try {
            val listSessions = entry.client.sendCommand(CONTROL_LIST_SESSIONS_COMMAND)
            when {
                listSessions.isError &&
                    listSessions.output.joinToString("\n").contains("no server running", ignoreCase = true) ->
                    FolderListResult.Sessions(rows = emptyList())
                listSessions.isError -> null
                else -> {
                    val baseRows = parseListSessionsRows(listSessions.output.joinToString(separator = "\n"))
                    val windowRows = runCatching {
                        val listPanes = entry.client.sendCommand(CONTROL_LIST_PANES_COMMAND)
                        if (!listPanes.isError) {
                            parseSessionWindowRows(listPanes.output.joinToString("\n"))
                        } else {
                            emptyList()
                        }
                    }.getOrDefault(emptyList())
                    val paneRows = activePaneRowsBySession(windowRows)
                    val windowsBySession = windowRows.groupBy { it.sessionName }

                    val rows = baseRows.map { row ->
                        val pane = paneRows[row.sessionName]
                        row.copy(
                            cwd = pane?.cwd ?: row.cwd,
                            agentKind = SessionAgentKind.Shell,
                            windows = windowsBySession[row.sessionName].orEmpty(),
                        )
                    }
                    FolderListResult.Sessions(rows = rows)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun ActiveTmuxClients.Entry.matches(host: HostEntity, keyPath: String): Boolean =
        hostname == host.hostname &&
            port == host.port &&
            username == host.username &&
            this.keyPath == keyPath

    private fun shellQuoteRemotePath(value: String): String =
        shellQuoteRemotePathValue(value)

    private suspend fun resolveRemoteDirectory(
        session: com.pocketshell.core.ssh.SshSession,
        path: String,
    ): Result<String> {
        val result = session.exec(pathAware("cd -- ${shellQuoteRemotePath(path)} && pwd -P"))
        return if (result.exitCode == 0) {
            Result.success(result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty())
        } else {
            Result.failure(RuntimeException(result.stderr.ifBlank { result.stdout }.trim()))
        }
    }

    /** Active-pane row carrying the per-session signals we use beyond cwd. */
    internal data class ActivePaneRow(
        val sessionName: String,
        val cwd: String?,
        val tty: String?,
        val command: String?,
        val windowIndex: Int? = null,
        val windowName: String? = null,
    )

    private data class WatchedRootProjectExpansion(
        val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
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

        internal fun shellQuoteRemotePathValue(value: String): String {
            val trimmed = value.trim().ifBlank { "~" }
            return when {
                trimmed == "~" || trimmed == "\$HOME" -> "\$HOME"
                trimmed.startsWith("~/") -> "\$HOME/" + shellQuoteValue(trimmed.removePrefix("~/"))
                trimmed.startsWith("\$HOME/") -> "\$HOME/" + shellQuoteValue(trimmed.removePrefix("\$HOME/"))
                else -> shellQuoteValue(trimmed)
            }
        }

        internal fun normaliseProjectFolderName(value: String): String? {
            val trimmed = value.trim().trim('/')
            if (trimmed.isBlank()) return null
            if (trimmed == "." || trimmed == "..") return null
            if ('/' in trimmed || '\\' in trimmed) return null
            return trimmed
        }

        internal fun childPath(parentPath: String, childName: String): String {
            val parent = parentPath.trim().trimEnd('/')
            return if (parent.isEmpty() || parent == "/") "/$childName" else "$parent/$childName"
        }

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
                "'#{session_name}$FIELD_SEP#{window_index}$FIELD_SEP#{window_name}$FIELD_SEP" +
                "#{window_active}$FIELD_SEP#{pane_active}$FIELD_SEP" +
                "#{pane_current_path}$FIELD_SEP#{pane_tty}$FIELD_SEP#{pane_current_command}'"

        const val POCKETSHELL_SESSIONS_COMMAND: String = "pocketshell sessions list --by activity"
        const val POCKETSHELL_PROJECT_HISTORY_COMMAND: String =
            "pocketshell logs tail --kind agent --json -n 200"

        const val CONTROL_LIST_SESSIONS_COMMAND: String =
            "list-sessions -F " +
                "'#{session_name}$FIELD_SEP#{session_created}$FIELD_SEP" +
                "#{session_activity}$FIELD_SEP#{session_attached}$FIELD_SEP#{session_path}'"

        const val CONTROL_LIST_PANES_COMMAND: String =
            "list-panes -a -F " +
                "'#{session_name}$FIELD_SEP#{window_index}$FIELD_SEP#{window_name}$FIELD_SEP" +
                "#{window_active}$FIELD_SEP#{pane_active}$FIELD_SEP" +
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

        internal fun parsePocketshellSessionsRows(
            stdout: String,
            parser: HostTmuxSessionListParser = HostTmuxSessionListParser(),
        ): List<FolderSessionRow> =
            parser.parsePocketshellSessionsList(stdout).map { row ->
                FolderSessionRow(
                    sessionName = row.name,
                    lastActivity = row.lastActivity,
                    attached = row.attached,
                    cwd = null,
                    agentKind = SessionAgentKind.Shell,
                )
            }

        internal fun parsePocketshellProjectHistory(stdout: String): List<String> {
            val array = try {
                JSONArray(stdout)
            } catch (_: Throwable) {
                return emptyList()
            }
            val recentFirst = (array.length() - 1 downTo 0)
            val seen = linkedSetOf<String>()
            for (index in recentFirst) {
                val item = array.optJSONObject(index) ?: continue
                val cwd = item.stringOrNull("cwd")
                    ?: item.optJSONObject("detail")?.stringOrNull("cwd")
                    ?: item.stringOrNull("project_path")
                    ?: item.stringOrNull("worktree")
                    ?: item.optJSONObject("detail")?.stringOrNull("project_path")
                    ?: item.optJSONObject("detail")?.stringOrNull("worktree")
                    ?: continue
                val clean = cwd.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: continue
                seen += clean.ifEmpty { "/" }
            }
            return seen.toList()
        }

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
         * Parse `list-panes -a` output into compact per-window rows. The
         * current command emits one row per pane with window identity;
         * only the active pane in each window is kept. The legacy 5-field
         * shape remains accepted for parser tests and live-client drift.
         */
        internal fun parseSessionWindowRows(stdout: String): List<FolderSessionWindowRow> {
            val lines = stdout.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) return emptyList()
            val firstParts = lines.first().split(FIELD_SEP, limit = 8)
            if (firstParts.size < 8) return parseLegacySessionWindowRows(lines)

            val rows = mutableListOf<FolderSessionWindowRow>()
            for (line in lines) {
                val parts = line.split(FIELD_SEP, limit = 8)
                if (parts.size < 8) continue
                val sessionName = parts[0].trim()
                if (sessionName.isEmpty()) continue
                val paneActive = (parts[4].trim().toLongOrNull() ?: 0L) > 0L
                if (!paneActive) continue
                rows += FolderSessionWindowRow(
                    sessionName = sessionName,
                    index = parts[1].trim().toIntOrNull(),
                    name = parts[2].trim().takeIf { it.isNotEmpty() },
                    active = (parts[3].trim().toLongOrNull() ?: 0L) > 0L,
                    cwd = parts[5].trim().takeIf { it.isNotEmpty() },
                    tty = parts[6].trim().takeIf { it.isNotEmpty() },
                    command = parts[7].trim().takeIf { it.isNotEmpty() },
                )
            }
            return rows
        }

        private fun parseLegacySessionWindowRows(lines: List<String>): List<FolderSessionWindowRow> {
            val rows = mutableListOf<FolderSessionWindowRow>()
            for (line in lines) {
                val parts = line.split(FIELD_SEP, limit = 5)
                if (parts.size < 3) continue
                val sessionName = parts[0].trim()
                if (sessionName.isEmpty()) continue
                val active = (parts[1].trim().toLongOrNull() ?: 0L) > 0L
                if (!active) continue
                rows += FolderSessionWindowRow(
                    sessionName = sessionName,
                    index = null,
                    name = null,
                    active = true,
                    cwd = parts.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() },
                    tty = parts.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() },
                    command = parts.getOrNull(4)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            return rows
        }

        internal fun activePaneRowsBySession(
            windows: List<FolderSessionWindowRow>,
        ): Map<String, ActivePaneRow> =
            windows
                .groupBy { it.sessionName }
                .mapValues { (_, rows) ->
                    val row = rows.firstOrNull { it.active } ?: rows.first()
                    ActivePaneRow(
                        sessionName = row.sessionName,
                        cwd = row.cwd,
                        tty = row.tty,
                        command = row.command,
                        windowIndex = row.index,
                        windowName = row.name,
                    )
                }

        /**
         * Parse `list-panes -a` output into a map from session name to
         * the active window's active-pane metadata.
         */
        internal fun parseActivePaneRows(stdout: String): Map<String, ActivePaneRow> =
            activePaneRowsBySession(parseSessionWindowRows(stdout))

        private fun JSONObject.stringOrNull(name: String): String? =
            when (val value = opt(name)) {
                null, JSONObject.NULL -> null
                is String -> value.takeIf { it.isNotBlank() }
                else -> null
            }
    }
}

private fun ExecResult.isPocketshellLogsMissing(): Boolean {
    if (exitCode == 127) return true
    val output = "$stderr\n$stdout"
    return output.contains("No such command 'logs'", ignoreCase = true) ||
        output.contains("No such command \"logs\"", ignoreCase = true)
}
