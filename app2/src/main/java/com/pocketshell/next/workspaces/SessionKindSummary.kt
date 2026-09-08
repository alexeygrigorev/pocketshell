package com.pocketshell.next.workspaces

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.pocketshell.core.hostapi.SessionRow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

private data class KindGroup(
    val label: String,
    val agent: String?,
    val count: Int,
)

/** Compact wrapped summary used under workspace rows in the host projection. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionKindSummary(
    sessions: List<SessionRow>,
    unavailable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    if (unavailable) {
        Text(
            text = "Status unavailable",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.metadata,
            modifier = modifier,
        )
        return
    }
    if (sessions.isEmpty()) {
        Text(
            text = "No sessions",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.metadata,
            modifier = modifier,
        )
        return
    }

    val groups = buildList<KindGroup> {
        sessions.forEach { session ->
            val label = sessionKindLabel(session)
            val index = indexOfFirst { it.label == label }
            if (index == -1) {
                add(KindGroup(label = label, agent = session.agent, count = 1))
            } else {
                val current = this[index]
                this[index] = current.copy(count = current.count + 1)
            }
        }
    }
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.md),
        verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
    ) {
        groups.take(3).forEach { group ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SessionKindMark(agent = group.agent)
                Text(
                    text = if (group.count == 1) group.label else "${group.label} ×${group.count}",
                    color = PocketShellColors.TextMuted,
                    style = PocketShellType.metadata,
                    modifier = Modifier.padding(start = PocketShellSpacing.xs),
                )
            }
        }
        if (groups.size > 3) {
            Text(
                text = "+${groups.size - 3} more kinds",
                color = PocketShellColors.TextMuted,
                style = PocketShellType.metadata,
            )
        }
    }
}
