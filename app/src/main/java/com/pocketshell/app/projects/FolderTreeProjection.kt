package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind

internal object FolderTreeProjection {
    /**
     * Canonicalise a `pane_current_path` / `session_path` value
     * into a stable grouping key.
     *
     *  - Trailing slashes are removed (so `/home/foo/` and
     *    `/home/foo` collapse to one folder).
     *  - A blank value collapses to [FolderListViewModel.UNTRACKED_PATH].
     *  - Otherwise the value is returned verbatim - we deliberately
     *    do NOT expand `~` to the user's home, because both tmux's
     *    `session_path` and `pane_current_path` already report
     *    absolute paths after process resolution.
     */
    fun canonicalisePath(value: String): String {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return FolderListViewModel.UNTRACKED_PATH
        val stripped = trimmed.trimEnd('/')
        return stripped.ifEmpty { "/" }
    }

    /**
     * Derive a user-visible label from a canonicalised path: the
     * trailing path component (`/home/alexey/git/pocketshell` ->
     * `pocketshell`). This is a guaranteed-non-blank fallback chain -
     * it never returns an empty string, a lone `"/"`, or any other
     * degenerate label that would read as a nameless folder in the
     * project tree (#438):
     *
     *  - the [FolderListViewModel.UNTRACKED_PATH] sentinel -> [FolderListViewModel.UNTRACKED_LABEL].
     *  - a blank path -> [FolderListViewModel.UNTRACKED_LABEL] (never blank).
     *  - filesystem root (`/`, `//`, ...) -> `"/ (root)"`.
     *  - a literal home marker (`~` / `$HOME`) -> `"~ (home)"`.
     *  - otherwise the trailing path segment, or the full path when
     *    there is no meaningful trailing segment.
     */
    fun defaultLabelForPath(path: String): String {
        if (path == FolderListViewModel.UNTRACKED_PATH) return FolderListViewModel.UNTRACKED_LABEL
        val clean = path.trim()
        if (clean.isEmpty()) return FolderListViewModel.UNTRACKED_LABEL
        val stripped = clean.trimEnd('/')
        if (stripped.isEmpty()) return FolderListViewModel.ROOT_LABEL
        if (stripped == "~" || stripped == "\$HOME") return FolderListViewModel.HOME_LABEL
        val tail = stripped.substringAfterLast('/')
        return tail.ifBlank { stripped }
    }

    /**
     * Pure folder-grouping function - visible through [FolderListViewModel] so
     * tests can drive the grouping without spinning up the gateway or DAO.
     *
     * Inputs:
     *  - [sessions]: every active session reported by the gateway
     *    probe (already classified per agent kind by the gateway).
     *  - [sessionFolderPaths]: map from session name to the
     *    canonicalised folder path (`pane_current_path`-primary,
     *    `session_path`-fallback). Sessions absent from this map
     *    are routed to [FolderListViewModel.UNTRACKED_PATH].
     *  - [watchedFolders]: the host's [ProjectRootEntity] overlay. A watched
     *    folder with zero matching sessions still appears as an [FolderRow.isEmpty]
     *    row so the user sees their pin.
     *
     * Output: folder rows sorted by activity recency descending,
     * with watched-but-empty rows after the active set and the
     * [FolderListViewModel.UNTRACKED_PATH] row (if any) last.
     */
    fun groupSessionsIntoFolders(
        sessions: List<FolderSessionEntry>,
        sessionFolderPaths: Map<String, String>,
        watchedFolders: List<ProjectRootEntity>,
        extraFolders: Map<String, String> = emptyMap(),
    ): List<FolderRow> {
        val watchedByPath = watchedFolders
            .associate { canonicalisePath(it.path) to it }
        val extraByPath = extraFolders
            .mapKeys { (path, _) -> canonicalisePath(path) }
        val groupedSessions: Map<String, List<FolderSessionEntry>> = sessions
            .groupBy { sessionFolderPaths[it.sessionName] ?: FolderListViewModel.UNTRACKED_PATH }
            .mapValues { (_, list) ->
                list.sortedWith(sessionEntrySort())
            }

        val allPaths = groupedSessions.keys + watchedByPath.keys + extraByPath.keys
        val rows = allPaths.map { path ->
            val matching = groupedSessions[path].orEmpty()
            val watched = watchedByPath[path]
            val label = when {
                watched != null ->
                    WatchedFoldersViewModel
                        .stripOrderPrefix(watched.label)
                        .ifBlank { defaultLabelForPath(path) }
                extraByPath[path] != null ->
                    extraByPath.getValue(path).ifBlank { defaultLabelForPath(path) }
                else -> defaultLabelForPath(path)
            }
            FolderRow(
                path = path,
                label = label,
                sessions = matching,
                isWatched = watched != null,
            )
        }

        // Partition into active / empty-watched / untracked so we
        // can apply distinct sort rules per bucket.
        val active = rows.filter { it.sessions.isNotEmpty() && it.path != FolderListViewModel.UNTRACKED_PATH }
            .sortedWith(folderRowSort())
        val watchedEmpty = rows.filter { it.sessions.isEmpty() && it.path != FolderListViewModel.UNTRACKED_PATH }
            .sortedBy { it.label.lowercase() }
        val untracked = rows.filter { it.path == FolderListViewModel.UNTRACKED_PATH }
        return active + watchedEmpty + untracked
    }

    fun buildFolderTree(
        sessions: List<FolderSessionEntry>,
        sessionFolderPaths: Map<String, String>,
        watchedFolders: List<ProjectRootEntity>,
        scannedProjectFoldersByRoot: Map<String, List<String>>,
        historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
        extraFolders: Map<String, String> = emptyMap(),
        // Issue #729 (#679 Slice 2): sticky bucket placement. Maps a
        // session name to the resolved root *match* path it was last placed
        // under. When the current probe momentarily degrades (an empty or
        // incomplete [resolvedWatchedRootPaths] so `bestRootForPath` no
        // longer matches), a session whose cwd is STILL within its sticky
        // root is held under that root instead of flashing into "Other
        // folders". `bestRootForPath` is therefore no longer the sole
        // per-projection authority for an already-placed node. Stickiness
        // never pins a session whose cwd genuinely left the root - that is
        // an authoritative move and re-buckets normally.
        stickyBuckets: Map<String, String> = emptyMap(),
    ): List<FolderTreeRoot> {
        val watchedRoots = watchedRootsOf(watchedFolders, resolvedWatchedRootPaths)

        val stickyByName = stickyBuckets
            .mapValues { (_, matchPath) -> canonicalisePath(matchPath) }

        val sessionProjectPaths = mutableMapOf<String, MutableList<FolderSessionEntry>>()
        val otherSessionPaths = mutableMapOf<String, MutableList<FolderSessionEntry>>()
        // #729: project paths placed by STICKINESS (not by the current probe)
        // map to the watched-root node id that must host them. The render
        // loop below filters session project paths by `root.matchPath`, which
        // a degraded probe no longer matches, so these explicit assignments
        // are what keep a sticky-held session under its node.
        val stickyProjectRootNode = mutableMapOf<String, String>()
        for (session in sessions) {
            val cwd = sessionFolderPaths[session.sessionName] ?: FolderListViewModel.UNTRACKED_PATH
            // The current probe's authority first. Stickiness only engages
            // when the live probe fails to place the node - i.e. the
            // watched-roots resolution transiently degraded - and never
            // overrides a live, authoritative placement.
            val liveRoot = bestRootForPath(cwd, watchedRoots)
            val root = liveRoot
                ?: stickyRootForSession(
                    cwd = cwd,
                    stickyMatchPath = stickyByName[session.sessionName],
                    watchedRoots = watchedRoots,
                )
            val projectPath = root?.let { projectPathUnderRoot(cwd, it.matchPath) }
            val target = if (projectPath != null) sessionProjectPaths else otherSessionPaths
            val key = projectPath ?: cwd
            target.getOrPut(key) { mutableListOf() }.add(session)
            // Record the hosting node only when stickiness (not the live
            // probe) placed it, so the render loop can include it under the
            // correct watched root despite the degraded match-path. A
            // non-null [projectPath] guarantees a non-null hosting [root].
            if (projectPath != null && liveRoot == null) {
                stickyProjectRootNode[projectPath] = root!!.path
            }
        }

        val extraByPath = extraFolders
            .mapKeys { (path, _) -> canonicalisePath(path) }
        val treeRoots = watchedRoots.map { root ->
            val scanned = scannedProjectFoldersByRoot[root.path].orEmpty() +
                scannedProjectFoldersByRoot[root.matchPath].orEmpty() +
                scannedProjectFoldersByRoot.entries
                    .firstOrNull {
                        val key = canonicalisePath(it.key)
                        key == root.path || key == root.matchPath
                    }
                    ?.value
                    .orEmpty()
            val scannedPaths = scanned
                .map(::canonicalisePath)
                .filter { pathWithinRoot(it, root.matchPath) }
            val extraPaths = extraByPath.keys.filter { pathWithinRoot(it, root.matchPath) }
            val sessionPaths = sessionProjectPaths.keys.filter {
                // #729: include a project path under this root when the live
                // match-path contains it OR when stickiness explicitly
                // assigned it to this watched-root node (degraded probe).
                pathWithinRoot(it, root.matchPath) ||
                    stickyProjectRootNode[it] == root.path
            }
            val historyPaths = historyProjectFoldersByRoot[root.path].orEmpty() +
                historyProjectFoldersByRoot[root.matchPath].orEmpty() +
                historyProjectFoldersByRoot.entries
                    .firstOrNull {
                        val key = canonicalisePath(it.key)
                        key == root.path || key == root.matchPath
                    }
                    ?.value
                    .orEmpty()
            val historyProjectPaths = historyPaths
                .map(::canonicalisePath)
                .filter { pathWithinRoot(it, root.matchPath) }
            val visibleProjectPaths = sessionPaths
                .distinct()
                .filter { it != FolderListViewModel.UNTRACKED_PATH }
            val sheetProjectPaths = (historyProjectPaths + scannedPaths + extraPaths)
                .distinct()
                .filter { it != FolderListViewModel.UNTRACKED_PATH }

            FolderTreeRoot(
                path = root.path,
                label = root.label,
                isWatched = true,
                folders = visibleProjectPaths
                    .map { path ->
                        folderRowForTreePath(
                            path = path,
                            sessions = sessionProjectPaths[path].orEmpty(),
                            watchedFolders = watchedFolders,
                            extraByPath = extraByPath,
                        )
                    }
                    .sortedForTree(),
                addSheetProjects = buildRootProjectCandidates(
                    projectPaths = sheetProjectPaths,
                    activeSessionsByProjectPath = sessionProjectPaths,
                    historyProjectPaths = historyProjectPaths,
                    scannedProjectPaths = scannedPaths,
                    extraByPath = extraByPath,
                ),
            )
        }

        val otherRows = otherSessionPaths
            .map { (path, entries) ->
                FolderRow(
                    path = path,
                    label = defaultLabelForPath(path),
                    sessions = entries.sortedWith(sessionEntrySort()),
                    isWatched = false,
                )
            }
            .sortedForTree()
        val flatFallbackRows = if (watchedRoots.isEmpty()) {
            groupSessionsIntoFolders(
                sessions = sessions,
                sessionFolderPaths = sessionFolderPaths,
                watchedFolders = watchedFolders,
                extraFolders = extraFolders,
            )
        } else {
            emptyList()
        }
        val otherRoot = if (otherRows.isNotEmpty() || flatFallbackRows.isNotEmpty()) {
            listOf(
                FolderTreeRoot(
                    path = FolderListViewModel.OTHER_ROOT_PATH,
                    label = FolderListViewModel.OTHER_ROOT_LABEL,
                    folders = flatFallbackRows.ifEmpty { otherRows },
                    isWatched = false,
                ),
            )
        } else {
            emptyList()
        }

        return treeRoots + otherRoot
    }

    /**
     * #729 sticky-bucket bookkeeping. Given the CURRENT (assumed healthy)
     * probe inputs, returns the resolved root *match* path for each session
     * the live probe places under a watched root. Sessions the probe does
     * not place (untracked cwd, or cwd outside every root) are absent.
     *
     * [HostTreeModel] folds the result into its sticky memory after each
     * reconcile: a present session refreshes/sets its sticky root, while a
     * session that AUTHORITATIVELY moved out of every root (placed by the
     * live probe but now matching nothing) is dropped. Held sticky entries
     * are the held-by-id placements [buildFolderTree] honours when a later
     * probe degrades. This reuses the exact `watchedRoots`/`bestRootForPath`
     * resolution `buildFolderTree` uses, so the sticky path is always the
     * same match-path the healthy projection bucketed under.
     */
    fun resolveStickyPlacements(
        sessionFolderPaths: Map<String, String>,
        watchedFolders: List<ProjectRootEntity>,
        resolvedWatchedRootPaths: Map<String, String>,
    ): Map<String, String> {
        val watchedRoots = watchedRootsOf(watchedFolders, resolvedWatchedRootPaths)
        if (watchedRoots.isEmpty()) return emptyMap()
        val placements = LinkedHashMap<String, String>()
        for ((sessionName, rawCwd) in sessionFolderPaths) {
            val cwd = canonicalisePath(rawCwd)
            val root = bestRootForPath(cwd, watchedRoots) ?: continue
            placements[sessionName] = root.matchPath
        }
        return placements
    }

    internal fun pathWithinRoot(path: String, root: String): Boolean =
        path == root || path.startsWith(root.trimEnd('/') + "/")

    internal fun buildRootProjectCandidates(
        projectPaths: List<String>,
        activeSessionsByProjectPath: Map<String, List<FolderSessionEntry>>,
        historyProjectPaths: List<String>,
        scannedProjectPaths: List<String>,
        extraByPath: Map<String, String> = emptyMap(),
    ): List<RootProjectCandidate> {
        val historyRank = historyProjectPaths
            .map(::canonicalisePath)
            .distinct()
            .withIndex()
            .associate { it.value to it.index }
        val activeProjectPaths = activeSessionsByProjectPath.keys
            .map(::canonicalisePath)
            .toSet()
        val scannedSet = scannedProjectPaths.map(::canonicalisePath).toSet()
        return projectPaths
            .map(::canonicalisePath)
            .distinct()
            .filter { it != FolderListViewModel.UNTRACKED_PATH && it !in activeProjectPaths }
            .map { path ->
                val source = when {
                    path in historyRank -> RootProjectSource.History
                    else -> RootProjectSource.Scanned
                }
                RootProjectCandidate(
                    path = path,
                    label = (extraByPath[path] ?: defaultLabelForPath(path))
                        .ifBlank { defaultLabelForPath(path) },
                    source = source,
                )
            }
            .filter { it.source != RootProjectSource.Scanned || it.path in scannedSet || it.path in extraByPath }
            .sortedWith(rootProjectCandidateSort(historyRank))
    }

    internal fun filterRootProjectCandidates(
        candidates: List<RootProjectCandidate>,
        query: String,
    ): List<RootProjectCandidate> {
        val clean = query.trim()
        if (clean.isEmpty()) return candidates
        return candidates.filter { candidate ->
            candidate.label.contains(clean, ignoreCase = true) ||
                candidate.path.contains(clean, ignoreCase = true)
        }
    }

    fun toggleProjectExpansion(expandedPaths: Set<String>, projectPath: String): Set<String> {
        val canonical = canonicalisePath(projectPath)
        return if (canonical in expandedPaths) expandedPaths - canonical else expandedPaths + canonical
    }

    /**
     * Issue #471: compute the next set of expanded folder paths for a
     * fresh emission, auto-expanding folders with active sessions while
     * respecting manual collapse. Pure + visible-for-test so the
     * collapse-stickiness invariant can be exercised without a view model.
     *
     * Rules:
     *  - Start from [previousExpanded] (so a folder the user manually
     *    expanded stays open), pruned to [visibleProjectPaths] so paths
     *    for folders that disappeared don't linger.
     *  - Auto-expand every path in [activeProjectPaths] (folders with >=1
     *    active session) EXCEPT those the user explicitly collapsed
     *    ([userCollapsedProjectPaths]). This is what keeps a poll / re-emit
     *    from re-opening a folder the user collapsed.
     *  - Empty folders are never in [activeProjectPaths], so they are never
     *    auto-expanded (they only open via an explicit user tap, which
     *    lands in [previousExpanded]).
     */
    fun resolveExpandedProjectPaths(
        previousExpanded: Set<String>,
        visibleProjectPaths: Set<String>,
        activeProjectPaths: Set<String>,
        userCollapsedProjectPaths: Set<String>,
    ): Set<String> {
        val carriedOver = previousExpanded.intersect(visibleProjectPaths)
        val autoExpand = activeProjectPaths - userCollapsedProjectPaths
        return carriedOver + autoExpand
    }

    internal fun mergeForwardingPortRows(
        discoveredPorts: List<HostDiscoveredPort>,
        activeRemotePorts: Set<Int>,
    ): List<HostDiscoveredPort> {
        if (activeRemotePorts.isEmpty()) {
            return discoveredPorts
                .map { it.copy(status = HostPortForwardingPortStatus.DISCOVERED) }
                .sortedBy { it.remotePort }
        }
        val active = activeRemotePorts.toSet()
        val discoveredByPort = discoveredPorts.associateBy { it.remotePort }
        return (discoveredByPort.keys + active).sorted().map { remotePort ->
            val discovered = discoveredByPort[remotePort]
            HostDiscoveredPort(
                remotePort = remotePort,
                process = discovered?.process.orEmpty(),
                status = if (remotePort in active) {
                    HostPortForwardingPortStatus.FORWARDING
                } else {
                    HostPortForwardingPortStatus.DISCOVERED
                },
                discovered = discovered != null,
            )
        }
    }

    private data class WatchedRoot(
        val path: String,
        val matchPath: String,
        val label: String,
    )

    /**
     * The canonicalised watched-root overlay used by both [buildFolderTree]
     * and [resolveStickyPlacements] so the two agree on every match-path.
     */
    private fun watchedRootsOf(
        watchedFolders: List<ProjectRootEntity>,
        resolvedWatchedRootPaths: Map<String, String>,
    ): List<WatchedRoot> {
        val resolvedByWatchedPath = resolvedWatchedRootPaths
            .mapKeys { (path, _) -> canonicalisePath(path) }
            .mapValues { (_, path) -> canonicalisePath(path) }
        return watchedFolders
            .map { root ->
                val path = canonicalisePath(root.path)
                val matchPath = resolvedByWatchedPath[path]
                    ?.takeIf { it != FolderListViewModel.UNTRACKED_PATH }
                    ?: path
                val label = WatchedFoldersViewModel
                    .stripOrderPrefix(root.label)
                    .ifBlank { defaultLabelForPath(path) }
                WatchedRoot(path = path, matchPath = matchPath, label = label)
            }
            .distinctBy { it.path }
    }

    private fun folderRowForTreePath(
        path: String,
        sessions: List<FolderSessionEntry>,
        watchedFolders: List<ProjectRootEntity>,
        extraByPath: Map<String, String>,
    ): FolderRow {
        val watched = watchedFolders.firstOrNull { canonicalisePath(it.path) == path }
        val label = when {
            watched != null -> WatchedFoldersViewModel
                .stripOrderPrefix(watched.label)
                .ifBlank { defaultLabelForPath(path) }
            extraByPath[path] != null ->
                extraByPath.getValue(path).ifBlank { defaultLabelForPath(path) }
            else -> defaultLabelForPath(path)
        }
        return FolderRow(
            path = path,
            label = label,
            sessions = sessions.sortedWith(sessionEntrySort()),
            isWatched = watched != null,
        )
    }

    private fun bestRootForPath(path: String, roots: List<WatchedRoot>): WatchedRoot? {
        if (path == FolderListViewModel.UNTRACKED_PATH) return null
        return roots
            .filter { pathWithinRoot(path, it.matchPath) }
            .maxByOrNull { it.matchPath.length }
    }

    /**
     * #729 sticky placement. Called only when the current probe's
     * [bestRootForPath] returned null (the watched-roots resolution
     * degraded). Holds a session under its previously-assigned root iff:
     *
     *  1. the session has a sticky match-path ([stickyMatchPath]),
     *  2. its current cwd is STILL within that sticky match-path (so the
     *     session has not authoritatively moved out of the root), and
     *  3. a watched root still owns that match-path - identified by node id
     *     via the canonical root *path*, not by re-running `bestRootForPath`
     *     against the degraded `matchPath`.
     *
     * Returns a [WatchedRoot] whose `matchPath` is overridden to the held
     * sticky path so [projectPathUnderRoot] computes the same project key as
     * the healthy probe did, and whose `path` (node id) is the stable
     * watched-root the healthy probe placed the session under so the project
     * still renders beneath that node. Returns null when the cwd genuinely
     * left the root (an authoritative move) or no watched root owns the
     * sticky path.
     */
    private fun stickyRootForSession(
        cwd: String,
        stickyMatchPath: String?,
        watchedRoots: List<WatchedRoot>,
    ): WatchedRoot? {
        if (cwd == FolderListViewModel.UNTRACKED_PATH) return null
        if (watchedRoots.isEmpty()) return null
        val sticky = stickyMatchPath ?: return null
        // Authoritative-move guard: the cwd must still sit under the sticky
        // root, otherwise the session genuinely left it and re-buckets.
        if (!pathWithinRoot(cwd, sticky)) return null
        // Resolve the sticky match-path back to its stable watched-root node
        // (held by id). The watched root's *current* match-path may be the
        // degraded raw path, so identify the owner by node relationship, not
        // by re-running `bestRootForPath` against the degraded roots:
        //  1. an exact id/match-path equality (healthy or already-resolved),
        //  2. else the longest watched root whose raw/match path is a prefix
        //     of the sticky path or vice versa (alias/symlink resolution),
        //  3. else, when there is a single watched root, that root.
        val owner = watchedRoots.firstOrNull { root ->
            root.matchPath == sticky || root.path == sticky
        } ?: watchedRoots
            .filter { root ->
                pathWithinRoot(sticky, root.path) ||
                    pathWithinRoot(sticky, root.matchPath) ||
                    pathWithinRoot(root.path, sticky) ||
                    pathWithinRoot(root.matchPath, sticky)
            }
            .maxByOrNull { maxOf(it.path.length, it.matchPath.length) }
            ?: watchedRoots.singleOrNull()
            ?: return null
        // Override the match-path to the held sticky path so the project key
        // is identical to the healthy projection's.
        return owner.copy(matchPath = sticky)
    }

    private fun projectPathUnderRoot(path: String, root: String): String {
        if (path == root) return root
        val prefix = root.trimEnd('/') + "/"
        val child = path.removePrefix(prefix).substringBefore('/').ifBlank { return root }
        return prefix + child
    }

    private fun rootProjectCandidateSort(
        historyRank: Map<String, Int>,
    ): Comparator<RootProjectCandidate> =
        compareBy<RootProjectCandidate> {
            when (it.source) {
                RootProjectSource.History -> 0
                RootProjectSource.Scanned -> 1
            }
        }.thenBy {
            if (it.source == RootProjectSource.History) historyRank[it.path] ?: Int.MAX_VALUE else Int.MAX_VALUE
        }.thenBy { it.label.lowercase() }
            .thenBy { it.path.lowercase() }

    /**
     * Within-folder session order: agents first, then most-recent activity,
     * then name. Session order is intrinsic to the maintained session list
     * [HostTreeModel] feeds in already-stable slots (#679/#733), so this is
     * the single ordering rule - no frozen display-rank is threaded.
     */
    private val recencySessionSort: Comparator<FolderSessionEntry> =
        compareByDescending<FolderSessionEntry> { it.agentKind.isAgentSession() }
            .thenByDescending { it.lastActivity ?: 0L }
            .thenBy { it.sessionName }

    private fun sessionEntrySort(): Comparator<FolderSessionEntry> = recencySessionSort

    /**
     * Order of active folder rows within a group: most-recent activity first,
     * ties broken on label so two folders never swap arbitrarily.
     */
    private fun folderRowSort(): Comparator<FolderRow> =
        compareByDescending<FolderRow> { it.mostRecentActivity }
            .thenBy { it.label.lowercase() }

    private fun List<FolderRow>.sortedForTree(): List<FolderRow> {
        val active = filter { it.sessions.isNotEmpty() && it.path != FolderListViewModel.UNTRACKED_PATH }
            .sortedWith(folderRowSort())
        val empty = filter { it.sessions.isEmpty() && it.path != FolderListViewModel.UNTRACKED_PATH }
            .sortedBy { it.label.lowercase() }
        val untracked = filter { it.path == FolderListViewModel.UNTRACKED_PATH }
        return active + empty + untracked
    }

    private fun SessionAgentKind.isAgentSession(): Boolean = when (this) {
        SessionAgentKind.Claude,
        SessionAgentKind.Codex,
        SessionAgentKind.OpenCode,
        SessionAgentKind.Probing,
        SessionAgentKind.Exited,
        -> true
        // Unknown (foreign, not-yet-classified) groups with shells (#821).
        SessionAgentKind.Shell,
        SessionAgentKind.Unknown,
        -> false
    }
}
