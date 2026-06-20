package com.pocketshell.app.fileviewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filterToOne
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #762 slice C — the file VIEWER adopts the shared [FileTypeIcon] the
 * explorer rows use, so the opened file leads with the same at-a-glance glyph in
 * the list and in the viewer (design-consistency).
 *
 * The acceptance is that the viewer header renders the [FileTypeIcon] for the
 * opened file and that the glyph matches the file's coarse type. The icon
 * carries a per-class accessibility label ("Image file" / "Code or text file" /
 * "Archive" / "File"), so each case asserts both that the tagged icon node is
 * displayed AND that its content description is the expected class — that pins
 * the actual GLYPH, not merely "an icon is present" (G6: the load-bearing
 * assertion is the type, not a structural proxy).
 *
 * These drive [FileViewerScaffold] directly with each render state (no SSH), the
 * same stateless harness the scaffold tests use. A SEPARATE file from
 * `FileViewerScaffoldTest` so it does not collide with the sibling lane that
 * owns that file.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerTypeIconTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setState(state: FileViewerUiState) {
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    hostName = "agents",
                    state = state,
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.waitForIdle()
    }

    private fun assertHeaderIcon(expectedContentDescription: String) {
        // The tagged node is the header's [FileTypeIcon] box; the per-class
        // accessibility label ("Image file" etc.) is on its child Icon. Assert
        // the box is on-screen AND that ITS icon child carries EXACTLY the
        // expected class label — scoping to the header's subtree so a second
        // identical icon elsewhere on screen (the download card) doesn't make the
        // match ambiguous. This pins the actual glyph class, not merely "an icon
        // is present" (G6).
        compose.onNodeWithTag(FILE_VIEWER_TYPE_ICON_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_TYPE_ICON_TAG, useUnmergedTree = true)
            .onChildren()
            .filterToOne(hasContentDescription(expectedContentDescription))
            .assertExists()
    }

    @Test
    fun imageStateHeaderShowsImageIcon() {
        setState(
            FileViewerUiState.Image(
                displayPath = "/tmp/screenshot.png",
                cacheFile = File.createTempFile("psicon", ".png"),
                sizeBytes = 1024,
            ),
        )
        // Confirmed-image render → IMAGE glyph regardless of suffix.
        assertHeaderIcon("Image file")
    }

    @Test
    fun textStateHeaderShowsCodeIcon() {
        setState(
            FileViewerUiState.TextContent(
                displayPath = "/tmp/Main.kt",
                content = "fun main() {}",
                sizeBytes = 13,
            ),
        )
        assertHeaderIcon("Code or text file")
    }

    @Test
    fun loadingStateHeaderUsesNameBasedIcon() {
        // Still loading → no confirmed render type yet, so the icon is derived
        // from the file NAME via the same shared map the explorer uses.
        setState(FileViewerUiState.Loading(displayPath = "/tmp/bundle.zip"))
        assertHeaderIcon("Archive")
    }

    @Test
    fun cannotPreviewBinaryHeaderShowsFileIcon() {
        setState(
            FileViewerUiState.CannotPreview(
                displayPath = "/tmp/core.dump",
                message = "This file can't be previewed.",
            ),
        )
        assertHeaderIcon("File")
    }

    @Test
    fun downloadOnlyCardAlsoLeadsWithTheTypeIcon() {
        // A downloaded-but-unpreviewable archive: the download-only card leads
        // with the SAME shared icon (ARCHIVE), not just the header.
        setState(
            FileViewerUiState.CannotPreview(
                displayPath = "/tmp/release.tar",
                message = "This file can't be previewed.",
                sizeBytes = 4096,
                cacheFile = File.createTempFile("psicon", ".tar"),
            ),
        )
        compose.onNodeWithTag(FILE_VIEWER_DOWNLOAD_ONLY_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_DOWNLOAD_TYPE_ICON_TAG).assertIsDisplayed()
        // The header carries the same archive glyph for this state too. The
        // download card AND the header both render an "Archive" icon node.
        assertHeaderIcon("Archive")
    }
}
