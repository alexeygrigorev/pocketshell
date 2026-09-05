package com.pocketshell.next.composer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #682 reproduce-first: hide/clearFocus must run BEFORE dispatch.
 *
 * A Send that dispatches first (or never hides) is the "Send opens the
 * keyboard" regression. This seam is what [ComposerBar] calls on the
 * production Send path.
 */
class ComposerSendCommitTest {

    @Test
    fun `flush clearFocus and hide run before dispatch`() {
        val steps = mutableListOf<ComposerSendStep>()
        commitComposerSend(
            flushDraft = { steps += ComposerSendStep.FlushDraft },
            clearFocus = { steps += ComposerSendStep.ClearFocus },
            hideKeyboard = { steps += ComposerSendStep.HideKeyboard },
            dispatch = { steps += ComposerSendStep.Dispatch },
        )
        assertEquals(
            listOf(
                ComposerSendStep.FlushDraft,
                ComposerSendStep.ClearFocus,
                ComposerSendStep.HideKeyboard,
                ComposerSendStep.Dispatch,
            ),
            steps,
        )
    }
}
