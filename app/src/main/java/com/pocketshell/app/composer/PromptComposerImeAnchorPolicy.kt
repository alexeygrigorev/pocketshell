@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pocketshell.app.composer

import androidx.compose.material3.SheetValue

/**
 * The one-shot anchor transitions owned by the composer's IME policy.
 *
 * Material owns the actual anchors and animations. This policy only decides
 * when the composer must leave a settled partial anchor because the measured
 * modal Surface crosses its owning root's keyboard boundary, and whether an
 * earlier policy-owned expansion may be restored after the IME hides.
 */
internal enum class ComposerImeAnchorAction {
    None,
    ExpandFromPartial,
    RestorePartial,
    ReleaseOwnership,
}

/**
 * How a policy-requested expansion stopped.
 *
 * Ownership begins only after [Completed]. Material cancels `expand()` when a
 * user gesture wins its mutation mutex; either cancellation outcome must leave
 * the resulting anchor user-owned so hiding the IME cannot collapse it.
 */
internal enum class ComposerImeExpansionOutcome {
    Completed,
    InterruptedByUser,
    EffectDisposed,
}

internal fun composerImeOwnsExpansionAfter(
    outcome: ComposerImeExpansionOutcome,
    preservePreImeExpanded: Boolean,
): Boolean = outcome == ComposerImeExpansionOutcome.Completed &&
    !preservePreImeExpanded

internal data class ComposerImeAnchorSnapshot(
    val imeVisible: Boolean,
    val overlapsKeyboard: Boolean,
    val currentValue: SheetValue,
    val targetValue: SheetValue,
    val hasPartiallyExpandedState: Boolean,
    val autoExpandedFromPartial: Boolean,
)

internal data class ComposerModalSurfaceGeometry(
    val rootBottomPx: Int,
    val surfaceHeightPx: Int,
)

/**
 * Remembers the last settled keyboard-down anchor while the IME is visible.
 *
 * Material may recalculate its anchors on the 0 -> positive inset transition
 * and retarget an already user/pre-IME-expanded sheet to Partial. The overlap
 * correction may expand it again, but must not claim that correction and later
 * collapse the user's original Expanded state when the IME hides.
 */
internal fun updateComposerPreImeExpanded(
    previous: Boolean,
    snapshot: ComposerImeAnchorSnapshot,
): Boolean {
    if (snapshot.imeVisible || snapshot.autoExpandedFromPartial) {
        return previous
    }
    if (snapshot.currentValue != snapshot.targetValue) {
        return previous
    }
    return when (snapshot.currentValue) {
        // A wrap-content sheet with no Partial anchor also reports Expanded;
        // that is Material's only/default compact state, not evidence that the
        // user chose Expanded over an available Partial anchor.
        SheetValue.Expanded -> snapshot.hasPartiallyExpandedState
        SheetValue.PartiallyExpanded -> false
        SheetValue.Hidden -> previous
    }
}

/**
 * Uses Material's anchor offset plus the exact measured Surface height and
 * owning-root boundary. The caller modifier is outside Material's internal
 * anchored translation, so its local position is not the Surface's displayed
 * top; [surfaceTopPx] is the authoritative same-root anchor coordinate.
 * Equality is safe: a Surface ending exactly at the keyboard top is contained
 * and needs no move.
 */
internal fun composerModalSurfaceOverlapsIme(
    geometry: ComposerModalSurfaceGeometry?,
    surfaceTopPx: Float?,
    imeBottomPx: Int,
): Boolean = geometry != null &&
    surfaceTopPx != null &&
    imeBottomPx > 0 &&
    surfaceTopPx + geometry.surfaceHeightPx >
    geometry.rootBottomPx - imeBottomPx

/**
 * Pure state oracle for the production modal-anchor effect.
 *
 * Expansion is requested only from a *settled* [SheetValue.PartiallyExpanded]
 * anchor. Waiting for both current and target values to agree avoids competing
 * with Material's opening or user-owned animations. Once this policy owns an
 * expansion, a later user collapse that recreates the overlap is expanded
 * again. A user-owned [SheetValue.Expanded] state is never claimed and
 * therefore never collapsed when the IME hides.
 */
internal fun decideComposerImeAnchorAction(
    snapshot: ComposerImeAnchorSnapshot,
): ComposerImeAnchorAction {
    if (!snapshot.imeVisible) {
        if (!snapshot.autoExpandedFromPartial) {
            return ComposerImeAnchorAction.None
        }
        if (
            snapshot.hasPartiallyExpandedState &&
            snapshot.targetValue == SheetValue.Expanded &&
            snapshot.currentValue != SheetValue.Expanded
        ) {
            // The IME disappeared while the policy-owned expansion was still
            // animating. Keep ownership until it settles; otherwise a large
            // keyboard-down sheet that still has a partial anchor could be
            // stranded expanded without ever receiving RestorePartial.
            return ComposerImeAnchorAction.None
        }
        return if (
            snapshot.hasPartiallyExpandedState &&
            snapshot.currentValue == SheetValue.Expanded &&
            snapshot.targetValue == SheetValue.Expanded
        ) {
            ComposerImeAnchorAction.RestorePartial
        } else {
            ComposerImeAnchorAction.ReleaseOwnership
        }
    }

    if (
        snapshot.overlapsKeyboard &&
        snapshot.currentValue == SheetValue.PartiallyExpanded &&
        snapshot.targetValue == SheetValue.PartiallyExpanded
    ) {
        return ComposerImeAnchorAction.ExpandFromPartial
    }

    return ComposerImeAnchorAction.None
}
