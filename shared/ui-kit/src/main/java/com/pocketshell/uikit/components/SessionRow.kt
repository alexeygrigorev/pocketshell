package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.Tag
import com.pocketshell.uikit.model.TagKind
import com.pocketshell.uikit.theme.JetBrainsMonoFamily
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Row item for the "Sessions" section of the dashboard. Matches
 * `.session-row` in `docs/mockups/styles.css` and the rows under
 * "Sessions" in `docs/mockups/dashboard.html`.
 *
 * Layout (per the CSS, `align-items: flex-start` because the row is
 * taller than `HostCard`):
 * - 38dp accent badge with a mono letter (the session's initial)
 * - 12dp gap
 * - Body column:
 *   - Top row: name + ` · host` muted mono suffix, timestamp at trailing edge
 *   - Preview line: mono, secondary colour, ellipsis if too long
 *   - Tags row: 6dp-spaced [Tag] pills via [TagChip]
 *
 * The card chrome (surface, border-soft, 14dp radius, 16dp h / 14dp v
 * padding) matches `.session-row` directly.
 *
 * ### Tag vocabulary — issue #202
 *
 * The mockup CSS used uppercase letter-spaced labels ("CLAUDE CODE",
 * "ATTACHED"). Active development on v0.2.8 surfaced that this style reads as
 * cryptic noise to a first-time user — the maintainer literally said
 * "the indicators we have for sessions, it's also not clear what these
 * things are." Per issue #202 we now:
 *
 *  - Render tag labels mixed-case ("Claude", "Codex", "Detached") so
 *    every chip is self-explanatory without consulting a legend.
 *  - Distinguish *what kind of session this is* (agent / deploy / ml)
 *    from *what state it is in* (attached / detached). Activity-state
 *    chips ([TagKind.Attached] / [TagKind.Detached]) lead with a small
 *    coloured dot so they read visually different from classifier chips
 *    and cannot be confused with the accent-soft agent badge.
 *  - Carry a content-description on each chip so TalkBack reads
 *    "<label> tag" instead of just "<label>" (which on uppercase
 *    "ATTACHED" rendered as letter-by-letter noise).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    badge: String,
    name: String,
    host: String,
    preview: String,
    time: String,
    tags: List<Tag>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    /**
     * Issue #171: agent classifier that tints the leading badge. `null`
     * selects the neutral `AccentSoft` / `Accent` cyan badge the
     * dashboard (#202) uses by design — there the badge is a plain
     * visual anchor and does not encode agent kind. The folder-list
     * surface maps its `AgentKind` -> [SessionAgentKind] so plain-shell
     * sessions read as neutral, Claude reads as cyan, Codex / OpenCode
     * read as purple, and edge states (probing / exited) read as
     * amber / muted per the spike's locked tokens.
     */
    agentKind: SessionAgentKind? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(color = PocketShellColors.Surface, shape = RoundedCornerShape(14.dp))
            .border(
                width = 1.dp,
                color = PocketShellColors.BorderSoft,
                shape = RoundedCornerShape(14.dp),
            )
            .combinedClickable(
                role = Role.Button,
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
        // `flex-start` in the CSS: the badge sits at the top, not the
        // middle of the multi-line body.
        verticalAlignment = Alignment.Top,
    ) {
        // Accent badge — 38dp, accent-soft fill, accent foreground,
        // mono. The badge carries the first letter of the session name
        // and is a purely visual anchor (it does NOT encode agent kind
        // or activity state — those live in the tag row below). Per
        // issue #202, the legend at the top of the dashboard spells
        // this out so a first-time user does not assume the cyan badge
        // means "Claude" — agent-kind has its own labelled chip.
        //
        // Issue #171: when [agentKind] is supplied (folder-list call
        // path), the badge tint switches to the kind's semantic colour
        // so a glance at the folder detail screen surfaces
        // shell-vs-agent at the row's most prominent visual anchor.
        // `null` selects the neutral cyan badge the dashboard uses by
        // design (the dashboard badge does not encode agent kind).
        val badgeTint = agentBadgeColors(agentKind)
        Box(
            modifier = Modifier
                .size(38.dp)
                .background(
                    color = badgeTint.background,
                    shape = RoundedCornerShape(10.dp),
                )
                .semantics { contentDescription = "Session initial $badge" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = badge.take(1).uppercase(),
                color = badgeTint.foreground,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            // Top row: name+host on the left, time on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = name,
                        color = PocketShellColors.Text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = " · $host",
                        color = PocketShellColors.TextMuted,
                        fontFamily = JetBrainsMonoFamily,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = time,
                    color = PocketShellColors.TextMuted,
                    fontSize = 11.sp,
                )
            }

            Spacer(modifier = Modifier.size(6.dp))

            // Preview — single-line monospace truncation.
            Text(
                text = preview,
                color = PocketShellColors.TextSecondary,
                fontFamily = JetBrainsMonoFamily,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            if (tags.isNotEmpty()) {
                Spacer(modifier = Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag -> TagChip(tag = tag) }
                }
            }
        }
    }
}

/**
 * Tag pill rendered inside a `SessionRow`. Originally matched the
 * uppercase `.tag` block in `docs/mockups/styles.css`; per issue #202
 * the label is now rendered mixed-case so a first-time user can read
 * it without decoding letter-spaced ALL-CAPS.
 *
 * Two slot categories:
 *
 *  - Classifier chips ([TagKind.Default] / [Agent] / [Deploy] / [Ml])
 *    describe *what kind of session this is*. Plain coloured pill.
 *  - Activity-state chips ([TagKind.Attached] / [TagKind.Detached])
 *    describe *what state the session is in right now*. These lead
 *    with a small coloured dot so they read as visually distinct from
 *    classifier chips. The dot is intentionally non-pulsing — pulsing
 *    is reserved for "connecting" per design-system §6.2, and the
 *    activity state here is a snapshot, not a transition.
 *
 * Kept private to this file because tags only render in this context.
 * If a downstream caller needs standalone tag rendering later, promote
 * this.
 */
/**
 * Two-tone palette for the leading badge — issue #171.
 *
 * Returned for the resolved [SessionAgentKind]: the [background] is the
 * 38dp tile fill; [foreground] is the mono initial drawn on top. The
 * mapping mirrors the spike's locked tokens (`Accent` for Claude,
 * `Purple` for Codex/OpenCode, `TextSecondary` for plain shell,
 * `Amber` for probing, `TextMuted` for exited) and uses the neutral
 * `AccentSoft` translucent-cyan badge when [kind] is null — the
 * dashboard's by-design plain anchor.
 */
internal data class SessionBadgeColors(
    val background: androidx.compose.ui.graphics.Color,
    val foreground: androidx.compose.ui.graphics.Color,
)

internal fun agentBadgeColors(kind: SessionAgentKind?): SessionBadgeColors =
    when (kind) {
        null -> SessionBadgeColors(
            background = PocketShellColors.AccentSoft,
            foreground = PocketShellColors.Accent,
        )
        SessionAgentKind.Claude -> SessionBadgeColors(
            background = PocketShellColors.AccentSoft,
            foreground = PocketShellColors.Accent,
        )
        SessionAgentKind.Codex,
        SessionAgentKind.OpenCode,
        -> SessionBadgeColors(
            // 12% purple — same alpha as AccentSoft's 0.12 cyan so the
            // visual weight matches across agent kinds.
            background = PocketShellColors.Purple.copy(alpha = 0.12f),
            foreground = PocketShellColors.Purple,
        )
        SessionAgentKind.Shell -> SessionBadgeColors(
            // Neutral surface-elev background with a secondary-text
            // initial — plain tmux pane reads as "not an agent" without
            // shouting for attention.
            background = PocketShellColors.SurfaceElev,
            foreground = PocketShellColors.TextSecondary,
        )
        SessionAgentKind.Probing -> SessionBadgeColors(
            background = PocketShellColors.Amber.copy(alpha = 0.12f),
            foreground = PocketShellColors.Amber,
        )
        SessionAgentKind.Exited -> SessionBadgeColors(
            background = PocketShellColors.SurfaceElev,
            foreground = PocketShellColors.TextMuted,
        )
    }

@Composable
private fun TagChip(tag: Tag) {
    val (textColor: Color, bgColor: Color) = when (tag.kind) {
        TagKind.Default -> PocketShellColors.TextMuted to PocketShellColors.SurfaceElev
        TagKind.Agent -> PocketShellColors.Accent to PocketShellColors.AccentSoft
        TagKind.Deploy -> PocketShellColors.Amber to PocketShellColors.Amber.copy(alpha = 0.12f)
        TagKind.Ml -> PocketShellColors.Purple to PocketShellColors.Purple.copy(alpha = 0.12f)
        TagKind.Attached -> PocketShellColors.Green to PocketShellColors.Green.copy(alpha = 0.12f)
        TagKind.Detached -> PocketShellColors.TextMuted to PocketShellColors.SurfaceElev
    }

    val showLeadingDot: Boolean = tag.kind == TagKind.Attached || tag.kind == TagKind.Detached
    val dotColor: Color = if (tag.kind == TagKind.Attached) {
        PocketShellColors.Green
    } else {
        PocketShellColors.TextMuted
    }

    Row(
        modifier = Modifier
            .background(color = bgColor, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { contentDescription = "${tag.label} tag" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLeadingDot) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(color = dotColor, shape = CircleShape),
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = tag.label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
