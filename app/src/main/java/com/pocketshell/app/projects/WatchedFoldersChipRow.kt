package com.pocketshell.app.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Issue #206 integration with #204: horizontal chip row of the host's
 * configured watched folders. Rendered above the start-folder text
 * field in the new-session create dialog so the user can tap a folder
 * instead of typing the absolute remote path.
 *
 * The chip row hides itself entirely when the host has no watched
 * folders configured AND [showEmptyNudge] is false; when there are
 * zero rows but [showEmptyNudge] is true (the new-session sheet's
 * default), it shows a one-line nudge directing the user to
 * Settings → Watched folders. This satisfies #206's "empty-state
 * nudge when host has 0 watched folders" requirement at the surface
 * the user actually feels it — the create-session flow.
 *
 * The composable is package-public so call sites in
 * `app.sessions` can import it without pulling in any of the
 * write / discover plumbing. The view model behind it is a
 * read-only DAO wrapper ([WatchedFoldersChipsViewModel]).
 *
 * @param hostId host whose watched folders should be shown. Null hides
 *   the row entirely (e.g. when the create dialog hasn't resolved a
 *   host yet).
 * @param onChipTap invoked when the user taps a chip — the lambda
 *   receives the chosen path (already in remote-absolute form,
 *   e.g. `~/git/pocketshell`).
 * @param showEmptyNudge whether to render the configure-in-Settings
 *   nudge when the host has no rows. Defaults to true.
 */
@Composable
fun WatchedFoldersChipRow(
    hostId: Long?,
    onChipTap: (path: String) -> Unit,
    modifier: Modifier = Modifier,
    showEmptyNudge: Boolean = true,
    viewModel: WatchedFoldersChipsViewModel = hiltViewModel(),
) {
    LaunchedEffect(hostId) { viewModel.bind(hostId) }
    val roots by viewModel.roots.collectAsState()
    if (hostId == null) return
    if (roots.isEmpty()) {
        if (showEmptyNudge) {
            Text(
                text = "Configure watched folders in Settings to quick-access them here.",
                color = PocketShellColors.TextMuted,
                fontSize = 11.sp,
                modifier = modifier
                    .testTag(WATCHED_FOLDERS_CHIP_EMPTY_NUDGE_TAG)
                    .padding(vertical = 2.dp),
            )
        }
        return
    }
    Column(modifier = modifier) {
        Text(
            text = "Watched folders",
            color = PocketShellColors.TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag(WATCHED_FOLDERS_CHIP_ROW_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            roots.forEach { row ->
                val visibleLabel = WatchedFoldersViewModel.stripOrderPrefix(row.label)
                Row(
                    modifier = Modifier
                        .background(
                            color = PocketShellColors.AccentSoft,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .border(
                            width = 1.dp,
                            color = PocketShellColors.Accent,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .clickable(role = Role.Button) { onChipTap(row.path) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                        .testTag(watchedFoldersChipTestTag(row.path)),
                ) {
                    Text(
                        text = visibleLabel.ifBlank { row.path },
                        color = PocketShellColors.Accent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

const val WATCHED_FOLDERS_CHIP_ROW_TAG: String = "watched-folders:chip-row"
const val WATCHED_FOLDERS_CHIP_EMPTY_NUDGE_TAG: String = "watched-folders:chip-row:empty-nudge"

fun watchedFoldersChipTestTag(path: String): String = "watched-folders:chip:$path"
