package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind

/**
 * The maintained in-memory project tree for ONE host — EPIC #679, Slice 1.
 *
 * ## Why this exists
 *
 * Before #679 the host-detail screen never held a tree. Every 5 s probe
 * overwrote a pile of `lastXxx` snapshot fields and `emitReady()` rebuilt the
 * whole `treeRoots`/`folders`/`flatSessions` from scratch, so order, expansion,
 * and bucket placement were *re-derived* on every tick. That from-scratch
 * rebuild is the structural cause of the artifacts the maintainer kept hitting
 * (mis-bucket flash, stale-window lingering, slow-to-appear window) — every
 * prior fix (#639/#663/#603/#656/#653/#675/#471/#602) was a stabilizer bolted
 * on *after* the rebuild.
 *
 * [HostTreeModel] makes the **held tree the source of truth**:
 *
 *  - Node existence, order, and expansion are **intrinsic state** — a session
 *    keeps its slot because it is the same keyed entry in the same
 *    [LinkedHashMap] position, not because a stabilizer re-froze the order.
 *  - The tree is **held across opens** — only a host *change* resets it
 *    ([reset]). Re-opening the same host renders the held tree INSTANTLY with
 *    no probe-on-open.
 *  - A probe becomes a **reconcile** ([reconcile]): diff incoming keys against
 *    held keys → add new, remove gone, update-in-place existing — never
 *    blank-and-rebuild.
 *  - App-initiated changes mutate the tree **directly by id**
 *    ([removeSession], [insertWindow]) the instant the app knows about them
 *    (#653 stop→remove, #678 create-window→insert), so the probe is a
 *    confirmation, not the source of truth for our own actions.
 *  - A just-created node carries [SessionNode.optimisticSince] so the next
 *    reconcile that has not yet observed it does **not** prune it
 *    ([OPTIMISTIC_GRACE_MS]).
 *
 * ## Visual parity (Slice 1 contract)
 *
 * This slice keeps the screen byte-identical: [project] feeds the SAME pure
 * builders ([FolderListViewModel.groupSessionsIntoFolders] /
 * [FolderListViewModel.buildFolderTree]) the legacy `emitReady()` used, so the
 * rendered `FolderRow`/`FolderTreeRoot`/flat shapes are produced by the exact
 * same code. What changes is the *driver*: the model holds keyed nodes across
 * reconciles and projects from them, instead of recomputing inputs from the
 * latest probe every tick. Order is intrinsic to the [sessionOrder] insertion
 * list; expansion is intrinsic to [expandedProjectPaths] +
 * [userCollapsedProjectPaths]. Sticky bucketing, deleting the legacy
 * stabilizer plumbing, and the cold-render cache redesign are later slices —
 * this slice keeps those legacy pure functions as the projection so parity is
 * provable.
 */
internal class HostTreeModel {

    /** The host this tree currently describes. `null` until first [reset]. */
    var hostId: Long? = null
        private set

    /**
     * True once at least one probe (or cold-cache restore) has populated the
     * session set, so [project] has something to render. Mirrors the legacy
     * `hasSessionProbeSnapshot`.
     */
    var hasSnapshot: Boolean = false
        private set

    /**
     * Wall-clock millis of the last successful [reconcile]. Drives the
     * infrequent-reconcile staleness gate (#679 requirement #2): on
     * resume/open we reconcile only when `now - lastReconciledAt` exceeds the
     * staleness window. `null` until the first reconcile.
     */
    var lastReconciledAt: Long? = null
        private set

    /**
     * Keyed session nodes in **insertion order** — the intrinsic display order
     * that replaces the #639 `stableSessionOrder` stabilizer. A session keeps
     * its slot across reconciles because it stays the same map entry; only
     * genuinely new sessions are appended (sorted among new peers), and removed
     * sessions drop out.
     */
    private val sessions = LinkedHashMap<String, SessionNode>()

    /** Folder path per session, canonicalised. Mirrors `lastSessionFolderPaths`. */
    private val sessionFolderPaths = LinkedHashMap<String, String>()

    /** The host's watched-root overlay (Room `project_roots`). */
    private var watchedFolders: List<ProjectRootEntity> = emptyList()

    private var scannedProjectFoldersByRoot: Map<String, List<String>> = emptyMap()
    private var historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap()
    private var resolvedWatchedRootPaths: Map<String, String> = emptyMap()

    /**
     * #729 (#679 Slice 2): sticky bucket placement memory. Maps a session name
     * to the resolved root *match* path it was last AUTHORITATIVELY placed under
     * by a healthy probe. A reconcile refreshes the entry whenever the live
     * probe resolves the session under a watched root, and drops it when the
     * session authoritatively moves out of every root. [project] threads this
     * into [FolderListViewModel.buildFolderTree] so a momentarily-degraded
     * `resolvedWatchedRootPaths` cannot flash an already-placed session into
     * "Other folders" — the placement is held by node id, not recomputed solely
     * from the degraded roots on every projection.
     */
    private val stickyBuckets = LinkedHashMap<String, String>()

    /**
     * Optimistic folder overlay (#653 create/import). Holds canonical path →
     * label for a folder the app created locally before a probe confirms it.
     * Generalised from the legacy `lastCreatedFolders`; pruned once a reconcile
     * observes the folder under a real session/scan.
     */
    private val optimisticFolders = LinkedHashMap<String, String>()

    /**
     * Folder paths the user explicitly collapsed (#471). Intrinsic expansion
     * memory so a reconcile never re-opens a folder the user closed.
     */
    private var userCollapsedProjectPaths: Set<String> = emptySet()

    /** Folder paths currently expanded (#471). Intrinsic across reconciles. */
    private var expandedProjectPaths: Set<String> = emptySet()

    private data class WindowState(
        val index: Int?,
        val name: String?,
        val active: Boolean,
        val command: String?,
        val agentKind: SessionAgentKind,
        /**
         * The stable tmux window id (`@N`, #653) — the id the live `-CC`
         * stream reports in `%window-close @<id>`. [removeWindow] keys the
         * window-level prune on this, not [index], because tmux renumbers
         * window indices when a window closes. `null` for a window the probe
         * could not tag with an id.
         */
        val windowId: String?,
    )

    /**
     * One maintained session node. Carries the per-session UI state that used
     * to be re-derived each probe, plus the optimistic-insert grace marker.
     */
    private data class SessionNode(
        var lastActivity: Long?,
        var attached: Boolean,
        var agentKind: SessionAgentKind,
        var windows: List<WindowState>,
        /**
         * Issue #858: the non-default profile label (e.g. `"Claude (Z.AI)"`)
         * read back from `@ps_agent_profile`, or `null` for a default /
         * non-profiled / legacy session. Held across reconciles like the kind
         * so a transient blank read doesn't drop the tree's profile chip.
         */
        var recordedProfile: String?,
        /**
         * Issue #899: tmux `#{session_id}` (`$N`) carried through the maintained
         * tree so folder-row navigation can pass durable identity into the tmux
         * runtime/reveal/memory keys.
         */
        var tmuxSessionId: String?,
        /**
         * Issue #899: tmux `#{session_created}` epoch seconds paired with
         * [tmuxSessionId] for durable identity construction.
         */
        var sessionCreated: Long?,
        /**
         * Wall-clock millis this node was inserted optimistically by an
         * app-initiated action (#653/#678), or `null` if it came from a probe.
         * A reconcile must NOT prune a node whose optimistic grace has not yet
         * elapsed, so a just-created session/window survives the immediately
         * following reconcile that has not observed it. Cleared once a probe
         * confirms the node.
         */
        var optimisticSince: Long?,
    )

    /** True when the model has never been bound or has been [reset] away. */
    val isEmpty: Boolean get() = hostId == null

    /**
     * Reset the model for a NEW host. The tree is held across opens of the SAME
     * host (so re-opening renders instantly); only a host *change* clears it.
     * Returns `true` when the bind targets a different host than the one held
     * (i.e. the caller must do a fresh load), `false` when it is the same host
     * (the held tree is reused as-is).
     */
    fun bindHost(hostId: Long): Boolean {
        if (this.hostId == hostId) return false
        reset(hostId)
        return true
    }

    private fun reset(hostId: Long) {
        this.hostId = hostId
        hasSnapshot = false
        lastReconciledAt = null
        sessions.clear()
        sessionFolderPaths.clear()
        watchedFolders = emptyList()
        scannedProjectFoldersByRoot = emptyMap()
        historyProjectFoldersByRoot = emptyMap()
        resolvedWatchedRootPaths = emptyMap()
        stickyBuckets.clear()
        optimisticFolders.clear()
        userCollapsedProjectPaths = emptySet()
        expandedProjectPaths = emptySet()
    }

    /** Update the watched-root overlay (the Room `project_roots` Flow). */
    fun setWatchedFolders(rows: List<ProjectRootEntity>) {
        watchedFolders = rows
    }

    /**
     * Epic #821 slice C (issue #837): SEED the tree from the host-side durable
     * registry on a cold start, BEFORE the first reconcile, so re-launching the
     * app renders the held ORDER + EXPAND/COLLAPSE state INSTANTLY (no Loading
     * flash, no order shuffle) — instead of starting empty and waiting for the
     * first authoritative probe.
     *
     * Each persisted node carries only the small *presentation* state that is
     * truly lost on process death: the session NAME (its identity + intrinsic
     * slot via insertion order), its FOLDER PATH (bucket placement), the user's
     * COLLAPSE choice, and an optional one-shot FOREIGN-GUESS kind. The richer
     * per-session content (windows, lastActivity, attached, the CONFIRMED kind
     * via `@ps_agent_kind`) is re-seeded by the immediately-following reconcile —
     * so a hydrated node is a placeholder that holds its slot until the probe
     * fills it.
     *
     * Hydrate is a no-op once the tree already has a snapshot (a live probe /
     * by-id mutation has already populated it): it must NEVER clobber fresher
     * authoritative content with a stale registry seed. It also nulls each
     * placeholder node's [SessionNode.optimisticSince] so the FIRST reconcile is
     * free to prune any registry session that is no longer live (reconcile's own
     * optimistic-grace guard only spares app-inserted nodes, not registry seeds).
     *
     * @param nodes the persisted node list from `tree.get`, in display order.
     */
    fun hydrate(nodes: List<HydratedNode>) {
        if (nodes.isEmpty()) return
        // Do not overwrite an already-populated tree — a live probe / by-id
        // mutation is always fresher than the durable registry seed.
        if (hasSnapshot) return

        val collapsedPaths = LinkedHashSet<String>()
        nodes.forEach { node ->
            val placeholder = SessionNode(
                lastActivity = null,
                attached = false,
                agentKind = node.foreignGuess ?: SessionAgentKind.Probing,
                windows = emptyList(),
                // The client cache holds ordering/expand/foreign-guess only, not
                // the recorded profile — the first reconcile re-reads it from the
                // host @ps_agent_profile option (#858).
                recordedProfile = null,
                tmuxSessionId = null,
                sessionCreated = null,
                // A registry seed is NOT app-inserted: leave it prunable by the
                // first reconcile if the session is gone.
                optimisticSince = null,
            )
            sessions[node.sessionName] = placeholder
            val canonicalFolder = FolderListViewModel.canonicalisePath(node.folderPath)
                .ifBlank { FolderListViewModel.UNTRACKED_PATH }
            sessionFolderPaths[node.sessionName] = canonicalFolder
            if (node.collapsed && canonicalFolder.isNotBlank()) {
                collapsedPaths.add(canonicalFolder)
            }
        }
        // Seed the user's collapse memory so a folder they closed stays closed
        // across the restart. The matching expanded set is the complement
        // [project] resolves on the next emit, honouring these collapses.
        userCollapsedProjectPaths = userCollapsedProjectPaths + collapsedPaths
        hasSnapshot = true
    }

    /**
     * Issue #867 (REOPEN): seed the STRUCTURAL projection inputs from the client
     * cold cache, so a cold-start instant render reproduces the SETTLED grouping
     * — sessions bucketed under their watched root ("git → 74 projects"), the
     * project subfolders + counts visible — instead of dumping everything into
     * "Other folders" with "0 projects" until the first reconcile lands.
     *
     * ## Why the per-session [hydrate] alone was not enough (the shipped gap)
     *
     * [buildFolderTree] needs more than the per-session name+cwd+collapse to
     * render the real tree: it needs [resolvedWatchedRootPaths] (so
     * `bestRootForPath` can place a session under git/tmp rather than "Other
     * folders"), and [scannedProjectFoldersByRoot]/[historyProjectFoldersByRoot]
     * (the directory scan that supplies the project subfolders + the "X projects"
     * count). The original #867 cache stored ONLY the session nodes, so on a cold
     * start `resolvedWatchedRootPaths` was empty → every session fell into "Other
     * folders" and each watched root showed "0 projects" — exactly the spinner /
     * empty-rebuild state the maintainer re-reported on v0.4.14. The shipped unit
     * test asserted only `flatSessions` (a flat list that renders fine without any
     * structure), so the broken grouping passed review (the G6/F2 proxy gap).
     *
     * This is ADVISORY like [hydrate]: a no-op once the tree already has a
     * snapshot (a live probe is always fresher), and the first authoritative
     * [reconcile] overwrites every structural map wholesale (so a stale scan can
     * never linger past the first refresh). It must run TOGETHER with [hydrate]
     * (same cold-start seed) — never gate it on `hasSnapshot` independently.
     */
    fun hydrateStructure(
        resolvedWatchedRootPaths: Map<String, String>,
        scannedProjectFoldersByRoot: Map<String, List<String>>,
        historyProjectFoldersByRoot: Map<String, List<String>>,
    ) {
        // Mirror [hydrate]'s advisory guard: never clobber a populated tree's
        // (fresher) structure with the cached seed.
        if (this.resolvedWatchedRootPaths.isNotEmpty() ||
            this.scannedProjectFoldersByRoot.isNotEmpty() ||
            this.historyProjectFoldersByRoot.isNotEmpty()
        ) {
            return
        }
        this.resolvedWatchedRootPaths = resolvedWatchedRootPaths
        this.scannedProjectFoldersByRoot = scannedProjectFoldersByRoot
        this.historyProjectFoldersByRoot = historyProjectFoldersByRoot
        // Re-seed sticky placement from the seeded structure so an
        // already-placed session is held under its root by id even before the
        // first reconcile refreshes the sticky map (mirrors [reconcile] →
        // [refreshStickyBuckets]). Without this the projection would re-bucket on
        // the very first probe and could momentarily flash a placed session.
        refreshStickyBuckets()
    }

    /**
     * Issue #867 (REOPEN): EXPORT the structural projection inputs for the client
     * cold cache, so the NEXT cold start can [hydrateStructure] the settled
     * grouping instantly. These are the same three maps [reconcile] stores from
     * the authoritative probe — purely presentation (directory layout +
     * watched-root resolution), never authoritative session/kind state.
     */
    fun exportStructure(): CachedStructure =
        CachedStructure(
            resolvedWatchedRootPaths = resolvedWatchedRootPaths,
            scannedProjectFoldersByRoot = scannedProjectFoldersByRoot,
            historyProjectFoldersByRoot = historyProjectFoldersByRoot,
        )

    /**
     * The structural projection inputs persisted to / restored from the client
     * cold cache (issue #867 reopen). Bridges [HostTreeModel] and
     * [TreeClientCache] without exposing the internal model fields.
     */
    data class CachedStructure(
        val resolvedWatchedRootPaths: Map<String, String>,
        val scannedProjectFoldersByRoot: Map<String, List<String>>,
        val historyProjectFoldersByRoot: Map<String, List<String>>,
    )

    /**
     * Epic #821 slice C (issue #837): EXPORT the current tree state as the node
     * list to persist via `tree.upsert` after a mutation (toggle expand,
     * rename/insert, post-reconcile). Carries ONLY the durable presentation
     * state — session name + intrinsic order + folder path + the user's collapse
     * choice + the optional foreign-guess kind. It NEVER exports the confirmed
     * agent kind: that lives in `@ps_agent_kind` (one source of truth), so a
     * recorded/confirmed kind is deliberately omitted from the registry.
     *
     * A folder is reported `collapsed` when it is in [userCollapsedProjectPaths]
     * (the user's explicit close), so the choice round-trips through the
     * registry.
     */
    fun exportNodes(): List<HydratedNode> {
        var order = 0
        return sessions.entries.map { (name, node) ->
            val folderPath = sessionFolderPaths[name] ?: FolderListViewModel.UNTRACKED_PATH
            HydratedNode(
                sessionName = name,
                order = order++,
                folderPath = folderPath,
                collapsed = folderPath in userCollapsedProjectPaths,
                foreignGuess = node.agentKind.takeIf { it.isForeignGuessExportable() },
            )
        }
    }

    /**
     * A node persisted to / restored from the durable registry. The bridge type
     * between [HostTreeModel] and `TreeRemoteSource` — it carries only the small
     * durable presentation state (NO confirmed kind; that stays in
     * `@ps_agent_kind`). [foreignGuess] is the one-shot foreign-guess hint.
     */
    data class HydratedNode(
        val sessionName: String,
        val order: Int,
        val folderPath: String,
        val collapsed: Boolean,
        val foreignGuess: SessionAgentKind? = null,
    )

    /**
     * Only the three concrete detected agent kinds are exportable as a
     * foreign-guess hint. [SessionAgentKind.Probing] / [SessionAgentKind.Shell]
     * / [SessionAgentKind.Exited] are transient/derived and carry no durable
     * hint worth persisting.
     */
    private fun SessionAgentKind.isForeignGuessExportable(): Boolean =
        this == SessionAgentKind.Claude ||
            this == SessionAgentKind.Codex ||
            this == SessionAgentKind.OpenCode

    /**
     * Reconcile the held tree against a fresh authoritative probe snapshot
     * (#679 reconcile). Diffs incoming sessions/windows against held nodes:
     *
     *  - **Remove** held sessions absent from the probe — *unless* the node is
     *    within its optimistic grace ([OPTIMISTIC_GRACE_MS]), so a just-created
     *    session the probe has not yet observed is not pruned.
     *  - **Add** probe sessions not held, appended in insertion order so they
     *    take a stable new slot without displacing existing tap targets.
     *  - **Update in place** sessions in both, mutating mutable fields
     *    (lastActivity/attached/agentKind/windows) WITHOUT moving the node and
     *    WITHOUT touching expansion — this is what kills the rebuild flash.
     *
     * A probe-confirmed node has its optimistic marker cleared (it is now
     * authoritative). The discovery maps (scanned/history/resolved/optimistic
     * folders) are replaced wholesale from the probe.
     *
     * @param now wall-clock millis used for the optimistic grace check and to
     *   stamp [lastReconciledAt]. Injectable for deterministic tests.
     */
    fun reconcile(snapshot: ProbeSnapshot, now: Long = System.currentTimeMillis()) {
        val incomingByName = snapshot.sessions.associateBy { it.sessionName }
        val incomingNames = incomingByName.keys

        // Remove held sessions the probe no longer reports, sparing nodes still
        // inside their optimistic grace window.
        val toRemove = sessions.keys.filter { name ->
            if (name in incomingNames) return@filter false
            val node = sessions[name]
            val since = node?.optimisticSince
            // Prune unless the node was optimistically inserted recently.
            since == null || (now - since) >= OPTIMISTIC_GRACE_MS
        }
        toRemove.forEach { name ->
            sessions.remove(name)
            sessionFolderPaths.remove(name)
        }

        // Add new + update existing, preserving the insertion order of held
        // sessions and appending genuinely-new ones in probe order.
        snapshot.sessions.forEach { entry ->
            val existing = sessions[entry.sessionName]
            if (existing == null) {
                sessions[entry.sessionName] = entry.toNode(optimisticSince = null)
            } else {
                existing.lastActivity = entry.lastActivity
                existing.attached = entry.attached
                // Issue #716: agent-ness is STICKY. A known agent kind is only
                // overwritten by another agent kind or a CONFIRMED Shell — an
                // incoming Probing must NOT clobber a known agent.
                existing.agentKind = mergeAgentKind(existing.agentKind, entry.agentKind)
                // Issue #858 / #889: reconcile the recorded profile against the
                // host. A fresh NON-NULL read is always authoritative (a z.ai
                // launch or a profile change). A BLANK read is authoritative
                // ONLY when the incoming read is itself authoritative — i.e. it
                // carries a real recorded agent kind or a confirmed shell (the
                // host returned a live `@ps_agent_kind`, not a slow/incomplete
                // probe). That blank means the host CLEARED `@ps_agent_profile`
                // (a default relaunch in the same tmux session, #889): the stale
                // z.ai chip MUST drop, not stay sticky. A blank read from a
                // DEGRADED probe (incoming kind still Probing/Exited —
                // `@ps_agent_kind` not read) keeps the held label so a transient
                // blank doesn't flicker the chip off (the #858 stickiness intent).
                existing.recordedProfile = when {
                    entry.recordedProfile != null -> entry.recordedProfile
                    entry.agentKind.isAuthoritativeRead -> null
                    else -> existing.recordedProfile
                }
                existing.tmuxSessionId = entry.tmuxSessionId
                existing.sessionCreated = entry.sessionCreated
                existing.windows = mergeWindows(existing.windows, entry.windows.map { it.toState() })
                // The probe confirmed this node — it is no longer optimistic.
                existing.optimisticSince = null
            }
            sessionFolderPaths[entry.sessionName] = snapshot.folderPaths[entry.sessionName]
                ?: FolderListViewModel.UNTRACKED_PATH
        }

        scannedProjectFoldersByRoot = snapshot.scannedProjectFoldersByRoot
        historyProjectFoldersByRoot = snapshot.historyProjectFoldersByRoot
        resolvedWatchedRootPaths = snapshot.resolvedWatchedRootPaths

        refreshStickyBuckets()

        // A reconcile that observes a created folder (under a real session or
        // scan) retires its optimistic overlay entry.
        if (optimisticFolders.isNotEmpty()) {
            val observed = sessionFolderPaths.values.toSet() +
                snapshot.scannedProjectFoldersByRoot.values.flatten()
                    .map(FolderListViewModel::canonicalisePath)
            optimisticFolders.keys.removeAll { it in observed }
        }

        hasSnapshot = true
        lastReconciledAt = now
    }

    /**
     * #729: fold the just-reconciled probe into the sticky-bucket memory.
     *
     *  - Prune sticky entries for sessions the probe no longer reports.
     *  - For each held session the live probe AUTHORITATIVELY places under a
     *    watched root, (re)set its sticky root to that resolved match-path.
     *  - For a session the live probe does NOT place, KEEP its sticky entry when
     *    its current cwd still sits within the held sticky root (a transiently
     *    degraded `resolvedWatchedRootPaths` — the placement is preserved), and
     *    DROP it only when the cwd has genuinely left that root (an
     *    authoritative move — it re-buckets to "Other folders").
     *
     * This keeps [buildFolderTree]'s sticky honouring an already-placed-by-id
     * node, never letting a degraded probe flash it into "Other folders".
     */
    private fun refreshStickyBuckets() {
        stickyBuckets.keys.retainAll(sessionFolderPaths.keys)
        val livePlacements = FolderListViewModel.resolveStickyPlacements(
            sessionFolderPaths = sessionFolderPaths,
            watchedFolders = watchedFolders,
            resolvedWatchedRootPaths = resolvedWatchedRootPaths,
        )
        for ((name, cwd) in sessionFolderPaths) {
            val live = livePlacements[name]
            if (live != null) {
                // Healthy placement — refresh the held root.
                stickyBuckets[name] = live
                continue
            }
            // No live placement. Keep a held sticky root only while the cwd
            // still sits under it (degraded probe); otherwise the session
            // authoritatively left the root, so drop the entry.
            val held = stickyBuckets[name]
            val canonicalCwd = FolderListViewModel.canonicalisePath(cwd)
            if (held != null && !FolderListViewModel.pathWithinRoot(canonicalCwd, held)) {
                stickyBuckets.remove(name)
            }
        }
    }

    /**
     * Optimistically remove a session by id (#653 stop/kill). The row drops
     * from the next [project] immediately; the following reconcile is a
     * confirmation, not the trigger. Returns `true` when a node was removed.
     */
    fun removeSession(sessionName: String): Boolean {
        if (sessions.remove(sessionName) == null) return false
        sessionFolderPaths.remove(sessionName)
        return true
    }

    /**
     * Remove a single WINDOW by its stable tmux window id (`@N`, #653) when the
     * live `-CC` stream reports `%window-close @<id>` while the parent session
     * stays alive. The window-level analogue of [removeSession]:
     *
     *  - finds the session node holding a window with [windowId] and drops ONLY
     *    that window from the node's window list — sibling windows and the
     *    parent session node keep their slots untouched;
     *  - is INCREMENTAL: it mutates the one node's `windows` list in place and
     *    never rebuilds the session map or re-derives order/expansion — the
     *    matching reconcile guarantee for a single window close;
     *  - is a no-op (returns `false`) when no held window carries [windowId]
     *    (already pruned, a window the probe never id-tagged, or an id for a
     *    session this host's tree does not hold).
     *
     * The whole session is deliberately NOT removed even if this drops the
     * session's last window: tmux closes the session itself when its final
     * window closes, which surfaces separately as `%sessions-changed` /
     * `removeSession` — keeping the two concerns hard-cut and single-purpose.
     */
    fun removeWindow(windowId: String): Boolean {
        if (windowId.isBlank()) return false
        sessions.values.forEach { node ->
            if (node.windows.any { it.windowId == windowId }) {
                node.windows = node.windows.filterNot { it.windowId == windowId }
                return true
            }
        }
        return false
    }

    /**
     * Optimistically rename a session by id, preserving its slot, windows, and
     * expansion (#653). The new name inherits the old node's insertion position
     * so the row does not jump. Returns `true` when the rename applied.
     */
    fun renameSession(oldName: String, newName: String): Boolean {
        if (oldName == newName) return false
        val node = sessions[oldName] ?: return false
        // Rebuild the map preserving order, swapping the key in place.
        val rebuilt = LinkedHashMap<String, SessionNode>(sessions.size)
        sessions.forEach { (name, value) ->
            if (name == oldName) rebuilt[newName] = value else rebuilt[name] = value
        }
        sessions.clear()
        sessions.putAll(rebuilt)
        sessionFolderPaths[oldName]?.let { path ->
            sessionFolderPaths.remove(oldName)
            sessionFolderPaths[newName] = path
        }
        return true
    }

    /**
     * Optimistically insert a folder the app just created (#653 create/import)
     * so it appears before the next probe confirms it. The overlay entry is
     * retired by the reconcile that observes the folder.
     */
    fun insertOptimisticFolder(path: String, label: String) {
        optimisticFolders[FolderListViewModel.canonicalisePath(path)] = label
    }

    /**
     * Optimistically insert (or update) a session node the app just created
     * (#678 create session / create agent window) so it appears immediately,
     * keyed by id and guarded by the optimistic grace so the next reconcile
     * does not prune it. A node already present is updated in place (keeps its
     * slot); a new node is appended.
     */
    fun insertSession(entry: FolderSessionEntry, folderPath: String, now: Long = System.currentTimeMillis()) {
        val existing = sessions[entry.sessionName]
        if (existing == null) {
            sessions[entry.sessionName] = entry.toNode(optimisticSince = now)
        } else {
            existing.lastActivity = entry.lastActivity
            existing.attached = entry.attached
            existing.agentKind = entry.agentKind
            existing.windows = entry.windows.map { it.toState() }
            existing.recordedProfile = entry.recordedProfile
            existing.tmuxSessionId = entry.tmuxSessionId
            existing.sessionCreated = entry.sessionCreated
            existing.optimisticSince = now
        }
        sessionFolderPaths[entry.sessionName] =
            FolderListViewModel.canonicalisePath(folderPath).ifBlank { FolderListViewModel.UNTRACKED_PATH }
        hasSnapshot = true
    }

    /**
     * Epic #821 slice C (issue #837): apply a `tree.reconcile` DELTA to the held
     * tree without a full reload — the "refresh to see which sessions are gone,
     * without reloading everything" path. Prunes each session in [goneNames]
     * from the held tree by id (sparing nodes still within their optimistic
     * grace, exactly like [reconcile]). Returns `true` when at least one node was
     * pruned, so the caller can persist + re-emit. ADDED sessions are NOT folded
     * here — they need a full probe to fetch their content, so the caller kicks a
     * reconcile when the delta reports additions.
     */
    fun applyReconcileGoneDelta(
        goneNames: Collection<String>,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        var pruned = false
        goneNames.forEach { name ->
            val node = sessions[name] ?: return@forEach
            val since = node.optimisticSince
            if (since != null && (now - since) < OPTIMISTIC_GRACE_MS) return@forEach
            sessions.remove(name)
            sessionFolderPaths.remove(name)
            stickyBuckets.remove(name)
            pruned = true
        }
        return pruned
    }

    /** Toggle the expanded state of [projectPath] (#471 user tap). */
    fun toggleProjectExpanded(projectPath: String) {
        val canonical = FolderListViewModel.canonicalisePath(projectPath)
        val wasExpanded = canonical in expandedProjectPaths
        expandedProjectPaths = FolderListViewModel.toggleProjectExpansion(expandedProjectPaths, canonical)
        userCollapsedProjectPaths = if (wasExpanded) {
            userCollapsedProjectPaths + canonical
        } else {
            userCollapsedProjectPaths - canonical
        }
    }

    /** The maintained session list in intrinsic insertion order. */
    fun sessionEntries(): List<FolderSessionEntry> =
        sessions.entries.map { (name, node) -> node.toEntry(name) }

    /** Current expanded-folder set (intrinsic, for the rendered Ready state). */
    fun expandedPaths(): Set<String> = expandedProjectPaths

    /**
     * Test seam: force the next [reconcileDue] check to report due by clearing
     * [lastReconciledAt], so a resume-when-stale unit test can drive the delta
     * reconcile path without depending on the wall clock.
     */
    @androidx.annotation.VisibleForTesting
    internal fun markReconcileDueForTest() {
        lastReconciledAt = null
    }

    /** Whether a reconcile is due given [staleAfterMs] since [lastReconciledAt]. */
    fun reconcileDue(now: Long, staleAfterMs: Long): Boolean {
        val last = lastReconciledAt ?: return true
        return (now - last) >= staleAfterMs
    }

    /**
     * Project the held tree into the render shapes — the SAME pure builders the
     * legacy `emitReady()` used, so the produced `FolderRow`/`FolderTreeRoot`/
     * flat list are byte-identical (Slice 1 visual-parity contract). Order is
     * intrinsic to the maintained session list, so no `sessionOrderRank` is
     * threaded — the builders see the sessions already in their stable slots.
     * Auto-expansion (#471) is folded into [expandedProjectPaths] here, exactly
     * as the legacy path did, but starting from the intrinsic expansion memory.
     */
    fun project(): Projection {
        val orderedSessions = sessionEntries()
        val folders = FolderListViewModel.groupSessionsIntoFolders(
            sessions = orderedSessions,
            sessionFolderPaths = sessionFolderPaths,
            watchedFolders = watchedFolders,
            extraFolders = optimisticFolders,
        )
        val treeRoots = FolderListViewModel.buildFolderTree(
            sessions = orderedSessions,
            sessionFolderPaths = sessionFolderPaths,
            watchedFolders = watchedFolders,
            scannedProjectFoldersByRoot = scannedProjectFoldersByRoot,
            historyProjectFoldersByRoot = historyProjectFoldersByRoot,
            resolvedWatchedRootPaths = resolvedWatchedRootPaths,
            extraFolders = optimisticFolders,
            stickyBuckets = stickyBuckets,
        )
        val visibleFolders = treeRoots.flatMap { it.folders }
        val visibleProjectPaths = visibleFolders.map { it.path }.toSet()
        val activeProjectPaths = visibleFolders
            .filter { it.sessions.isNotEmpty() }
            .map { it.path }
            .toSet()
        expandedProjectPaths = FolderListViewModel.resolveExpandedProjectPaths(
            previousExpanded = expandedProjectPaths,
            visibleProjectPaths = visibleProjectPaths,
            activeProjectPaths = activeProjectPaths,
            userCollapsedProjectPaths = userCollapsedProjectPaths,
        )
        userCollapsedProjectPaths = userCollapsedProjectPaths.intersect(visibleProjectPaths)
        return Projection(
            folders = folders,
            treeRoots = treeRoots,
            flatSessions = orderedSessions,
            expandedProjectPaths = expandedProjectPaths,
        )
    }

    /** Render output of [project] — fed straight into [FolderListUiState.Ready]. */
    data class Projection(
        val folders: List<FolderRow>,
        val treeRoots: List<FolderTreeRoot>,
        val flatSessions: List<FolderSessionEntry>,
        val expandedProjectPaths: Set<String>,
    )

    /**
     * The authoritative probe inputs a [reconcile] diffs against — the shape of
     * one `FolderListResult.Sessions` mapped into render entries.
     */
    data class ProbeSnapshot(
        val sessions: List<FolderSessionEntry>,
        val folderPaths: Map<String, String>,
        val scannedProjectFoldersByRoot: Map<String, List<String>>,
        val historyProjectFoldersByRoot: Map<String, List<String>>,
        val resolvedWatchedRootPaths: Map<String, String>,
    )

    /**
     * Issue #716: sticky agent-ness merge. Once a session/window is known to
     * be an agent (Claude/Codex/OpenCode), it STAYS an agent across
     * reconcile/reconnect/switch unless an EXPLICIT signal downgrades it:
     *
     *  - the incoming kind is itself an agent kind (an agent → agent change,
     *    e.g. Claude → Codex, is honoured); OR
     *  - the incoming kind is a CONFIRMED [SessionAgentKind.Shell] — the
     *    affirmative-shell verdict the gateway only emits for a positively
     *    seen interactive-shell pane (#716 gateway change). That is the one
     *    explicit "this is now a shell" event that downgrades.
     *
     * An incoming [SessionAgentKind.Probing] ("presumed-agent / still
     * detecting") NEVER clobbers a known agent — a slow/incomplete re-probe
     * must not flip a confirmed agent back to uncertain. When the held kind is
     * not yet an agent (Probing/Shell/Exited), the incoming kind wins, so a
     * Probing session can still be UPGRADED to a detected agent.
     */
    private fun mergeAgentKind(held: SessionAgentKind, incoming: SessionAgentKind): SessionAgentKind {
        if (!held.isAgent) return incoming
        // Held is a known agent: keep it unless the probe explicitly says
        // another agent or a CONFIRMED shell.
        return when {
            incoming.isAgent -> incoming
            incoming == SessionAgentKind.Shell -> incoming
            else -> held // incoming Probing/Exited does not downgrade a known agent
        }
    }

    /**
     * Issue #716: apply the sticky [mergeAgentKind] guard per window, matching
     * incoming windows to held windows by [WindowState.windowId] (stable tmux
     * id) and falling back to [WindowState.index]. A held window's agent kind
     * is preserved against an incoming Probing; everything else on the window
     * (name/active/command/id) is taken from the fresh probe.
     */
    private fun mergeWindows(held: List<WindowState>, incoming: List<WindowState>): List<WindowState> {
        if (held.isEmpty()) return incoming
        return incoming.map { incomingWindow ->
            val match = held.firstOrNull { heldWindow ->
                val byId = incomingWindow.windowId != null &&
                    heldWindow.windowId == incomingWindow.windowId
                val byIndex = incomingWindow.windowId == null &&
                    heldWindow.windowId == null &&
                    incomingWindow.index != null &&
                    heldWindow.index == incomingWindow.index
                byId || byIndex
            } ?: return@map incomingWindow
            incomingWindow.copy(
                agentKind = mergeAgentKind(match.agentKind, incomingWindow.agentKind),
            )
        }
    }

    private fun FolderSessionEntry.toNode(optimisticSince: Long?): SessionNode =
        SessionNode(
            lastActivity = lastActivity,
            attached = attached,
            agentKind = agentKind,
            windows = windows.map { it.toState() },
            recordedProfile = recordedProfile,
            tmuxSessionId = tmuxSessionId,
            sessionCreated = sessionCreated,
            optimisticSince = optimisticSince,
        )

    private fun SessionNode.toEntry(name: String): FolderSessionEntry =
        FolderSessionEntry(
            sessionName = name,
            lastActivity = lastActivity,
            attached = attached,
            agentKind = agentKind,
            recordedProfile = recordedProfile,
            tmuxSessionId = tmuxSessionId,
            sessionCreated = sessionCreated,
            windows = windows.map { it.toEntry() },
        )

    private fun FolderSessionWindowEntry.toState(): WindowState =
        WindowState(
            index = index,
            name = name,
            active = active,
            command = command,
            agentKind = agentKind,
            windowId = windowId,
        )

    private fun WindowState.toEntry(): FolderSessionWindowEntry =
        FolderSessionWindowEntry(
            index = index,
            name = name,
            active = active,
            command = command,
            agentKind = agentKind,
            windowId = windowId,
        )

    /**
     * Issue #716: a CONCRETE known-agent kind for the sticky-merge guard.
     * Only the three detected agent runtimes count — [SessionAgentKind.Probing]
     * (presumed/detecting) and [SessionAgentKind.Exited] are deliberately NOT
     * "known agents" so they can still be upgraded by an incoming detected
     * agent kind on the next reconcile.
     */
    private val SessionAgentKind.isAgent: Boolean
        get() = this == SessionAgentKind.Claude ||
            this == SessionAgentKind.Codex ||
            this == SessionAgentKind.OpenCode

    /**
     * Issue #889: an AUTHORITATIVE host read of this session's classification —
     * a detected agent runtime OR a confirmed [SessionAgentKind.Shell]. These
     * are the kinds the gateway emits when it actually read back a host-side
     * `@ps_agent_kind` (or affirmatively saw a shell pane), so a co-reported
     * BLANK `@ps_agent_profile` is the host's "this session has no profile"
     * verdict — a default relaunch CLEARED it — not a degraded/transient probe.
     * [SessionAgentKind.Probing] / [SessionAgentKind.Exited] are NOT
     * authoritative: a blank profile alongside them is an incomplete probe and
     * must keep the held label (the #858 transient-blank protection).
     */
    private val SessionAgentKind.isAuthoritativeRead: Boolean
        get() = isAgent || this == SessionAgentKind.Shell

    companion object {
        /**
         * #679 requirement #2: a maintained tree reconciles INFREQUENTLY, not
         * on a constant 5 s loop. A reconcile fires on foreground-resume/open
         * only when the tree is older than this staleness window (or never
         * reconciled) — and always on an explicit pull-to-refresh. ~15 minutes,
         * D21-clean (evaluated on resume/open, no `Timer`/`AlarmManager`/
         * `WorkManager`).
         */
        const val RECONCILE_STALENESS_MS: Long = 15 * 60 * 1000L

        /**
         * #679 risk #1 (optimistic-insert vs probe-prune race): a node inserted
         * optimistically by an app action is spared from pruning by a reconcile
         * for this grace window, so a just-created session/window survives the
         * immediately-following reconcile that has not yet observed it. Longer
         * than one reconcile round-trip; the probe clears the marker once it
         * confirms the node.
         */
        const val OPTIMISTIC_GRACE_MS: Long = 30 * 1000L
    }
}
