package com.pocketshell.app.git

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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

const val GIT_HISTORY_SCREEN_TAG = "gitHistoryScreen"
const val GIT_HISTORY_BACK_TAG = "gitHistoryBack"
const val GIT_HISTORY_LOADING_TAG = "gitHistoryLoading"
const val GIT_HISTORY_EMPTY_TAG = "gitHistoryEmpty"
const val GIT_HISTORY_NOT_A_REPO_TAG = "gitHistoryNotARepo"
const val GIT_HISTORY_ERROR_TAG = "gitHistoryError"
const val GIT_HISTORY_RETRY_TAG = "gitHistoryRetry"
const val GIT_HISTORY_TRUNCATED_TAG = "gitHistoryTruncated"
const val GIT_HISTORY_ROW_TAG_PREFIX = "gitHistoryRow:"

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
    GitHistoryScaffold(
        hostName = hostName,
        state = state,
        onBack = onBack,
        onRetry = viewModel::retry,
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
                is GitHistoryUiState.Ready -> ReadyPanel(state)
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
private fun ReadyPanel(state: GitHistoryUiState.Ready) {
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
