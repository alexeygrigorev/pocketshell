package com.pocketshell.uikit.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellDensity

/** The glyph inside the 48dp box — small ink, full-size hit area. */
private val HEADER_ICON_GLYPH = 20.dp

/**
 * A compact page action in [ScreenHeader]'s trailing slot.
 *
 * The 48dp `IconButton` is the whole touch target, so the paint can be a small
 * 20dp glyph without dropping below the a11y floor — that split is the point of
 * #2630: shrink the ink, never the hit area.
 *
 * #2635 moved this out of `HostListScreen` (where it was private) into the kit:
 * the session header's "+" is the same affordance, and a second hand-rolled
 * copy is how the three different "create" grammars the audit found got there
 * in the first place.
 */
@Composable
fun HeaderIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    testTag: String,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(PocketShellDensity.tapTargetMin)
            .testTag(testTag),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = PocketShellColors.TextSecondary,
            modifier = Modifier.size(HEADER_ICON_GLYPH),
        )
    }
}
