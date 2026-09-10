package com.pocketshell.next.tree

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.usage.UsageGlancePillState
import com.pocketshell.next.usage.UsageGlanceViewModel
import com.pocketshell.next.workspaces.SessionKindMark
import com.pocketshell.next.workspaces.sessionKindLabel
import com.pocketshell.next.workspaces.sessionDisplayNames
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ConfirmDialog
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.Kebab
import com.pocketshell.uikit.components.KebabItem
import com.pocketshell.uikit.components.KebabTrigger
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SheetHeader
import com.pocketshell.uikit.icons.PocketShellIcons
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
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

/** The header action that opens this host's port-forward panel (task P-4). */
const val SESSION_TREE_PORTS_TAG: String = "session-tree-ports"

/** The header action that pops back to Hosts (issue #2532). */
const val SESSION_TREE_BACK_TAG: String = "session-tree-back"

/** The header action that opens this host's usage panel (issue #2532). */
const val SESSION_TREE_USAGE_TAG: String = "session-tree-usage"
const val SESSION_TREE_ACTIONS_TAG: String = "session-tree-actions"

fun sessionRowTag(name: String): String = "session-row-$name"

fun sessionRowMenuTag(name: String): String = "session-row-menu-$name"

fun rootHeaderTag(key: String): String = "root-header-$key"

/** Overflow item and confirmation copy for ending a session (issue #2535). */
const val STOP_SESSION_ITEM_LABEL: String = "End session…"
const val STOP_SESSION_TITLE: String = "End Terminal?"
const val STOP_SESSION_CONFIRM_LABEL: String = "End session"
const val STOP_SESSION_ITEM_TAG: String = "session-stop-item"
const val STOP_SESSION_CONFIRM_TAG: String = "session-stop-confirm"
const val STOP_SESSION_CANCEL_TAG: String = "session-stop-cancel"
const val STOP_SESSION_TITLE_TAG: String = "session-stop-title"
const val STOP_SESSION_MESSAGE_TAG: String = "session-stop-message"

fun stopSessionMessage(name: String, workspace: String? = null, host: String? = null): String {
    val context = listOfNotNull(
        workspace?.trim()?.takeIf { it.isNotEmpty() }?.let { "workspace \"$it\"" },
        host?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" on ")
    val location = context.takeIf { it.isNotEmpty() }?.let { " in $it" }.orEmpty()
    return "End \"$name\"$location? This ends the session and anything running in it. There is no undo."
}

fun folderHeaderTag(key: String): String = "folder-header-$key"

/**
 * NOT reachable from the nav graph (#2635 audit).
 *
 * `SessionTreeRoute`/`SessionTreeScreen` were the pre-#2607 session-tree
 * surface; `HostWorkspacesScreen` replaced them and `MainActivity` stopped
 * calling this — #2635 removed the last stale import. The audit's dead-canon
 * sweep proposed deleting the file outright, and the composables below ARE
 * dead, but the file is NOT: its ~25 top-level test tags and helpers
 * (`sessionRowTag` in 8 files, `STOP_SESSION_*` in 5, `stopSessionMessage` in
 * 4, `SESSION_TREE_FILES_TAG`/`_PORTS_TAG`/`_USAGE_TAG` on the live host
 * screen) are load-bearing across app2 and its journeys.
 *
 * Cutting the composables therefore means RELOCATING those symbols first, which
 * is a mechanical but cross-cutting refactor of its own size and touches
 * journeys — deliberately left out of a density pass rather than half-done. It
 * is filed as the remaining piece of the dead-canon cleanup; the ui-kit half
 * (`HostCard`, `SessionRow`, `Breadcrumb`, `CommandChip`, `KeyBar` and their
 * models/renders) is gone in this change.
 */
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
    onOpenPorts: () -> Unit,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionTreeViewModel = hiltViewModel(),
    usageGlanceViewModel: UsageGlanceViewModel? = null,
) {
    val state by viewModel.state.collectAsState()
    val usagePillState = usageGlancePill(usageGlanceViewModel)

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
        onRefreshEngines = viewModel::refreshEngines,
        onDismissCreate = viewModel::dismissCreateSheet,
        onRequestStop = viewModel::requestStopSession,
        onConfirmStop = viewModel::confirmStopSession,
        onCancelStop = viewModel::cancelStopSession,
        onOpenFiles = onOpenFiles,
        onOpenPorts = onOpenPorts,
        onBack = onBack,
        onOpenUsage = onOpenUsage,
        usagePillState = usagePillState,
        modifier = modifier,
    )
}

/**
 * Optional so a Robolectric composition that has no Hilt graph can still host
 * the route. Production always passes a [UsageGlanceViewModel]; the tree still
 * paints its Usage text button when the pill is absent.
 */
@Composable
private fun usageGlancePill(viewModel: UsageGlanceViewModel?): UsageGlancePillState? {
    if (viewModel == null) return null
    val pill by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    return pill
}

/**
 * The workspace + session tree for one host (rewrite task U-3).
 *
 * ## What it draws, and what it deliberately does not
 *
 * A three-level tree: root (`~/git`) → folder (cwd basename) → session. Root
 * and folder rows are headers, not attach targets; only a session row tap
 * opens the session. There is no collapse state, no drag reordering, no
 * persisted node registry — the old client's tree had those and they are the
 * machinery the rewrite is removing. A 1-session folder still draws its
 * folder row (collapsing it is the "grouping doesn't work" failure). The FAB
 * creates a session (U-6); the row kebab's Stop session kills one after
 * confirmation (#2535). Swipe actions are out of scope.
 *
 * ## The partial-list banner is the point of the screen, not decoration
 *
 * When `sessions list --json` reports an enumeration failure, the screen says
 * so instead of rendering the result as an empty healthy list. The error is a
 * warning while stale rows remain visible, and a hard listing failure is shown
 * separately.
 *
 * Built from ui-kit primitives ([ScreenHeader], [SectionHeader], [ListRow],
 * [Banner], [EmptyState], [StatusDot]) so the row density, tap-target floor and
 * status vocabulary are the shared ones. Agent/engine chrome is deliberately
 * absent (rewrite U-9 stays cut): no [com.pocketshell.uikit.components.AgentKindBadge],
 * no [com.pocketshell.uikit.components.AgentStateChip], no engine/profile
 * in the subtitle. Attached is the green [StatusDot] only.
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
    onOpenPorts: () -> Unit = {},
    onBack: () -> Unit = {},
    onOpenUsage: () -> Unit = {},
    usagePillState: UsageGlancePillState? = null,
    modifier: Modifier = Modifier,
    onCreateSession: () -> Unit = {},
    onSubmitCreate: (CreateSessionRequest) -> Unit = {},
    onRefreshEngines: () -> Unit = {},
    onDismissCreate: () -> Unit = {},
    onRequestStop: (String) -> Unit = {},
    onConfirmStop: () -> Unit = {},
    onCancelStop: () -> Unit = {},
    nowSec: Long = System.currentTimeMillis() / 1000,
) {
    var hostToolsOpen by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Surface)
            .zIndex(1f),
    ) {
        SessionTreeBody(
            state = state,
            onRefresh = onRefresh,
            onOpenSession = onOpenSession,
            onRequestStop = onRequestStop,
            onOpenFiles = onOpenFiles,
            onOpenPorts = onOpenPorts,
            onBack = onBack,
            onOpenUsage = onOpenUsage,
            usagePillState = usagePillState,
            nowSec = nowSec,
            onOpenHostTools = { hostToolsOpen = true },
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
                imageVector = PocketShellIcons.Plus,
                contentDescription = SESSION_TREE_CREATE_LABEL,
            )
        }
    }

    if (hostToolsOpen) {
        SessionTreeHostToolsSheet(
            onOpenFiles = {
                hostToolsOpen = false
                onOpenFiles()
            },
            onOpenPorts = {
                hostToolsOpen = false
                onOpenPorts()
            },
            onOpenUsage = {
                hostToolsOpen = false
                onOpenUsage()
            },
            onDismiss = { hostToolsOpen = false },
        )
    }

    if (state.create.visible) {
        CreateSessionSheet(
            state = state.create,
            defaultFolder = state.suggestedFolder,
            existingSessionNames = existingSessionTags(
                state.roots.flatMap { root -> root.folders.flatMap { folder -> folder.rows } },
            ),
            onSubmit = onSubmitCreate,
            onCancel = onDismissCreate,
            onRefreshEngines = onRefreshEngines,
        )
    }

    state.pendingStop?.let { name ->
        val pendingSession = state.roots
            .asSequence()
            .flatMap { it.folders.asSequence() }
            .flatMap { it.rows.asSequence() }
            .firstOrNull { it.name == name }
        ConfirmDialog(
            title = STOP_SESSION_TITLE,
            message = stopSessionMessage(
                name = com.pocketshell.next.workspaces.readableSessionName(name),
                workspace = pendingSession?.workspace,
                host = "host #${state.hostId}",
            ),
            confirmLabel = STOP_SESSION_CONFIRM_LABEL,
            dismissLabel = "Keep running",
            destructive = true,
            onConfirm = onConfirmStop,
            onDismiss = onCancelStop,
            confirmTestTag = STOP_SESSION_CONFIRM_TAG,
            dismissTestTag = STOP_SESSION_CANCEL_TAG,
            titleTestTag = STOP_SESSION_TITLE_TAG,
            messageTestTag = STOP_SESSION_MESSAGE_TAG,
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
    onRequestStop: (String) -> Unit,
    onOpenFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onBack: () -> Unit,
    onOpenUsage: () -> Unit,
    usagePillState: UsageGlancePillState?,
    nowSec: Long,
    onOpenHostTools: () -> Unit,
) {
    val displayNames = remember(state.roots) {
        sessionDisplayNames(
            state.roots.flatMap { root -> root.folders.flatMap { folder -> folder.rows } },
        )
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .testTag(SESSION_TREE_TAG),
    ) {
        ScreenHeader(
            title = "Sessions",
            subtitle = headerSubtitle(state),
            onBack = onBack,
            backTestTag = SESSION_TREE_BACK_TAG,
            trailing = {
                KebabTrigger(
                    onClick = onOpenHostTools,
                    contentDescription = "Host tools",
                    triggerTestTag = SESSION_TREE_ACTIONS_TAG,
                )
            },
        )

        if (state.errors.isNotEmpty()) {
            Banner(
                text = "Some sessions may be missing: ${partialErrors(state)}",
                role = BannerRole.Warning,
                maxLines = 4,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(SESSION_TREE_PARTIAL_BANNER_TAG),
            )
        }

        // "That session already existed" — an INFO note, not an error: the
        // host CLI's create is idempotent and `created:false` is a success
        // (see CreateSessionState). The existing row remains an explicit tap.
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
                    description = "This host has no sessions running.",
                    modifier = Modifier.testTag(SESSION_TREE_EMPTY_TAG),
                )

                else -> LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag(SESSION_TREE_LIST_TAG),
                    contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
                ) {
                    state.roots.forEach { root ->
                        item(key = "root:${root.key}") {
                            SectionHeader(
                                label = root.headerLabel,
                                count = root.sessionCount,
                                modifier = Modifier.testTag(rootHeaderTag(root.key)),
                            )
                        }
                        root.folders.forEach { folder ->
                            if (!folder.untracked) {
                                item(key = "folder:${root.key}:${folder.key}") {
                                    SectionHeader(
                                        label = folder.label,
                                        count = folder.rows.size.takeIf { it >= 2 },
                                        modifier = Modifier
                                            .padding(start = PocketShellDensity.treeIndent)
                                            .testTag(folderHeaderTag(folder.key)),
                                    )
                                }
                            }
                            items(
                                count = folder.rows.size,
                                key = { index ->
                                    "session:${root.key}:${folder.key}:${folder.rows[index].name}"
                                },
                            ) { index ->
                                val row = folder.rows[index]
                                SessionTreeRow(
                                    row = row,
                                    displayName = displayNames[row.name] ?: row.name,
                                    indentLevels = if (folder.untracked) 1 else 2,
                                    onClick = { onOpenSession(row.name) },
                                    onRequestStop = { onRequestStop(row.name) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One session row. Host connectivity and process attachment are not agent
 * state, so the row uses a muted program mark rather than a green status dot.
 */
@Composable
private fun SessionTreeRow(
    row: SessionRow,
    displayName: String = row.name,
    indentLevels: Int,
    onClick: () -> Unit,
    onRequestStop: () -> Unit,
) {
    ListRow(
        title = displayName,
        subtitle = sessionKindLabel(row),
        leading = { SessionKindMark(agent = row.agent) },
        trailing = {
            Kebab(
                items = listOf(
                    KebabItem(
                        label = STOP_SESSION_ITEM_LABEL,
                        onClick = onRequestStop,
                        testTag = STOP_SESSION_ITEM_TAG,
                    ),
                ),
                contentDescription = "Actions for ${row.name}",
                triggerTestTag = sessionRowMenuTag(row.name),
            )
        },
        onClick = onClick,
        modifier = Modifier
            .padding(start = PocketShellDensity.treeIndent * indentLevels)
            .testTag(sessionRowTag(row.name)),
    )
}

/** `4 sessions · 2 roots`, or a quieter line before the first listing. */
private fun headerSubtitle(state: SessionTreeUiState): String = when {
    !state.loaded -> "host ${state.hostId}"
    else -> "${state.sessionCount} " + plural(state.sessionCount, "session") +
        " · ${state.roots.size} " + plural(state.roots.size, "root")
}

private fun plural(count: Int, noun: String): String = if (count == 1) noun else "${noun}s"

/** The host-side error text, deduplicated for a compact banner. */
private fun partialErrors(state: SessionTreeUiState): String =
    state.errors.map { it.message }.distinct().joinToString("; ")

/** Host-scoped utilities live in one sheet so they do not compete with rows. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionTreeHostToolsSheet(
    onOpenFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onOpenUsage: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(SESSION_TREE_ACTIONS_TAG),
    ) {
        SessionTreeHostToolsContent(
            onOpenFiles = onOpenFiles,
            onOpenPorts = onOpenPorts,
            onOpenUsage = onOpenUsage,
            onDismiss = onDismiss,
        )
    }
}

/** The sheet body, kept separate so the action semantics can be verified without modal animation. */
@Composable
internal fun SessionTreeHostToolsContent(
    onOpenFiles: () -> Unit,
    onOpenPorts: () -> Unit,
    onOpenUsage: () -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = PocketShellSpacing.lg),
    ) {
        SheetHeader(
            title = "Host tools",
            subtitle = "Utilities for this host",
            onClose = onDismiss,
        )
        ListRow(
            title = "Files",
            subtitle = "Browse the remote filesystem",
            onClick = onOpenFiles,
            modifier = Modifier.testTag(SESSION_TREE_FILES_TAG),
        )
        ListRow(
            title = "Services & tunnels",
            subtitle = "Forward a remote service",
            onClick = onOpenPorts,
            modifier = Modifier.testTag(SESSION_TREE_PORTS_TAG),
        )
        ListRow(
            title = "Usage",
            subtitle = "Provider capacity on this host",
            onClick = onOpenUsage,
            modifier = Modifier.testTag(SESSION_TREE_USAGE_TAG),
        )
    }
}

internal const val ATTACHED_DESCRIPTION: String = "Attached"
