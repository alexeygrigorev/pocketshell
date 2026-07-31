package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposListResult
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.CancellationException

/**
 * Owns the batched, mobile-RTT-aware host reads used by one folder reconcile.
 *
 * The required tmux enumeration and optional host-CLI decoration use two
 * sectioned execs. The required batch always completes first, so a slow
 * optional command cannot starve it on a high-RTT shared transport; the
 * optional batch then degrades independently at the unchanged per-exec bound.
 * This collapses the old per-root serial chain to a bounded number of SSH
 * channels while keeping the 12-second user-visible reconcile bound unchanged.
 */
internal class FolderListLandingProbeOwner(
    private val reposRemoteSource: ReposRemoteSource,
) {
    suspend fun execute(
        watchedRoots: List<ProjectRootEntity>,
        includeEnumeration: Boolean,
        exec: suspend (String) -> ExecResult,
    ): FolderListLandingProbe = executeOptional(
        watchedRoots = watchedRoots,
        required = executeRequired(watchedRoots, includeEnumeration, exec),
        exec = exec,
    )

    /**
     * Completes the small, correctness-critical landing batch before any
     * best-effort work is allowed onto the shared SSH transport.
     */
    suspend fun executeRequired(
        watchedRoots: List<ProjectRootEntity>,
        includeEnumeration: Boolean,
        exec: suspend (String) -> ExecResult,
    ): FolderListLandingProbe {
        val rootPaths = watchedRootPaths(watchedRoots)
        val requiredSections = buildList {
            if (includeEnumeration) {
                add(LandingSection.ListSessions to SshFolderListGateway.LIST_SESSIONS_COMMAND)
                add(LandingSection.ListPanes to SshFolderListGateway.LIST_PANES_COMMAND)
            }
            if (rootPaths.isNotEmpty()) {
                add(LandingSection.RemoteHome to "printf '%s\\n' \"\$HOME\"")
            }
        }
        val sections = execSections(requiredSections, exec)
        return FolderListLandingProbe(
            listSessions = sections[LandingSection.ListSessions] ?: ExecResult("", "", 0),
            listPanes = sections[LandingSection.ListPanes]
                ?.takeIf { it.exitCode == 0 }
                ?: ExecResult("", "", 0),
            remoteHome = sections[LandingSection.RemoteHome]?.let(::parseRemoteHome),
            projectHistory = emptyList(),
            rootPayloads = emptyMap(),
        )
    }

    /**
     * Adds best-effort history/repository decoration to an already completed
     * required probe. Callers may overlap this phase with kind and port probes.
     */
    suspend fun executeOptional(
        watchedRoots: List<ProjectRootEntity>,
        required: FolderListLandingProbe,
        exec: suspend (String) -> ExecResult,
    ): FolderListLandingProbe {
        val rootPaths = watchedRootPaths(watchedRoots)
        val optionalSections = buildList {
            if (rootPaths.isNotEmpty()) {
                add(
                    LandingSection.ProjectHistory to
                        quiet(SshFolderListGateway.POCKETSHELL_PROJECT_HISTORY_COMMAND),
                )
                rootPaths.forEach { rootPath ->
                    add(
                        LandingSection.RootRepos(rootPath) to quiet(
                            "pocketshell repos list --local --json --root " +
                                watchedRootShellExpr(rootPath),
                        ),
                    )
                }
            }
        }

        val optional = runCatching { execSections(optionalSections, exec) }
            .getOrElse { error ->
                if (error is CancellationException) throw error
                emptyMap()
            }
        return required.copy(
            projectHistory = optional[LandingSection.ProjectHistory]
                ?.takeIf { it.exitCode == 0 && !it.isPocketshellLogsMissing() }
                ?.let { SshFolderListGateway.parsePocketshellProjectHistory(it.stdout) }
                .orEmpty(),
            rootPayloads = optional
                .filterKeys { it is LandingSection.RootRepos }
                .mapKeys { (key, _) -> (key as LandingSection.RootRepos).rootPath },
        )
    }

    fun buildWatchedRootExpansion(
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
        probe: FolderListLandingProbe,
    ): WatchedRootProjectExpansion {
        val rootPaths = watchedRootPaths(watchedRoots)
        if (rootPaths.isEmpty()) return WatchedRootProjectExpansion()
        val namespace = "${host.id}:${host.username}@${host.hostname}:${host.port}"

        val projectFoldersByRoot = mutableMapOf<String, List<String>>()
        val historyProjectFoldersByRoot = mutableMapOf<String, List<String>>()
        val resolvedWatchedRootPaths = mutableMapOf<String, String>()
        rootPaths.forEach { rootPath ->
            val resolvedRootPath = expandRemoteHomeShortcut(rootPath, probe.remoteHome)
            resolvedWatchedRootPaths[rootPath] = resolvedRootPath
            val payload = probe.rootPayloads[rootPath]
            val repos = if (payload == null) {
                reposRemoteSource.cachedLocalRoot(resolvedRootPath, namespace).orEmpty()
            } else {
                when (
                    val adopted = reposRemoteSource.adoptLocalRootPayload(
                        root = resolvedRootPath,
                        cacheNamespace = namespace,
                        exitCode = payload.exitCode,
                        stdout = payload.stdout,
                        stderr = payload.stderr,
                    )
                ) {
                    is ReposListResult.Success -> adopted.repos
                    ReposListResult.ToolMissing, is ReposListResult.Failed ->
                        reposRemoteSource.cachedLocalRoot(resolvedRootPath, namespace).orEmpty()
                }
            }
            projectFoldersByRoot[rootPath] = repos
                .mapNotNull { repo -> repo.local?.path?.trim()?.takeIf(String::isNotEmpty) }
                .distinct()
            historyProjectFoldersByRoot[rootPath] = probe.projectHistory
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

    private suspend fun execSections(
        sections: List<Pair<LandingSection, String>>,
        exec: suspend (String) -> ExecResult,
    ): Map<LandingSection, ExecResult> {
        if (sections.isEmpty()) return emptyMap()
        val chained = sections.joinToString(" ; ") { (_, command) ->
            "$command ; printf '\\n%s %s\\n' ${SshFolderListGateway.ENUMERATION_MARKER} \"\$?\""
        }
        val result = exec(chained)
        val parsed = splitLandingSections(result.stdout)
        if (parsed.isEmpty()) return mapOf(sections.first().first to result)
        return sections.withIndex().mapNotNull { (index, entry) ->
            parsed.getOrNull(index)?.let { (text, rc) ->
                entry.first to ExecResult(
                    stdout = text,
                    stderr = result.stderr,
                    exitCode = rc ?: result.exitCode,
                )
            }
        }.toMap()
    }
}

internal data class FolderListLandingProbe(
    val listSessions: ExecResult,
    val listPanes: ExecResult,
    val remoteHome: String?,
    val projectHistory: List<String>,
    val rootPayloads: Map<String, ExecResult>,
)

internal data class WatchedRootProjectExpansion(
    val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
    val historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
    val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
)

private sealed interface LandingSection {
    data object ListSessions : LandingSection
    data object ListPanes : LandingSection
    data object RemoteHome : LandingSection
    data object ProjectHistory : LandingSection
    data class RootRepos(val rootPath: String) : LandingSection
}

private fun watchedRootPaths(watchedRoots: List<ProjectRootEntity>): List<String> =
    watchedRoots.mapNotNull { it.path.trim().takeIf(String::isNotEmpty) }.distinct()

private fun parseRemoteHome(result: ExecResult): String? {
    if (result.exitCode != 0) return null
    return result.stdout.lineSequence()
        .firstOrNull(String::isNotBlank)
        ?.trim()
        ?.trimEnd('/')
        ?.takeIf(String::isNotEmpty)
}

private fun quiet(command: String): String = "{ $command ; } 2>/dev/null"

private fun splitLandingSections(stdout: String): List<Pair<String, Int?>> {
    val marker = SshFolderListGateway.ENUMERATION_MARKER
    if (!stdout.contains(marker)) return emptyList()
    val sections = mutableListOf<Pair<String, Int?>>()
    val current = StringBuilder()
    stdout.lineSequence().forEach { line ->
        if (line.startsWith(marker)) {
            sections += current.toString().trimEnd('\n') to
                line.removePrefix(marker).trim().toIntOrNull()
            current.setLength(0)
        } else {
            current.append(line).append('\n')
        }
    }
    return sections
}

private fun watchedRootShellExpr(path: String): String {
    val clean = path.trim().trimEnd('/').ifBlank { path.trim() }
    return when {
        clean == "~" -> "\"\$HOME\""
        clean.startsWith("~/") ->
            "\"\$HOME/" + doubleQuoteEscape(clean.removePrefix("~/")) + "\""
        else -> SshFolderListGateway.shellQuoteValue(clean)
    }
}

private fun doubleQuoteEscape(value: String): String =
    value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("$", "\\$")
        .replace("`", "\\`")

private fun expandRemoteHomeShortcut(path: String, remoteHome: String?): String {
    val clean = path.trim().trimEnd('/').ifBlank { path.trim() }
    val home = remoteHome?.trimEnd('/')?.takeIf(String::isNotEmpty)
    return when {
        home == null -> clean
        clean == "~" -> home
        clean.startsWith("~/") -> "$home/${clean.removePrefix("~/")}"
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

private fun canonicalRemotePath(path: String): String =
    path.trim().trimEnd('/').ifEmpty { "/" }
