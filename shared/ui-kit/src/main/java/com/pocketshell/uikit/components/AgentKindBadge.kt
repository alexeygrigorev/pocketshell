package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Compact agent-kind monogram used by session-list rows (#1701).
 *
 * The visible monogram reclaims the width previously consumed by full agent
 * names. [label] remains the node's accessibility description, while the terse
 * glyph is removed from semantics so TalkBack reads "Claude", not "CL".
 */
@Composable
fun AgentKindBadge(
    monogram: String,
    label: String,
    isAgent: Boolean,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalPocketShellSemantic.current
    val foreground = if (isAgent) semantic.agentAccent else PocketShellColors.TextSecondary
    val background = if (isAgent) {
        semantic.agentAccent.copy(alpha = 0.16f)
    } else {
        PocketShellColors.SurfaceElev.copy(alpha = 0.72f)
    }

    Box(
        modifier = modifier
            .semantics(mergeDescendants = true) {
                contentDescription = label
            }
            .background(background, RoundedCornerShape(6.dp))
            .padding(
                horizontal = PocketShellDensity.chipPadH,
                vertical = PocketShellDensity.chipPadV,
            ),
    ) {
        Text(
            text = monogram,
            color = foreground,
            style = PocketShellType.labelMono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}
