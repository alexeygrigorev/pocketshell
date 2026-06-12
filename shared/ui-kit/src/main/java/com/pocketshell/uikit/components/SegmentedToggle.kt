package com.pocketshell.uikit.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity
import com.pocketshell.uikit.theme.PocketShellShapes
import com.pocketshell.uikit.theme.PocketShellType

/**
 * Shared segmented toggle — the cyan-active "pick one of N" control the
 * design language locked on #479 / #481 (the maintainer's terminal mockup
 * `issue-482-terminal-screen-mockup.png` shows it as the
 * `Terminal | Conversation` switch in the session header).
 *
 * One bordered, rounded container holds all segments so the user reads it
 * as a single co-located control rather than separate buttons. The selected
 * segment takes the [PocketShellColors.Accent] cyan fill with
 * [PocketShellColors.Background]-coloured label (dark-on-cyan); unselected
 * segments sit on the elevated surface with [PocketShellColors.TextSecondary]
 * labels. This is the canonical look so every screen that needs a segmented
 * switch renders identically.
 *
 * Presentational only — selection state and the click callback are owned by
 * the caller. [segmentTag] lets a call site attach a stable per-segment test
 * tag (e.g. the tmux session screen tags index 0 as the "Terminal" segment).
 *
 * Sizing matches the mockup's compact header control: a 32dp-tall track with
 * 8/4dp segment padding so two short labels fit inside a 56dp toolbar row.
 * More segments keep working but widen the control.
 */
@Composable
fun SegmentedToggle(
    labels: List<String>,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
    segmentTag: (index: Int) -> String? = { null },
    fillSegments: Boolean = false,
) {
    // #461 token migration: the track + segments snap onto the chip shape rung
    // (`PocketShellShapes.small` = 8dp) rather than the previous off-ladder 10dp,
    // matching the chip/key vocabulary in the design system.
    val trackShape = PocketShellShapes.small
    Row(
        modifier = modifier
            .heightIn(min = 32.dp)
            .background(color = PocketShellColors.SurfaceElev, shape = trackShape)
            .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = trackShape)
            .selectableGroup()
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val tag = segmentTag(index)
            Box(
                modifier = (if (fillSegments) Modifier.weight(1f) else Modifier)
                    .background(
                        color = if (selected) PocketShellColors.Accent else PocketShellColors.SurfaceElev,
                        shape = PocketShellShapes.small,
                    )
                    .clickable(role = Role.Tab, onClick = { onSelected(index) })
                    .padding(
                        horizontal = PocketShellDensity.chipPadH,
                        vertical = PocketShellDensity.chipPadV,
                    )
                    .let { if (tag != null) it.testTag(tag) else it },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (selected) PocketShellColors.Background else PocketShellColors.TextSecondary,
                    // #461: snap the off-ladder 12sp label onto the dense type
                    // rung (`bodyDense` = 13sp), the design system's named size
                    // for compact controls.
                    style = PocketShellType.bodyDense,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
