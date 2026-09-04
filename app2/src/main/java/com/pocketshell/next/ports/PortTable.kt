package com.pocketshell.next.ports

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

/** One column of the port table: a header label and its width weight. */
internal data class PortColumn(val label: String, val weight: Float)

/**
 * The port-table chrome, ported from the old client's `portfwd/PortTable.kt`.
 *
 * It is a set of primitives rather than one `PortTable(rows)` composable because
 * the row CONTENT differs per surface while the CHROME must not: uppercase muted
 * headers on the background surface, the same row padding, the same tap-target
 * floor on a clickable row. Everything composes ui-kit tokens only, so a token
 * change moves every port table at once (docs/design-system.md).
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
 * One port-table row. A null [onClick] makes the row inert; a clickable row takes
 * the full tap-target minimum height, which is why the height is decided here and
 * not by each caller.
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
                if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base
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
