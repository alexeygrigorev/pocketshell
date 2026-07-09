package com.pocketshell.app.projects

import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind

/**
 * One folder row in the folder list — issue #171.
 *
 * Folders are derived (auto-discovered from each session's
 * `pane_current_path` / `session_path`) and optionally enriched by the
 * user's per-host [ProjectRootEntity] overlay so a watched folder with
 * zero active sessions still appears as a row. Tap actions diverge
 * depending on whether the folder has any active sessions (see the
 * spike's locked decision in Section 3).
 *
 * @property path canonical folder path used as the grouping key
 *   (e.g. `/home/alexey/git/pocketshell`).
 * @property label user-visible label. Watched-folder rows use the
 *   [ProjectRootEntity.label] (with the order prefix stripped); pure
 *   auto-discovered rows fall back to the trailing path segment.
 * @property sessions the folder's active sessions, sorted by recency
 *   descending. Empty when the folder is a watched-folder overlay with
 *   no live sessions.
 * @property isWatched true when the folder is also a
 *   [ProjectRootEntity] for the host — drives a visual "pinned" hint
 *   so the user can tell auto-discovered folders apart from explicit
 *   pins.
 */
data class FolderRow(
    val path: String,
    val label: String,
    val sessions: List<FolderSessionEntry>,
    val isWatched: Boolean,
) {
    /** True when the folder has zero active tmux sessions today. */
    val isEmpty: Boolean get() = sessions.isEmpty()

    /** Most-recent activity across [sessions] — used for sort ordering. */
    val mostRecentActivity: Long get() = sessions.maxOfOrNull { it.lastActivity ?: 0L } ?: 0L
}

/**
 * One watched parent root in the host-detail tree. Each root owns the
 * project folders discovered under it by `pocketshell repos list --local
 * --root <path>` plus any live session folders that fall under that root.
 */
data class FolderTreeRoot(
    val path: String,
    val label: String,
    val folders: List<FolderRow>,
    val isWatched: Boolean,
    val addSheetProjects: List<RootProjectCandidate> = emptyList(),
) {
    val isEmpty: Boolean get() = folders.isEmpty()
    val mostRecentActivity: Long get() = folders.maxOfOrNull { it.mostRecentActivity } ?: 0L
    val displayPath: String? get() = path.takeUnless { it == FolderListViewModel.OTHER_ROOT_PATH }
    val activeProjectCount: Int get() = folders.count { it.sessions.isNotEmpty() }
    val sessionCount: Int get() = folders.sumOf { it.sessions.size }
    val inactiveProjectCount: Int get() = addSheetProjects.size
}

data class RootProjectCandidate(
    val path: String,
    val label: String,
    val source: RootProjectSource,
)

enum class RootProjectSource { History, Scanned }

data class HostDiscoveredPort(
    val remotePort: Int,
    val process: String,
    val status: HostPortForwardingPortStatus = HostPortForwardingPortStatus.DISCOVERED,
    val discovered: Boolean = true,
)

enum class HostPortForwardingPortStatus {
    DISCOVERED,
    FORWARDING,
}

data class HostPortForwardingSummary(
    val discoveredPorts: List<HostDiscoveredPort> = emptyList(),
    val active: Boolean = false,
    val activeTunnelCount: Int = 0,
    val entryAvailable: Boolean = false,
    val discoveryLoading: Boolean = false,
) {
    val discoveredCount: Int
        get() = discoveredPorts.count { it.discovered }
}

/**
 * One session inside a [FolderRow]. Carries the minimum fields the
 * folder detail screen needs to render a `SessionRow` and route the
 * tap to `AppDestination.TmuxSession`.
 */
data class FolderSessionEntry(
    val sessionName: String,
    val lastActivity: Long?,
    val attached: Boolean,
    val agentKind: SessionAgentKind,
    val windows: List<FolderSessionWindowEntry> = emptyList(),
    /**
     * Issue #858: the human label of the non-default profile this session was
     * launched with (e.g. `"Claude (Z.AI)"`), read back from the host-side
     * `@ps_agent_profile` tmux user option. `null` for a default / non-profiled
     * / legacy session — so the tree distinguishes a z.ai Claude from a default
     * Claude, and shows no spurious chip for a default session.
     */
    val recordedProfile: String? = null,
    /**
     * Issue #899: tmux `#{session_id}` (`$N`) carried from discovery into
     * navigation so runtime/reveal/memory keys can avoid stale name reuse.
     */
    val tmuxSessionId: String? = null,
    /**
     * Issue #899: tmux `#{session_created}` epoch seconds carried with
     * [tmuxSessionId] to build the durable session key.
     */
    val sessionCreated: Long? = null,
)

data class FolderSessionWindowEntry(
    val index: Int?,
    val name: String?,
    val active: Boolean,
    val command: String?,
    val agentKind: SessionAgentKind,
    /**
     * Issue #653: the stable tmux window id (`@N`) — the same id the live `-CC`
     * stream reports in `%window-close @<id>`. Carried into the maintained tree
     * so a single window close prunes exactly that window node by id (the window
     * index renumbers across closes and cannot key the prune). `null` for a
     * window the probe path could not tag with an id (e.g. an older cached row).
     */
    val windowId: String? = null,
)

sealed interface FolderListUiState {
    data class Loading(
        val portForwarding: HostPortForwardingSummary = HostPortForwardingSummary(
            entryAvailable = true,
            discoveryLoading = true,
        ),
    ) : FolderListUiState

    data class Ready(
        val folders: List<FolderRow>,
        val treeRoots: List<FolderTreeRoot>,
        val flatSessions: List<FolderSessionEntry>,
        val expandedProjectPaths: Set<String>,
        val isRefreshing: Boolean = false,
        val isCreatingSession: Boolean = false,
        val portForwarding: HostPortForwardingSummary = HostPortForwardingSummary(),
    ) : FolderListUiState

    data class Failed(val message: String) : FolderListUiState

    data class ConnectError(val message: String, val cause: Throwable) : FolderListUiState

    data object ToolUnavailable : FolderListUiState
}

/**
 * Host-detail action feedback surface (#656).
 *
 * For a routine **success** there is deliberately no final status state at all
 * — the list updating (a session disappearing on Stop, appearing on Create) IS
 * the feedback, so a success never produces a banner that would push the list
 * down. Failures carry a message (the user must know it did not work), and the
 * screen surfaces them as NON-displacing overlays rather than top-of-list rows.
 */
sealed interface FolderActionStatus {
    data object Idle : FolderActionStatus

    /**
     * Non-displacing in-progress feedback for actions whose result is not
     * otherwise visible until a remote call returns. Create-session uses this so
     * closing the picker is followed by an explicit "still working" signal
     * rather than an apparently frozen/no-op host screen.
     */
    data class Running(val message: String) : FolderActionStatus

    /**
     * A failure banner. [isRefreshFailure] marks the calm "couldn't refresh the
     * project tree" band (issue #711) so its #656 auto-clear can recognise it by
     * TYPE rather than by matching the user-facing message text. Action failures
     * (kill / rename / create / import / host-not-found) leave it `false` —
     * those are explicit, user-dismissed errors that must NOT be silently
     * cleared when a later reconcile succeeds.
     */
    data class Failed(
        val message: String,
        val isRefreshFailure: Boolean = false,
    ) : FolderActionStatus
}

/**
 * Issue #702: surfaced as a retryable [FolderListUiState.ConnectError] when a
 * whole [FolderListViewModel] reconcile out-waits its outer bound. The gateway
 * already self-bounds its live `-CC` enumeration (#702) and its SSH-lease exec
 * reads (#470); this is the last-line defence so a future unbounded gateway
 * path can never pin the session picker in `Loading`. The user gets a Retry
 * panel instead of an indefinite spinner.
 */
class FolderReconcileTimeoutException(
    timeoutMs: Long,
) : RuntimeException(
    "Session list didn't load within ${timeoutMs}ms. Tap to retry.",
)

/**
 * Active/idle split of the flat host-detail session list (#489).
 *
 * The flat view groups every session into an **Active** and an **Idle**
 * section instead of by folder. A session reads *active* purely when it is
 * running an agent (Claude / Codex / OpenCode / probing / exited-agent) — the
 * same green-dot condition the row [com.pocketshell.uikit.components.StatusDot]
 * paints, so the section a row lands in always agrees with its dot colour.
 * Everything else (plain shells, attached or not) is *idle* (amber).
 *
 * The split deliberately does NOT key on
 * [FolderSessionEntry.attached] (#663): `attached` flips true the instant the
 * user opens a session, which would jump a plain shell from Idle to Active and
 * reorder the list under the user's finger, causing mis-taps. Agent activity is
 * the only thing that moves a row between sections; opening/attaching a plain
 * shell leaves its row in exactly the same section and index.
 *
 * Order inside each section is preserved from the already-sorted `flatSessions`
 * input (agents first, then most-recent activity, then name — see
 * `sessionEntrySort`), so the split is purely a partition and never re-sorts.
 */
data class FlatSessionGroups(
    val active: List<FolderSessionEntry>,
    val idle: List<FolderSessionEntry>,
) {
    val activeCount: Int get() = active.size
    val idleCount: Int get() = idle.size
    val totalCount: Int get() = active.size + idle.size

    companion object {
        /**
         * Partition [sessions] into the Active / Idle sections (#489, #663). A
         * session is active when its [FolderSessionEntry.agentKind] is an agent
         * kind — agent activity only, NOT [FolderSessionEntry.attached], so
         * opening/attaching a plain shell never moves its row between sections.
         * Relative order within each section is the input order (already sorted
         * upstream).
         */
        fun from(sessions: List<FolderSessionEntry>): FlatSessionGroups {
            val (active, idle) = sessions.partition { it.agentKind.isFlatActiveAgent() }
            return FlatSessionGroups(active = active, idle = idle)
        }

        private fun SessionAgentKind.isFlatActiveAgent(): Boolean = when (this) {
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
}
