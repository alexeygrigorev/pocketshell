package com.pocketshell.next.workspaces

import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.WorkspaceMembership

/** A locally saved root used to keep an empty root visible on the host screen. */
data class RegisteredWorkspaceRoot(
    val path: String,
    val label: String,
    val createdAt: Long = 0L,
)

/** One host root section in the Quiet workspace projection. */
data class WorkspaceRootProjection(
    val key: String,
    val label: String,
    val displayPath: String,
    val path: String?,
    val workspaces: List<WorkspaceProjection>,
    val rootSessions: List<SessionRow>,
    val other: Boolean = false,
) {
    val sessionCount: Int
        get() = rootSessions.size + workspaces.sumOf { it.sessions.size }
}

/** A persistent workspace row, or a real session-derived fallback workspace. */
data class WorkspaceProjection(
    /** Canonical absolute remote identity used by the route and host commands. */
    val path: String,
    /** Short presentation label; never used as an identity. */
    val label: String,
    /** Host-provided presentation spelling, kept separate from [path]. */
    val displayPath: String,
    val sessions: List<SessionRow>,
    /** True only when the host's durable membership list contains this path. */
    val durable: Boolean,
)

/** The label used for sessions that do not belong to a known root. */
const val OTHER_WORKSPACE_ROOT_LABEL: String = "Other"

/**
 * Builds the host's Quiet navigation model from two independent live inputs:
 * the host's durable workspace membership and the host's current session list.
 *
 * Membership is the source of empty rows. Session enumeration is the source of
 * session rows. A session whose cwd is not durable is retained as a real,
 * session-derived workspace so live sessions remain reachable without turning
 * a guessed directory into a fake workspace. Empty rows are never guessed.
 */
fun projectWorkspaceRoots(
    sessions: List<SessionRow>,
    memberships: List<WorkspaceMembership>,
    registeredRoots: List<RegisteredWorkspaceRoot> = emptyList(),
    home: String? = null,
): List<WorkspaceRootProjection> {
    val sourcePaths = buildList {
        addAll(memberships.map { it.path })
        addAll(sessions.mapNotNull { it.workspace })
        addAll(registeredRoots.map { it.path })
    }
    val resolvedHome = canonicalRemotePath(home) ?: inferRemoteHome(sourcePaths)

    val rootSpecs = linkedMapOf<String, RootSpec>()
    val configured = registeredRoots
        .mapNotNull { root ->
            val path = canonicalRemotePath(root.path, resolvedHome) ?: return@mapNotNull null
            RootSpec(
                path = path,
                label = root.label.trim().ifEmpty { pathLabel(path, resolvedHome) },
                order = root.createdAt,
                configured = true,
            )
        }
        .sortedWith(compareBy<RootSpec> { it.order }.thenBy { it.path })
    configured.forEach { rootSpecs.putIfAbsent(it.key, it) }

    val canonicalMemberships = linkedMapOf<String, WorkspaceMembership>()
    for (membership in memberships) {
        val path = canonicalRemotePath(membership.path, resolvedHome) ?: continue
        canonicalMemberships.putIfAbsent(
            path,
            membership.copy(
                path = path,
                displayPath = membership.displayPath.trim().ifEmpty { path },
            ),
        )
    }

    if (configured.isEmpty()) {
        (canonicalMemberships.keys + sessions.mapNotNull { canonicalRemotePath(it.workspace, resolvedHome) })
            .forEach { path ->
                inferredRootForPath(path, resolvedHome)?.let { rootPath ->
                    rootSpecs.putIfAbsent(
                        rootPath,
                        RootSpec(
                            path = rootPath,
                            label = pathLabel(rootPath, resolvedHome),
                            order = Long.MAX_VALUE,
                            configured = false,
                        ),
                    )
                }
            }
    }

    val rootBuckets = linkedMapOf<String, RootBucket>()
    rootSpecs.values.forEach { root -> rootBuckets[root.key] = RootBucket(root) }
    val durableRows = linkedMapOf<String, MutableWorkspace>()

    for ((path, membership) in canonicalMemberships) {
        val root = findRoot(path, rootSpecs.values.toList(), resolvedHome)
            ?: rootSpecs.getOrPut(OTHER_WORKSPACE_ROOT_KEY) {
                RootSpec(
                    path = null,
                    label = OTHER_WORKSPACE_ROOT_LABEL,
                    order = Long.MAX_VALUE,
                    configured = false,
                )
            }
        val bucket = rootBuckets.getOrPut(root.key) { RootBucket(root) }
        val workspace = MutableWorkspace(
            path = path,
            displayPath = membership.displayPath,
            sessions = mutableListOf(),
            durable = true,
        )
        durableRows[path] = workspace
        bucket.workspaces[path] = workspace
    }

    for (session in sessions) {
        val path = canonicalRemotePath(session.workspace, resolvedHome)
        if (path == null) {
            otherBucket(rootBuckets, rootSpecs).rootSessions += session
            continue
        }

        val root = findRoot(path, rootSpecs.values.toList(), resolvedHome)
            ?: otherBucket(rootBuckets, rootSpecs).rootSpec
        val bucket = rootBuckets.getOrPut(root.key) { RootBucket(root) }

        // A session exactly at a root belongs in the quiet root-level group.
        // This prevents a fake child workspace named after the root.
        if (root.path != null && path == root.path) {
            bucket.rootSessions += session
        } else {
            val workspace = durableRows[path] ?: bucket.workspaces.getOrPut(path) {
                MutableWorkspace(
                    path = path,
                    displayPath = pathLabel(path, resolvedHome),
                    sessions = mutableListOf(),
                    durable = false,
                )
            }
            workspace.sessions += session
        }
    }

    return rootBuckets.values
        .filter { it.rootSpec.path != null || it.workspaces.isNotEmpty() || it.rootSessions.isNotEmpty() }
        .map { bucket ->
            val workspaces = disambiguateWorkspaceLabels(
                bucket.workspaces.values
                    .sortedWith(compareBy<MutableWorkspace> { workspaceCreated(it.sessions) }.thenBy { it.path })
                    .map { workspace ->
                        WorkspaceProjection(
                            path = workspace.path,
                            label = basename(workspace.displayPath, workspace.path),
                            displayPath = workspace.displayPath,
                            sessions = workspace.sessions.sortedWith(SESSION_ORDER),
                            durable = workspace.durable,
                        )
                    },
            )
            WorkspaceRootProjection(
                key = bucket.rootSpec.key,
                label = bucket.rootSpec.label,
                displayPath = bucket.rootSpec.path?.let { pathLabel(it, resolvedHome) }
                    ?: OTHER_WORKSPACE_ROOT_LABEL,
                path = bucket.rootSpec.path,
                workspaces = workspaces,
                rootSessions = bucket.rootSessions.sortedWith(SESSION_ORDER),
                other = bucket.rootSpec.key == OTHER_WORKSPACE_ROOT_KEY,
            )
        }
        .sortedWith(ROOT_ORDER)
}

/** Lexically canonicalises a remote POSIX path without touching the phone filesystem. */
fun canonicalRemotePath(raw: String?, home: String? = null): String? {
    val value = raw?.trim().orEmpty()
    if (value.isEmpty()) return null
    val suppliedHome = home?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
    val expanded = when {
        value == "~" -> suppliedHome ?: "~"
        value.startsWith("~/") && suppliedHome != null -> "$suppliedHome/${value.removePrefix("~/")}"
        value == "\$HOME" -> suppliedHome ?: value
        value.startsWith("\$HOME/") && suppliedHome != null ->
            "$suppliedHome/${value.removePrefix("\$HOME/")}"
        else -> value
    }
    val absolute = expanded.startsWith('/')
    val prefix = if (absolute) "/" else ""
    val stack = ArrayDeque<String>()
    expanded.split('/').forEach { part ->
        when {
            part.isEmpty() || part == "." -> Unit
            part == ".." -> if (stack.isNotEmpty() && stack.last() != "..") stack.removeLast()
            else if (!absolute) stack.addLast(part)
            else -> stack.addLast(part)
        }
    }
    val joined = stack.joinToString("/")
    return when {
        absolute && joined.isEmpty() -> "/"
        absolute -> prefix + joined
        joined.isEmpty() -> "."
        else -> joined
    }
}

private data class RootSpec(
    val path: String?,
    val label: String,
    val order: Long,
    val configured: Boolean,
) {
    val key: String get() = path ?: OTHER_WORKSPACE_ROOT_KEY
}

private class RootBucket(val rootSpec: RootSpec) {
    val workspaces: LinkedHashMap<String, MutableWorkspace> = linkedMapOf()
    var rootSessions: MutableList<SessionRow> = mutableListOf()
}

private data class MutableWorkspace(
    val path: String,
    val displayPath: String,
    val sessions: MutableList<SessionRow>,
    val durable: Boolean,
)

private const val OTHER_WORKSPACE_ROOT_KEY = "::quiet-other::"

private val SESSION_ORDER: Comparator<SessionRow> =
    compareBy<SessionRow> { it.createdEpoch ?: 0L }.thenBy { it.name }

private val ROOT_ORDER: Comparator<WorkspaceRootProjection> =
    compareBy<WorkspaceRootProjection> { it.other }
        .thenBy { it.label.lowercase() }

private fun otherBucket(
    buckets: MutableMap<String, RootBucket>,
    specs: MutableMap<String, RootSpec>,
): RootBucket {
    val spec = specs.getOrPut(OTHER_WORKSPACE_ROOT_KEY) {
        RootSpec(
            path = null,
            label = OTHER_WORKSPACE_ROOT_LABEL,
            order = Long.MAX_VALUE,
            configured = false,
        )
    }
    return buckets.getOrPut(spec.key) { RootBucket(spec) }
}

private fun findRoot(path: String, roots: List<RootSpec>, home: String?): RootSpec? {
    return roots
        .asSequence()
        .filter { it.path != null && pathWithin(path, it.path, home) }
        .maxByOrNull { it.path!!.length }
}

private fun pathWithin(path: String, root: String?, home: String?): Boolean {
    val canonicalRoot = canonicalRemotePath(root, home) ?: return false
    val canonicalPath = canonicalRemotePath(path, home) ?: return false
    return canonicalPath == canonicalRoot || canonicalPath.startsWith("$canonicalRoot/")
}

private fun inferredRootForPath(path: String, home: String?): String? {
    val canonicalHome = canonicalRemotePath(home)
    if (canonicalHome != null) {
        if (path == canonicalHome) return canonicalHome
        if (path.startsWith("$canonicalHome/")) {
            val relative = path.removePrefix("$canonicalHome/")
            val first = relative.substringBefore('/').takeIf { it.isNotEmpty() } ?: return canonicalHome
            return "$canonicalHome/$first"
        }
        return null
    }
    return if (path.startsWith('/')) {
        path.split('/').filter { it.isNotEmpty() }.take(2).joinToString("/").let { "/$it" }
    } else {
        null
    }
}

private fun inferRemoteHome(paths: List<String>): String? {
    val candidates = paths.mapNotNull { path ->
        val canonical = canonicalRemotePath(path) ?: return@mapNotNull null
        when {
            canonical == "/root" || canonical.startsWith("/root/") -> "/root"
            canonical.startsWith("/home/") -> "/home/${canonical.removePrefix("/home/").substringBefore('/')}"
            canonical.startsWith("/Users/") -> "/Users/${canonical.removePrefix("/Users/").substringBefore('/')}"
            canonical.startsWith("/var/home/") ->
                "/var/home/${canonical.removePrefix("/var/home/").substringBefore('/')}"
            else -> null
        }
    }
    return candidates.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

private fun pathLabel(path: String, home: String?): String {
    val canonical = canonicalRemotePath(path, home) ?: return path
    val canonicalHome = canonicalRemotePath(home)
    return when {
        canonicalHome != null && canonical == canonicalHome -> "~"
        canonicalHome != null && canonical.startsWith("$canonicalHome/") ->
            "~/${canonical.removePrefix("$canonicalHome/")}"
        else -> canonical
    }
}

private fun basename(displayPath: String, canonicalPath: String): String {
    val candidate = displayPath.trimEnd('/').substringAfterLast('/').ifBlank { canonicalPath }
    return candidate.removePrefix("~").ifBlank { "~" }
}

private fun workspaceCreated(sessions: List<SessionRow>): Long =
    sessions.minOfOrNull { it.createdEpoch ?: 0L } ?: Long.MAX_VALUE

private fun disambiguateWorkspaceLabels(
    workspaces: List<WorkspaceProjection>,
): List<WorkspaceProjection> {
    val byLabel = workspaces.groupBy { it.label }
    return workspaces.map { workspace ->
        val collision = byLabel[workspace.label].orEmpty().size > 1
        if (!collision) return@map workspace
        workspace.copy(label = workspace.displayPath.trimEnd('/').removePrefix("~/"))
    }
}
