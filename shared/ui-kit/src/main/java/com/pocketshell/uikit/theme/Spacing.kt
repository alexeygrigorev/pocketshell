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

    // There is deliberately no 32dp `section` rung (#2635 T3). It existed only
    // because the kit's prose asked for "32dp section separation"; the app's
    // actual section separation is [PocketShellDensity.sectionGap] (24dp), and
    // the rung's single remaining consumer moved to it. A rung nothing uses is
    // an invitation to reintroduce a second spacing grammar.
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
    /**
     * 56 dp — standard row minimum height.
     *
     * `docs/design-language.md` asks for a 48 dp *tap* floor, not a 72 dp row.
     * The Quiet redesign read 72 dp as the design target (#2630): a Hosts
     * screen with one host and two tools rows then spent ~360 dp of a 915 dp
     * phone on six items. 56 dp clears [tapTargetMin] with 8 dp to spare and
     * still fits a title + subtitle at the reconciled 14sp/11sp rungs.
     */
    val rowMinHeight = 56.dp

    /** 64 dp — the workspace row's primary navigation target, one rung taller. */
    val workspaceRowMinHeight = 64.dp

    /**
     * The standard row's minimum touch and reading height.
     *
     * An alias of [rowMinHeight], not a second value: #2630 shipped because
     * duplicated copies of one token drifted.
     */
    val standardRowMinHeight = rowMinHeight

    /** 8 dp — row vertical padding. Rows may grow for wrapped content. */
    val rowPadV = 8.dp

    /** 20 dp — Quiet screen gutter used by standard and workspace rows. */
    val rowPadH = 20.dp

    /** 6 dp — chip vertical padding. */
    val chipPadV = 6.dp

    /** 10 dp — chip horizontal padding. */
    val chipPadH = 10.dp

    /** 24 dp — separation between independent sections. */
    val sectionGap = 24.dp

    /** 16 dp — indent applied per workspace-tree nesting level. */
    val treeIndent = 16.dp

    /** 48 dp — a11y touch-target floor. Visual density never drops the hit area below this. */
    val tapTargetMin = 48.dp

    /** 56 dp — the minimum height of a text field or a full-width button. */
    val fieldMinHeight = 56.dp
}
