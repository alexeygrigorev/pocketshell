package com.pocketshell.app.composer

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pocketshell.uikit.theme.PocketShellColors

/**
 * Issue #1622 — the composer sheet's own Material chrome, in ONE place.
 *
 * ## Why this exists
 *
 * The composer's `ModalBottomSheet` parameters used to be inline in
 * [PromptComposerSheet], and every geometry proof re-declared them by hand.
 * `ComposerPartialExpandE2eTest` even carried a comment begging future readers
 * to "keep it in lockstep" with production, because #615 shipped a regression
 * precisely when the harness copy drifted. A copy that must be kept in lockstep
 * by hand is a copy that will drift, so there is now exactly one declaration and
 * the proofs mount THIS.
 *
 * ## What it fixes (the measured #1622 dead band)
 *
 * Two of the three mechanisms behind the ~67 dp of chrome the maintainer
 * measured above the grabber — in BOTH keyboard states — live here.
 *
 * **Mechanism A — the top inset was dead padding.** The old call passed
 * `contentWindowInsets = safeDrawing.only(Top + Horizontal)`. Material applies
 * that with `windowInsetsPadding` on the sheet's internal column
 * (`ModalBottomSheetKt$ModalBottomSheetContent$7`, verified in the 1.3.2
 * bytecode), and `InsetsPaddingModifier` is **not position aware** — it applies
 * the raw `getTop()` with no clipping against where the element actually sits.
 * So a sheet floating ~1400 px below the status bar still paid the device's full
 * status-bar/cutout inset (118 px = 45 dp on the maintainer's Pixel), as a band
 * of nothing between the sheet's top edge and the grabber. Dropping `Top` here
 * removes it. The genuine concern it was standing in for — a pathologically tall
 * sheet growing up under the status bar — is now handled where it belongs, as a
 * height CAP on the body in [SheetContent] (`maxHeight - safeDrawing.top`), which
 * costs nothing until the sheet actually gets that tall.
 *
 * **Mechanism B — the default drag handle spends 48 dp on a 4 dp bar.**
 * Material's `BottomSheetDefaults.DragHandle` pads the bar by
 * `DragHandleVerticalPadding = 22.dp` top and bottom. [ComposerDragHandle] keeps
 * the identical 32x4 dp bar (so the affordance reads the same) with
 * [ComposerDragHandleVerticalPadding] instead, for a ~22 dp visual block.
 *
 * Nothing about the drag or dismiss affordance changes. In material3 1.3.2 the
 * drag gesture lives on the sheet Surface (`anchoredDraggable`), not on the
 * handle, and the accessibility dismiss/expand/collapse actions live on the
 * `Box` that `ModalBottomSheetContent` wraps around whatever `dragHandle`
 * composable it is given — this one included. The handle is a visual cue with a
 * semantics wrapper, so shrinking its padding removes painted emptiness and no
 * interaction.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ComposerModalBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = PocketShellColors.Surface,
        contentColor = PocketShellColors.Text,
        // Issue #1744: measure Material's real Surface and move a settled partial
        // anchor only when that Surface crosses this modal root's exact IME
        // boundary. The policy owns only expansions it initiated, so a
        // user-expanded sheet is never collapsed when the keyboard hides. It is
        // applied HERE rather than at the call site so a proof that mounts this
        // wrapper gets the real anchor behaviour and cannot silently prove
        // geometry against a sheet that never corrects its partial anchor.
        modifier = modifier.composerImeAnchorPolicy(sheetState),
        dragHandle = { ComposerDragHandle() },
        // Issue #1622 (mechanism A). HORIZONTAL ONLY. The cutout/status clearance
        // this used to include never applied to a sheet that does not reach the
        // top of the screen, but `windowInsetsPadding` charged for it anyway; see
        // the block comment above. `Bottom` is deliberately absent too: Material's
        // own container is already IME-padded, and the keyboard-DOWN nav-bar
        // clearance is taken by `navigationBarsPadding()` inside [SheetContent].
        contentWindowInsets = {
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)
        },
        content = content,
    )
}

/**
 * The composer's grabber: Material's exact 32x4 dp bar, with the painted dead
 * space around it cut from 22 dp to [ComposerDragHandleVerticalPadding].
 *
 * See [ComposerModalBottomSheet] for why this costs no affordance.
 */
@Composable
internal fun ComposerDragHandle(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .padding(vertical = ComposerDragHandleVerticalPadding)
            .size(width = ComposerDragHandleBarWidth, height = ComposerDragHandleBarHeight)
            .background(
                color = PocketShellColors.TextSecondary,
                shape = RoundedCornerShape(percent = 50),
            )
            .testTag(COMPOSER_DRAG_HANDLE_TAG),
    )
}

/**
 * Issue #1622: the grabber's painted block, top edge to bottom edge —
 * `2 * `[ComposerDragHandleVerticalPadding]` + `[ComposerDragHandleBarHeight].
 * Material's default is 48 dp for the same 4 dp bar.
 *
 * Proofs assert the sheet's total chrome against this rather than a literal, so
 * a future padding change moves the expectation with it instead of silently
 * eating the reclaimed room.
 */
internal val ComposerDragHandleBlockHeight: Dp
    get() = ComposerDragHandleVerticalPadding * 2 + ComposerDragHandleBarHeight

internal val ComposerDragHandleVerticalPadding = 9.dp
internal val ComposerDragHandleBarWidth = 32.dp
internal val ComposerDragHandleBarHeight = 4.dp

internal const val COMPOSER_DRAG_HANDLE_TAG = "prompt-composer-drag-handle"
