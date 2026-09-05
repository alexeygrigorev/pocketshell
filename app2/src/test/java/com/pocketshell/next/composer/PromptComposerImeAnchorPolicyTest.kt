package com.pocketshell.next.composer

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Overlay-geometry IME policy ported from v0.4.47 (#2521). */
@OptIn(ExperimentalMaterial3Api::class)
class PromptComposerImeAnchorPolicyTest {

    @Test
    fun `a settled partial sheet that overlaps the keyboard expands`() {
        val action = decideComposerImeAnchorAction(
            ComposerImeAnchorSnapshot(
                imeVisible = true,
                overlapsKeyboard = true,
                currentValue = SheetValue.PartiallyExpanded,
                targetValue = SheetValue.PartiallyExpanded,
                hasPartiallyExpandedState = true,
                autoExpandedFromPartial = false,
            ),
        )
        assertEquals(ComposerImeAnchorAction.ExpandFromPartial, action)
    }

    @Test
    fun `hiding the ime restores a policy-owned expansion`() {
        val action = decideComposerImeAnchorAction(
            ComposerImeAnchorSnapshot(
                imeVisible = false,
                overlapsKeyboard = false,
                currentValue = SheetValue.Expanded,
                targetValue = SheetValue.Expanded,
                hasPartiallyExpandedState = true,
                autoExpandedFromPartial = true,
            ),
        )
        assertEquals(ComposerImeAnchorAction.RestorePartial, action)
    }

    @Test
    fun `a surface ending exactly at the keyboard is not overlapping`() {
        assertFalse(
            composerModalSurfaceOverlapsIme(
                geometry = ComposerModalSurfaceGeometry(rootBottomPx = 2000, surfaceHeightPx = 400),
                surfaceTopPx = 1600f,
                imeBottomPx = 0,
            ),
        )
        assertTrue(
            composerModalSurfaceOverlapsIme(
                geometry = ComposerModalSurfaceGeometry(rootBottomPx = 2000, surfaceHeightPx = 400),
                surfaceTopPx = 1700f,
                imeBottomPx = 400,
            ),
        )
    }
}
