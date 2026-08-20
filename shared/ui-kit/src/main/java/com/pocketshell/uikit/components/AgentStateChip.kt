package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
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
 *  - Idle    → neutral grey: finished / resting (a checkmark, never a pause
 *    affordance).
 *
 * The visible words were deliberately hard-cut in #1701 to return their width
 * to the session name. The explicit state descriptions remain on the icons so
 * TalkBack receives the same state information without making an idle agent
 * sound like a paused control.
 */
@Composable
fun AgentStateChip(
    state: SessionAgentState,
    modifier: Modifier = Modifier,
) {
    val label = state.accessibilityDescription ?: return
    val imageVector = agentStateIconFor(state) ?: return
    val tint = when (state) {
        SessionAgentState.WaitingForInput -> PocketShellColors.Amber
        SessionAgentState.Working -> PocketShellColors.Accent
        SessionAgentState.Idle -> PocketShellColors.TextSecondary
        SessionAgentState.Unknown -> return
    }
    Icon(
        imageVector = imageVector,
        contentDescription = label,
        tint = tint,
        modifier = modifier.size(AgentStateIconSize),
    )
}

/**
 * The visual state vocabulary is deliberately separate from provider identity
 * badges and from the green attached/agent-kind dot used by the tree rows.
 * In particular, [SessionAgentState.Idle] means the agent finished/rested; it
 * is not a user-controlled paused session, so it must not use a pause glyph.
 */
internal fun agentStateIconFor(state: SessionAgentState): ImageVector? = when (state) {
    SessionAgentState.WaitingForInput -> Icons.Outlined.HourglassEmpty
    SessionAgentState.Working -> Icons.Filled.Autorenew
    SessionAgentState.Idle -> Icons.Outlined.CheckCircle
    SessionAgentState.Unknown -> null
}

private val SessionAgentState.accessibilityDescription: String?
    get() = when (this) {
        SessionAgentState.WaitingForInput -> "Waiting for input"
        SessionAgentState.Working -> "Working"
        SessionAgentState.Idle -> "Idle (finished)"
        SessionAgentState.Unknown -> null
    }

internal val AgentStateIconSize = 18.dp
