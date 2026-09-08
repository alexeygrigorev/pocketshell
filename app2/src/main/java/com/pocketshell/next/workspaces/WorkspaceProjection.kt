package com.pocketshell.next.workspaces

import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.core.hostapi.WorkspaceMembership

/** A locally saved root used to keep an empty root visible on the host screen. */
data class RegisteredWorkspaceRoot(
    val path: String,
    val label: String,
    val createdAt: Long = 0L,
    /** Room identity, when this projection came from a saved root shortcut. */
    val id: Long = 0L,
    /** User-controlled placement; old callers fall back to creation order. */
    val sortOrder: Long = createdAt,
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
    /** Saved root row identity; null for inferred and Other sections. */
    val registeredRootId: Long? = null,
    /** Stable saved-root order. Live refreshes must not use activity to reorder it. */
    val order: Long = Long.MAX_VALUE,
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
    /** Durable membership order; inferred session workspaces sort after it. */
    val membershipOrder: Int = Int.MAX_VALUE,
)

/**
 * The quiet one-line summary shown under a workspace name. A workspace row is
 * already scoped by its root, so repeating its full path spends the secondary
 * line on information the user has already seen. The useful distinction here
 * is what kind of terminals are inside it.
 */
fun workspaceSessionSummary(sessions: List<SessionRow>): String {
    if (sessions.isEmpty()) return "No sessions"
    val counts = linkedMapOf<String, Int>()
    sessions.forEach { session ->
        val kind = sessionKindLabel(session)
        counts[kind] = (counts[kind] ?: 0) + 1
    }
    val visible = counts.entries.take(3).map { (kind, count) ->
        if (count == 1) kind else "$kind ×$count"
    }
    val hidden = counts.size - visible.size
    return if (hidden > 0) {
        visible.joinToString(" · ") + " · +$hidden more kinds"
    } else {
        visible.joinToString(" · ")
    }
}

/** Maps host metadata to the readable, neutral session vocabulary in the kit. */
fun sessionKindLabel(session: SessionRow): String = when (
    session.agent?.trim()?.lowercase()
) {
    "claude" -> "Claude"
    "codex" -> "Codex"
    "opencode", "open_code", "open-code" -> "OpenCode"
    "grok" -> "Grok"
    "shell" -> "Terminal"
    null, "", "unknown" -> "Terminal"
    else -> session.agent.orEmpty().replace('_', ' ').replace('-', ' ')
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
        .ifBlank { "Terminal" }
}

/**
 * Chooses a readable row label without changing the host identity used for
 * navigation. Generated shell tags become "Terminal"; meaningful tags keep
 * their short leaf so two sessions remain distinguishable.
 */
fun sessionDisplayNames(sessions: List<SessionRow>): Map<String, String> {
    val occurrences = mutableMapOf<String, Int>()
    return sessions.associate { session ->
        val base = readableSessionName(session.name)
        val occurrence = (occurrences[base] ?: 0) + 1
        occurrences[base] = occurrence
        session.name to if (occurrence == 1) base else "$base $occurrence"
    }
}

/** Converts a host session identifier into the short name shown in the UI. */
fun readableSessionName(name: String): String {
    val leaf = name.substringAfterLast(':').trim()
    return when (leaf.lowercase()) {
        "", "shell", "terminal", "default" -> "Terminal"
        else -> leaf
    }
}

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
    workspaceOrders: Map<String, List<String>> = emptyMap(),
): List<WorkspaceRootProjection> {
    val sourcePaths = buildList {
        addAll(memberships.map { it.path })
        addAll(sessions.mapNotNull { it.workspace })
        addAll(registeredRoots.map { it.path })
    }
    val resolvedHome = canonicalRemotePath(home) ?: inferRemoteHome(sourcePaths)
    val canonicalWorkspaceOrders = workspaceOrders.mapKeys { (path, _) ->
        canonicalRemotePath(path, resolvedHome) ?: path
    }.mapValues { (_, paths) ->
        paths.mapNotNull { canonicalRemotePath(it, resolvedHome) ?: it }
    }

    val rootSpecs = linkedMapOf<String, RootSpec>()
    val configured = registeredRoots
        .mapNotNull { root ->
            val path = canonicalRemotePath(root.path, resolvedHome) ?: return@mapNotNull null
            RootSpec(
                path = path,
                label = root.label.trim().ifEmpty { pathLabel(path, resolvedHome) },
                order = root.sortOrder,
                configured = true,
                registeredRootId = root.id.takeIf { it > 0L },
            )
        }
        .sortedWith(compareBy<RootSpec> { it.order }.thenBy { it.path })
    configured.forEach { rootSpecs.putIfAbsent(it.key, it) }

    val canonicalMemberships = linkedMapOf<String, WorkspaceMembership>()
    val membershipOrders = mutableMapOf<String, Int>()
    for ((membershipIndex, membership) in memberships.withIndex()) {
        val path = canonicalRemotePath(membership.path, resolvedHome) ?: continue
        if (canonicalMemberships.putIfAbsent(
                path,
                membership.copy(
                    path = path,
                    displayPath = membership.displayPath.trim().ifEmpty { path },
                ),
            ) == null
        ) {
            membershipOrders[path] = membershipIndex
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
        // A configured root is already a navigable host location. If the host
        // registry also reports that exact path as a workspace, keep sessions
        // in the root-level group rather than rendering a second child row
        // with the same identity.
        if (root.configured && path == root.path) continue
        val bucket = rootBuckets.getOrPut(root.key) { RootBucket(root) }
        val workspace = MutableWorkspace(
            path = path,
            displayPath = membership.displayPath,
            sessions = mutableListOf(),
            durable = true,
            membershipOrder = membershipOrders[path] ?: Int.MAX_VALUE,
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
            val explicitOrder = canonicalWorkspaceOrders[bucket.rootSpec.key]
                .orEmpty()
                .withIndex()
                .associate { it.value to it.index }
            val workspaces = disambiguateWorkspaceLabels(
                bucket.workspaces.values
                    .sortedWith(
                        compareBy<MutableWorkspace> {
                            explicitOrder[it.path] ?: Int.MAX_VALUE
                        }
                            .thenBy { it.membershipOrder }
                            .thenBy { workspaceCreated(it.sessions) }
                            .thenBy { it.path },
                    )
                    .map { workspace ->
                        WorkspaceProjection(
                            path = workspace.path,
                            label = basename(workspace.displayPath, workspace.path),
                            displayPath = workspace.displayPath,
                            sessions = workspace.sessions.sortedWith(SESSION_ORDER),
                            durable = workspace.durable,
                            membershipOrder = workspace.membershipOrder,
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
                registeredRootId = bucket.rootSpec.registeredRootId,
                order = bucket.rootSpec.order,
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
    val registeredRootId: Long? = null,
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
    val membershipOrder: Int = Int.MAX_VALUE,
)

private const val OTHER_WORKSPACE_ROOT_KEY = "::quiet-other::"

private val SESSION_ORDER: Comparator<SessionRow> =
    compareBy<SessionRow> { it.createdEpoch ?: 0L }.thenBy { it.name }

private val ROOT_ORDER: Comparator<WorkspaceRootProjection> =
    compareBy<WorkspaceRootProjection> { it.other }
        .thenBy { it.order }
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
