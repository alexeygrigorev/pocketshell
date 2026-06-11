package com.pocketshell.app.assistant

import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.projects.FolderListResult
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.app.sessions.SharedSshLeaseManager
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.flow.first

/**
 * SSH connection parameters for the host the assistant acts against. Supplied
 * by the live session screen (it already holds the resolved key path +
 * unlocked passphrase from the nav destination).
 */
internal data class AssistantSshParams(
    val hostId: Long,
    val hostName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val passphrase: CharArray?,
)

/**
 * Opens a short-lived [SshSession] for inspect/exec tools. Seam so the
 * actions are unit-testable without a real socket.
 */
internal interface AssistantSshExecutor {
    suspend fun <T> withSession(params: AssistantSshParams, block: suspend (SshSession) -> T): Result<T>
}

/**
 * Production executor — issue #699.
 *
 * Borrows a reference-counted lease on the app-wide `@Singleton`
 * [SshLeaseManager] (resolved via [SharedSshLeaseManager] since this is
 * built field-side in view models with no DI graph access) and runs the tool's
 * exec on a channel of the host's WARM transport, releasing the refcount — NOT
 * closing the transport — when done. Previously every inspect/exec tool call
 * dialed a fresh ~3-4s SSH handshake; it now reuses the same pooled connection
 * the session screens hold, keyed identically on `"$hostId:$keyPath"`.
 */
internal class RealAssistantSshExecutor(
    private val leaseManager: SshLeaseManager = SharedSshLeaseManager.get(),
) : AssistantSshExecutor {
    override suspend fun <T> withSession(
        params: AssistantSshParams,
        block: suspend (SshSession) -> T,
    ): Result<T> =
        LeaseSessionExec.withSession(
            leaseManager = leaseManager,
            target = LeaseSessionTarget(
                hostId = params.hostId,
                hostname = params.hostname,
                port = params.port,
                username = params.username,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
            ),
            block = block,
        )
}

/**
 * Issue #699: connected-test access to the production [RealAssistantSshExecutor]
 * so a Docker instrumentation test can prove the assistant SSH path reuses the
 * app-wide warm lease instead of dialing a fresh handshake per tool call. The
 * executor + its params are `internal`, so this shim exposes the minimum the
 * test needs (build the executor on a given [SshLeaseManager], run one exec).
 */
@androidx.annotation.VisibleForTesting
internal object AssistantSshExecutorTestAccess {
    fun real(leaseManager: SshLeaseManager): AssistantSshExecutor =
        RealAssistantSshExecutor(leaseManager)

    suspend fun exec(
        executor: AssistantSshExecutor,
        hostId: Long,
        hostName: String,
        hostname: String,
        port: Int,
        username: String,
        keyPath: String,
        command: String,
    ): Result<com.pocketshell.core.ssh.ExecResult> =
        executor.withSession(
            AssistantSshParams(
                hostId = hostId,
                hostName = hostName,
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
                passphrase = null,
            ),
        ) { session -> session.exec(command) }
}

/**
 * Production [AssistantActions] bridging the agent loop onto the live app
 * surfaces (issue #266):
 *
 *  - terminal-mode `run_command` → [SessionActionBridge.sendCommand]
 *  - navigation → [SessionActionBridge.navigate]
 *  - host / folder / session inspection → [HostDao] + [FolderListGateway]
 *  - directory / file / file-create → SSH `exec` via [AssistantSshExecutor]
 *  - repos → [ReposRemoteSource] over the same SSH executor
 *
 * The terminal-mode actions (`run_command`, `create_file`) target the
 * already-connected session screen, so they go through the byte path rather
 * than opening a new connection. Inspect tools that need arbitrary hosts use
 * the executor with [AssistantSshParams].
 */
internal class AppAssistantActions(
    private val bridge: SessionActionBridge,
    private val hostDao: HostDao,
    private val folderListGateway: FolderListGateway,
    private val reposRemoteSource: ReposRemoteSource,
    private val sshExecutor: AssistantSshExecutor,
    /** Resolve SSH params for a host by saved name. Supplied by the screen. */
    private val resolveParams: suspend (hostName: String) -> AssistantSshParams?,
    /** SSH params for the active host (for path-scoped inspect/exec). */
    private val activeParams: () -> AssistantSshParams?,
    /** Extra screen-specific context appended to get_context. */
    private val extraContext: suspend () -> String = { "" },
    /** Called when a project path is created or cloned so host detail can refresh optimistically. */
    private val onProjectCreated: (String) -> Unit = {},
) : AssistantActions {

    override suspend fun getContext(): String = buildString {
        appendLine("screen: ${bridge.currentScreenLabel()}")
        appendLine("active_host: ${bridge.activeHostName() ?: "(none)"}")
        appendLine("active_session: ${bridge.activeSessionName() ?: "(none)"}")
        appendLine("cwd: ${bridge.activeCwd() ?: "(unknown)"}")
        val extra = extraContext().trim()
        if (extra.isNotEmpty()) appendLine(extra)
    }.trim()

    override suspend fun listHosts(): String {
        val hosts = runCatching { hostDao.getAll().first() }.getOrElse {
            return "Failed to read hosts: ${it.message}"
        }
        if (hosts.isEmpty()) return "No saved hosts."
        return hosts.joinToString("\n") { "${it.name} (${it.username}@${it.hostname}:${it.port})" }
    }

    override suspend fun listFolders(host: String): String {
        val params = resolveParams(host) ?: return "Unknown host: $host"
        val entity = hostEntity(params) ?: return "Unknown host: $host"
        return when (val result = folderListGateway.listSessionsWithFolder(entity, params.keyPath, params.passphrase)) {
            is FolderListResult.Sessions -> {
                val folders = result.rows.mapNotNull { it.cwd }.distinct()
                if (folders.isEmpty()) "No folders found on $host." else folders.joinToString("\n")
            }
            FolderListResult.ToolUnavailable -> "tmux is not available on $host."
            is FolderListResult.Failed -> "Failed to list folders: ${result.message}"
            is FolderListResult.ConnectFailed -> "Could not connect to $host: ${result.cause.message}"
        }
    }

    override suspend fun resolveFolder(host: String, query: String): FolderResolutionResult {
        val params = resolveParams(host) ?: return FolderResolutionResult.Unavailable("Unknown host: $host")
        val entity = hostEntity(params) ?: return FolderResolutionResult.Unavailable("Unknown host: $host")
        return when (val result = folderListGateway.listSessionsWithFolder(entity, params.keyPath, params.passphrase)) {
            is FolderListResult.Sessions -> {
                val candidates = folderCandidates(result)
                if (candidates.isEmpty()) {
                    FolderResolutionResult.Unavailable("No folders found on $host.")
                } else {
                    FolderResolutionResult.Resolved(FolderResolver.resolve(query, candidates))
                }
            }
            FolderListResult.ToolUnavailable -> FolderResolutionResult.Unavailable("tmux is not available on $host.")
            is FolderListResult.Failed ->
                FolderResolutionResult.Unavailable("Failed to read folders: ${result.message}")
            is FolderListResult.ConnectFailed ->
                FolderResolutionResult.Unavailable("Could not connect to $host: ${result.cause.message}")
        }
    }

    /**
     * Flatten the gateway probe into the FULL, untruncated candidate set the
     * resolver ranks against: every live session's cwd plus every discovered /
     * historical project folder under a watched root. Labels are the path tail
     * (matching `FolderListViewModel.defaultLabelForPath`); session counts come
     * from how many live sessions share a cwd. Crucially this never applies a
     * `take(N)` cap, so the target folder can't be silently dropped the way the
     * `get_context` summary can.
     */
    private fun folderCandidates(result: FolderListResult.Sessions): List<FolderCandidate> {
        val sessionsByPath: Map<String, Int> = result.rows
            .mapNotNull { it.cwd }
            .groupingBy { it }
            .eachCount()

        val discovered: Set<String> = buildSet {
            result.projectFoldersByRoot.values.forEach { addAll(it) }
            result.historyProjectFoldersByRoot.values.forEach { addAll(it) }
        }

        val allPaths = (sessionsByPath.keys + discovered).distinct()
        return allPaths.map { path ->
            FolderCandidate(
                path = path,
                label = labelForPath(path),
                sessionCount = sessionsByPath[path] ?: 0,
            )
        }
    }

    /** Mirrors `FolderListViewModel.defaultLabelForPath`: the trailing path segment. */
    private fun labelForPath(path: String): String {
        val tail = path.trimEnd('/').substringAfterLast('/')
        return tail.ifBlank { path }
    }

    override suspend fun listSessions(host: String): String {
        val params = resolveParams(host) ?: return "Unknown host: $host"
        val entity = hostEntity(params) ?: return "Unknown host: $host"
        return when (val result = folderListGateway.listSessionsWithFolder(entity, params.keyPath, params.passphrase)) {
            is FolderListResult.Sessions -> {
                if (result.rows.isEmpty()) "No tmux sessions on $host."
                else result.rows.joinToString("\n") {
                    "${it.sessionName} [${it.agentKind}] cwd=${it.cwd ?: "?"}"
                }
            }
            FolderListResult.ToolUnavailable -> "tmux is not available on $host."
            is FolderListResult.Failed -> "Failed to list sessions: ${result.message}"
            is FolderListResult.ConnectFailed -> "Could not connect to $host: ${result.cause.message}"
        }
    }

    override suspend fun listDirectory(path: String): String =
        execOnActive("ls -la ${shellQuote(path)}") ?: "No active host connection."

    override suspend fun readFile(path: String): String =
        execOnActive("head -c 8192 ${shellQuote(path)}") ?: "No active host connection."

    override suspend fun listRepos(): String {
        val params = activeParams() ?: return "No active host connection."
        return sshExecutor.withSession(params) { session ->
            val result = session.exec(ReposRemoteSource.pathAwareCommand("pocketshell repos list --json"))
            when {
                result.exitCode == 0 -> result.stdout.ifBlank { "No repositories." }
                result.exitCode == 127 -> "pocketshell repos is not installed on this host."
                ghUnauthenticated(result.stderr) -> "GitHub not authenticated on ${params.hostName}."
                else -> "Failed to list repos: ${result.stderr.ifBlank { result.stdout }}"
            }
        }.getOrElse { "Failed to list repos: ${it.message}" }
    }

    override suspend fun openFolder(host: String, path: String): String {
        val params = resolveParams(host) ?: return "Unknown host: $host"
        bridge.navigate(
            AppDestination.FolderList(
                hostId = params.hostId,
                hostName = params.hostName,
                hostname = params.hostname,
                port = params.port,
                username = params.username,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
            ),
        )
        return "Opened folder $path on $host."
    }

    override suspend fun openSession(sessionName: String): String {
        val params = activeParams() ?: return "No active host connection."
        bridge.navigate(
            AppDestination.TmuxSession(
                hostId = params.hostId,
                hostName = params.hostName,
                hostname = params.hostname,
                port = params.port,
                username = params.username,
                keyPath = params.keyPath,
                passphrase = params.passphrase,
                sessionName = sessionName,
            ),
        )
        return "Opening session $sessionName."
    }

    override suspend fun openScreen(destination: String): String {
        val dest = when (destination.lowercase()) {
            "hosts" -> AppDestination.HostList
            "settings" -> AppDestination.Settings
            "usage" -> AppDestination.Usage
            "ai_costs" -> AppDestination.AiCosts
            "crash_reports" -> AppDestination.CrashReports
            else -> return "Unknown screen: $destination"
        }
        bridge.navigate(dest)
        return "Opened $destination."
    }

    override suspend fun startSession(host: String, cwd: String, agent: String): ActionResult {
        val params = resolveParams(host) ?: return ActionResult.error("Unknown host: $host")
        val entity = hostEntity(params) ?: return ActionResult.error("Unknown host: $host")
        if (!isKnownAgent(agent)) return ActionResult.error("Unknown agent: $agent")
        val startCommand = startCommandFor(agent)
        val sessionName = deriveSessionName(cwd)
        val result = folderListGateway.createSession(
            host = entity,
            keyPath = params.keyPath,
            passphrase = params.passphrase,
            sessionName = sessionName,
            cwd = cwd,
            startCommand = startCommand,
        )
        return result.fold(
            onSuccess = { resolved ->
                bridge.navigate(
                    AppDestination.TmuxSession(
                        hostId = params.hostId,
                        hostName = params.hostName,
                        hostname = params.hostname,
                        port = params.port,
                        username = params.username,
                        keyPath = params.keyPath,
                        passphrase = params.passphrase,
                        sessionName = resolved,
                        startDirectory = cwd,
                    ),
                )
                ActionResult.ok("Started $agent session \"$resolved\" in $cwd on $host.")
            },
            onFailure = { ActionResult.error("Failed to start session: ${it.message}") },
        )
    }

    override suspend fun sendPromptToSession(sessionName: String, prompt: String): ActionResult {
        if (sessionName.isBlank()) return ActionResult.error("Missing target session.")
        if (prompt.isBlank()) return ActionResult.error("Missing prompt.")
        bridge.sendPromptToSession(sessionName, prompt).onSuccess {
            return ActionResult.ok("Sent prompt to session $sessionName.")
        }

        val params = activeParams()
            ?: return ActionResult.error("Failed to send prompt to session $sessionName: no active host connection.")
        return sshExecutor.withSession(params) { session ->
            val target = shellQuote(sessionName)
            val command = "tmux send-keys -t $target -l ${shellQuote(prompt)} && tmux send-keys -t $target Enter"
            val result = session.exec(command)
            if (result.exitCode == 0) {
                ActionResult.ok("Sent prompt to session $sessionName.")
            } else {
                ActionResult.error(
                    "Failed to send prompt to session $sessionName: " +
                        result.stderr.ifBlank { "exit ${result.exitCode}" },
                )
            }
        }.getOrElse {
            ActionResult.error("Failed to send prompt to session $sessionName: ${it.message ?: it.javaClass.simpleName}")
        }
    }

    override suspend fun createProject(host: String, parentPath: String, folderName: String): ActionResult {
        val params = resolveParams(host) ?: return ActionResult.error("Unknown host: $host")
        val entity = hostEntity(params) ?: return ActionResult.error("Unknown host: $host")
        val result = folderListGateway.createEmptyProject(
            host = entity,
            keyPath = params.keyPath,
            passphrase = params.passphrase,
            parentPath = parentPath,
            folderName = folderName,
        )
        return result.fold(
            onSuccess = { path ->
                onProjectCreated(path)
                ActionResult.ok("Created project $path.")
            },
            onFailure = { ActionResult.error("Failed to create project: ${it.message}") },
        )
    }

    override suspend fun runCommand(command: String): ActionResult {
        // Safety already validated by the loop before this call.
        if (bridge.activeHostName() == null) {
            return ActionResult.error("No active terminal to run the command in.")
        }
        return bridge.sendCommand(command).fold(
            onSuccess = { ActionResult.ok("Ran: $command") },
            onFailure = {
                ActionResult.error(
                    "Failed to send command to the active terminal: ${it.message ?: it.javaClass.simpleName}",
                )
            },
        )
    }

    override suspend fun createFile(path: String, content: String): ActionResult {
        val params = activeParams() ?: return ActionResult.error("No active host connection.")
        return sshExecutor.withSession(params) { session ->
            // Write via a here-doc piped to the file; the content reaches the
            // remote over the exec channel's command string. The path is
            // shell-quoted so an unusual path cannot break the redirect.
            val heredoc = buildString {
                append("cat > ")
                append(shellQuote(path))
                append(" <<'POCKETSHELL_EOF'\n")
                append(content)
                if (!content.endsWith("\n")) append("\n")
                append("POCKETSHELL_EOF\n")
            }
            val result = session.exec(heredoc)
            if (result.exitCode == 0) {
                ActionResult.ok("Created $path.")
            } else {
                ActionResult.error("Failed to create $path: ${result.stderr.ifBlank { "exit ${result.exitCode}" }}")
            }
        }.getOrElse { ActionResult.error("Failed to create $path: ${it.message}") }
    }

    override suspend fun cloneRepo(fullName: String, folder: String?): ActionResult {
        val params = activeParams() ?: return ActionResult.error("No active host connection.")
        return sshExecutor.withSession(params) { session ->
            val root = folder?.takeIf { it.isNotBlank() } ?: "~/git"
            val command = ReposRemoteSource.pathAwareCommand(
                "pocketshell repos clone ${shellQuote(fullName)} --root ${shellQuote(root)} --protocol ssh",
            )
            val result = session.exec(command)
            when {
                result.exitCode == 0 -> {
                    val path = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
                    path?.let(onProjectCreated)
                    ActionResult.ok("Cloned $fullName to ${path ?: root}.")
                }
                result.exitCode == 127 ->
                    ActionResult.error("pocketshell repos is not installed on ${params.hostName}.")
                ghUnauthenticated(result.stderr) ->
                    ActionResult.error("GitHub not authenticated on ${params.hostName}.")
                else ->
                    ActionResult.error("Failed to clone $fullName: ${result.stderr.ifBlank { result.stdout }}")
            }
        }.getOrElse { ActionResult.error("Failed to clone $fullName: ${it.message}") }
    }

    private suspend fun execOnActive(command: String): String? {
        val params = activeParams() ?: return null
        return sshExecutor.withSession(params) { session ->
            val result = session.exec(ReposRemoteSource.pathAwareCommand(command))
            result.stdout.ifBlank { result.stderr }.ifBlank { "(no output, exit ${result.exitCode})" }
        }.getOrElse { "Command failed: ${it.message}" }
    }

    /**
     * Resolve the real [HostEntity] (it carries the `keyId` foreign key the
     * gateway needs) from the DB by the id the screen supplied. Returns null
     * when the host row is gone.
     */
    private suspend fun hostEntity(params: AssistantSshParams): HostEntity? =
        runCatching { hostDao.getById(params.hostId) }.getOrNull()

    private fun isKnownAgent(agent: String): Boolean =
        agent.lowercase() in setOf("claude", "codex", "opencode", "shell")

    /**
     * Map an agent choice to the CLI launched in the new pane. `shell` ⇒
     * null (a plain shell session — no agent CLI is run).
     */
    private fun startCommandFor(agent: String): String? = when (agent.lowercase()) {
        "claude" -> "claude"
        "codex" -> "codex"
        "opencode" -> "opencode"
        else -> null
    }

    private fun deriveSessionName(cwd: String): String {
        val base = cwd.trimEnd('/').substringAfterLast('/').ifBlank { "session" }
        return base.replace(Regex("[^A-Za-z0-9_-]"), "_").take(40)
    }

    private fun ghUnauthenticated(stderr: String): Boolean =
        stderr.contains("not authenticated", ignoreCase = true) ||
            stderr.contains("gh auth", ignoreCase = true) ||
            stderr.contains("authentication required", ignoreCase = true)

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"
}
