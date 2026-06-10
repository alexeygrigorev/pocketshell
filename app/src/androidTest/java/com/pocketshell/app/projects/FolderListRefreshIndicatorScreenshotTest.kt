package com.pocketshell.app.projects

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellColors
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #639 — the host-detail refresh indicator must NOT displace the list and
 * a routine refresh must NOT reorder rows.
 *
 * This test drives the real [FolderListContent] (flat view) on the emulator:
 *
 *  - It captures the top Y of the first visible session row with the refresh
 *    indicator OFF, flips `isRefreshing` ON (same sessions), and asserts the
 *    first row's top Y is UNCHANGED. The previous in-list "Refreshing sessions"
 *    row pushed every row down; the new thin top progress bar overlays and
 *    reflows nothing.
 *  - It asserts the rendered row order is exactly the (stable) order it was
 *    given, in both the OFF and ON states — the screen renders in the order the
 *    ViewModel supplies, and [FolderListViewModel.stabiliseSessionOrder] freezes
 *    that order across a refresh (covered by the JVM unit tests).
 *
 * Before/after PNGs are written for the status comment.
 */
class FolderListRefreshIndicatorScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val sessions = listOf(
        flatEntry("claude-pocketshell", SessionAgentKind.Claude, attached = true),
        flatEntry("codex-zoomcamp", SessionAgentKind.Codex, attached = true),
        flatEntry("build-shell", SessionAgentKind.Shell, attached = false),
        flatEntry("logs-shell", SessionAgentKind.Shell, attached = false),
    )

    @Test
    fun refreshIndicatorDoesNotDisplaceOrReorderRows() {
        var refreshing by mutableStateOf(false)

        compose.setContent {
            PocketShellTheme {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(PocketShellColors.Background),
                ) {
                    FolderListContent(
                        hostName = "demo-host",
                        folders = listOf(
                            FolderRow(
                                path = "/home/alexey/git/pocketshell",
                                label = "pocketshell",
                                sessions = sessions,
                                isWatched = false,
                            ),
                        ),
                        treeRoots = emptyList(),
                        flatSessions = sessions,
                        expandedProjectPaths = emptySet(),
                        isRefreshing = refreshing,
                        portForwarding = HostPortForwardingSummary(),
                        showFlatFolderList = true,
                        actionStatus = FolderActionStatus.Idle,
                        onDismissActionStatus = {},
                        onOpenPortForwarding = {},
                        onCreateTopLevelSession = {},
                        onSessionClick = { _, _, _ -> },
                        onRenameSession = {},
                        onStopSession = {},
                        onFolderActions = {},
                        onCreateInRoot = {},
                        onRootActions = {},
                        onToggleProjectExpanded = {},
                    )
                }
            }
        }

        compose.waitForIdle()
        SystemClock.sleep(200)

        // --- Before: indicator OFF ---
        val firstRowTag = folderListFlatRowTestTag("claude-pocketshell")
        compose.onNodeWithTag(firstRowTag).assertIsDisplayed()
        val topBefore = compose.onNodeWithTag(firstRowTag)
            .fetchSemanticsNode().boundsInRoot.top
        val orderBefore = visibleRowOrder()
        capture("issue-639-refresh-off.png")

        // --- After: indicator ON (same sessions) ---
        refreshing = true
        compose.waitForIdle()
        SystemClock.sleep(200)

        // The thin progress bar exists but must not push the list down.
        compose.onNodeWithTag(FOLDER_LIST_REFRESHING_TAG).assertIsDisplayed()
        val topAfter = compose.onNodeWithTag(firstRowTag)
            .fetchSemanticsNode().boundsInRoot.top
        val orderAfter = visibleRowOrder()
        capture("issue-639-refresh-on.png")

        assertEquals(
            "Refresh indicator must not displace the first row (no jump)",
            topBefore,
            topAfter,
        )
        assertEquals(
            "Row order must be stable across the refresh",
            listOf("claude-pocketshell", "codex-zoomcamp", "build-shell", "logs-shell"),
            orderBefore,
        )
        assertEquals(
            "Row order must be stable across the refresh",
            orderBefore,
            orderAfter,
        )
    }

    /** Visible session rows sorted by their on-screen top, by name. */
    private fun visibleRowOrder(): List<String> =
        sessions
            .mapNotNull { session ->
                val node = runCatching {
                    compose.onNodeWithTag(folderListFlatRowTestTag(session.sessionName))
                        .fetchSemanticsNode()
                }.getOrNull() ?: return@mapNotNull null
                session.sessionName to node.boundsInRoot.top
            }
            .sortedBy { it.second }
            .map { it.first }

    private fun capture(name: String) {
        val dir = ensureArtifactDir()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = File(dir, name)
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write screenshot: ${file.absolutePath}"
                }
            }
            println("FOLDER_LIST_REFRESH_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/folder-list-refresh")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create folder-list-refresh screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun flatEntry(
        name: String,
        kind: SessionAgentKind,
        attached: Boolean,
    ): FolderSessionEntry =
        FolderSessionEntry(
            sessionName = name,
            lastActivity = 1_000L,
            attached = attached,
            agentKind = kind,
        )
}
