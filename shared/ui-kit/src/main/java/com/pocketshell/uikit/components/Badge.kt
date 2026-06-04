package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The semantic role a [Badge] paints. Screens pass the *role*, not a raw
 * colour, so the agent-purple / shell-grey / status vocabulary stays sourced
 * from [LocalPocketShellSemantic] (#461 §3.5) and can't drift per call site.
 *
 * The mockup's right-aligned pills map onto these roles:
 *
 * - [Agent] — Claude / Codex / OpenCode classifier pills. Purple
 *   ([com.pocketshell.uikit.theme.PocketShellSemanticColors.agentAccent]).
 * - [Shell] — plain shell / non-agent session pills. Neutral grey.
 * - [Active] / [Idle] / [Error] — status badges folding the status-dot
 *   vocabulary into a labelled pill where a row needs the word, not just a dot.
 */
enum class BadgeRole {
    Agent,
    Shell,
    Active,
    Idle,
    Error,
}

/**
 * Right-aligned pill badge — the shared "agent / type / status" chip that the
 * mockup (`folder-tree-target-20260604.png`) places at the trailing edge of a
 * row. Generalises the per-row tag-pill recipe `SessionRow` previously kept
 * private, so every screen renders the same pill.
 *
 * Tokens (#461 §3.5 chip/pill pattern):
 * - `small`(8) shape from [PocketShellShapes].
 * - [PocketShellDensity.chipPadH]`(10)` / [PocketShellDensity.chipPadV]`(6)`
 *   padding.
 * - [PocketShellType.bodyMono]`(13)` by default (`mono = true`, for IDs /
 *   classifier labels in a mono context) or [PocketShellType.labelMono]`(11)`
 *   when `mono = false` (compact counts / captions).
 *
 * The foreground colour comes from [role]; the background is a 12%-alpha tint of
 * the foreground (the same `rgba(..., 0.12)` recipe the CSS tags use), except
 * the neutral [BadgeRole.Shell] / [BadgeRole.Idle] roles which sit on the
 * always-dark `SurfaceElev` neutral so a non-accent badge reads as chrome.
 *
 * Decorative + label only (no `onClick`) — a row's single action lives in the
 * [Kebab], not the badge.
 */
@Composable
fun Badge(
    label: String,
    role: BadgeRole,
    modifier: Modifier = Modifier,
    mono: Boolean = true,
) {
    val semantic = LocalPocketShellSemantic.current
    val (textColor: Color, bgColor: Color) = when (role) {
        BadgeRole.Agent -> semantic.agentAccent to semantic.agentAccent.copy(alpha = 0.12f)
        BadgeRole.Shell -> PocketShellColors.TextMuted to PocketShellColors.SurfaceElev
        BadgeRole.Active -> semantic.statusActive to semantic.statusActive.copy(alpha = 0.12f)
        BadgeRole.Idle -> PocketShellColors.TextMuted to PocketShellColors.SurfaceElev
        BadgeRole.Error -> semantic.statusError to semantic.statusError.copy(alpha = 0.12f)
    }

    Text(
        text = label,
        color = textColor,
        style = if (mono) PocketShellType.bodyMono else PocketShellType.labelMono,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .background(color = bgColor, shape = PocketShellShapes.small)
            .padding(
                horizontal = PocketShellDensity.chipPadH,
                vertical = PocketShellDensity.chipPadV,
            ),
    )
}
