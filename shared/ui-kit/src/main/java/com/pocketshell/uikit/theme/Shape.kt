package com.pocketshell.uikit.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Quiet shape tokens from `docs/design-kit/design-system/tokens.json` and the
 * Android handoff theme.
 *
 * The kit maps field and button corners to the Material `small` and `medium`
 * slots, and sheet corners to `large`. The other Material slots keep their
 * library defaults because Quiet defines no additional radius role.
 */
val PocketShellShapes: Shapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(24.dp),
)
