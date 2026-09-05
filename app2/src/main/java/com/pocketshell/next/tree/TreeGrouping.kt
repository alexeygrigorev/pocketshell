package com.pocketshell.next.tree

import com.pocketshell.core.hostapi.SessionRow

/**
 * Catch-all root for sessions whose cwd matched no registered (or inferred)
 * root — blank/null workspace, `$HOME` itself, or a path outside `$HOME`.
 */
const val OTHER_ROOT_KEY: String = "::other::"
const val OTHER_ROOT_LABEL: String = "other"

/** @see OTHER_ROOT_LABEL */
const val OTHER_WORKSPACE_LABEL: String = OTHER_ROOT_LABEL

/** Sentinel path for a session the host reported with no workspace. */
const val UNTRACKED_PATH: String = "::untracked::"

private const val HOME_LABEL: String = "~ (home)"
private const val ROOT_LABEL: String = "/ (root)"

/**
 * One top-level folder in the session tree: a `$HOME` child (`~/git`), a
 * registered workspace root, or the [OTHER_ROOT_LABEL] bucket.
 *
 * [headerLabel] is what the screen prints (`~/git`, `other`). [folders] are
 * the working directories under this root; a 1-session folder still occupies
 * its own node — collapsing it is the "grouping doesn't work" failure the
 * desktop learned (SESSIONLIST.md revision 3).
 */
data class SessionRoot(
    val key: String,
    val label: String,
    val folders: List<SessionFolderNode>,
    val sessionCount: Int,
    val createdEpoch: Long,
    val attached: Boolean,
    val other: Boolean,
    val configured: Boolean,
) {
    /** Root header text: the home-relative key (`~/git`), or `other`. */
    val headerLabel: String
        get() = when {
            other -> OTHER_ROOT_LABEL
            key == "~" || key == "\$HOME" -> HOME_LABEL
            else -> key
        }
}

/**
 * One working directory under a [SessionRoot] — the tree's middle level.
 *
 * Always a header with its sessions nested beneath it, whatever it holds.
 * The one exception is [untracked]: a session with no reported cwd has no
 * directory to name, so the renderer draws it as an orphan row under the
 * root rather than inventing a folder whose label would duplicate the
 * session name.
 */
data class SessionFolderNode(
    val key: String,
    val path: String,
    val label: String,
    val rows: List<SessionRow>,
    val createdEpoch: Long,
    val attached: Boolean,
    val untracked: Boolean,
)

private data class ResolvedRoot(
    val key: String,
    val label: String,
)

internal data class RootPlacement(
    val key: String,
    val label: String,
)

/**
 * Group sessions into the desktop-style `root → folder → session` tree
 * (issue #2530; pocketshell-electron `groupSessionsIntoRoots`).
 *
 * ## Grouping is a path lookup, never a name parse
 *
 * [SessionRow.workspace] is the cwd, the same field desktop calls `path`.
 * Nothing here strips a `git-` prefix, splits an aplexer `workspace:tag`
 * name, or recovers a root from the session name. Blank/null workspace is
 * untracked and lands in [OTHER_ROOT_LABEL].
 *
 * ## Roots
 *
 * When [registeredRoots] is non-empty those paths (Settings → Workspace
 * roots) are the top level, in the order they were passed — which the
 * caller keeps as registration/`createdAt` order. Longest prefix match on a
 * `/` boundary wins, so `~/git` never swallows `~/gitlab`. Anything that
 * matches none of them goes to `other`, pinned last. A registered root with
 * no sessions still renders.
 *
 * When nothing is registered, roots are synthesised from `$HOME`'s children
 * the way desktop does with an empty settings list (`inferHome` +
 * `rootForPath`). [home] is used when the caller already knows it; otherwise
 * it is inferred from the session paths (`/home/<user>`, `/Users/<user>`,
 * `/var/home/<user>`, `/root`).
 *
 * ## Folders
 *
 * Inside a root, sessions group by the full working directory (home-relative
 * so `/home/x/git/a` and `~/git/a` are one node). The folder label is the
 * path basename; a collision inside the same root grows a parent segment
 * (`git/foo` vs `nested/foo`). A 1:1 folder is still a folder — never folded
 * into the session row.
 *
 * ## Order is creation, never activity
 *
 * Sessions: [SessionRow.createdEpoch] oldest first, name ascending.
 * Folders: oldest session in the folder, then case-insensitive label.
 * Derived roots: oldest session under the root, then case-insensitive label.
 * Registered roots: the incoming list order. `other` is always last.
 *
 * [SessionRow.activityEpoch] is display-only. Bumping it must not move a
 * row; that is the whole complaint the desktop panel was rewritten to end.
 *
 * Every input row appears in exactly one output folder. Pure data
 * transformation: no Android, no Compose, no coroutines, no clock.
 */
fun groupSessionsIntoRoots(
    sessions: List<SessionRow>,
    home: String? = null,
    registeredRoots: List<String> = emptyList(),
): List<SessionRoot> {
    val resolvedHome = normaliseHome(home) ?: inferHome(sessions.map { it.workspace })
    val configured = resolveRoots(registeredRoots, resolvedHome)
    val configuredKeys = configured.map { it.key }.toSet()

    val byRoot = linkedMapOf<String, MutableList<SessionRow>>()
    val rootMeta = linkedMapOf<String, RootPlacement>()
    for (root in configured) {
        byRoot[root.key] = mutableListOf()
        rootMeta[root.key] = RootPlacement(key = root.key, label = root.label)
    }

    for (session in sessions) {
        val placement = placeSession(session, resolvedHome, configured)
        val bucket = byRoot.getOrPut(placement.key) { mutableListOf() }
        bucket.add(session)
        rootMeta.putIfAbsent(placement.key, placement)
    }

    val folders = byRoot.map { (key, rows) ->
        val meta = rootMeta.getValue(key)
        val directories = buildDirectories(rows, resolvedHome)
        disambiguateLabels(directories)
        directories.sortWith(FOLDER_ORDER)
        val created = directories.minOfOrNull { it.createdEpoch } ?: Long.MAX_VALUE
        SessionRoot(
            key = meta.key,
            label = meta.label,
            folders = directories,
            sessionCount = rows.size,
            createdEpoch = created,
            attached = directories.any { it.attached },
            other = meta.key == OTHER_ROOT_KEY,
            configured = meta.key in configuredKeys,
        )
    }

    val rooted = folders.filterNot { it.other }.toMutableList()
    val other = folders.filter { it.other }
    if (configured.isNotEmpty()) {
        val rank = configured.mapIndexed { index, root -> root.key to index }.toMap()
        rooted.sortBy { rank[it.key] ?: Int.MAX_VALUE }
    } else {
        rooted.sortWith(ROOT_ORDER)
    }
    return rooted + other
}

private fun placeSession(
    session: SessionRow,
    home: String?,
    configured: List<ResolvedRoot>,
): RootPlacement {
    val folderPath = canonicalisePath(session.workspace)
    if (configured.isEmpty()) return rootForPath(folderPath, home)
    val match = bestRootForPath(folderPath, home, configured)
    return if (match == null) {
        RootPlacement(OTHER_ROOT_KEY, OTHER_ROOT_LABEL)
    } else {
        RootPlacement(match.key, match.label)
    }
}

private fun buildDirectories(
    sessions: List<SessionRow>,
    home: String?,
): MutableList<SessionFolderNode> {
    val ordered = sessions.sortedWith(ROW_ORDER)
    val byKey = linkedMapOf<String, MutableList<SessionRow>>()
    val meta = linkedMapOf<String, Triple<String, String, Boolean>>()
    for (session in ordered) {
        val folderPath = canonicalisePath(session.workspace)
        val untracked = folderPath == UNTRACKED_PATH
        val path = if (untracked) UNTRACKED_PATH else directoryKey(folderPath, home)
        val key = if (untracked) "$UNTRACKED_PATH\u0000${session.name}" else path
        val bucket = byKey.getOrPut(key) { mutableListOf() }
        bucket.add(session)
        if (key !in meta) {
            val label = if (untracked) session.name else defaultLabelForPath(path)
            meta[key] = Triple(path, label, untracked)
        }
    }
    return byKey.map { (key, rows) ->
        val (path, label, untracked) = meta.getValue(key)
        SessionFolderNode(
            key = key,
            path = path,
            label = label,
            rows = rows,
            createdEpoch = rows.minOf { sessionCreated(it) },
            attached = rows.any { it.attached },
            untracked = untracked,
        )
    }.toMutableList()
}

/** Oldest created first, name ascending. Never [SessionRow.activityEpoch]. */
private val ROW_ORDER: Comparator<SessionRow> =
    compareBy<SessionRow> { sessionCreated(it) }.thenBy { it.name }

private val FOLDER_ORDER: Comparator<SessionFolderNode> =
    compareBy<SessionFolderNode> { it.createdEpoch }.thenBy { it.label.lowercase() }

private val ROOT_ORDER: Comparator<SessionRoot> =
    compareBy<SessionRoot> { it.createdEpoch }.thenBy { it.label.lowercase() }

internal fun sessionCreated(session: SessionRow): Long = session.createdEpoch ?: 0L

internal fun canonicalisePath(value: String?): String {
    val trimmed = value?.trim().orEmpty()
    if (trimmed.isEmpty()) return UNTRACKED_PATH
    val stripped = trimmed.trimEnd('/')
    return stripped.ifEmpty { "/" }
}

internal fun defaultLabelForPath(path: String): String {
    if (path == UNTRACKED_PATH) return OTHER_ROOT_LABEL
    val stripped = path.trim().trimEnd('/')
    if (stripped.isEmpty()) return ROOT_LABEL
    if (stripped == "~" || stripped == "\$HOME") return HOME_LABEL
    val tail = stripped.substringAfterLast('/')
    return tail.ifBlank { stripped }
}

internal fun inferHome(paths: List<String?>): String? {
    val votes = mutableMapOf<String, Int>()
    for (raw in paths) {
        val path = canonicalisePath(raw)
        if (path == UNTRACKED_PATH) continue
        val candidate = homeCandidate(path) ?: continue
        votes[candidate] = (votes[candidate] ?: 0) + 1
    }
    return votes.maxByOrNull { it.value }?.key
}

private fun homeCandidate(path: String): String? {
    if (path == ROOT_HOME || path.startsWith("$ROOT_HOME/")) return ROOT_HOME
    for (parent in HOME_PARENTS) {
        if (!path.startsWith("$parent/")) continue
        val user = path.removePrefix("$parent/").substringBefore('/')
        if (user.isNotEmpty()) return "$parent/$user"
    }
    return null
}

private val HOME_PARENTS: List<String> = listOf("/home", "/Users", "/var/home")
private const val ROOT_HOME: String = "/root"

private fun normaliseHome(home: String?): String? {
    val trimmed = home?.trim()?.trimEnd('/')
    return trimmed?.takeIf { it.isNotEmpty() }
}

private fun homeRelative(folderPath: String, home: String?): String? {
    if (folderPath == "~" || folderPath == "\$HOME") return ""
    if (folderPath.startsWith("~/")) return folderPath.removePrefix("~/")
    val homePrefix = normaliseHome(home) ?: return null
    if (folderPath == homePrefix) return ""
    if (folderPath.startsWith("$homePrefix/")) return folderPath.removePrefix("$homePrefix/")
    return null
}

internal fun rootForPath(folderPath: String, home: String?): RootPlacement {
    val other = RootPlacement(OTHER_ROOT_KEY, OTHER_ROOT_LABEL)
    if (folderPath == UNTRACKED_PATH) return other
    val relative = homeRelative(folderPath, home) ?: return other
    val first = relative.split('/').firstOrNull { it.isNotEmpty() } ?: return other
    return RootPlacement(key = "~/$first", label = first)
}

internal fun directoryKey(folderPath: String, home: String?): String {
    if (folderPath == UNTRACKED_PATH) return UNTRACKED_PATH
    val relative = homeRelative(folderPath, home) ?: return folderPath
    return if (relative.isEmpty()) "~" else "~/$relative"
}

internal fun pathWithinRoot(path: String, root: String): Boolean {
    if (path == root) return true
    val prefix = if (root.endsWith("/")) root else "$root/"
    return path.startsWith(prefix)
}

internal fun normaliseRootPath(value: String): String? {
    if (value.any { it < ' ' }) return null
    val canonical = canonicalisePath(value)
    if (canonical == UNTRACKED_PATH) return null
    if (canonical.split('/').contains("..")) return null
    if (canonical != "~" && !canonical.startsWith("~/") && !canonical.startsWith("/")) return null
    return canonical
}

private fun resolveRoots(roots: List<String>, home: String?): List<ResolvedRoot> {
    val resolved = mutableListOf<ResolvedRoot>()
    val seen = mutableSetOf<String>()
    for (source in roots) {
        val canonical = normaliseRootPath(source) ?: continue
        val key = directoryKey(canonical, home)
        if (!seen.add(key)) continue
        resolved.add(ResolvedRoot(key = key, label = defaultLabelForPath(key)))
    }
    labelRootsApart(resolved)
    return resolved
}

private fun labelRootsApart(roots: MutableList<ResolvedRoot>) {
    val counts = roots.groupingBy { it.label }.eachCount()
    for (i in roots.indices) {
        val root = roots[i]
        if ((counts[root.label] ?: 0) < 2) continue
        val grown = if (root.key.startsWith("~/")) root.key.removePrefix("~/") else root.key
        roots[i] = root.copy(label = grown)
    }
}

private fun bestRootForPath(
    folderPath: String,
    home: String?,
    roots: List<ResolvedRoot>,
): ResolvedRoot? {
    if (folderPath == UNTRACKED_PATH) return null
    val key = directoryKey(folderPath, home)
    var best: ResolvedRoot? = null
    for (root in roots) {
        if (!pathWithinRoot(key, root.key)) continue
        if (best == null || root.key.length > best.key.length) best = root
    }
    return best
}

/**
 * Two directories in the same root can share a basename (`~/git/foo` and
 * `~/git/nested/foo`). Grow every colliding label by parent segments until
 * unique. Untracked nodes are left alone — their label is the session name.
 */
private fun disambiguateLabels(folders: MutableList<SessionFolderNode>) {
    val pathsByLabel = linkedMapOf<String, MutableSet<String>>()
    for (folder in folders) {
        if (folder.untracked) continue
        pathsByLabel.getOrPut(folder.label) { mutableSetOf() }.add(folder.path)
    }
    for ((label, paths) in pathsByLabel) {
        if (paths.size < 2) continue
        val deepest = paths.maxOf { it.split('/').count { part -> part.isNotEmpty() } }
        var depth = 2
        while (depth < deepest) {
            val expanded = paths.map { tailSegments(it, depth) }.toSet()
            if (expanded.size == paths.size) break
            depth += 1
        }
        for (i in folders.indices) {
            val folder = folders[i]
            if (folder.untracked || folder.label != label) continue
            val grown = tailSegments(folder.path, depth).ifEmpty { folder.label }
            folders[i] = folder.copy(label = grown)
        }
    }
}

private fun tailSegments(path: String, count: Int): String =
    path.split('/').filter { it.isNotEmpty() }.takeLast(count).joinToString("/")

/**
 * Coarse "how long ago" label for a session's last activity.
 *
 * Deliberately coarse and deliberately not a clock time. The value answers
 * "is this still warm?" at a glance while scanning a list; a `14:32` would make
 * the reader do the subtraction, and a live-updating "3 minutes 12 seconds"
 * would be a second thing to keep in sync for no extra information.
 *
 * [epochSec] is the host's `activity_epoch` (seconds), [nowSec] the phone's
 * current time in the same unit. `null` in gives `null` out — the caller renders
 * nothing rather than inventing "unknown".
 *
 * A timestamp in the future (host clock ahead of the phone's, which happens) is
 * clamped to "now" rather than rendering a negative age.
 *
 * Display only: this string is never a sort key.
 */
fun relativeActivityLabel(epochSec: Long?, nowSec: Long): String? {
    if (epochSec == null) return null
    val ageSec = (nowSec - epochSec).coerceAtLeast(0)
    return when {
        ageSec < MINUTE_SEC -> "just now"
        ageSec < HOUR_SEC -> "${ageSec / MINUTE_SEC}m ago"
        ageSec < DAY_SEC -> "${ageSec / HOUR_SEC}h ago"
        else -> "${ageSec / DAY_SEC}d ago"
    }
}

private const val MINUTE_SEC: Long = 60
private const val HOUR_SEC: Long = 60 * MINUTE_SEC
private const val DAY_SEC: Long = 24 * HOUR_SEC
