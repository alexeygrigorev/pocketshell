package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.uikit.theme.PocketShellTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #857: the session kebab (overflow) menu was a flat, ungrouped list. It
 * is now grouped into logical sections with a header per section:
 *
 *   This session: Rename · What is this session?/Change kind · Stop · Detach
 *   Sessions:     New session · Switch session
 *   Files:        Browse files… · Open file…
 *   Connection:   Port forwarding
 *   Host & app:   Recurring jobs · Usage · Settings
 *
 * This is ordering/grouping only — every existing item, its onClick, and its
 * test tag are preserved. These tests prove:
 *  - every section header AND every item is present (no item dropped),
 *  - "What is this session?"/"Change kind" sits with the current-session
 *    actions (between Rename and Stop), not buried mid-lifecycle,
 *  - the headers + items appear in the expected grouped vertical order,
 *  - clicking each item still fires its callback (no behaviour change).
 */
@RunWith(AndroidJUnit4::class)
class TmuxMoreMenuGroupingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setMenu(
        changeKindIsUnknown: Boolean = true,
        onCreateSession: () -> Unit = {},
        onRenameSession: () -> Unit = {},
        onKillSession: () -> Unit = {},
        onChangeKind: () -> Unit = {},
        onSwitchSession: () -> Unit = {},
        onOpenJobs: () -> Unit = {},
        onOpenUsage: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
        onOpenFile: () -> Unit = {},
        onBrowseFiles: () -> Unit = {},
        onOpenPortForwarding: () -> Unit = {},
        onDetach: () -> Unit = {},
    ) {
        compose.setContent {
            PocketShellTheme {
                val expanded = mutableStateOf(true)
                TmuxMoreMenu(
                    expanded = expanded.value,
                    onDismiss = { expanded.value = false },
                    onCreateSession = onCreateSession,
                    onRenameSession = onRenameSession,
                    onKillSession = onKillSession,
                    onChangeKind = onChangeKind,
                    changeKindIsUnknown = changeKindIsUnknown,
                    onSwitchSession = onSwitchSession,
                    onOpenJobs = onOpenJobs,
                    onOpenUsage = onOpenUsage,
                    onOpenSettings = onOpenSettings,
                    onOpenFile = onOpenFile,
                    onBrowseFiles = onBrowseFiles,
                    onOpenPortForwarding = onOpenPortForwarding,
                    onDetach = onDetach,
                )
            }
        }
        compose.waitForIdle()
    }

    private fun node(text: String): SemanticsNodeInteraction =
        compose.onNodeWithText(text, useUnmergedTree = true)

    /** Vertical centre of a node's bounds in the root, in pixels. */
    private fun centerY(text: String): Float {
        val bounds = node(text).fetchSemanticsNode().boundsInRoot
        return bounds.top + bounds.size.height / 2f
    }

    @Test
    fun allSectionHeadersAndItemsArePresent() {
        setMenu()

        // Section headers (the grouping itself).
        node("This session").assertIsDisplayed()
        node("Sessions").assertIsDisplayed()
        node("Files").assertIsDisplayed()
        node("Connection").assertIsDisplayed()
        node("Host & app").assertIsDisplayed()

        // Every original item is preserved (foreign session -> "What is this
        // session?" label).
        node("Rename session").assertIsDisplayed()
        node("What is this session?").assertIsDisplayed()
        node("Stop session").assertIsDisplayed()
        node("Detach").assertIsDisplayed()
        node("+ New session").assertIsDisplayed()
        node("Switch session").assertIsDisplayed()
        node("Browse files…").assertIsDisplayed()
        node("Open file…").assertIsDisplayed()
        node("Port forwarding").assertIsDisplayed()
        node("Recurring jobs").assertIsDisplayed()
        node("Usage").assertIsDisplayed()
        node("Settings").assertIsDisplayed()

        // Visual evidence of the grouped menu (best-effort; the load-bearing
        // proof is the assertions above + the ordering tests below).
        captureScreenshot("session-kebab-grouped")
    }

    private fun captureScreenshot(name: String) {
        runCatching {
            compose.waitForIdle()
            val instr = InstrumentationRegistry.getInstrumentation()
            val bitmap = instr.uiAutomation.takeScreenshot() ?: return@runCatching
            val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instr.targetContext)
            val dir = File(mediaRoot, "additional_test_output/issue857-kebab-menu")
                .apply { mkdirs() }
            FileOutputStream(File(dir, "$name.png")).use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun classifiedSessionShowsChangeKindLabel() {
        setMenu(changeKindIsUnknown = false)
        // The other label variant is also preserved + still in the group.
        node("Change kind").assertIsDisplayed()
    }

    @Test
    fun itemsAppearInGroupedVerticalOrder() {
        setMenu()

        // The exact top-to-bottom order: section header followed by its items,
        // for all five groups. Each subsequent entry must sit strictly below the
        // previous one.
        val order = listOf(
            "This session",
            "Rename session",
            "What is this session?",
            "Stop session",
            "Detach",
            "Sessions",
            "+ New session",
            "Switch session",
            "Files",
            "Browse files…",
            "Open file…",
            "Connection",
            "Port forwarding",
            "Host & app",
            "Recurring jobs",
            "Usage",
            "Settings",
        )

        val ys = order.map { it to centerY(it) }
        for (i in 1 until ys.size) {
            val (prevLabel, prevY) = ys[i - 1]
            val (curLabel, curY) = ys[i]
            assertTrue(
                "'$curLabel' (y=$curY) should be below '$prevLabel' (y=$prevY)",
                curY > prevY,
            )
        }
    }

    @Test
    fun changeKindIsGroupedWithCurrentSessionActions() {
        setMenu()
        // "What is this session?" must sit between Rename and Stop (current-
        // session group), not buried elsewhere — the #857 reported placement.
        val rename = centerY("Rename session")
        val identify = centerY("What is this session?")
        val stop = centerY("Stop session")
        assertTrue("Identify should be below Rename", identify > rename)
        assertTrue("Identify should be above Stop", identify < stop)
        // And it must be above the next section header.
        val sessionsHeader = centerY("Sessions")
        assertTrue("Identify should be in the 'This session' group", identify < sessionsHeader)
    }

    @Test
    fun everyItemStillInvokesItsCallback() {
        var renameClicks = 0
        var changeKindClicks = 0
        var killClicks = 0
        var detachClicks = 0
        var createClicks = 0
        var switchClicks = 0
        var browseClicks = 0
        var openFileClicks = 0
        var portFwdClicks = 0
        var jobsClicks = 0
        var usageClicks = 0
        var settingsClicks = 0

        setMenu(
            onCreateSession = { createClicks++ },
            onRenameSession = { renameClicks++ },
            onKillSession = { killClicks++ },
            onChangeKind = { changeKindClicks++ },
            onSwitchSession = { switchClicks++ },
            onOpenJobs = { jobsClicks++ },
            onOpenUsage = { usageClicks++ },
            onOpenSettings = { settingsClicks++ },
            onOpenFile = { openFileClicks++ },
            onBrowseFiles = { browseClicks++ },
            onOpenPortForwarding = { portFwdClicks++ },
            onDetach = { detachClicks++ },
        )

        // The menu dismisses on item click in production, but here onDismiss
        // only flips a local state that does not collapse the popup synchronously
        // within a single test action, so click each item on a fresh layout pass.
        node("Rename session").performClick()
        node("What is this session?").performClick()
        node("Stop session").performClick()
        node("Detach").performClick()
        node("+ New session").performClick()
        node("Switch session").performClick()
        node("Browse files…").performClick()
        node("Open file…").performClick()
        node("Port forwarding").performClick()
        node("Recurring jobs").performClick()
        node("Usage").performClick()
        node("Settings").performClick()
        compose.waitForIdle()

        assertEquals("Rename", 1, renameClicks)
        assertEquals("Change kind / identify", 1, changeKindClicks)
        assertEquals("Stop", 1, killClicks)
        assertEquals("Detach", 1, detachClicks)
        assertEquals("New session", 1, createClicks)
        assertEquals("Switch session", 1, switchClicks)
        assertEquals("Browse files", 1, browseClicks)
        assertEquals("Open file", 1, openFileClicks)
        assertEquals("Port forwarding", 1, portFwdClicks)
        assertEquals("Recurring jobs", 1, jobsClicks)
        assertEquals("Usage", 1, usageClicks)
        assertEquals("Settings", 1, settingsClicks)
    }
}
