package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.SessionAgentState
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Compact agent state icon (issues #1237/#1701) — idle / waiting / working —
 * shared by the host cards and the session rows so the same signal reads
 * identically on every surface.
 *
 * Renders NOTHING for [SessionAgentState.Unknown] (absent, not a wrong chip),
 * honouring the "absent — not wrong — when unknown" rule. Callers that need to
 * gate surrounding spacing should branch on
 * [SessionAgentState.chipLabel]` != null` first.
 *
 * Design-language semantic colours:
 *  - Waiting → amber: the agent is blocked on the user — the "come look" signal.
 *  - Working → accent cyan: actively working.
 *  - Idle    → neutral grey: finished / resting.
 *
 * The visible words were deliberately hard-cut in #1701 to return their width
 * to the session name. The full words remain as icon content descriptions so
 * TalkBack receives the same state information.
 */
@Composable
fun AgentStateChip(
    state: SessionAgentState,
    modifier: Modifier = Modifier,
) {
    val label = state.chipLabel ?: return
    val (imageVector, tint) = when (state) {
        SessionAgentState.WaitingForInput -> Icons.Outlined.HourglassEmpty to PocketShellColors.Amber
        SessionAgentState.Working -> Icons.Filled.Autorenew to PocketShellColors.Accent
        SessionAgentState.Idle -> Icons.Outlined.PauseCircle to PocketShellColors.TextSecondary
        SessionAgentState.Unknown -> return
    }
    Icon(
        imageVector = imageVector,
        contentDescription = label,
        tint = tint,
        modifier = modifier.size(AgentStateIconSize),
    )
}

internal val AgentStateIconSize = 18.dp
