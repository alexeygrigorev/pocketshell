package com.pocketshell.next.share

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.Text
import com.pocketshell.uikit.components.Banner
import com.pocketshell.uikit.components.BannerRole
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.EmptyState
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.LoadingIndicator
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.components.SpinnerSize
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Stable test tags — a journey taps a host without matching user-visible copy. */
const val SHARE_SCREEN_TAG: String = "share-screen"
const val SHARE_PROGRESS_TAG: String = "share-progress"
const val SHARE_SUCCESS_TAG: String = "share-success"
const val SHARE_FAILURE_TAG: String = "share-failure"
const val SHARE_RETRY_TAG: String = "share-retry"
const val SHARE_PICK_ANOTHER_TAG: String = "share-pick-another"
const val SHARE_DONE_TAG: String = "share-done"
const val SHARE_EMPTY_TAG: String = "share-no-hosts"

fun shareHostRowTag(hostId: Long): String = "share-host-$hostId"

/** Route-level entry point: binds the Hilt ViewModel to the stateless screen. */
@Composable
fun ShareRoute(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ShareViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    SharePickerScreen(
        state = state,
        onPickHost = viewModel::uploadTo,
        onRetry = viewModel::retry,
        onPickAnother = viewModel::backToPicker,
        onFinished = onFinished,
        modifier = modifier,
    )
}

/**
 * The share target's one screen (rewrite task P-9).
 *
 * Four states, one at a time, deliberately not stacked: pick a host, watch it
 * upload, read the result, leave. The shipping client's picker also carried a
 * text-vs-file dispatch dialog, a per-host project-target chooser and a
 * paste-into-session branch — roughly 1,000 lines of surface for a gesture whose
 * whole job is "put this file on that machine".
 *
 * The screen is stateless: everything it paints comes from [ShareUiState], which
 * is what lets a Robolectric test render each state directly and a design render
 * show them side by side.
 */
@Composable
fun SharePickerScreen(
    state: ShareUiState,
    onPickHost: (Long) -> Unit,
    onRetry: (Long) -> Unit,
    onPickAnother: () -> Unit,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().testTag(SHARE_SCREEN_TAG)) {
        ScreenHeader(
            title = "Send to host",
            // The staged files only. The DESTINATION is stated by whichever
            // state is below (the picker's section header, the progress line,
            // the landed paths) — repeating it here just truncated the header
            // subtitle to `~/inbox/pock…`.
            subtitle = stagedSummary(state.items),
            trailing = {
                // "Done" is the primary action ONLY when the share worked. A
                // primary Done next to "Try again" on a failure reads as if the
                // failure were an outcome to accept.
                val (label, variant) = when (state.upload) {
                    is ShareUploadState.Success -> "Done" to ButtonVariant.Primary
                    is ShareUploadState.Failed -> "Close" to ButtonVariant.Text
                    else -> "Cancel" to ButtonVariant.Text
                }
                PocketShellButton(
                    text = label,
                    onClick = onFinished,
                    variant = variant,
                    compact = true,
                    modifier = Modifier.testTag(SHARE_DONE_TAG),
                )
            },
        )

        // `weight(1f)` rather than another `fillMaxSize`: a child that fills the
        // whole parent height UNDER a header pushes its own content past the
        // bottom edge, which renders a host list nobody can see or tap.
        val body = Modifier.weight(1f)
        when (val upload = state.upload) {
            is ShareUploadState.Idle -> HostPicker(state, onPickHost, body)
            is ShareUploadState.Running -> Progress(upload, body)
            is ShareUploadState.Success -> Success(upload, body)
            is ShareUploadState.Failed -> Failure(upload, state, onRetry, onPickAnother, body)
        }
    }
}

@Composable
private fun HostPicker(state: ShareUiState, onPickHost: (Long) -> Unit, modifier: Modifier) {
    when {
        !state.hostsLoaded -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator.Spinner(size = SpinnerSize.Medium)
        }

        state.hosts.isEmpty() -> EmptyState(
            title = "No hosts yet",
            description = "Add a host in PocketShell first, then share again.",
            modifier = modifier.testTag(SHARE_EMPTY_TAG),
        )

        else -> Column(modifier = modifier.fillMaxSize()) {
            // The destination lives here, in full: it is the one thing the user
            // cannot change on this screen, so it must be stated rather than
            // discovered after the upload.
            SectionHeader(label = "Send to ${ShareUploader.INBOX_DISPLAY_PATH} on")
            LazyColumn(
                // `weight`, not `fillMaxSize`, for the same reason the body
                // slot above uses it: under the section header, a full-height
                // list starts below the screen.
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(bottom = PocketShellSpacing.lg),
            ) {
                items(state.hosts, key = { it.id }) { host ->
                    ListRow(
                        title = host.name,
                        subtitle = host.subtitle,
                        onClick = { onPickHost(host.id) },
                        // The connected host is marked rather than reordered:
                        // moving rows around under a finger already reaching for
                        // one is how a share lands on the wrong machine.
                        trailing = if (host.connected) {
                            { Pill(label = "connected", kind = PillKind.Ok) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag(shareHostRowTag(host.id)),
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(upload: ShareUploadState.Running, modifier: Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PocketShellSpacing.lg)
            .testTag(SHARE_PROGRESS_TAG),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md, Alignment.CenterVertically),
    ) {
        LoadingIndicator.Spinner(size = SpinnerSize.Medium)
        Text(
            text = upload.detail,
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
        )
        Text(
            text = "to ${upload.hostName}:${ShareUploader.INBOX_DISPLAY_PATH}",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.bodyDense,
        )
    }
}

/**
 * The result screen. No "Done" button of its own — the header's is the primary
 * action once the share succeeded, and two of them on one screen is a choice the
 * user has to read twice to discover is not a choice.
 */
@Composable
private fun Success(
    upload: ShareUploadState.Success,
    modifier: Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PocketShellSpacing.lg)
            .testTag(SHARE_SUCCESS_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        Banner(text = upload.message, role = BannerRole.Info)
        // Every landed path, verbatim: the user's next move is usually to type
        // (or dictate) this path at an agent, so it has to be the real one.
        upload.paths.forEach { path ->
            Text(
                text = path,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.bodyMono,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Failure(
    upload: ShareUploadState.Failed,
    state: ShareUiState,
    onRetry: (Long) -> Unit,
    onPickAnother: () -> Unit,
    modifier: Modifier,
) {
    val hostId = state.hosts.firstOrNull { it.name == upload.hostName }?.id
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(PocketShellSpacing.lg)
            .testTag(SHARE_FAILURE_TAG),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
    ) {
        Banner(text = upload.message, role = BannerRole.Error)
        upload.uploaded.forEach { path ->
            Text(
                text = path,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.bodyMono,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm)) {
            if (hostId != null) {
                PocketShellButton(
                    text = "Try again",
                    onClick = { onRetry(hostId) },
                    variant = ButtonVariant.Primary,
                    modifier = Modifier.testTag(SHARE_RETRY_TAG),
                )
            }
            PocketShellButton(
                text = "Choose another host",
                onClick = onPickAnother,
                variant = ButtonVariant.Text,
                modifier = Modifier.testTag(SHARE_PICK_ANOTHER_TAG),
            )
        }
    }
}

/** What is being sent: "photo.png", or "3 files" once naming them all stops fitting. */
private fun stagedSummary(items: List<String>): String = when {
    items.isEmpty() -> "Nothing to send"
    items.size == 1 -> items.single()
    else -> "${items.size} files"
}
