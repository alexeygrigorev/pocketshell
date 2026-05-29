package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * GitHub repos browser — issue #230 (app-side slice of #205).
 *
 * Lists the user's GitHub repositories (via `pocketshell repos list
 * --remote`) joined with the host's cloned repos (`--local`). Tapping a
 * not-yet-cloned repo clones it on the remote and opens a session in the
 * fresh clone; tapping an already-cloned repo resolves its path and
 * opens a session there directly.
 *
 * ## Interaction
 *
 *  - Tap a "Clone" row → runs `pocketshell repos clone`, shows a per-row
 *    progress spinner, then fires [onOpenRepo] with the clone path.
 *  - Tap an "Open" (already-cloned) row → resolves the path via
 *    `pocketshell repos open` and fires [onOpenRepo].
 *  - A clone/open failure surfaces an inline dismissible banner; the
 *    list stays usable.
 */
@Composable
fun RepoBrowserScreen(
    hostName: String,
    hostname: String,
    port: Int,
    username: String,
    keyPath: String,
    passphrase: CharArray?,
    cloneRoot: String = "~/git",
    onBack: () -> Unit,
    /**
     * Fired when a repo is ready to open — the path is the local clone
     * directory (freshly cloned or already on disk). The caller routes
     * to a tmux session with this path as the start directory.
     */
    onOpenRepo: (path: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RepoBrowserViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostname, port, username, keyPath) {
        viewModel.bind(
            RepoBrowserViewModel.SshCredentials(
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
                passphrase = passphrase,
                cloneRoot = cloneRoot,
            ),
        )
    }
    val state by viewModel.state.collectAsState()

    RepoBrowserScaffold(
        hostName = hostName,
        cloneRoot = cloneRoot,
        state = state,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onRepoClick = { row -> viewModel.onRepoTapped(row, onResolved = onOpenRepo) },
        onDismissError = viewModel::clearActionError,
        modifier = modifier,
    )
}

/**
 * Stateless body of [RepoBrowserScreen] — split out from the view-model
 * wiring so Compose tests can drive every UI state (Loading, Failed,
 * ToolUnavailable, Ready with Clone/Open rows, pending spinner, error
 * banner) without an SSH session. Mirrors the
 * [SessionTypePickerContent] split convention.
 */
@Composable
internal fun RepoBrowserScaffold(
    hostName: String,
    cloneRoot: String = "~/git",
    state: RepoBrowserUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onRepoClick: (RepoRow) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(REPO_BROWSER_SCREEN_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            RepoBrowserAppBar(hostName = hostName, cloneRoot = cloneRoot, onBack = onBack)
            when (val s = state) {
                RepoBrowserUiState.Loading -> LoadingPanel()
                is RepoBrowserUiState.Failed -> RepoErrorPanel(
                    message = s.message,
                    onRetry = onRetry,
                )
                RepoBrowserUiState.ToolUnavailable -> RepoErrorPanel(
                    message = "pocketshell is not installed on $hostName.",
                    onRetry = onRetry,
                )
                is RepoBrowserUiState.Ready -> RepoBrowserContent(
                    state = s,
                    onRepoClick = onRepoClick,
                    onDismissError = onDismissError,
                )
            }
        }
    }
}

@Composable
private fun RepoBrowserAppBar(
    hostName: String,
    cloneRoot: String,
    onBack: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(60.dp)
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clickable(role = Role.Button, onClick = onBack)
                .testTag(REPO_BROWSER_BACK_TAG),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "‹",
                color = PocketShellColors.TextSecondary,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        Column(modifier = Modifier.padding(start = 4.dp).weight(1f)) {
            Text(
                text = "GitHub repos",
                color = PocketShellColors.Text,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.testTag(REPO_BROWSER_TITLE_TAG),
            )
            Text(
                text = if (cloneRoot == "~/git") hostName else "$hostName · $cloneRoot",
                color = PocketShellColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun LoadingPanel() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .testTag(REPO_BROWSER_LOADING_TAG),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = PocketShellColors.Accent)
    }
}

@Composable
private fun RepoErrorPanel(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp)
            .testTag(REPO_BROWSER_ERROR_TAG),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = message,
            color = PocketShellColors.Text,
            fontSize = 14.sp,
        )
        TextButton(onClick = onRetry, modifier = Modifier.testTag(REPO_BROWSER_RETRY_TAG)) {
            Text("Retry", color = PocketShellColors.Accent)
        }
    }
}

@Composable
private fun RepoBrowserContent(
    state: RepoBrowserUiState.Ready,
    onRepoClick: (RepoRow) -> Unit,
    onDismissError: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        state.actionError?.let { error ->
            ActionErrorBanner(message = error, onDismiss = onDismissError)
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.repos.isEmpty()) {
                item { EmptyState() }
            } else {
                items(state.repos, key = { it.fullName }) { repo ->
                    RepoCard(
                        repo = repo,
                        pending = state.pendingFullName == repo.fullName,
                        anyPending = state.pendingFullName != null,
                        onClick = { onRepoClick(repo) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ActionErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(PocketShellColors.Red.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
            .border(1.dp, PocketShellColors.Red.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
            .testTag(REPO_BROWSER_ACTION_ERROR_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            color = PocketShellColors.Text,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(REPO_BROWSER_ACTION_ERROR_DISMISS_TAG),
        ) {
            Text("Dismiss", color = PocketShellColors.Red, fontSize = 12.sp)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag(REPO_BROWSER_EMPTY_TAG),
    ) {
        Text(
            text = "No repositories found",
            color = PocketShellColors.Text,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Make sure `gh` is authenticated on this host.",
            color = PocketShellColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

/**
 * One repository row. The trailing affordance flips between a
 * progress spinner (action in flight), an "Open" pill (cloned), and a
 * "Clone" pill (GitHub-only). The whole card is the tap target.
 */
@Composable
private fun RepoCard(
    repo: RepoRow,
    pending: Boolean,
    anyPending: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface, RoundedCornerShape(12.dp))
            .border(1.dp, PocketShellColors.BorderSoft, RoundedCornerShape(12.dp))
            .clickable(enabled = !anyPending, role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag(repoCardTestTag(repo.fullName)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = repo.name,
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            val subtitle = buildString {
                append(repo.fullName)
                repo.defaultBranch?.takeIf { it.isNotBlank() }?.let {
                    append(" · ")
                    append(it)
                }
                if (repo.cloned && repo.path != null) {
                    append(" · ")
                    append(repo.path)
                }
            }
            Text(
                text = subtitle,
                color = PocketShellColors.TextSecondary,
                fontSize = 11.sp,
            )
        }
        Spacer(modifier = Modifier.size(10.dp))
        when {
            pending -> CircularProgressIndicator(
                color = PocketShellColors.Accent,
                strokeWidth = 2.dp,
                modifier = Modifier
                    .size(18.dp)
                    .testTag(repoCardPendingTestTag(repo.fullName)),
            )
            repo.cloned -> ActionPill(
                label = "Open",
                fg = PocketShellColors.Accent,
                bg = PocketShellColors.AccentSoft,
            )
            else -> ActionPill(
                label = "Clone",
                fg = PocketShellColors.Purple,
                bg = PocketShellColors.Purple.copy(alpha = 0.12f),
            )
        }
    }
}

@Composable
private fun ActionPill(label: String, fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(bg, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// Test tags exposed for the unit / connected E2E suite.
const val REPO_BROWSER_SCREEN_TAG: String = "repo-browser:screen"
const val REPO_BROWSER_BACK_TAG: String = "repo-browser:back"
const val REPO_BROWSER_TITLE_TAG: String = "repo-browser:title"
const val REPO_BROWSER_LOADING_TAG: String = "repo-browser:loading"
const val REPO_BROWSER_ERROR_TAG: String = "repo-browser:error"
const val REPO_BROWSER_RETRY_TAG: String = "repo-browser:retry"
const val REPO_BROWSER_EMPTY_TAG: String = "repo-browser:empty"
const val REPO_BROWSER_ACTION_ERROR_TAG: String = "repo-browser:action-error"
const val REPO_BROWSER_ACTION_ERROR_DISMISS_TAG: String = "repo-browser:action-error:dismiss"

fun repoCardTestTag(fullName: String): String = "repo-browser:card:$fullName"
fun repoCardPendingTestTag(fullName: String): String = "repo-browser:card:$fullName:pending"
