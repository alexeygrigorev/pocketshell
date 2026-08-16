package com.pocketshell.app.portfwd

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #2176: the port-table chrome shared by BOTH port surfaces — the
 * host-wide [PortForwardPanelScreen] and the per-session [SessionPortsPanel].
 *
 * These were private composables inside `PortForwardPanelScreen.kt`. They are
 * extracted verbatim (same tokens, same paddings, same weights semantics) so the
 * per-session panel is the SAME table rather than a second, subtly different
 * one. The maintainer's standing design-consistency direction is that the same
 * element looks identical on every screen (`docs/design-system.md`, #479); a
 * copy-pasted second table is exactly how that drifts.
 *
 * Everything here composes ui-kit tokens only — no local literals — so a token
 * change moves both tables at once.
 */
internal data class PortColumn(val label: String, val weight: Float)

/**
 * Column headers for a port table. The caller supplies the columns because the
 * two tables genuinely differ in CONTENT (the host-wide table has a live
 * traffic column; a session's mentioned ports have no traffic of their own),
 * while the CHROME — uppercase muted labels on the background surface, the same
 * row padding — is shared.
 */
@Composable
internal fun PortTableHeader(columns: List<PortColumn>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background)
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (column in columns) {
            PortHeaderCell(column.label, column.weight)
        }
    }
}

/**
 * One port-table row. [onClick] null makes the row inert; a clickable row also
 * takes the full tap-target minimum height so it is comfortably hittable, which
 * is why the height is decided here rather than by each caller.
 */
@Composable
internal fun PortTableRow(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(
                minHeight = if (onClick != null) {
                    PocketShellDensity.tapTargetMin
                } else {
                    PocketShellDensity.rowMinHeight
                },
            )
            .let { base ->
                if (onClick != null) {
                    base.clickable(role = Role.Button, onClick = onClick)
                } else {
                    base
                }
            }
            .padding(
                horizontal = PocketShellDensity.rowPadH,
                vertical = PocketShellDensity.rowPadV,
            ),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
internal fun RowScope.PortHeaderCell(text: String, weight: Float) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.weight(weight),
        color = PocketShellColors.TextMuted,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun RowScope.PortBodyCell(
    text: String,
    weight: Float,
    monospace: Boolean = false,
    color: Color = PocketShellColors.TextSecondary,
) {
    Text(
        text = text,
        modifier = Modifier.weight(weight),
        color = color,
        style = if (monospace) PocketShellType.bodyMono else PocketShellType.bodyDense,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
