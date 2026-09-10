package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.material3.Text
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * The core list row — the one source of truth for flat rows in
 * the app (host list, sessions, settings, conversation list, port-forward
 * panel, …). Encodes the issue #489 row pattern and the design language locked
 * on #479:
 *
 * ```
 * ┌──────────────────────────────────────────────────────┐
 * │ ● │  agent-main                       [Claude]  [⋮]   │   <- leading / title / trailing
 * │   │  ~/proj/agent                                     │   <- subtitle (mono, muted)
 * └──────────────────────────────────────────────────────┘
 * ```
 *
 * Slots (every visual region is a caller-supplied lambda so screens compose
 * their own status dot / avatar / badge / kebab without re-encoding the row):
 *
 * - **[leading]** (optional) — status dot ([StatusDot]) / avatar / icon. Pass
 *   `null` for a flush-left title (e.g. settings rows).
 * - **title** — the primary scan target, [PocketShellType.body] (14sp) on the
 *   bright text token.
 * - **[subtitle]** (optional) — paths / IDs / `user@host`, rendered
 *   [PocketShellType.metadata] (11sp) on the muted token. The default is a
 *   single ellipsised line; callers such as [WorkspaceRow] may opt into a
 *   second line when the label itself is part of navigation.
 * - **[trailing]** (optional) — badge ([Badge]) / count / kebab ([Kebab]). One
 *   overflow affordance per row (design language: avoid multiple inline action
 *   buttons).
 *
 * ### Density and touch floor
 *
 * Rows use the [PocketShellDensity.rowMinHeight] (56dp) minimum and the 20dp
 * screen gutter — comfortably above the 48dp tap floor, without spending a
 * phone screen on six items (#2630). The whole row is the tap target when
 * [onClick] is supplied, and wrapped content is allowed to grow.
 *
 * [PocketShellDensity.rowPadV] is applied to the TEXT column, not to the row
 * (#2635 2a). Padding the row meant a row whose trailing slot was a 48dp
 * control could not be 56dp tall: 48 + 2×8 = 64, so the host list read as
 * "56dp rows, except the ones with a menu, which are 64". With the padding on
 * the text, a 48dp kebab measures 48 against a 56dp floor and every row in the
 * app lands on one height, whatever it carries.
 *
 * [minHeight] exists for genuinely single-line, information-dense lists that
 * want the 48dp touch floor itself as their height (the workspace list, #2630).
 * It is a floor, never a cap — never pass anything below
 * [PocketShellDensity.tapTargetMin].
 *
 * Colours stay on the always-dark raw tokens (#477 single dark scheme) so the
 * row never flips with the system light setting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ListRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    /**
     * The row's alternate action (`design-language.md`: "Long-press = always
     * available alternate action").
     *
     * #2635 N1 made this load-bearing rather than decorative: deleting the
     * workspace page moved its per-workspace actions (copy path, remove from
     * list) onto the row itself, and a long-press is where the design language
     * has always said they go.
     */
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
    titleStyle: TextStyle = PocketShellType.body,
    subtitleStyle: TextStyle = PocketShellType.metadata,
    titleWeight: FontWeight? = null,
    subtitleContent: (@Composable () -> Unit)? = null,
    minHeight: Dp = PocketShellDensity.rowMinHeight,
) {

    Column(
        modifier = if (onClick == null) modifier.fillMaxWidth() else Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = minHeight)
                .then(
                    when {
                        onClick != null && onLongClick != null -> Modifier.combinedClickable(
                            role = Role.Button,
                            onClick = onClick,
                            onLongClick = onLongClick,
                            onLongClickLabel = onLongClickLabel,
                        )
                        onClick != null -> Modifier.clickable(role = Role.Button, onClick = onClick)
                        else -> Modifier
                    },
                )
                .then(if (onClick != null) modifier else Modifier)
                .padding(horizontal = PocketShellDensity.rowPadH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
        if (leading != null) {
            // A fixed-width leading box keeps every row's title left edge
            // aligned regardless of whether the leading slot is an 8dp dot or a
            // wider glyph, so a stacked list reads as a clean column.
            Box(contentAlignment = Alignment.Center) {
                leading()
            }
            Spacer(modifier = Modifier.width(PocketShellSpacing.md))
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(vertical = PocketShellDensity.rowPadV),
        ) {
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = titleStyle,
                fontWeight = titleWeight,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null || subtitleContent != null) {
                Spacer(modifier = Modifier.size(2.dp))
                if (subtitleContent != null) {
                    subtitleContent()
                } else {
                    Text(
                        text = requireNotNull(subtitle),
                        color = PocketShellColors.TextMuted,
                        style = subtitleStyle,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (trailing != null) {
            Spacer(modifier = Modifier.width(PocketShellSpacing.sm))
            Row(
                horizontalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                trailing()
            }
        }
        }
        HorizontalDivider(
            modifier = Modifier.padding(horizontal = PocketShellDensity.rowPadH),
            color = PocketShellColors.BorderSoft,
        )
    }
}
