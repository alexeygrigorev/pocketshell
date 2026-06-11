package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The semantic role a [Banner] paints. Screens pass the *role*, not a raw
 * colour, so the info / warning / error / agent-hint vocabulary stays sourced
 * from [LocalPocketShellSemantic] (#461 §3.5) and can't drift per call site.
 *
 * The roles fold the existing inline-callout colour usage across screens onto
 * one named vocabulary, each backed by a [com.pocketshell.uikit.theme.PocketShellSemanticColors]
 * role:
 *
 * - [Info] — neutral cyan hint / "tap to …" callout. Accent
 *   (`accent` text on a 12%-alpha `accentSoft`-class fill, `accentDim` border).
 *   This is the "hint/banner fill" the design-system token table calls out for
 *   `AccentSoft`.
 * - [Warning] — caution / attention callout. Amber (`statusConnecting` /
 *   `statusAttention`).
 * - [Error] — failure / blocking callout. Red (`statusError`).
 * - [AgentHint] — agent / assistant-scoped hint. Purple (`agentAccent`), the
 *   same role glyph colour the conversation surfaces use.
 */
enum class BannerRole {
    Info,
    Warning,
    Error,
    AgentHint,
}

/**
 * Full-width inline callout banner — the shared "hint / warning / error" strip
 * that screens render above content (an inactive-root hint, a "limits reset"
 * notice, a connection-error band, an agent-scoped tip). Generalises the
 * per-screen tinted-callout recipe so every banner renders with one consistent
 * fill / border / icon / text treatment.
 *
 * Tokens (#461 §3.5 callout pattern — NO raw `dp`/`sp`/hex):
 * - [PocketShellShapes.medium]`(14)` rounded corners (the "content tile" radius).
 * - [PocketShellSpacing.md]`(12)` internal padding, [PocketShellSpacing.sm]`(8)`
 *   icon-to-text gap.
 * - [PocketShellType.bodyDense]`(13)` text.
 * - Colours come entirely from [role] via [LocalPocketShellSemantic]: the
 *   foreground (text + icon + border) is the role colour, the fill is its
 *   12%-alpha tint (the same `rgba(..., 0.12)` recipe the chips/badges use).
 *
 * Decorative + label only (no `onClick`) — a banner states context; any action
 * lives in a row, button, or kebab beside it, not on the banner surface.
 */
@Composable
fun Banner(
    text: String,
    role: BannerRole,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
) {
    val semantic = LocalPocketShellSemantic.current
    val foreground: Color = when (role) {
        BannerRole.Info -> semantic.accent
        BannerRole.Warning -> semantic.statusConnecting
        BannerRole.Error -> semantic.statusError
        BannerRole.AgentHint -> semantic.agentAccent
    }
    val fill: Color = foreground.copy(alpha = 0.12f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = fill, shape = PocketShellShapes.medium)
            .border(width = 1.dp, color = foreground, shape = PocketShellShapes.medium)
            .padding(PocketShellSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = foreground,
                modifier = Modifier.size(PocketShellSpacing.lg),
            )
        }
        Text(
            text = text,
            color = foreground,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.Medium,
        )
    }
}
