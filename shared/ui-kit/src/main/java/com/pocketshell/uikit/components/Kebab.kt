package com.pocketshell.uikit.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellType

/**
 * One overflow-menu action. Used by [Kebab]. [icon] is optional — a `null`
 * icon renders a label-only row (e.g. a destructive or rarely-used action that
 * doesn't have a glyph in `material-icons-core`, the only ramp on the
 * classpath).
 *
 * Most rows are a plain label (+ optional [icon]). A row that needs richer
 * content — e.g. a non-clickable status header that renders a badge instead of
 * a text label — can supply [content], which fully replaces the label/icon
 * rendering. Pair [content] with `enabled = false` + a no-op [onClick] for a
 * read-only header row.
 *
 * [contentDescription] sets a semantics content description on the row so
 * instrumentation can locate it by description (some call sites historically
 * located the action by `contentDescription` rather than [testTag]); both can
 * be set independently.
 */
data class KebabItem(
    val label: String,
    val onClick: () -> Unit,
    val icon: ImageVector? = null,
    /** Optional stable test tag for the menu item, for instrumentation. */
    val testTag: String? = null,
    /** Optional semantics content description for the menu item. */
    val contentDescription: String? = null,
    val enabled: Boolean = true,
    /**
     * Optional custom content that replaces the default label/icon row. Used
     * for read-only header rows that render a badge or other composable
     * instead of a plain label.
     */
    val content: (@Composable () -> Unit)? = null,
)

/**
 * The ⋮ overflow trigger + the M3 [DropdownMenu] it anchors — the shared "one
 * action affordance per row" the design language locked on #479 (avoid multiple
 * inline action buttons). Generalises the merged-#455 host-card kebab so every
 * screen's row overflow renders identically.
 *
 * - The trigger is a 40dp circular [PocketShellColors.SurfaceElev] container
 *   with a 1dp [PocketShellColors.BorderSoft] hairline border (design-system §8
 *   "always-visible affordance"), holding the three-dot glyph drawn directly
 *   with [Canvas] (no `material-icons-extended` dependency for `MoreVert`). The
 *   40dp container already clears the 48dp touch floor once `minimumInteractive`
 *   semantics expand around it; the explicit 40dp visible chrome matches the
 *   mockup.
 * - The menu opens on [PocketShellColors.SurfaceElev] (the
 *   `surfaceContainerHigh`-equivalent raw token in our single dark scheme) with
 *   each row at [PocketShellType.bodyDense]`(13)` + an optional leading icon.
 *
 * Expansion state is owned internally (`remember`), so the common call site is a
 * one-liner: `Kebab(items = listOf(KebabItem("Ports", onPorts), …))`. A caller
 * that needs to drive expansion externally can wire [expanded] + [onExpandedChange];
 * pass both to control it.
 *
 * [triggerTestTag] overrides the default [KEBAB_BUTTON_TAG] on the trigger so a
 * screen that already has stable instrumentation for its overflow button can
 * adopt this component without breaking existing tests.
 */
@Composable
fun Kebab(
    items: List<KebabItem>,
    modifier: Modifier = Modifier,
    contentDescription: String = "More actions",
    triggerTestTag: String = KEBAB_BUTTON_TAG,
    expanded: Boolean? = null,
    onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    var internalExpanded by remember { mutableStateOf(false) }
    val isExpanded = expanded ?: internalExpanded
    val setExpanded: (Boolean) -> Unit = { next ->
        if (onExpandedChange != null) onExpandedChange(next) else internalExpanded = next
    }

    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(color = PocketShellColors.SurfaceElev, shape = CircleShape)
                .border(width = 1.dp, color = PocketShellColors.BorderSoft, shape = CircleShape)
                .clickable(role = Role.Button, onClick = { setExpanded(true) })
                .semantics { this.contentDescription = contentDescription }
                .testTag(triggerTestTag),
            contentAlignment = Alignment.Center,
        ) {
            KebabIcon()
        }
        DropdownMenu(
            expanded = isExpanded,
            onDismissRequest = { setExpanded(false) },
            modifier = Modifier.background(PocketShellColors.SurfaceElev),
        ) {
            items.forEach { item ->
                var itemModifier: Modifier = Modifier
                item.contentDescription?.let { description ->
                    itemModifier = itemModifier.semantics { this.contentDescription = description }
                }
                item.testTag?.let { tag ->
                    itemModifier = itemModifier.testTag(tag)
                }
                DropdownMenuItem(
                    enabled = item.enabled,
                    text = item.content ?: {
                        Text(
                            text = item.label,
                            color = PocketShellColors.Text,
                            style = PocketShellType.bodyDense,
                        )
                    },
                    leadingIcon = item.icon?.let { icon ->
                        {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = PocketShellColors.TextSecondary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    onClick = {
                        setExpanded(false)
                        item.onClick()
                    },
                    modifier = itemModifier,
                )
            }
        }
    }
}

/**
 * Three small dots stacked vertically — the classic "more" affordance. Drawn
 * with [Canvas] (3 filled circles) because `material-icons-core` (the only icon
 * ramp on the ui-kit classpath) does not ship `MoreVert`. Coloured
 * [PocketShellColors.TextSecondary] so it reads as chrome, not a primary
 * affordance.
 */
@Composable
private fun KebabIcon() {
    val color = PocketShellColors.TextSecondary
    Canvas(modifier = Modifier.size(width = 4.dp, height = 18.dp)) {
        val r = size.width / 2f
        val gap = (size.height - 6f * r) / 2f
        drawCircle(color = color, radius = r, center = Offset(r, r))
        drawCircle(color = color, radius = r, center = Offset(r, 3f * r + gap))
        drawCircle(color = color, radius = r, center = Offset(r, 5f * r + 2f * gap))
    }
}

/** Stable tag for the kebab trigger so instrumentation can find it. */
const val KEBAB_BUTTON_TAG: String = "kebab:button"
