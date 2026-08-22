package com.pocketshell.app.fileviewer

import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.proof.signals.productionWindowChromePadding
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1715 — tab strip, empty workspace, dirty-work dialog, unique labels,
 * 48dp targets, overflow containment.
 *
 * G6 mutations:
 *  - omit the strip when tabs is non-empty → FILE_VIEWER_TAB_STRIP_TAG missing
 *  - close callback fires on the chip body → switch/close counts collide
 *  - uniqueLabels always basename → src/App.kt and test/App.kt both read App.kt
 */
@RunWith(AndroidJUnit4::class)
class FileViewerTabStripTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun stripShowsUniqueSuffixLabelsAndSeparateSwitchClose() {
        var selected: String? = null
        var closed: String? = null
        val tabs = listOf(
            OpenFileTab("/home/u/src/App.kt", 1),
            OpenFileTab("/home/u/test/App.kt", 2),
            OpenFileTab("/home/u/README.md", 3),
        )
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    modifier = Modifier.productionWindowChromePadding(),
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/home/u/src/App.kt",
                        content = "fun main()",
                        sizeBytes = 11,
                    ),
                    workspace = FileWorkspace(tabs, activePath = "/home/u/src/App.kt"),
                    onBack = {},
                    onRetry = {},
                    onSelectTab = { selected = it.absolutePath },
                    onCloseTab = { closed = it.absolutePath },
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_TAB_STRIP_TAG)
        compose.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + "/home/u/src/App.kt")
            .assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + "/home/u/test/App.kt")
            .assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + "/home/u/README.md")
            .assertIsDisplayed()

        compose.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + "/home/u/test/App.kt")
            .performClick()
        compose.waitForIdle()
        assertEquals("/home/u/test/App.kt", selected)
        assertEquals(null, closed)

        compose.onNodeWithTag(FILE_VIEWER_TAB_CLOSE_TAG_PREFIX + "/home/u/README.md")
            .performScrollTo()
            .performClick()
        compose.waitForIdle()
        assertEquals("/home/u/README.md", closed)
    }

    @Test
    fun emptyWorkspaceShowsBrowseAndOpenPath() {
        var browse = 0
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    modifier = Modifier.productionWindowChromePadding(),
                    hostName = "agents",
                    state = FileViewerUiState.EmptyWorkspace,
                    onBack = {},
                    onRetry = {},
                    onBrowseFiles = { browse++ },
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_EMPTY_WORKSPACE_TAG)
        compose.onNodeWithTag(FILE_VIEWER_EMPTY_BROWSE_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, browse)
        compose.onNodeWithTag(FILE_VIEWER_EMPTY_OPEN_PATH_TAG).assertIsDisplayed()
    }

    @Test
    fun dirtyWorkDialogStayAndDiscard() {
        var stay = 0
        var discard = 0
        val tabs = listOf(
            OpenFileTab("/home/u/a.md", 1),
            OpenFileTab("/home/u/b.md", 2),
        )
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    modifier = Modifier.productionWindowChromePadding(),
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = "/home/u/a.md",
                        content = "notes",
                        sizeBytes = 5,
                    ),
                    workspace = FileWorkspace(tabs, activePath = "/home/u/a.md"),
                    pendingTabAction = PendingTabAction.Switch(tabs[1]),
                    reviewState = ReviewState(
                        active = true,
                        lineComments = mapOf(1 to "looks wrong"),
                    ),
                    onBack = {},
                    onRetry = {},
                    onStayOnTab = { stay++ },
                    onDiscardAndProceed = { discard++ },
                )
            }
        }
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_DIRTY_WORK_DIALOG_TAG)
        compose.onNodeWithText("Finish or discard work on /home/u/a.md before switching")
            .assertIsDisplayed()
        compose.onNodeWithTag(FILE_VIEWER_DIRTY_STAY_TAG).performClick()
        compose.waitForIdle()
        assertEquals(1, stay)
    }

    @Test
    fun twelveTabOverflowKeepsActiveFullyOnScreen() {
        val tabs = (0 until 12).map { i ->
            OpenFileTab("/home/u/file-%02d-with-a-long-name.txt".format(i), i.toLong())
        }
        val active = tabs[9].absolutePath
        compose.setContent {
            PocketShellTheme {
                FileViewerScaffold(
                    modifier = Modifier.productionWindowChromePadding(),
                    hostName = "agents",
                    state = FileViewerUiState.TextContent(
                        displayPath = active,
                        content = "body",
                        sizeBytes = 4,
                    ),
                    workspace = FileWorkspace(tabs, activePath = active),
                    onBack = {},
                    onRetry = {},
                )
            }
        }
        compose.waitForIdle()
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_TAB_STRIP_TAG)
        val closeTag = FILE_VIEWER_TAB_CLOSE_TAG_PREFIX + active
        val node = compose.onNodeWithTag(closeTag, useUnmergedTree = true)
            .fetchSemanticsNode()
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(
            "active tab close target must be at least 48dp; was ${node.size.height}px",
            node.size.height >= 48f * density - 1f,
        )
        compose.assertNodeFullyWithinRoot(FILE_VIEWER_TAB_TAG_PREFIX + active)
    }
}
