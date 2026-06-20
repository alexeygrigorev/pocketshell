package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType
import com.pocketshell.uikit.theme.PocketShellTypography

/**
 * The canonical centered **empty / nothing-here** state for PocketShell (#756).
 *
 * The UI-consistency audit (#756) found ~28 hand-rolled
 * `Box(contentAlignment = Center) { Text("No items") }` blocks across the app —
 * "no panes", "no items to scan", "no folders yet", per-screen empty rows — each
 * with its own typography, spacing, icon (or none), and optional action. That is
 * the same "every screen rolls its own grammar" drift the audit flagged for
 * loading indicators and buttons. [EmptyState] is the single shared surface
 * those sites converge onto: a centered icon (optional), a title, an optional
 * supporting line, and an optional action button — all token-driven so a "no
 * items" placeholder reads the same on every screen.
 *
 * Layout (centered in its available space, capped reading width via the caller's
 * [modifier] padding):
 * ```
 *            [ icon ]            ← optional, muted
 *            Title               ← titleMedium, primary text
 *      supporting sentence       ← optional, bodyDense muted, centered
 *          [ Action ]            ← optional PocketShellButton
 * ```
 *
 * Tokens (NO raw `dp`/`sp`/hex):
 * - Title is [PocketShellTypography] `titleMedium` (16sp) in [PocketShellColors.Text].
 * - The supporting line is [PocketShellType.bodyDense] (13sp) in the muted
 *   `statusIdle` semantic colour, centered.
 * - Icon (when supplied) is [IconSize] tall, tinted muted `statusIdle`.
 * - Vertical rhythm uses [PocketShellSpacing] (`sm` between icon/title/body,
 *   `md` before the action).
 *
 * The [action] slot is the escape hatch for the empties that offer a primary
 * affordance ("Add host", "Connect a folder"); pass a [PocketShellButton] there.
 * For a plain "nothing here" placeholder omit it.
 *
 * @param title the headline ("No sessions", "No panes yet").
 * @param modifier outer modifier — defaults to filling + centering in the
 *   available area; a caller can constrain it (e.g. a card-sized empty).
 * @param description optional supporting sentence under the title.
 * @param icon optional leading glyph above the title (muted).
 * @param action optional action affordance under the text (e.g. a
 *   [PocketShellButton]).
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val semantic = LocalPocketShellSemantic.current
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(PocketShellSpacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.sm),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = semantic.statusIdle,
                    modifier = Modifier.size(IconSize),
                )
            }
            Text(
                text = title,
                color = PocketShellColors.Text,
                style = PocketShellTypography.titleMedium,
                textAlign = TextAlign.Center,
            )
            if (description != null) {
                Text(
                    text = description,
                    color = semantic.statusIdle,
                    style = PocketShellType.bodyDense,
                    fontWeight = FontWeight.Normal,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                // A touch more breathing room before the action than between the
                // text lines, so the button reads as a distinct affordance.
                Box(modifier = Modifier.padding(top = PocketShellSpacing.xs)) {
                    action()
                }
            }
        }
    }
}

/** The one canonical empty-state icon diameter — the large decorative glyph. */
private val IconSize = 40.dp
