package com.pocketshell.next.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.hostapi.AgentState
import com.pocketshell.core.hostapi.Backend
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.uikit.components.AgentKindBadge
import com.pocketshell.uikit.components.AgentStateChip
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.model.SessionAgentState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing

/**
 * Stable test tags. Rows and section headers are keyed by the host's own
 * strings so a journey asserts against the session the fixture really reported,
 * not against a rendering-order index.
 */
const val SESSION_TREE_TAG: String = "session-tree"
const val SESSION_TREE_LIST_TAG: String = "session-tree-list"
const val SESSION_TREE_PARTIAL_BANNER_TAG: String = "session-tree-partial-banner"
const val SESSION_TREE_ERROR_BANNER_TAG: String = "session-tree-error-banner"
const val SESSION_TREE_ERROR_RETRY_TAG: String = "session-tree-error-retry"
const val SESSION_TREE_LOADING_TAG: String = "session-tree-loading"
const val SESSION_TREE_EMPTY_TAG: String = "session-tree-empty"
const val SESSION_TREE_CREATE_FAB_TAG: String = "session-tree-create-fab"
const val SESSION_TREE_CREATE_NOTICE_TAG: String = "session-tree-create-notice"

/** The FAB's accessibility label, and what a journey taps by description. */
const val SESSION_TREE_CREATE_LABEL: String = "New session"

/** The header action that opens this host's file explorer (task P-3a). */
const val SESSION_TREE_FILES_TAG: String = "session-tree-files"

fun sessionRowTag(name: String): String = "session-row-$name"

fun workspaceHeaderTag(label: String): String = "workspace-header-$label"

/**
 * Route-level entry point: binds the Hilt-provided [SessionTreeViewModel] to
 * the stateless [SessionTreeScreen] and drives the lifecycle-aware refresh.
 *
 * `ON_START` rather than a `LaunchedEffect(Unit)`: the same event covers first
 * entry, coming back from the session screen, and returning from the background
 * — three moments after which the host's session list has very likely changed
 * and none of which a one-shot effect keyed on `Unit` would see. The ViewModel
 * collapses overlapping calls, so this cannot stack reads.
 */
@Composable
fun SessionTreeRoute(
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionTreeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }

    // A created session is opened through the SAME edge a row tap uses, so the
    // session route has exactly one caller. Keyed on the name so a second
    // create after the first navigation still fires; `consumeOpenRequest` runs
    // BEFORE the navigation so coming Back to the tree cannot re-trigger it
    // (the same shape as ConnectGate's navigate-once effect).
    LaunchedEffect(state.create.openRequest) {
        val name = state.create.openRequest ?: return@LaunchedEffect
        viewModel.consumeOpenRequest()
        onOpenSession(name)
    }

    SessionTreeScreen(
        state = state,
        onRefresh = viewModel::refresh,
        onOpenSession = onOpenSession,
        onCreateSession = viewModel::openCreateSheet,
        onSubmitCreate = viewModel::createSession,
        onDismissCreate = viewModel::dismissCreateSheet,
        onOpenFiles = onOpenFiles,
        modifier = modifier,
    )
}

/**
 * The workspace + session tree for one host (rewrite task U-3).
 *
 * ## What it draws, and what it deliberately does not
 *
 * One section per workspace the host reported, each holding that workspace's
 * sessions. There is no collapse state, no drag reordering, no persisted node
 * registry, and no per-row action menu — the old client's tree had all four and
 * they are the machinery the rewrite is removing, not features being deferred.
 * A tap opens the session and the FAB creates one (U-6); that is the screen's
 * whole interaction budget (swipe actions are out of scope entirely).
 *
 * ## The partial-list banner is the point of the screen, not decoration
 *
 * When `sessions list --json` reports a backend that failed to enumerate, the
 * list is SHORT and the screen says so, naming the manager. Without it,
 * "aplexer is broken" and "aplexer has no sessions" render identically — the
 * exact failure the host's schema-2 `errors[]` list exists to make visible
 * (#2426). It is a warning rather than an error because the sessions that DID
 * arrive are real and usable.
 *
 * Built from ui-kit primitives ([ScreenHeader], [SectionHeader], [ListRow],
 * [Banner], [EmptyState], [StatusDot], [AgentKindBadge], [AgentStateChip]) so
 * the row density, tap-target floor and status vocabulary are the shared ones.
 *
 * Stateless: everything it paints comes from [state], so it renders identically
 * from a journey, a Robolectric test and a design render.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionTreeScreen(
    state: SessionTreeUiState,
    onRefresh: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit = {},
    modifier: Modifier = Modifier,
    onCreateSession: () -> Unit = {},
    onSubmitCreate: (name: String, cwd: String?) -> Unit = { _, _ -> },
    onDismissCreate: () -> Unit = {},
    nowSec: Long = System.currentTimeMillis() / 1000,
) {
    Box(modifier = modifier.fillMaxSize()) {
        SessionTreeBody(
            state = state,
            onRefresh = onRefresh,
            onOpenSession = onOpenSession,
            onOpenFiles = onOpenFiles,
            nowSec = nowSec,
        )

        // Bottom-end FAB over the list, the one create affordance on this
        // screen (U-6). Drawn in a Box above the content rather than inside a
        // Scaffold so the list's own pull-to-refresh box keeps owning the
        // whole viewport.
        FloatingActionButton(
            onClick = onCreateSession,
            containerColor = PocketShellColors.Accent,
            contentColor = PocketShellColors.OnAccent,
            shape = PocketShellShapes.large,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(PocketShellSpacing.lg)
                .testTag(SESSION_TREE_CREATE_FAB_TAG),
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = SESSION_TREE_CREATE_LABEL,
            )
        }
    }

    if (state.create.visible) {
        CreateSessionSheet(
            state = state.create,
            defaultFolder = state.suggestedFolder,
            onSubmit = onSubmitCreate,
            onCancel = onDismissCreate,
        )
    }
}

/** The tree's own chrome + list, split out so the FAB can sit over it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTreeBody(
    state: SessionTreeUiState,
    onRefresh: () -> Unit,
    onOpenSession: (String) -> Unit,
    onOpenFiles: () -> Unit,
    nowSec: Long,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SESSION_TREE_TAG),
    ) {
        ScreenHeader(
            title = "Sessions",
            subtitle = headerSubtitle(state),
            // Task P-3a: the host's file browser. It lives in the header rather
            // than behind a menu because this is the app's ONLY way to reach the
            // explorer until U-4's terminal chrome lands, and browsing a host's
            // files is a first-class job the maintainer does regularly — not a
            // secondary action to bury.
            trailing = {
                PocketShellButton(
                    text = "Files",
                    onClick = onOpenFiles,
                    variant = ButtonVariant.Text,
                    compact = true,
                    modifier = Modifier.testTag(SESSION_TREE_FILES_TAG),
                )
            },
        )

        if (state.errors.isNotEmpty()) {
            Banner(
                text = "Some sessions may be missing: ${partialManagers(state)}",
                role = BannerRole.Warning,
                maxLines = 4,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(SESSION_TREE_PARTIAL_BANNER_TAG),
            )
        }

        // "That session already existed, so it was opened" — an INFO note, not
        // an error: the host CLI's create is idempotent and `created:false` is
        // a success (see CreateSessionState).
        state.create.notice?.let { notice ->
            Banner(
                text = notice,
                role = BannerRole.Info,
                maxLines = 3,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(SESSION_TREE_CREATE_NOTICE_TAG),
            )
        }

        state.failure?.let { failure ->
            Banner(
                text = failure,
                role = BannerRole.Error,
                maxLines = 4,
                trailingContent = {
                    PocketShellButton(
                        text = "Retry",
                        onClick = onRefresh,
                        variant = ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(SESSION_TREE_ERROR_RETRY_TAG),
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(SESSION_TREE_ERROR_BANNER_TAG),
            )
        }

        // `isRefreshing` is fed ONLY by the pull-to-refresh state, never by the
        // first load: the indicator is an indeterminate spinner, and an infinite
        // animation keeps Compose's test clock from ever going idle. Gating it
        // on a gesture the user just made keeps every "wait for the screen to
        // settle" assertion reachable (the same reason U-2's connect gate shows
        // a text banner instead of a spinner).
        PullToRefreshBox(
            isRefreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f),
        ) {
            when {
                state.loading && !state.loaded -> EmptyState(
                    title = "Loading sessions…",
                    description = "Reading the host's session list.",
                    modifier = Modifier.testTag(SESSION_TREE_LOADING_TAG),
                )

                state.isEmptyAndHealthy -> EmptyState(
                    title = "No sessions",
                    description = "This host has no tmux or aplexer sessions running.",
                    modifier = Modifier.testTag(SESSION_TREE_EMPTY_TAG),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(SESSION_TREE_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                ) {
                    state.groups.forEach { group ->
                        item(key = "workspace:${group.label}") {
                            SectionHeader(
                                label = group.label,
                                count = group.rows.size,
                                modifier = Modifier.testTag(workspaceHeaderTag(group.label)),
                            )
                        }
                        items(
                            count = group.rows.size,
                            key = { index -> "session:${group.label}:${group.rows[index].name}" },
                        ) { index ->
                            SessionTreeRow(
                                row = group.rows[index],
                                nowSec = nowSec,
                                onClick = { onOpenSession(group.rows[index].name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One session row: attach dot, name, "backend · tag · last activity", the
 * engine/manager monogram and the agent-state glyph.
 *
 * The leading dot is always drawn (green when attached, muted when not) rather
 * than drawn-only-when-attached, so every row's title starts at the same x and
 * a column of rows scans as a column.
 */
@Composable
private fun SessionTreeRow(
    row: SessionRow,
    nowSec: Long,
    onClick: () -> Unit,
) {
    ListRow(
        title = row.name,
        subtitle = rowSubtitle(row, nowSec),
        leading = {
            StatusDot(
                status = if (row.attached) ConnectionStatus.Connected else ConnectionStatus.Idle,
            )
        },
        trailing = {
            AgentStateChip(state = row.agentState.toUiState())
            val badge = row.badge()
            AgentKindBadge(
                monogram = badge.monogram,
                label = badge.label,
                isAgent = badge.isAgent,
            )
        },
        onClick = onClick,
        modifier = Modifier.testTag(sessionRowTag(row.name)),
    )
}

/** `4 sessions · 2 workspaces`, or a quieter line before the first listing. */
private fun headerSubtitle(state: SessionTreeUiState): String = when {
    !state.loaded -> "host ${state.hostId}"
    else -> "${state.sessionCount} " + plural(state.sessionCount, "session") +
        " · ${state.groups.size} " + plural(state.groups.size, "workspace")
}

private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"

/** `aplexer`, or `aplexer, tmux` — the managers that failed, deduplicated. */
private fun partialManagers(state: SessionTreeUiState): String =
    state.errors.map { it.manager }.distinct().joinToString(", ")

/**
 * `tmux · reviews · 5m ago`. Every part is optional: a plain tmux session with
 * no tag and no reported activity renders just `tmux`.
 */
private fun rowSubtitle(row: SessionRow, nowSec: Long): String = listOfNotNull(
    row.backend.label(),
    row.tag,
    relativeActivityLabel(row.activityEpoch, nowSec),
).joinToString(" · ")

private fun Backend.label(): String = when (this) {
    Backend.TMUX -> Backend.WIRE_TMUX
    Backend.APLEXER -> Backend.WIRE_APLEXER
    // A manager this build does not know about. Named rather than hidden: the
    // row is real, the app just cannot say what runs it.
    Backend.UNKNOWN -> "unknown manager"
}

/**
 * The badge slot: the ENGINE when the host named one (that is the useful
 * identity — "which agent is in here"), otherwise the manager.
 *
 * `isAgent` drives the accent colour, so an agent session's monogram reads
 * differently from a plain shell's at a glance.
 */
private data class RowBadge(val monogram: String, val label: String, val isAgent: Boolean)

private fun SessionRow.badge(): RowBadge {
    val engine = this.engine?.takeIf { it.isNotBlank() }
    if (engine != null) {
        return RowBadge(monogram = monogram(engine), label = engine, isAgent = true)
    }
    return when (backend) {
        Backend.TMUX -> RowBadge("TM", "tmux session", isAgent = false)
        Backend.APLEXER -> RowBadge("AP", "aplexer session", isAgent = false)
        Backend.UNKNOWN -> RowBadge("?", "unknown manager", isAgent = false)
    }
}

/**
 * Two-letter monogram for an engine name — its first two alphanumerics, so
 * `claude` → `CL`, `codex` → `CO`, `opencode` → `OP`, `grok` → `GR`.
 *
 * A rule rather than a lookup table on purpose: `engines list` is the HOST's to
 * define, so a phone that only badges names it knows would render `?` for an
 * engine the user added last week. The rule collides (`claude`/`clyde` both
 * `CL`), which is acceptable — the badge is a glance-level hint and the full
 * name is on the row's accessibility description.
 */
private fun monogram(engine: String): String {
    val letters = engine.filter { it.isLetterOrDigit() }
    return when {
        letters.isEmpty() -> "?"
        letters.length == 1 -> letters.uppercase()
        else -> letters.take(2).uppercase()
    }
}

/**
 * `core-hostapi`'s reported state → the ui-kit chip vocabulary. `null` (the
 * host has no opinion) maps to [SessionAgentState.Unknown], which renders
 * NOTHING — absent, never a wrong chip.
 */
private fun AgentState?.toUiState(): SessionAgentState = when (this) {
    AgentState.IDLE -> SessionAgentState.Idle
    AgentState.WAITING -> SessionAgentState.WaitingForInput
    AgentState.WORKING -> SessionAgentState.Working
    null -> SessionAgentState.Unknown
}
