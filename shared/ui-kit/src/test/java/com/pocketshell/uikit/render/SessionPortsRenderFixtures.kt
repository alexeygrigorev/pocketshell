package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.components.SectionHeader
import com.pocketshell.uikit.theme.LocalPocketShellSemantic
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #2176: fast JVM render of the per-session Ports panel.
 *
 * The real panel is `com.pocketshell.app.portfwd.SessionPortsOverlay`, which
 * lives in the app module and cannot be referenced from `:shared:ui-kit` (the
 * dependency runs the other way). Like every other app-screen render here, this
 * MIRRORS it using the same ui-kit primitives, tokens and column weights the
 * real panel composes — `ScreenHeader`, `SectionHeader`, the port table's muted
 * uppercase header cells, `PocketShellButton`, `LocalPocketShellSemantic`'s
 * `statusActive` — so the fast visual check answers the design question this
 * feature is judged on: **does the per-session table read as the SAME table as
 * the host-wide port-forward panel?** The emulator screenshot remains the
 * acceptance.
 */

private data class RenderPortRow(
    val port: Int,
    val local: String,
    val process: String,
    val status: String,
    val forwarded: Boolean,
    val matchedText: String,
)

@Composable
internal fun SessionPortsPanelRender() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .border(1.dp, PocketShellColors.BorderSoft),
    ) {
        ScreenHeader(
            title = "Ports",
            subtitle = "hetzner · pocketshell",
            modifier = Modifier
                .background(PocketShellColors.Background)
                .border(1.dp, PocketShellColors.BorderSoft),
            leading = { RenderCloseBox() },
        )
        SectionHeader(label = "Opened by this session", count = 4)
        RenderPortTableHeader()
        for (row in SAMPLE_ROWS) {
            RenderPortRowItem(row)
        }
        RenderAllHostPortsRow("All host ports (58 forwarding)")
    }
}

/**
 * The honest empty state. A session that has not started a server yet must get
 * an explanation, never a blank surface — an empty screen reads as "broken".
 */
@Composable
internal fun SessionPortsPanelEmptyRender() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Surface)
            .border(1.dp, PocketShellColors.BorderSoft),
    ) {
        ScreenHeader(
            title = "Ports",
            subtitle = "hetzner · notes",
            modifier = Modifier
                .background(PocketShellColors.Background)
                .border(1.dp, PocketShellColors.BorderSoft),
            leading = { RenderCloseBox() },
        )
        SectionHeader(label = "Opened by this session", count = 0)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PocketShellSpacing.lg),
            verticalArrangement = Arrangement.spacedBy(PocketShellSpacing.xs),
        ) {
            Text(
                text = "No ports from this session yet.",
                color = PocketShellColors.Text,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Start a server here — a port shows up once this session " +
                    "prints its address and it is confirmed listening.",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
            )
        }
        RenderAllHostPortsRow("All host ports (58 forwarding)")
    }
}

/**
 * Issue #2176 addendum: the reconciled route onward. The forwarding pill and the
 * kebab both open THIS panel, so the full host-wide forward list the maintainer
 * asked to browse manually stays one clearly-labelled tap away rather than
 * becoming a second, competing chrome button.
 */
@Composable
private fun RenderAllHostPortsRow(label: String) {
    ListRow(
        title = label,
        onClick = {},
        trailing = {
            Text(
                text = "›",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.bodyDense,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Deliberately includes ports OUTSIDE the host-wide `3000..10000` denoise range
 * (22 and 41337): a session's own ports are shown regardless of that filter, so
 * the render must show them too or it would be checking the wrong design.
 */
private val SAMPLE_ROWS = listOf(
    RenderPortRow(
        port = 5173,
        local = "18173",
        process = "node",
        status = "Forwarding",
        forwarded = true,
        matchedText = "Local:   http://localhost:5173/",
    ),
    RenderPortRow(
        port = 8000,
        local = "-",
        process = "python3",
        status = "Not forwarded",
        forwarded = false,
        matchedText = "Serving HTTP on 0.0.0.0 port 8000 (http://0.0.0.0:8000/) ...",
    ),
    RenderPortRow(
        port = 41337,
        local = "-",
        process = "uvicorn",
        status = "Not forwarded",
        forwarded = false,
        matchedText = "Uvicorn running on http://127.0.0.1:41337 (Press CTRL+C to quit)",
    ),
    RenderPortRow(
        port = 22,
        local = "-",
        process = "sshd",
        status = "Not forwarded",
        forwarded = false,
        matchedText = "",
    ),
)

@Composable
private fun RenderPortTableHeader() {
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
        RenderHeaderCell("Remote", 0.18f)
        RenderHeaderCell("Local", 0.16f)
        RenderHeaderCell("Process", 0.28f)
        RenderHeaderCell("Status", 0.24f)
    }
}

@Composable
private fun RenderPortRowItem(row: RenderPortRow) {
    val semantic = LocalPocketShellSemantic.current
    val statusColor: Color = if (row.forwarded) {
        semantic.statusActive
    } else {
        PocketShellColors.TextSecondary
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = PocketShellDensity.tapTargetMin)
                .padding(
                    horizontal = PocketShellDensity.rowPadH,
                    vertical = PocketShellDensity.rowPadV,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RenderBodyCell("${row.port}", 0.18f, monospace = true)
            RenderBodyCell(row.local, 0.16f, monospace = true)
            RenderBodyCell(row.process, 0.28f)
            RenderBodyCell(row.status, 0.24f, color = statusColor)
            Spacer(Modifier.width(PocketShellSpacing.sm))
            PocketShellButton(
                text = if (row.forwarded) "Open" else "Forward",
                onClick = {},
                variant = if (row.forwarded) ButtonVariant.Primary else ButtonVariant.Text,
            )
        }
        if (row.matchedText.isNotBlank()) {
            Text(
                text = row.matchedText,
                modifier = Modifier.padding(
                    start = PocketShellDensity.rowPadH,
                    end = PocketShellDensity.rowPadH,
                    bottom = PocketShellDensity.rowPadV,
                ),
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RowScope.RenderHeaderCell(text: String, weight: Float) {
    Text(
        text = text.uppercase(),
        modifier = Modifier.weight(weight),
        color = PocketShellColors.TextMuted,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
private fun RowScope.RenderBodyCell(
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

@Composable
private fun RenderCloseBox() {
    Box(
        modifier = Modifier
            .defaultMinSize(
                minWidth = PocketShellDensity.tapTargetMin,
                minHeight = PocketShellDensity.tapTargetMin,
            )
            .background(PocketShellColors.SurfaceElev, PocketShellShapes.small)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.small)
            .padding(
                horizontal = PocketShellDensity.chipPadH,
                vertical = PocketShellDensity.chipPadV,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "<",
            color = PocketShellColors.TextSecondary,
            style = PocketShellType.bodyDense,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
