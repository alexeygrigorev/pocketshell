package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.pocketshell.next.tree.CreateSessionSheet
import com.pocketshell.next.tree.SessionTreeViewModel
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.theme.PocketShellSpacing

/** Stable tags for the workspace-start page (#2635 N1). */
const val WORKSPACE_SCREEN_TAG: String = "workspace-screen"
const val WORKSPACE_BACK_TAG: String = "workspace-back"
const val WORKSPACE_ERROR_TAG: String = "workspace-error"
const val WORKSPACE_RETRY_TAG: String = "workspace-retry"
const val WORKSPACE_CREATE_NOTICE_TAG: String = "workspace-create-notice"
const val WORKSPACE_NEW_SESSION_TAG: String = "workspace-new-session"
const val WORKSPACE_NEW_SESSION_LABEL: String = "New session"

/**
 * "Start something in this workspace" — all that is left of the workspace page.
 *
 * ## Why this is not the old `WorkspaceScreen` (#2635 N1)
 *
 * The workspace page used to be a list of a workspace's sessions as rows to tap
 * a SECOND time, plus a "New session" button, plus two utility rows. The
 * maintainer's report was tap count: "I don't want to have another screen". N1
 * (maintainer-approved, 2026-09-10) makes a workspace tap open its terminal
 * directly, and the terminal's own tab strip is the sibling-session list — so
 * every row that page carried now exists somewhere the user was already going.
 *
 * What could NOT move is the zero-session case: a workspace with nothing
 * running has no terminal to open. Rather than land the user on a page whose
 * only content is one button, this route opens the create sheet ON ENTRY. The
 * page behind the sheet is a header and an empty state, and exists only so the
 * sheet has something to dismiss to and Back has something to pop.
 *
 * The utilities went with the page: "Copy path / Reorder / Remove from list"
 * are on the workspace row's long-press and the host kebab
 * ([HostWorkspacesScreen]), and "Services & tunnels" is in the terminal's own
 * actions sheet.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceStartRoute(
    workspacePath: String,
    onOpenSession: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SessionTreeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.refresh() }
    // The sheet IS the screen: opening it on entry is what removes the
    // intermediate page the maintainer objected to.
    LaunchedEffect(Unit) { viewModel.openCreateSheet() }
    LaunchedEffect(state.create.openRequest) {
        val name = state.create.openRequest ?: return@LaunchedEffect
        viewModel.consumeOpenRequest()
        onOpenSession(name)
    }

    WorkspaceStartScreen(
        workspacePath = workspacePath,
        notice = state.create.notice,
        failure = state.failure,
        onRetry = viewModel::refresh,
        onNewSession = viewModel::openCreateSheet,
        onBack = onBack,
        modifier = modifier,
    )

    if (state.create.visible) {
        CreateSessionSheet(
            state = state.create,
            defaultFolder = workspacePath,
            existingSessionNames = state.workspaceSessions.map { it.name },
            onSubmit = viewModel::createSession,
            onRefreshEngines = viewModel::refreshEngines,
            onCancel = viewModel::dismissCreateSheet,
        )
    }
}

/** The page behind the create sheet. Deliberately almost nothing. */
@Composable
fun WorkspaceStartScreen(
    workspacePath: String,
    notice: String?,
    failure: String?,
    onRetry: () -> Unit,
    onNewSession: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(WORKSPACE_SCREEN_TAG),
    ) {
        ScreenHeader(
            title = workspaceLabelFor(workspacePath),
            subtitle = workspacePath.takeIf { it.isNotBlank() },
            titleMaxLines = 1,
            onBack = onBack,
            backTestTag = WORKSPACE_BACK_TAG,
        )

        notice?.let { text ->
            Banner(
                text = text,
                role = BannerRole.Info,
                maxLines = 3,
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(WORKSPACE_CREATE_NOTICE_TAG),
            )
        }

        failure?.let { text ->
            Banner(
                text = text,
                role = BannerRole.Error,
                maxLines = 4,
                trailingContent = {
                    PocketShellButton(
                        text = "Retry",
                        onClick = onRetry,
                        variant = com.pocketshell.uikit.components.ButtonVariant.Text,
                        compact = true,
                        modifier = Modifier.testTag(WORKSPACE_RETRY_TAG),
                    )
                },
                modifier = Modifier
                    .padding(horizontal = PocketShellSpacing.md)
                    .padding(bottom = PocketShellSpacing.sm)
                    .testTag(WORKSPACE_ERROR_TAG),
            )
        }

        EmptyState(
            title = "No sessions here yet",
            description = "Start a terminal in this folder.",
            action = {
                PocketShellButton(
                    text = WORKSPACE_NEW_SESSION_LABEL,
                    onClick = onNewSession,
                    modifier = Modifier.testTag(WORKSPACE_NEW_SESSION_TAG),
                )
            },
        )
    }
}

/** The workspace's short name — its last path segment. */
internal fun workspaceLabelFor(path: String?): String =
    path?.trimEnd('/')?.substringAfterLast('/')?.ifBlank { path }.orEmpty().ifBlank { "Workspace" }
