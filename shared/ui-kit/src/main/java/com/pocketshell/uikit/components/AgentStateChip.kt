package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.model.SessionAgentState
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Compact agent resting-state chip (issue #1237) — idle / waiting / working —
 * shared by the host cards and the session rows so the same signal reads
 * identically on every surface.
 *
 * Renders NOTHING for [SessionAgentState.Unknown] (absent, not a wrong chip),
 * honouring the "absent — not wrong — when unknown" rule. Callers that need to
 * gate surrounding spacing should branch on
 * [SessionAgentState.chipLabel]` != null` first.
 *
 * Design-language semantic colours (docs/design-language.md — "green = ok, amber
 * = warning" for status; UI chrome stays neutral):
 *  - Waiting → amber: the agent is blocked on the user — the "come look" signal.
 *  - Working → accent cyan: actively working.
 *  - Idle    → neutral grey: finished / resting.
 */
@Composable
fun AgentStateChip(
    state: SessionAgentState,
    modifier: Modifier = Modifier,
) {
    val label = state.chipLabel ?: return
    val fg = when (state) {
        SessionAgentState.WaitingForInput -> PocketShellColors.Amber
        SessionAgentState.Working -> PocketShellColors.Accent
        SessionAgentState.Idle -> PocketShellColors.TextSecondary
        SessionAgentState.Unknown -> return
    }
    Box(
        modifier = modifier
            .background(fg.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = PocketShellDensity.chipPadH, vertical = PocketShellDensity.chipPadV),
    ) {
        Text(
            text = label,
            color = fg,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
