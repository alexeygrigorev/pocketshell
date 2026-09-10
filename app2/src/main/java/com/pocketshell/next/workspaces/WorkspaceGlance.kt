package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.next.tree.relativeActivityLabel
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/** Test tags for the dense workspace row's inline summary (#2630). */
fun workspaceGlanceCountTag(path: String): String = "workspace-glance-count:$path"

fun workspaceGlanceActivityTag(path: String): String = "workspace-glance-activity:$path"

/**
 * The single-line workspace summary: a session-count badge and a relative
 * activity label, sized to sit inside a 48dp row next to the chevron.
 *
 * Replaces the wrapped per-kind `SessionKindSummary` subtitle on the workspace
 * list, which #2630 deleted along with its last call site. The maintainer's
 * reference is PocketShell Desktop's sidebar — `dtc-website  4  now` — one line
 * carrying name, count and recency, rather than a title row plus a wrapped
 * "Terminal ×2 · Claude" line underneath.
 *
 * The per-kind breakdown is not lost, only moved: opening the workspace still
 * lists every session with its kind. What a scan of this list needs is "how
 * much is in here and is it warm", and that is two short tokens.
 */
@Composable
fun WorkspaceGlance(
    path: String,
    sessions: List<SessionRow>,
    nowSec: Long,
    unavailable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        if (unavailable) {
            Text(
                text = "—",
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
                modifier = Modifier.testTag(workspaceGlanceCountTag(path)),
            )
            return@Row
        }

        // A zero count renders nothing rather than a "0" chip: an empty
        // workspace should read as quiet, not as a badge saying it is empty.
        //
        // #2635 (D1 remainder): a bare muted integer, not a filled chip. The
        // desktop sidebar this row copies uses a plain number; a filled surface
        // on EVERY row is the one bit of chrome a scannable list does not need,
        // and its 6dp radius was off the token ladder as well.
        if (sessions.isNotEmpty()) {
            Text(
                text = sessions.size.toString(),
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
                modifier = Modifier.testTag(workspaceGlanceCountTag(path)),
            )
        }

        latestActivityLabel(sessions, nowSec)?.let { label ->
            Text(
                text = label,
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
                maxLines = 1,
                modifier = Modifier.testTag(workspaceGlanceActivityTag(path)),
            )
        }
    }
}

/**
 * The freshest activity across [sessions], as a short relative label.
 *
 * Null when no session carries a timestamp — the host does not always report
 * one, and inventing "unknown" would be noise on every row of a list whose
 * whole job is to be scannable.
 */
internal fun latestActivityLabel(sessions: List<SessionRow>, nowSec: Long): String? {
    val latest = sessions.mapNotNull { it.activityEpoch }.maxOrNull() ?: return null
    return relativeActivityLabel(latest, nowSec)
}

