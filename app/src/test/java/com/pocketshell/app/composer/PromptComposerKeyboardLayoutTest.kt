package com.pocketshell.app.composer

import androidx.compose.material3.SheetValue
import androidx.compose.material3.ExperimentalMaterial3Api
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalMaterial3Api::class)
class PromptComposerKeyboardLayoutTest {

    @Test
    fun composerUsesRestingHeightWhenImeHidden() {
        assertEquals(0.65f, promptComposerSheetHeightFraction(isImeVisible = false), 0.0001f)
    }

    @Test
    fun composerUsesFullHeightWhenImeVisible() {
        assertEquals(1f, promptComposerSheetHeightFraction(isImeVisible = true), 0.0001f)
    }

    @Test
    fun partiallyExpandedComposerAutoExpandsWhenImeAppears() {
        assertTrue(
            shouldAutoExpandPromptComposerForIme(
                isImeVisible = true,
                currentValue = SheetValue.PartiallyExpanded,
                expandedForIme = false,
            ),
        )
    }

    @Test
    fun composerDoesNotFightUserExpandedSheetWhenImeAppears() {
        assertFalse(
            shouldAutoExpandPromptComposerForIme(
                isImeVisible = true,
                currentValue = SheetValue.Expanded,
                expandedForIme = false,
            ),
        )
    }

    @Test
    fun imeExpandedComposerRestoresPartialStateWhenKeyboardHides() {
        assertTrue(
            shouldRestorePromptComposerPartialAfterIme(
                isImeVisible = false,
                expandedForIme = true,
            ),
        )
    }
}
