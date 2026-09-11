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
 * The scale follows the Quiet design kit. Row and touch dimensions live in
 * [PocketShellDensity] so spacing and hit targets cannot drift independently.
 */
object PocketShellSpacing {
    /** 4 dp — micro-gaps (icon-to-label, breadcrumb separators). */
    val xs = 4.dp

    /** 8 dp — standard gap (chip-to-chip, row-to-row padding), key bar gap. */
    val sm = 8.dp

    /** 12 dp — local control gaps and compact inline padding. */
    val md = 12.dp

    /** 16 dp — large padding (app bar, sheet header, host-card internal), dialog padding. */
    val lg = 16.dp

    /** 20 dp — the Quiet screen gutter and primary page inset. */
    val xl = 20.dp

    /** 24 dp — sheet and large surface inset. */
    val xxl = 24.dp

    /** 32 dp — separation between independent content sections. */
    val section = 32.dp
}

/**
 * PocketShell geometry shared by rows, chips and the workspace tree.
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

    /** 16 dp — row vertical padding. Rows may grow for wrapped content. */
    val rowPadV = 16.dp

    /** 20 dp — Quiet screen gutter used by standard and workspace rows. */
    val rowPadH = 20.dp

    /** 6 dp — chip vertical padding. */
    val chipPadV = 6.dp

    /** 10 dp — chip horizontal padding. */
    val chipPadH = 10.dp

    /** 32 dp — separation between independent sections. */
    val sectionGap = 32.dp

    /** 16 dp — indent applied per workspace-tree nesting level. */
    val treeIndent = 16.dp

    /** 48 dp — a11y touch-target floor. Visual density never drops the hit area below this. */
    val tapTargetMin = 48.dp
}
