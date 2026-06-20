package com.pocketshell.app.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #840 slice 2 (D22 hard-cut guard): the two deferred disclosure sites
 * must adopt the single shared rotating `DisclosureIcon` and the divergent
 * ad-hoc glyphs must be DELETED, not kept as a fallback.
 *
 * This is the source-structure regression net that backs the ui-kit pixel test
 * (`DisclosureIconSlice2Test`) — it fails if a site reverts to a bespoke
 * disclosure glyph:
 *  - `FolderListScreen.kt` must no longer carry the private `DisclosureIndicator`
 *    composable (the two-distinct-triangle-Paths affordance) and must call the
 *    shared `DisclosureIcon`.
 *  - `ConversationSystemNoteRow` (`TmuxSessionScreen.kt`) must lead its header
 *    with a `DisclosureIcon` (it previously had NO affordance at all).
 *
 * Runs in the plain `:app:testDebugUnitTest` Unit CI job.
 */
class DisclosureIconAdoptionTest {

    @Test
    fun folderListScreenUsesSharedDisclosureIconAndDeletedThePrivateIndicator() {
        val src = locate("projects/FolderListScreen.kt")

        assertTrue(
            "FolderListScreen.kt must import the shared DisclosureIcon",
            src.contains("import com.pocketshell.uikit.components.DisclosureIcon"),
        )
        assertTrue(
            "FolderListScreen.kt must call the shared DisclosureIcon at the tree row",
            src.contains("DisclosureIcon("),
        )
        // D22 hard-cut: the divergent private affordance must be gone, not kept.
        assertFalse(
            "FolderListScreen.kt still declares the private DisclosureIndicator — " +
                "the divergent two-triangle-Paths glyph must be DELETED (#840 D22).",
            src.contains("fun DisclosureIndicator("),
        )
    }

    @Test
    fun conversationSystemNoteRowLeadsWithSharedDisclosureIcon() {
        val src = locate("tmux/TmuxSessionScreen.kt")

        val rowStart = src.indexOf("fun ConversationSystemNoteRow(")
        assertTrue("ConversationSystemNoteRow not found in TmuxSessionScreen.kt", rowStart >= 0)
        // Look at the composable body (bounded window) to confirm it now carries
        // the shared affordance it previously lacked.
        val rowBody = src.substring(rowStart, minOf(rowStart + 3000, src.length))
        assertTrue(
            "ConversationSystemNoteRow must lead its header with the shared DisclosureIcon " +
                "(it had no disclosure affordance before #840 slice 2).",
            rowBody.contains("DisclosureIcon("),
        )
    }

    private fun locate(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/$relative"),
            File("src/main/java/com/pocketshell/app/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
    }
}
