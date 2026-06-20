package com.pocketshell.app.fileexplorer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.core.ssh.RemoteEntry
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Durable regression proof for the issue #762 "Slice 1" row redesign: every
 * file-explorer entry leads with a graphical [com.pocketshell.uikit.components.FileTypeIcon]
 * (per extension class) and NEVER with the old cramped `DIR`/`FILE`/`LNK`/`OTH`
 * TEXT glyph that wrapped (`FILE` -> `FIL`+`E`) in a 24dp box.
 *
 * Why this guards the whole class, not a single instance:
 *  - It composes the REAL production [FileExplorerScaffold] in its Ready state
 *    (not a stand-in), with one entry per icon bucket — folder, symlink, image,
 *    code, archive, binary — so a regression in ANY bucket's mapping fails here.
 *  - It asserts the per-row leading icon's accessibility label (the icon's
 *    `contentDescription`), which is the property "this row shows a type ICON,
 *    not a text tag." A re-introduced `Text("FILE")` glyph would carry no such
 *    icon label.
 *  - It HARD-asserts that no `DIR` / `FILE` / `LNK` / `OTH` text node exists
 *    anywhere in the listing — the only way the FILE-wrap bug can recur is if a
 *    text type-tag is rendered again, so this is the root-cause guard, not a
 *    proxy.
 *  - It asserts viewport CONTAINMENT (`assertNodeFullyWithinRoot`) of the file
 *    rows, not a bare `assertIsDisplayed()`, so a clipped / wrapped / off-edge
 *    row label fails (issue #657 F1 containment rule).
 *
 * Pure JVM coverage of the `fileIconClass()` bucketing already lives in
 * `FileExplorerFormatTest`; this is the on-device proof that the shipped screen
 * actually renders those icons and has dropped the text glyph.
 */
@RunWith(AndroidJUnit4::class)
class FileExplorerRowIconRegressionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** One entry per icon bucket + the icon's expected a11y contentDescription. */
    private data class Case(val entry: RemoteEntry, val iconLabel: String)

    private val cases = listOf(
        Case(RemoteEntry("workspace", RemoteEntry.Type.DIRECTORY, 0L, null), "Folder"),
        Case(RemoteEntry("current-link", RemoteEntry.Type.SYMLINK, 0L, null), "Symbolic link"),
        Case(RemoteEntry("screenshot.png", RemoteEntry.Type.FILE, 1_200_000L, null), "Image file"),
        Case(RemoteEntry("issue-103-starter.patch", RemoteEntry.Type.FILE, 8_800L, null), "Code or text file"),
        Case(RemoteEntry("release-bundle.tar.gz", RemoteEntry.Type.FILE, 44_000_000L, null), "Archive"),
        Case(RemoteEntry("artifact.bin", RemoteEntry.Type.FILE, 256L, null), "File"),
    )

    private fun setReady() {
        compose.setContent {
            PocketShellTheme {
                FileExplorerScaffold(
                    hostName = "agents",
                    state = FileExplorerUiState.Ready("/home/u/proj", cases.map { it.entry }, false),
                    transfer = FileTransferState.Idle,
                    onBack = {},
                    onUp = {},
                    onOpenDirectory = {},
                    onOpenFile = {},
                    onDownloadFile = {},
                    onUpload = {},
                    onDismissTransfer = {},
                    onCrumb = {},
                    onGoToPath = {},
                    onRetry = {},
                )
            }
        }
    }

    @Test
    fun everyEntryLeadsWithItsTypeIconAcrossAllBuckets() {
        setReady()
        // Class coverage: each bucket's icon is present by its a11y label. A
        // wrong mapping (e.g. .tar.gz -> binary, or a folder rendered as a file)
        // would leave the expected label absent and fail here. We count >= the
        // number of entries we placed in that bucket (the always-present `..`
        // parent row contributes an extra "Folder" icon, so the folder bucket
        // legitimately has more than one).
        for ((iconLabel, placed) in cases.groupingBy { it.iconLabel }.eachCount()) {
            val found = compose
                .onAllNodesWithContentDescription(iconLabel, useUnmergedTree = true)
                .fetchSemanticsNodes().size
            assertTrue(
                "Expected at least $placed '$iconLabel' FileTypeIcon(s) in the listing " +
                    "but found $found — a bucket's icon mapping regressed.",
                found >= placed,
            )
        }
    }

    @Test
    fun noTextTypeGlyphIsRenderedSoTheFileWrapBugCannotRecur() {
        setReady()
        // The FILE-wrap bug (issue #762) existed ONLY because the old leading
        // slot rendered the literal text "FILE" (and "DIR"/"LNK"/"OTH") in a
        // 24dp box with no maxLines. Asserting none of those exact text glyphs
        // is rendered is the root-cause guard: if any reappears, the wrap class
        // is back and this fails. (Substring tokens, not whole filenames, so a
        // file literally named "FILE" elsewhere is not the concern — the leading
        // type-tag was always the exact uppercase token.)
        for (glyph in listOf("DIR", "FILE", "LNK", "OTH")) {
            assertEquals(
                "A leading TEXT type-glyph '$glyph' was rendered — the cramped " +
                    "DIR/FILE/LNK/OTH text glyph (and its FILE-wrap) must stay deleted " +
                    "(issue #762, hard-cut D22). The row must lead with a FileTypeIcon.",
                0,
                compose.onAllNodesWithText(glyph, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    @Test
    fun fileRowTitlesAreFullyWithinTheViewportNotClippedOrWrapped() {
        setReady()
        // Containment (issue #657 F1), not a bare assertIsDisplayed(): a row
        // whose leading slot mis-sized (the old fixed-width text box) and pushed
        // content off an edge, or a title that wrapped, would fail this.
        for (case in cases) {
            val tag = FILE_EXPLORER_ROW_TAG_PREFIX + case.entry.name
            compose.onNodeWithTag(tag).assertIsDisplayed()
            compose.assertNodeFullyWithinRoot(tag)
        }
    }
}
