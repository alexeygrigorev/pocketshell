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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

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
 *    progress spinner, then opens the Shell/Agent [SessionTypePickerSheet]
 *    pre-filled with the clone path.
 *  - Tap an "Open" (already-cloned) row → resolves the path via
 *    `pocketshell repos open` and opens the same picker pre-filled with
 *    that path.
 *  - Confirming the picker creates + attaches the session via the SAME
 *    picker→create→attach path the folder "+ New session" flow uses
 *    ([FolderListViewModel.createSession] → [onSessionCreated]); there is
 *    no direct `navigate(TmuxSession)` bypass (issue #516, D22).
 *  - A clone/open failure surfaces an inline dismissible banner; the
 *    list stays usable.
 */
@Composable
fun RepoBrowserScreen(
    hostId: Long,
    hostName: String,
    hostname: String,
    port: Int,
    username: String,
    keyPath: String,
    passphrase: CharArray?,
    cloneRoot: String = "~/git",
    onBack: () -> Unit,
    /**
     * Fired after the [SessionTypePickerSheet] successfully created a
     * session on the remote in the resolved repo clone directory — the
     * SAME contract the folder new-session flow uses
     * ([FolderListScreen]'s `onSessionCreated`). By the time this fires
     * the tmux session exists (and, for an Agent pick, the agent CLI has
     * already been `send-keys`'d into the new pane by the gateway), so
     * the caller (MainActivity) only needs to attach by routing to
     * `AppDestination.TmuxSession` with the resolved name + cwd.
     */
    onSessionCreated: (sessionName: String, cwd: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RepoBrowserViewModel = hiltViewModel(),
    sessionViewModel: FolderListViewModel = hiltViewModel(),
    suggestStartDirectories: (suspend (String) -> List<String>)? = null,
) {
    LaunchedEffect(hostId, hostname, port, username, keyPath) {
        viewModel.bind(
            RepoBrowserViewModel.SshCredentials(
                hostId = hostId,
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
                passphrase = passphrase,
                cloneRoot = cloneRoot,
            ),
        )
    }
    // Reuse the folder flow's create path: the picker confirm calls
    // FolderListViewModel.createSession, which runs the same
    // FolderListGateway.createSession used by the folder new-session
    // sheet. Bind it to this host so the create RPC has credentials.
    LaunchedEffect(hostId, hostname, port, username, keyPath) {
        sessionViewModel.bind(
            hostId = hostId,
            hostName = hostName,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = passphrase,
        )
    }
    val state by viewModel.state.collectAsState()
    val sessionState by sessionViewModel.state.collectAsState()

    // Pre-filled repo path the picker should open on. Set once a repo tap
    // resolves (clone-then-open or already-cloned), cleared on dismiss /
    // confirm. Replaces the old direct onOpenRepo → TmuxSession bypass.
    var pickerRepoPath by remember { mutableStateOf<String?>(null) }

    RepoBrowserScaffold(
        hostName = hostName,
        cloneRoot = cloneRoot,
        state = state,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onRepoClick = { row ->
            viewModel.onRepoTapped(row, onResolved = { path -> pickerRepoPath = path })
        },
        onDismissError = viewModel::clearActionError,
        modifier = modifier,
    )

    pickerRepoPath?.let { repoPath ->
        SessionTypePickerSheet(
            folderPath = repoPath,
            folderLabel = repoFolderLabel(repoPath),
            onDismiss = { pickerRepoPath = null },
            suggestStartDirectories = suggestStartDirectories,
            onCreate = { choice ->
                pickerRepoPath = null
                val newName = derivedSessionName(
                    choice = choice,
                    homeDirectory = conventionalRemoteHome(username),
                    existingNames = knownSessionNames(sessionState),
                )
                sessionViewModel.createSession(
                    sessionName = newName,
                    cwd = choice.startDirectory,
                    startCommand = choice.startCommand(),
                    onResolved = { resolved ->
                        onSessionCreated(resolved, choice.startDirectory)
                    },
                )
            },
        )
    }
}

/**
 * Short label for the picker header — the repo clone's trailing folder
 * segment (e.g. `/home/alexey/git/pocketshell` → `pocketshell`), falling
 * back to the full path when there's no usable segment.
 */
internal fun repoFolderLabel(path: String): String =
    path.trim().trimEnd('/').substringAfterLast('/').ifBlank { path }

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
    ScreenHeader(
        title = "GitHub repos",
        subtitle = if (cloneRoot == "~/git") hostName else "$hostName · $cloneRoot",
        titleTestTag = REPO_BROWSER_TITLE_TAG,
        leading = {
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
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
        },
    )
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
            style = PocketShellType.bodyDense,
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
            contentPadding = PaddingValues(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellSpacing.md,
            ),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            if (state.repos.isEmpty()) {
                item { EmptyState() }
            } else {
                item {
                    SectionHeader(label = "Repositories", count = state.repos.size)
                }
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
            style = PocketShellType.bodyDense,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.testTag(REPO_BROWSER_ACTION_ERROR_DISMISS_TAG),
        ) {
            Text("Dismiss", color = PocketShellColors.Red, style = MaterialTheme.typography.labelSmall)
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
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.md)
            .testTag(REPO_BROWSER_EMPTY_TAG),
    ) {
        Text(
            text = "No repositories found",
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Make sure `gh` is authenticated on this host.",
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
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
    ListRow(
        title = repo.name,
        subtitle = subtitle,
        modifier = Modifier.testTag(repoCardTestTag(repo.fullName)),
        onClick = onClick.takeIf { !anyPending },
        trailing = {
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
                    role = BadgeRole.Active,
                )
                else -> ActionPill(
                    label = "Clone",
                    role = BadgeRole.Agent,
                )
            }
        },
    )
}

@Composable
private fun ActionPill(label: String, role: BadgeRole) {
    Badge(label = label, role = role, mono = false)
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
