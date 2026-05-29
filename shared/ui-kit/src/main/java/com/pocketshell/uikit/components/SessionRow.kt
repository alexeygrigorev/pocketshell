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
import com.pocketshell.uikit.model.Tag
import com.pocketshell.uikit.model.TagKind
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Compact row for a session inside the per-host folder list.
 *
 * The host and folder context already scopes the list, so the row
 * deliberately renders only the session name plus chips. The retired
 * all-host dashboard badge, host suffix, timestamp, preview/status
 * prose, and legend are not part of this component.
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
 *    and do not need a second prose status line.
 *  - Carry a content-description on each chip so TalkBack reads
 *    "<label> tag" instead of just "<label>" (which on uppercase
 *    "ATTACHED" rendered as letter-by-letter noise).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionRow(
    name: String,
    tags: List<Tag>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                color = PocketShellColors.Text,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
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
