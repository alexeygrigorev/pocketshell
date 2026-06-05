package com.pocketshell.uikit.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Corner-radius tokens, sourced from `docs/mockups/styles.css` `:root`:
 *
 * - `--r-card: 14px` -> 14dp (`.host-row`, `.session-row`, `.job-row`, `.usage-card`)
 * - `--r-chip: 8px` -> 8dp (`.chip`, `.key`, tag pills)
 * - `--r-fab: 28px` -> 28dp (`.fab`, `.mic-btn` — both are 56dp pills)
 * - `--r-sheet: 20px` -> 20dp (`.sheet` top corners)
 *
 * Material 3's [Shapes] only has five named slots; the mockup vocabulary is
 * wider. The mapping below covers the three slots with size semantics that
 * line up (small/medium/large), and exposes the remaining tokens as standalone
 * `Shape` constants so downstream call sites can opt in by name (`PocketShellShapes.fab`).
 */
val PocketShellShapes: Shapes = Shapes(
    // 8dp — chip + key bar. Smallest interactive corner.
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),

    // 14dp — host / session / job / usage cards. The default for any
    // "content tile" surface.
    medium = RoundedCornerShape(14.dp),

    // 20dp — bottom sheet top corners. Slightly softer than the cards because
    // the sheet is a larger surface and sharper corners look chunky at scale.
    large = RoundedCornerShape(20.dp),

    // 28dp — FAB / mic button. Effectively a pill at the 56dp diameter the
    // mockups use.
    extraLarge = RoundedCornerShape(28.dp),
)
