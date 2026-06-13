package com.pocketshell.app.git

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.components.Badge
import com.pocketshell.uikit.components.BadgeRole
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.components.StatusDot
import com.pocketshell.uikit.model.ConnectionStatus
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

const val GIT_HISTORY_SCREEN_TAG = "gitHistoryScreen"
const val GIT_HISTORY_BACK_TAG = "gitHistoryBack"
const val GIT_HISTORY_LOADING_TAG = "gitHistoryLoading"
const val GIT_HISTORY_EMPTY_TAG = "gitHistoryEmpty"
const val GIT_HISTORY_NOT_A_REPO_TAG = "gitHistoryNotARepo"
const val GIT_HISTORY_ERROR_TAG = "gitHistoryError"
const val GIT_HISTORY_RETRY_TAG = "gitHistoryRetry"
const val GIT_HISTORY_TRUNCATED_TAG = "gitHistoryTruncated"
const val GIT_HISTORY_ROW_TAG_PREFIX = "gitHistoryRow:"

// Overview tab (issue #647): branches, worktrees, status.
const val GIT_TAB_TOGGLE_TAG = "gitTabToggle"
const val GIT_OVERVIEW_TAB_TAG = "gitTabOverview"
const val GIT_HISTORY_TAB_TAG = "gitTabHistory"
const val GIT_OVERVIEW_STATUS_TAG = "gitOverviewStatus"
const val GIT_OVERVIEW_STATUS_UNAVAILABLE_TAG = "gitOverviewStatusUnavailable"
const val GIT_BRANCH_ROW_TAG_PREFIX = "gitBranchRow:"
const val GIT_WORKTREE_ROW_TAG_PREFIX = "gitWorktreeRow:"

// "Open on GitHub" action (issue #648) — only shown when origin is a GitHub repo.
const val GIT_OPEN_ON_GITHUB_TAG = "gitOpenOnGitHub"

// Issues tab (issue #649): the repo's GitHub issues via `gh issue list`.
const val GIT_ISSUES_TAB_TAG = "gitTabIssues"
const val GIT_ISSUES_HINT_TAG = "gitIssuesHint"
const val GIT_ISSUES_EMPTY_TAG = "gitIssuesEmpty"
const val GIT_ISSUES_UNAVAILABLE_TAG = "gitIssuesUnavailable"
const val GIT_ISSUE_ROW_TAG_PREFIX = "gitIssueRow:"

// Create-issue form (issue #650): the "New issue" affordance + entry sheet.
const val GIT_NEW_ISSUE_TAG = "gitNewIssue"
const val GIT_CREATE_ISSUE_SHEET_TAG = "gitCreateIssueSheet"
const val GIT_CREATE_ISSUE_TITLE_TAG = "gitCreateIssueTitle"
const val GIT_CREATE_ISSUE_BODY_TAG = "gitCreateIssueBody"
const val GIT_CREATE_ISSUE_SUBMIT_TAG = "gitCreateIssueSubmit"
const val GIT_CREATE_ISSUE_CANCEL_TAG = "gitCreateIssueCancel"
const val GIT_CREATE_ISSUE_ERROR_TAG = "gitCreateIssueError"
const val GIT_CREATE_ISSUE_SUCCESS_TAG = "gitCreateIssueSuccess"
const val GIT_CREATE_ISSUE_OPEN_TAG = "gitCreateIssueOpen"

/**
 * Git commit-history / timeline view for a project directory — issue #646
 * (epic #644 slice 2).
 *
 * Read-only: lists recent commits (short hash, author, relative time, subject)
 * via `git log` over SSH through [GitHistoryViewModel]. Reachable from the
 * host-detail folder action sheet's "Git history" row, rooted at that project's
 * path.
 */
@Composable
fun GitHistoryScreen(
    hostId: Long,
    hostName: String,
    hostname: String,
    port: Int,
    username: String,
    keyPath: String,
    passphrase: CharArray?,
    dir: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GitHistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostId, hostname, port, username, keyPath, dir) {
        viewModel.start(
            GitHistoryViewModel.Request(
                hostId = hostId,
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
                passphrase = passphrase,
                dir = dir,
            ),
        )
    }
    val state by viewModel.state.collectAsState()
    val createState by viewModel.createState.collectAsState()
    val context = LocalContext.current
    val openUrl: (String) -> Unit = { url ->
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }
    GitHistoryScaffold(
        hostName = hostName,
        state = state,
        createState = createState,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenGitHub = openUrl,
        onSubmitNewIssue = viewModel::createIssue,
        onDismissCreateIssue = viewModel::dismissCreateIssue,
        onOpenIssueUrl = openUrl,
        modifier = modifier,
    )
}

/**
 * Stateless body — split from the view-model wiring so Compose / render tests
 * can drive every state (Loading, Ready, empty, NotARepo, Failed) without an
 * SSH session. Mirrors the file-explorer's scaffold convention.
 */
@Composable
internal fun GitHistoryScaffold(
    hostName: String,
    state: GitHistoryUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    createState: CreateIssueUiState = CreateIssueUiState.Idle,
    onOpenGitHub: (String) -> Unit = {},
    onSubmitNewIssue: (String, String) -> Unit = { _, _ -> },
    onDismissCreateIssue: () -> Unit = {},
    onOpenIssueUrl: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showCreateSheet by rememberSaveable { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(PocketShellColors.Background)
            .testTag(GIT_HISTORY_SCREEN_TAG),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            GitHistoryHeader(
                hostName = hostName,
                dir = state.dir,
                onBack = onBack,
            )
            when (state) {
                is GitHistoryUiState.Loading -> LoadingPanel()
                is GitHistoryUiState.Ready -> ReadyPanel(
                    state = state,
                    onOpenGitHub = onOpenGitHub,
                    onNewIssue = {
                        onDismissCreateIssue()
                        showCreateSheet = true
                    },
                )
                is GitHistoryUiState.NotARepo -> NotARepoPanel(state.dir)
                is GitHistoryUiState.Failed -> ErrorPanel(
                    message = state.message,
                    canRetry = state.canRetry,
                    onRetry = onRetry,
                )
            }
        }
    }

    if (showCreateSheet) {
        CreateIssueSheet(
            createState = createState,
            onSubmit = onSubmitNewIssue,
            onOpenUrl = onOpenIssueUrl,
            onDismiss = {
                showCreateSheet = false
                onDismissCreateIssue()
            },
        )
    }
}

/**
 * The two top-level views (issue #647): the repo Overview (branches, worktrees,
 * status) and the commit History from #646.
 */
private enum class GitTab(val label: String) {
    Overview("Overview"),
    History("History"),
    Issues("Issues"),
}

@Composable
private fun ReadyPanel(
    state: GitHistoryUiState.Ready,
    onOpenGitHub: (String) -> Unit,
    onNewIssue: () -> Unit,
) {
    // Default to Overview so the at-a-glance "what's happening" view (status,
    // branches, worktrees) is what the user lands on; History is one tap away.
    var tab by rememberSaveable { mutableStateOf(GitTab.Overview) }
    Column(modifier = Modifier.fillMaxSize()) {
        SegmentedToggle(
            labels = GitTab.entries.map { it.label },
            selectedIndex = tab.ordinal,
            onSelected = { tab = GitTab.entries[it] },
            fillSegments = true,
            segmentTag = { index ->
                when (GitTab.entries[index]) {
                    GitTab.Overview -> GIT_OVERVIEW_TAB_TAG
                    GitTab.History -> GIT_HISTORY_TAB_TAG
                    GitTab.Issues -> GIT_ISSUES_TAB_TAG
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PocketShellDensity.rowPadH,
                    vertical = PocketShellSpacing.sm,
                )
                .testTag(GIT_TAB_TOGGLE_TAG),
        )
        when (tab) {
            GitTab.Overview -> OverviewPanel(state, onOpenGitHub)
            GitTab.History -> HistoryPanel(state)
            GitTab.Issues -> IssuesPanel(state, onNewIssue = onNewIssue)
        }
    }
}

@Composable
private fun GitHistoryHeader(
    hostName: String,
    dir: String,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft),
    ) {
        ScreenHeader(
            title = "Git history",
            subtitle = hostName.ifBlank { null },
            leading = {
                Box(
                    modifier = Modifier
                        .size(PocketShellDensity.tapTargetMin)
                        .clickable(role = Role.Button, onClick = onBack)
                        .testTag(GIT_HISTORY_BACK_TAG),
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
        if (dir.isNotBlank()) {
            Text(
                text = dir,
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyMono,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = PocketShellDensity.rowPadH,
                        end = PocketShellDensity.rowPadH,
                        bottom = PocketShellSpacing.sm,
                    ),
            )
        }
    }
}

@Composable
private fun HistoryPanel(state: GitHistoryUiState.Ready) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        item(key = "__commits_header__") {
            SectionHeader(label = "Commits", count = state.commits.size)
        }
        if (state.commits.isEmpty()) {
            item(key = "__empty__") {
                ListRow(
                    title = "No commits yet",
                    subtitle = "This repository has no commit history.",
                    leading = { GlyphCell("--") },
                    modifier = Modifier.testTag(GIT_HISTORY_EMPTY_TAG),
                )
            }
        }
        itemsIndexed(state.commits, key = { index, c -> "$index:${c.shortHash}" }) { _, commit ->
            ListRow(
                title = commit.subject.ifBlank { "(no subject)" },
                subtitle = "${commit.author} · ${commit.relativeTime}",
                leading = { GlyphCell(commit.shortHash) },
                modifier = Modifier.testTag(GIT_HISTORY_ROW_TAG_PREFIX + commit.shortHash),
            )
        }
        if (state.truncated) {
            item(key = "__truncated__") {
                ListRow(
                    title = "History truncated",
                    subtitle = "Showing the most recent ${state.commits.size} commits.",
                    leading = { GlyphCell("…") },
                    modifier = Modifier.testTag(GIT_HISTORY_TRUNCATED_TAG),
                )
            }
        }
    }
}

/**
 * Issues tab (issue #649): the repo's GitHub issues via `gh issue list`.
 *
 * Gated on gh being configured on the remote (slice 1, #645). When gh is not
 * installed/authenticated, [GitHistoryUiState.Ready.ghHint] is set and we show a
 * single "configure gh" hint row instead of a list. When gh is configured the
 * list renders (number, title, open/closed dot, labels); an empty list shows an
 * empty state. When the listing failed despite gh being configured (issues null,
 * no hint), a neutral "unavailable" row is shown.
 */
@Composable
private fun IssuesPanel(
    state: GitHistoryUiState.Ready,
    onNewIssue: () -> Unit,
) {
    val ghHint = state.ghHint
    val issues = state.issues
    // The "New issue" affordance is gated on gh being configured (#645/#649):
    // only offered when there's no configure-gh hint to show.
    val canCreate = ghHint == null
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        if (canCreate) {
            item(key = "__new_issue__") {
                ListRow(
                    title = "New issue",
                    subtitle = "Create a GitHub issue in this repository.",
                    leading = { GlyphCell("+") },
                    onClick = onNewIssue,
                    modifier = Modifier.testTag(GIT_NEW_ISSUE_TAG),
                )
            }
        }
        if (ghHint != null) {
            item(key = "__issues_hint_header__") { SectionHeader(label = "GitHub issues") }
            item(key = "__issues_hint__") {
                ListRow(
                    title = "Configure gh to see issues",
                    subtitle = ghHint,
                    leading = { GlyphCell("gh") },
                    trailing = { Badge(label = "Setup", role = BadgeRole.Idle, mono = false) },
                    modifier = Modifier.testTag(GIT_ISSUES_HINT_TAG),
                )
            }
            return@LazyColumn
        }

        if (issues == null) {
            item(key = "__issues_unavailable_header__") { SectionHeader(label = "GitHub issues") }
            item(key = "__issues_unavailable__") {
                ListRow(
                    title = "Issues unavailable",
                    subtitle = "Couldn't list GitHub issues for this repository.",
                    leading = { GlyphCell("--") },
                    modifier = Modifier.testTag(GIT_ISSUES_UNAVAILABLE_TAG),
                )
            }
            return@LazyColumn
        }

        item(key = "__issues_header__") {
            SectionHeader(label = "GitHub issues", count = issues.size)
        }
        if (issues.isEmpty()) {
            item(key = "__issues_empty__") {
                ListRow(
                    title = "No issues",
                    subtitle = "This repository has no GitHub issues.",
                    leading = { GlyphCell("--") },
                    modifier = Modifier.testTag(GIT_ISSUES_EMPTY_TAG),
                )
            }
        }
        items(issues, key = { "issue:${it.number}" }) { issue ->
            val subtitle = buildString {
                append("#").append(issue.number)
                if (issue.labels.isNotEmpty()) {
                    append(" · ").append(issue.labels.joinToString(", "))
                }
            }
            ListRow(
                title = issue.title.ifBlank { "(no title)" },
                subtitle = subtitle,
                leading = {
                    Box(
                        modifier = Modifier.width(64.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        StatusDot(status = issue.state.connectionStatus())
                    }
                },
                trailing = {
                    when (issue.state) {
                        GitHubIssueState.Open ->
                            Badge(label = "Open", role = BadgeRole.Active, mono = false)
                        GitHubIssueState.Closed ->
                            Badge(label = "Closed", role = BadgeRole.Idle, mono = false)
                        GitHubIssueState.Unknown -> Unit
                    }
                },
                modifier = Modifier.testTag(GIT_ISSUE_ROW_TAG_PREFIX + issue.number),
            )
        }
    }
}

/** Map an issue's open/closed state onto the shared status-dot vocabulary. */
private fun GitHubIssueState.connectionStatus(): ConnectionStatus = when (this) {
    GitHubIssueState.Open -> ConnectionStatus.Connected
    GitHubIssueState.Closed -> ConnectionStatus.Idle
    GitHubIssueState.Unknown -> ConnectionStatus.Idle
}

/**
 * Repo Overview (issue #647): a Status summary row, the local Branches, and the
 * linked Worktrees. The overview is best-effort — when its probe failed but the
 * history loaded, [GitHistoryUiState.Ready.overview] is null and we show a single
 * "unavailable" row rather than a hard error.
 */
@Composable
private fun OverviewPanel(
    state: GitHistoryUiState.Ready,
    onOpenGitHub: (String) -> Unit,
) {
    val overview = state.overview
    val gitHubUrl = state.gitHubUrl
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        // GitHub repo detected (issue #648): an "Open on GitHub" row that fires
        // an ACTION_VIEW intent to the canonical repo page. Only present when
        // origin points at GitHub.
        if (gitHubUrl != null) {
            item(key = "__github_header__") { SectionHeader(label = "Remote") }
            item(key = "__github_row__") {
                ListRow(
                    title = "Open on GitHub",
                    subtitle = gitHubUrl.removePrefix("https://"),
                    leading = { GlyphCell("↗") },
                    onClick = { onOpenGitHub(gitHubUrl) },
                    modifier = Modifier.testTag(GIT_OPEN_ON_GITHUB_TAG),
                )
            }
        }

        if (overview == null) {
            item(key = "__status_header__") { SectionHeader(label = "Status") }
            item(key = "__status_unavailable__") {
                ListRow(
                    title = "Overview unavailable",
                    subtitle = "Couldn't read branches and status for this repository.",
                    leading = { GlyphCell("--") },
                    modifier = Modifier.testTag(GIT_OVERVIEW_STATUS_UNAVAILABLE_TAG),
                )
            }
            return@LazyColumn
        }

        val status = overview.status
        item(key = "__status_header__") { SectionHeader(label = "Status") }
        item(key = "__status_row__") {
            val branchLabel = status.currentBranch ?: "detached HEAD"
            val trackingLine = when {
                status.hasNoCommits -> "No commits yet"
                status.upstream == null -> "No upstream"
                status.ahead == 0 && status.behind == 0 -> "Up to date with ${status.upstream}"
                else -> buildString {
                    val parts = mutableListOf<String>()
                    if (status.ahead > 0) parts += "↑${status.ahead}"
                    if (status.behind > 0) parts += "↓${status.behind}"
                    append(parts.joinToString(" "))
                    append(" vs ").append(status.upstream)
                }
            }
            val dirtyLine = if (status.dirty) {
                "${status.changedFiles} uncommitted change${if (status.changedFiles == 1) "" else "s"}"
            } else {
                "Working tree clean"
            }
            ListRow(
                title = branchLabel,
                subtitle = "$trackingLine · $dirtyLine" +
                    (status.lastCommit?.let { "\n$it" } ?: ""),
                leading = { GlyphCell("▶") },
                trailing = {
                    if (status.dirty) {
                        Badge(label = "Dirty", role = BadgeRole.Error, mono = false)
                    } else {
                        Badge(label = "Clean", role = BadgeRole.Active, mono = false)
                    }
                },
                modifier = Modifier.testTag(GIT_OVERVIEW_STATUS_TAG),
            )
        }

        item(key = "__branches_header__") {
            SectionHeader(label = "Branches", count = overview.branches.size)
        }
        items(overview.branches, key = { "branch:${it.name}" }) { branch ->
            ListRow(
                title = branch.name,
                subtitle = branch.subject?.ifBlank { null }
                    ?: branch.upstream?.let { "tracks $it" }
                    ?: "Local branch",
                leading = { GlyphCell(if (branch.current) "●" else "○") },
                trailing = {
                    if (branch.current) {
                        Badge(label = "Current", role = BadgeRole.Active, mono = false)
                    }
                },
                modifier = Modifier.testTag(GIT_BRANCH_ROW_TAG_PREFIX + branch.name),
            )
        }

        item(key = "__worktrees_header__") {
            SectionHeader(label = "Worktrees", count = overview.worktrees.size)
        }
        items(overview.worktrees, key = { "worktree:${it.path}" }) { worktree ->
            val label = when {
                worktree.bare -> "(bare)"
                worktree.detached || worktree.branch == null ->
                    "detached${worktree.head?.let { " · $it" } ?: ""}"
                else -> worktree.branch
            }
            ListRow(
                title = worktree.path,
                subtitle = label,
                leading = { GlyphCell(worktree.head ?: "--") },
                modifier = Modifier.testTag(GIT_WORKTREE_ROW_TAG_PREFIX + worktree.path),
            )
        }
    }
}

@Composable
private fun GlyphCell(glyph: String) {
    Box(
        modifier = Modifier.width(64.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = glyph,
            color = PocketShellColors.Accent,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
private fun LoadingPanel() {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(GIT_HISTORY_LOADING_TAG),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        item { SectionHeader(label = "Status") }
        item {
            ListRow(
                title = "Loading history",
                subtitle = "Reading git log",
                leading = {
                    LoadingIndicator.Spinner(size = SpinnerSize.Small)
                },
            )
        }
    }
}

@Composable
private fun NotARepoPanel(dir: String) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(GIT_HISTORY_NOT_A_REPO_TAG),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        item { SectionHeader(label = "Status") }
        item {
            ListRow(
                title = "Not a git repository",
                subtitle = "$dir isn't a git working tree, so there's no history to show.",
                leading = { GlyphCell("--") },
                trailing = { Badge(label = "No git", role = BadgeRole.Idle, mono = false) },
            )
        }
    }
}

@Composable
private fun ErrorPanel(
    message: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(GIT_HISTORY_ERROR_TAG),
        contentPadding = PaddingValues(vertical = PocketShellSpacing.sm),
    ) {
        item { SectionHeader(label = "Status") }
        item {
            ListRow(
                title = "Could not load history",
                subtitle = message,
                leading = { GlyphCell("!") },
                trailing = { Badge(label = "Error", role = BadgeRole.Error, mono = false) },
            )
        }
        if (canRetry) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PocketShellDensity.rowPadH),
                    horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
                ) {
                    TextButton(
                        onClick = onRetry,
                        modifier = Modifier.testTag(GIT_HISTORY_RETRY_TAG),
                    ) {
                        Text(
                            "Retry",
                            color = PocketShellColors.Accent,
                            style = PocketShellType.bodyDense,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Create-issue entry sheet (issue #650, epic #644 slice 6).
 *
 * A Material3 [ModalBottomSheet] with a title + body field and a confirm
 * button. While [CreateIssueUiState.Submitting] the fields and confirm are
 * disabled and the button shows a spinner. On [CreateIssueUiState.Success] the
 * form is replaced by a success row carrying the new issue URL and an "Open"
 * action ([onOpenUrl], the #648 ACTION_VIEW pattern). On
 * [CreateIssueUiState.Failure] an inline error row is shown above the still-
 * editable form so the user can fix the input and retry.
 *
 * Confirm is enabled only when the title is non-blank — gh rejects an empty
 * title, and [GitHistoryGateway.createIssue] fails fast on a blank one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateIssueSheet(
    createState: CreateIssueUiState,
    onSubmit: (String, String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var title by rememberSaveable { mutableStateOf("") }
    var body by rememberSaveable { mutableStateOf("") }
    val titleFocus = remember { FocusRequester() }
    val submitting = createState is CreateIssueUiState.Submitting
    val success = createState as? CreateIssueUiState.Success

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        modifier = Modifier.testTag(GIT_CREATE_ISSUE_SHEET_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        ) {
            Text(
                text = "New GitHub issue",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )

            if (success != null) {
                CreateIssueSuccess(url = success.url, onOpenUrl = onOpenUrl, onClose = onDismiss)
                return@Column
            }

            LaunchedEffect(Unit) { titleFocus.requestFocus() }

            (createState as? CreateIssueUiState.Failure)?.let { failure ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
                        .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
                        .padding(PocketShellSpacing.md)
                        .testTag(GIT_CREATE_ISSUE_ERROR_TAG),
                ) {
                    Text(
                        text = "Couldn't create issue: ${failure.message}",
                        color = PocketShellColors.Red,
                        style = PocketShellType.bodyDense,
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                enabled = !submitting,
                label = { Text("Title") },
                colors = issueFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(titleFocus)
                    .testTag(GIT_CREATE_ISSUE_TITLE_TAG),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                enabled = !submitting,
                label = { Text("Body (optional)") },
                colors = issueFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp)
                    .testTag(GIT_CREATE_ISSUE_BODY_TAG),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
                TextButton(
                    onClick = onDismiss,
                    enabled = !submitting,
                    modifier = Modifier.testTag(GIT_CREATE_ISSUE_CANCEL_TAG),
                ) {
                    Text(
                        "Cancel",
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.bodyDense,
                    )
                }
                Button(
                    onClick = { onSubmit(title.trim(), body.trim()) },
                    enabled = !submitting && title.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PocketShellColors.Accent,
                        contentColor = PocketShellColors.Background,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .testTag(GIT_CREATE_ISSUE_SUBMIT_TAG),
                ) {
                    if (submitting) {
                        CircularProgressIndicator(
                            color = PocketShellColors.Background,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Text("Create issue", style = PocketShellType.bodyDense)
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateIssueSuccess(
    url: String,
    onOpenUrl: (String) -> Unit,
    onClose: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .testTag(GIT_CREATE_ISSUE_SUCCESS_TAG),
    ) {
        ListRow(
            title = "Issue created",
            subtitle = url.removePrefix("https://"),
            leading = { GlyphCell("✓") },
            onClick = { onOpenUrl(url) },
            trailing = { Badge(label = "Open", role = BadgeRole.Active, mono = false) },
            modifier = Modifier.testTag(GIT_CREATE_ISSUE_OPEN_TAG),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
        TextButton(
            onClick = onClose,
            modifier = Modifier.testTag(GIT_CREATE_ISSUE_CANCEL_TAG),
        ) {
            Text(
                "Done",
                color = PocketShellColors.Accent,
                style = PocketShellType.bodyDense,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun issueFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = PocketShellColors.Text,
    unfocusedTextColor = PocketShellColors.Text,
    disabledTextColor = PocketShellColors.TextSecondary,
    focusedBorderColor = PocketShellColors.Accent,
    unfocusedBorderColor = PocketShellColors.BorderSoft,
    focusedLabelColor = PocketShellColors.Accent,
    unfocusedLabelColor = PocketShellColors.TextSecondary,
    cursorColor = PocketShellColors.Accent,
)
