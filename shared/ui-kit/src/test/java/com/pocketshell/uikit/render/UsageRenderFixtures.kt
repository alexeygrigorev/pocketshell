package com.pocketshell.uikit.render

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketshell.uikit.components.ButtonVariant
import com.pocketshell.uikit.components.ListRow
import com.pocketshell.uikit.components.Pill
import com.pocketshell.uikit.components.PocketShellButton
import com.pocketshell.uikit.components.ProgressBar
import com.pocketshell.uikit.components.ScreenHeader
import com.pocketshell.uikit.model.PillKind
import com.pocketshell.uikit.model.ProgressKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellSpacing
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Issue #2534: fast JVM render of the usage panel's compact-first layout.
 *
 * The real screen is `com.pocketshell.next.usage.UsageScreen`, which lives in
 * app2 and cannot be referenced from `:shared:ui-kit`. This fixture mirrors it
 * with the same ui-kit primitives the real panel composes — [ScreenHeader],
 * [ListRow], [Pill], [ProgressBar] — so `scripts/render.sh` can snapshot
 * collapsed vs one-row-expanded. The real composable is rendered by
 * `UsageScreenRenders` and by journey J12.
 */

private data class CompactUsageRow(
    val name: String,
    val reset: String,
    val percent: String,
)

private val SAMPLE_ROWS: List<CompactUsageRow> = listOf(
    CompactUsageRow("Claude Code", "in 2h 35m", "12% used"),
    CompactUsageRow("Codex", "in 2 days", "60% used"),
    CompactUsageRow("GitHub Copilot", "in 26 days", "0% used"),
    CompactUsageRow("OpenCode Go", "in 5h", "58% used"),
    CompactUsageRow("Grok Build", "in 3 days", "45% used"),
    CompactUsageRow("Zai", "in 5 days", "7% used"),
)

@Composable
internal fun UsageScreenCollapsedRender() {
    UsageScreenChrome {
        UsageCompactStrip()
    }
}

@Composable
internal fun UsageScreenCodexExpandedRender() {
    UsageScreenChrome {
        UsageCompactStrip()
        UsageCodexCard()
    }
}

@Composable
private fun UsageScreenChrome(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(PocketShellColors.Background),
    ) {
        ScreenHeader(
            title = "Usage",
            modifier = Modifier.border(width = 1.dp, color = PocketShellColors.BorderSoft),
            leading = {
                PocketShellButton(
                    text = "‹",
                    onClick = {},
                    variant = ButtonVariant.Text,
                    compact = true,
                )
            },
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = PocketShellDensity.rowPadH + PocketShellSpacing.sm,
                    vertical = PocketShellSpacing.md,
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Last sync 20:25",
                color = PocketShellColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text = "6 providers · 1 hosts",
                color = PocketShellColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        content()
    }
}

@Composable
private fun UsageCompactStrip() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.md)
            .background(PocketShellColors.Surface, PocketShellShapes.extraSmall)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.extraSmall)
            .padding(horizontal = PocketShellSpacing.xs, vertical = PocketShellSpacing.xs),
    ) {
        SAMPLE_ROWS.forEach { row ->
            ListRow(
                title = row.name,
                leading = { UsageProviderDot(PocketShellColors.Green) },
                trailing = {
                    Text(
                        text = row.reset,
                        color = PocketShellColors.TextMuted,
                        style = PocketShellType.labelMono,
                        modifier = Modifier.padding(end = PocketShellSpacing.sm),
                    )
                    Text(
                        text = row.percent,
                        color = PocketShellColors.TextSecondary,
                        style = PocketShellType.labelMono,
                    )
                },
                onClick = {},
            )
        }
    }
}

@Composable
private fun UsageCodexCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PocketShellSpacing.md, vertical = PocketShellSpacing.sm)
            .background(PocketShellColors.Surface, PocketShellShapes.extraSmall)
            .border(1.dp, PocketShellColors.BorderSoft, PocketShellShapes.extraSmall)
            .padding(horizontal = PocketShellSpacing.lg, vertical = PocketShellSpacing.lg),
    ) {
        ListRow(
            title = "Codex",
            leading = { UsageProviderDot(PocketShellColors.Green) },
            trailing = { Pill(label = "OK", kind = PillKind.Ok) },
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.md))
        UsageWindowBlock(label = "7d window", percent = "60% used", progress = 0.60f)
        Spacer(modifier = Modifier.height(PocketShellSpacing.lg))
        Text(
            text = "Reset credits · 3 available",
            color = PocketShellColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(PocketShellSpacing.sm))
        Text(
            text = "Full reset",
            color = PocketShellColors.Text,
            style = PocketShellType.bodyDense,
        )
        Text(
            text = "expires in 16 days",
            color = PocketShellColors.TextMuted,
            style = PocketShellType.labelMono,
        )
    }
}

@Composable
private fun UsageWindowBlock(label: String, percent: String, progress: Float) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = label,
                color = PocketShellColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.5.sp,
            )
            Text(
                text = percent,
                color = PocketShellColors.Text,
                style = PocketShellType.labelMono,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(modifier = Modifier.height(PocketShellSpacing.xs + 2.dp))
        ProgressBar(progress = progress, kind = ProgressKind.Default)
        Column(modifier = Modifier.padding(top = PocketShellSpacing.xs + 2.dp)) {
            Text(
                text = "resets in 2 days",
                color = PocketShellColors.TextMuted,
                style = PocketShellType.labelMono,
            )
            Text(
                text = "Mon Sep 7, 08:45",
                color = PocketShellColors.TextSecondary,
                style = PocketShellType.labelMono,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun UsageProviderDot(color: Color) {
    Box(
        modifier = Modifier
            .size(PocketShellSpacing.sm)
            .background(color = color, shape = RoundedCornerShape(PocketShellSpacing.xs)),
    )
}
