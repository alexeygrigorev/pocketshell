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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.pocketshell.uikit.components.SegmentedToggle
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
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
    LaunchedEffect(hostname, port, username, keyPath, dir) {
        viewModel.start(
            GitHistoryViewModel.Request(
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
    val context = LocalContext.current
    GitHistoryScaffold(
        hostName = hostName,
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
        onOpenGitHub = { url ->
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
        },
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
    onOpenGitHub: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
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
                is GitHistoryUiState.Ready -> ReadyPanel(state, onOpenGitHub)
                is GitHistoryUiState.NotARepo -> NotARepoPanel(state.dir)
                is GitHistoryUiState.Failed -> ErrorPanel(
                    message = state.message,
                    canRetry = state.canRetry,
                    onRetry = onRetry,
                )
            }
        }
    }
}

/**
 * The two top-level views (issue #647): the repo Overview (branches, worktrees,
 * status) and the commit History from #646.
 */
private enum class GitTab(val label: String) {
    Overview("Overview"),
    History("History"),
}

@Composable
private fun ReadyPanel(
    state: GitHistoryUiState.Ready,
    onOpenGitHub: (String) -> Unit,
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
                    CircularProgressIndicator(
                        color = PocketShellColors.Accent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp),
                    )
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
