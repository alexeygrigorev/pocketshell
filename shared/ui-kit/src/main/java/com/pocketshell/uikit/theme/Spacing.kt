package com.pocketshell.uikit.theme

import androidx.compose.ui.unit.dp

/**
 * PocketShell spacing scale — the 4 dp base grid shared by Linear and Material 3.
 *
 * Codifies `docs/design-system.md` §3 (which previously admitted spacing was
 * "not yet codified"). Call sites should reach for these named rungs instead of
 * freehand `.dp` literals so the 4 dp grid stays enforced; if a padding/gap/margin
 * value doesn't land on a rung, it's a bug or scope creep (§3).
 *
 * The defaults deliberately favour the tighter rungs for the dev-tool density the
 * maintainer asked for (#461 Δ6) — see [PocketShellDensity] for the row/chip knobs
 * that consume these.
 */
object PocketShellSpacing {
    /** 4 dp — micro-gaps (icon-to-label, breadcrumb separators). */
    val xs = 4.dp

    /** 8 dp — standard gap (chip-to-chip, row-to-row padding), key bar gap. */
    val sm = 8.dp

    /** 12 dp — card internal padding, row vertical padding (the compact default). */
    val md = 12.dp

    /** 16 dp — large padding (app bar, sheet header, host-card internal), dialog padding. */
    val lg = 16.dp
}

/**
 * PocketShell density knob (#461 Δ6) — the compact dev-tool defaults for rows,
 * chips, and trees.
 *
 * **Visual density is kept separate from the touch floor.** [rowPadV]/[chipPadV]
 * shrink the *paint* so more rows fit per screen, while [tapTargetMin] (48 dp) is
 * the a11y hit-area floor every interactive element must still honour via
 * `Modifier.sizeIn` / `minimumInteractiveComponentSize`. Shrinking the paint must
 * never shrink the hit area below 48 dp.
 */
object PocketShellDensity {
    /** 72 dp — standard Quiet row minimum height. */
    val rowMinHeight = 72.dp

    /** 88 dp — the Quiet workspace row's primary navigation target. */
    val workspaceRowMinHeight = 88.dp

    /** 72 dp — the Quiet standard row's minimum touch and reading height. */
    val standardRowMinHeight = 72.dp

    /** 12 dp — row vertical padding. Rows may grow for wrapped content. */
    val rowPadV = 12.dp

    /** 12 dp — row horizontal padding. */
    val rowPadH = 12.dp

    /** 6 dp — chip vertical padding. */
    val chipPadV = 6.dp

    /** 10 dp — chip horizontal padding. */
    val chipPadH = 10.dp

    /** 8 dp — gap between sections / stacked groups. */
    val sectionGap = 8.dp

    /** 16 dp — indent applied per workspace-tree nesting level. */
    val treeIndent = 16.dp

    /** 48 dp — a11y touch-target floor. Visual density never drops the hit area below this. */
    val tapTargetMin = 48.dp
}
