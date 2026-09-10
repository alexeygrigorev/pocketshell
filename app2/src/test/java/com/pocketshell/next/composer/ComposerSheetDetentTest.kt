package com.pocketshell.next.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2635 C1: the composer sheet has a PARTIAL detent again.
 *
 * `ux-rules.md` Breakage 4 — "user cannot see the terminal while composing" —
 * has been unticked since the sheet forced `skipPartiallyExpanded = true`: with
 * a single expanded detent, a long draft grew the sheet toward the top of the
 * screen with no settled state between "typing" and "the terminal is gone", and
 * the user could not push it back down without dismissing it.
 *
 * The assertion is on the SOURCE rather than on a rendered sheet, deliberately:
 * Robolectric drops clicks and drags on a `ModalBottomSheet` (the reason
 * [PromptComposerContent] exists as a separate embeddable body at all), so a
 * host-JVM test cannot drive the detent it wants to check. A source assertion is
 * honest about what it proves — that the production sheet asks Material for the
 * partial state — and it reddens the moment someone re-adds the flag, which is
 * the regression this guards.
 *
 * The behavioural half is the emulator/device pass, which `AGENTS.md` requires
 * for a composer change regardless.
 *
 * This is deliberately NOT the desktop's non-modal floating card: D11 locks
 * "prompt composer is a bottom sheet (modal over terminal), terminal dims
 * behind", and a non-modal card would need a recorded D11 amendment first.
 */
class ComposerSheetDetentTest {

    @Test
    fun `the prompt composer sheet asks Material for a partial detent`() {
        val source = sourceOf("PromptComposerSheet.kt")

        assertTrue(
            "PromptComposerSheet must construct its own sheet state",
            source.contains("rememberModalBottomSheetState("),
        )
        assertEquals(
            "#2635 C1: the composer sheet must NOT skip the partially-expanded " +
                "detent — that is `ux-rules.md` Breakage 4",
            0,
            Regex("skipPartiallyExpanded\\s*=\\s*true").findAll(source).count(),
        )
        assertTrue(
            "the partial detent must be requested explicitly, not by omission, " +
                "so the choice is visible at the call site",
            source.contains("skipPartiallyExpanded = false"),
        )
    }

    /**
     * D11's other half: it is still a MODAL sheet over the terminal. A change
     * that quietly turned it into a non-modal surface would contradict a locked
     * decision, so it fails here rather than in review.
     */
    @Test
    fun `the composer sheet is still a modal bottom sheet`() {
        val chrome = sourceOf("ComposerSheetChrome.kt")

        assertTrue(
            "D11: the composer is a ModalBottomSheet over the terminal",
            chrome.contains("ModalBottomSheet("),
        )
    }

    private fun sourceOf(fileName: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val relative = "app2/src/main/java/com/pocketshell/next/composer/$fileName"
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        error("could not find $relative above ${System.getProperty("user.dir")}")
    }
}
