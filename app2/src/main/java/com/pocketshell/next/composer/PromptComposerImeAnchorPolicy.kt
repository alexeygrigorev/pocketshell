@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.pocketshell.next.composer

import androidx.compose.material3.SheetValue

/**
 * The one-shot anchor transitions owned by the composer's IME policy.
 *
 * Ported from v0.4.47 for overlay geometry only (#2521): Material owns the
 * actual anchors and animations. This policy decides when the composer must
 * leave a settled partial anchor because the measured modal Surface crosses
 * its owning root's keyboard boundary, and whether an earlier policy-owned
 * expansion may be restored after the IME hides.
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
        SheetValue.Expanded -> snapshot.hasPartiallyExpandedState
        SheetValue.PartiallyExpanded -> false
        SheetValue.Hidden -> previous
    }
}

internal fun composerModalSurfaceOverlapsIme(
    geometry: ComposerModalSurfaceGeometry?,
    surfaceTopPx: Float?,
    imeBottomPx: Int,
): Boolean = geometry != null &&
    surfaceTopPx != null &&
    imeBottomPx > 0 &&
    surfaceTopPx + geometry.surfaceHeightPx >
    geometry.rootBottomPx - imeBottomPx

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
