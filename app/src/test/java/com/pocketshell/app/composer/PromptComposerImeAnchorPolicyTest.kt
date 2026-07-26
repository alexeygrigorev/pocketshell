package com.pocketshell.app.composer

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class PromptComposerImeAnchorPolicyTest {

    @Test
    fun measuredSurfaceUsesExactSameRootKeyboardBoundary() {
        val geometry = ComposerModalSurfaceGeometry(
            rootBottomPx = 2_400,
            surfaceHeightPx = 852,
        )

        assertEquals(
            false,
            composerModalSurfaceOverlapsIme(
                geometry = null,
                surfaceTopPx = 802f,
                imeBottomPx = 774,
            ),
        )
        assertEquals(
            false,
            composerModalSurfaceOverlapsIme(
                geometry = geometry,
                surfaceTopPx = null,
                imeBottomPx = 774,
            ),
        )
        assertEquals(
            false,
            composerModalSurfaceOverlapsIme(
                geometry = geometry,
                surfaceTopPx = 802f,
                imeBottomPx = 0,
            ),
        )
        assertEquals(
            false,
            composerModalSurfaceOverlapsIme(
                geometry = geometry,
                surfaceTopPx = 774f,
                imeBottomPx = 774,
            ),
        )
        assertEquals(
            true,
            composerModalSurfaceOverlapsIme(
                geometry = geometry,
                surfaceTopPx = 802f,
                imeBottomPx = 774,
            ),
        )
    }

    @Test
    fun settledPartialExpandsOnlyWhenItsMeasuredSurfaceOverlapsVisibleIme() {
        assertEquals(
            ComposerImeAnchorAction.ExpandFromPartial,
            decide(
                imeVisible = true,
                overlapsKeyboard = true,
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.PartiallyExpanded,
            ),
        )
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = true,
                overlapsKeyboard = false,
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.PartiallyExpanded,
            ),
        )
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = false,
                overlapsKeyboard = true,
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.PartiallyExpanded,
            ),
        )
    }

    @Test
    fun openingAndExpansionAnimationsAreNeverCompetedWith() {
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = true,
                overlapsKeyboard = true,
                currentValue = SheetValue.Hidden,
                targetValue = SheetValue.PartiallyExpanded,
            ),
        )
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = true,
                overlapsKeyboard = true,
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.Expanded,
                autoExpandedFromPartial = true,
            ),
        )
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.Expanded,
                autoExpandedFromPartial = true,
            ),
        )
    }

    @Test
    fun userOwnedExpandedStateIsNeverClaimedOrCollapsed() {
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = true,
                overlapsKeyboard = true,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
            ),
        )
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
                hasPartiallyExpandedState = true,
                autoExpandedFromPartial = false,
            ),
        )
    }

    @Test
    fun imeOwnedExpansionRestoresOnlyWhenPartialAnchorStillExists() {
        val ownsExpansion = composerImeOwnsExpansionAfter(
            outcome = ComposerImeExpansionOutcome.Completed,
            preservePreImeExpanded = false,
        )
        assertEquals(true, ownsExpansion)
        assertEquals(
            ComposerImeAnchorAction.RestorePartial,
            decide(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
                hasPartiallyExpandedState = true,
                autoExpandedFromPartial = ownsExpansion,
            ),
        )
        assertEquals(
            ComposerImeAnchorAction.ReleaseOwnership,
            decide(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
                hasPartiallyExpandedState = false,
                autoExpandedFromPartial = true,
            ),
        )
    }

    @Test
    fun imeOwnedSheetReexpandsAfterASettledUserCollapseRecreatesOverlap() {
        assertEquals(
            ComposerImeAnchorAction.ExpandFromPartial,
            decide(
                imeVisible = true,
                overlapsKeyboard = true,
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.PartiallyExpanded,
                hasPartiallyExpandedState = true,
                autoExpandedFromPartial = true,
            ),
        )
    }

    @Test
    fun interruptedExpansionEndingExpandedIsUserOwnedAndNeverRestoredOnImeHide() {
        val ownsExpansion = composerImeOwnsExpansionAfter(
            outcome = ComposerImeExpansionOutcome.InterruptedByUser,
            preservePreImeExpanded = false,
        )

        assertEquals(false, ownsExpansion)
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
                autoExpandedFromPartial = ownsExpansion,
            ),
        )
    }

    @Test
    fun interruptedExpansionEndingPartialMaySafelyRetryVisibleOverlap() {
        val ownsExpansion = composerImeOwnsExpansionAfter(
            outcome = ComposerImeExpansionOutcome.InterruptedByUser,
            preservePreImeExpanded = false,
        )

        assertEquals(false, ownsExpansion)
        assertEquals(
            ComposerImeAnchorAction.ExpandFromPartial,
            decide(
                imeVisible = true,
                overlapsKeyboard = true,
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.PartiallyExpanded,
                autoExpandedFromPartial = ownsExpansion,
            ),
        )
    }

    @Test
    fun disposedExpansionClearsOwnershipAndLeavesNoRestoreAction() {
        val ownsExpansion = composerImeOwnsExpansionAfter(
            outcome = ComposerImeExpansionOutcome.EffectDisposed,
            preservePreImeExpanded = false,
        )

        assertEquals(false, ownsExpansion)
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
                autoExpandedFromPartial = ownsExpansion,
            ),
        )
    }

    @Test
    fun materialRetargetOfPreImeExpandedSheetIsCorrectedWithoutTakingOwnership() {
        var preservePreImeExpanded = false
        val keyboardDownExpanded = snapshot(
            imeVisible = false,
            overlapsKeyboard = false,
            currentValue = SheetValue.Expanded,
            targetValue = SheetValue.Expanded,
            autoExpandedFromPartial = false,
        )
        preservePreImeExpanded = updateComposerPreImeExpanded(
            previous = preservePreImeExpanded,
            snapshot = keyboardDownExpanded,
        )
        assertEquals(true, preservePreImeExpanded)

        val materialRetargetedPartial = snapshot(
            imeVisible = true,
            overlapsKeyboard = true,
            currentValue = SheetValue.PartiallyExpanded,
            targetValue = SheetValue.PartiallyExpanded,
            autoExpandedFromPartial = false,
        )
        preservePreImeExpanded = updateComposerPreImeExpanded(
            previous = preservePreImeExpanded,
            snapshot = materialRetargetedPartial,
        )
        assertEquals(true, preservePreImeExpanded)
        assertEquals(
            ComposerImeAnchorAction.ExpandFromPartial,
            decideComposerImeAnchorAction(materialRetargetedPartial),
        )

        val ownsCorrection = composerImeOwnsExpansionAfter(
            outcome = ComposerImeExpansionOutcome.Completed,
            preservePreImeExpanded = preservePreImeExpanded,
        )
        assertEquals(false, ownsCorrection)
        assertEquals(
            ComposerImeAnchorAction.None,
            decide(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
                autoExpandedFromPartial = ownsCorrection,
            ),
        )
    }

    @Test
    fun defaultExpandedWithoutPartialAnchorDoesNotBlockLaterOwnedRestoration() {
        var preservePreImeExpanded = false
        val keyboardDownDefaultExpanded = snapshot(
            imeVisible = false,
            overlapsKeyboard = false,
            currentValue = SheetValue.Expanded,
            targetValue = SheetValue.Expanded,
            hasPartiallyExpandedState = false,
            autoExpandedFromPartial = false,
        )
        preservePreImeExpanded = updateComposerPreImeExpanded(
            previous = preservePreImeExpanded,
            snapshot = keyboardDownDefaultExpanded,
        )
        assertEquals(false, preservePreImeExpanded)

        val laterOverlappingPartial = snapshot(
            imeVisible = true,
            overlapsKeyboard = true,
            currentValue = SheetValue.PartiallyExpanded,
            targetValue = SheetValue.PartiallyExpanded,
            hasPartiallyExpandedState = true,
            autoExpandedFromPartial = false,
        )
        preservePreImeExpanded = updateComposerPreImeExpanded(
            previous = preservePreImeExpanded,
            snapshot = laterOverlappingPartial,
        )
        assertEquals(false, preservePreImeExpanded)
        assertEquals(
            ComposerImeAnchorAction.ExpandFromPartial,
            decideComposerImeAnchorAction(laterOverlappingPartial),
        )

        val ownsExpansion = composerImeOwnsExpansionAfter(
            outcome = ComposerImeExpansionOutcome.Completed,
            preservePreImeExpanded = preservePreImeExpanded,
        )
        assertEquals(true, ownsExpansion)
        assertEquals(
            ComposerImeAnchorAction.RestorePartial,
            decide(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
                hasPartiallyExpandedState = true,
                autoExpandedFromPartial = ownsExpansion,
            ),
        )
    }

    private fun decide(
        imeVisible: Boolean,
        overlapsKeyboard: Boolean,
        currentValue: SheetValue,
        targetValue: SheetValue,
        hasPartiallyExpandedState: Boolean = true,
        autoExpandedFromPartial: Boolean = false,
    ): ComposerImeAnchorAction = decideComposerImeAnchorAction(
        snapshot(
            imeVisible = imeVisible,
            overlapsKeyboard = overlapsKeyboard,
            currentValue = currentValue,
            targetValue = targetValue,
            hasPartiallyExpandedState = hasPartiallyExpandedState,
            autoExpandedFromPartial = autoExpandedFromPartial,
        ),
    )

    private fun snapshot(
        imeVisible: Boolean,
        overlapsKeyboard: Boolean,
        currentValue: SheetValue,
        targetValue: SheetValue,
        hasPartiallyExpandedState: Boolean = true,
        autoExpandedFromPartial: Boolean = false,
    ): ComposerImeAnchorSnapshot =
        ComposerImeAnchorSnapshot(
            imeVisible = imeVisible,
            overlapsKeyboard = overlapsKeyboard,
            currentValue = currentValue,
            targetValue = targetValue,
            hasPartiallyExpandedState = hasPartiallyExpandedState,
            autoExpandedFromPartial = autoExpandedFromPartial,
        )
}
