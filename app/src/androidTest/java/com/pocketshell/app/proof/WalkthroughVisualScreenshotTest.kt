package com.pocketshell.app.proof

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ACTIONS_BUTTON_TAG
import com.pocketshell.app.hosts.HOST_IMPORT_ACTION_TAG
import com.pocketshell.app.hosts.HOST_LIST_ADD_FAB_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_NEW_SESSION_FAB_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.voice.SESSION_ADD_SNIPPET_CHIP_TAG
import com.pocketshell.app.voice.SHOW_KEYBOARD_CHIP_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WalkthroughVisualScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun capturesMainWalkthroughScreens() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val key = instrumentation.context.assets
                .open("test_key")
                .bufferedReader()
                .use { it.readText() }
            val marker = "psvisual${System.currentTimeMillis()}"
            val tmuxSessionName = "visual-$marker"
            val sshKey = SshKey.Pem(key)

            val hostRowTag = seedWalkthroughHostAndSnippets(key)
            waitForSshFixtureReady(sshKey)
            prepareRemoteTmuxSession(key, tmuxSessionName)

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Walkthrough Docker", useUnmergedTree = true).assertExists()
            val hostListScreenshot = WalkthroughScreenshotArtifacts.capture("01-host-list")
            compose.onNodeWithTag(HOST_LIST_ADD_FAB_TAG, useUnmergedTree = true).assertExists()
            assertTrue(
                "host landing must not render the retired all-host Sessions section",
                compose.onAllNodesWithTag(DASHBOARD_SESSIONS_SECTION_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            assertTrue(
                "host landing must not render all-host dashboard session rows",
                compose.onAllNodesWithTag(
                    DASHBOARD_SESSION_ROW_TAG_PREFIX + tmuxSessionName,
                    useUnmergedTree = true,
                )
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            // Issue #299 collapsed the old Hosts / Settings / Import /
            // Scan / Keys pseudo-tab row into a title row with a
            // Settings gear and an actions overflow. Issue #290 moves
            // Scan into Add host.
            compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(HOST_ACTIONS_BUTTON_TAG, useUnmergedTree = true).assertExists()
            listOf("Settings", "Import", "Scan", "Keys").forEach { oldTabLabel ->
                assertTrue(
                    "old $oldTabLabel pseudo-tab text should not be visible on the host list",
                    compose.onAllNodesWithText(oldTabLabel, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isEmpty(),
                )
            }
            compose.onNodeWithTag(HOST_ACTIONS_BUTTON_TAG, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(HOST_IMPORT_ACTION_TAG, useUnmergedTree = true).assertExists()
            assertTrue(
                "host actions menu must not expose key management as a top-level action",
                compose.onAllNodesWithText("Keys", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            assertTrue(
                "host actions menu must not expose Scan as a top-level action",
                compose.onAllNodesWithText("Scan", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isEmpty(),
            )
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            compose.waitForIdle()
            assertTextsClearOfStatusBar(
                texts = listOf("PocketShell"),
                screenshotName = "01-host-list.png",
                artifact = hostListScreenshot,
            )
            compose.onNodeWithTag(HOST_LIST_ADD_FAB_TAG, useUnmergedTree = true).performClick()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithText("Add host", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            compose.waitUntil(timeoutMillis = 5_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }

            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
            // Issue #171: post-tap surface is the FolderListScreen
            // ("Folders" title) with sessions visible inline as
            // SessionRow nodes under their folder header. The seeded
            // session lives in its session_path so it surfaces under a
            // folder row; tapping its name routes to TmuxSession.
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodesWithText("Folders").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText(tmuxSessionName).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(FOLDER_LIST_NEW_SESSION_FAB_TAG, useUnmergedTree = true).assertExists()
            val sessionPickerScreenshot = WalkthroughScreenshotArtifacts.capture("02-host-setup-folder-list")
            assertTextsClearOfNavigationBar(
                texts = listOf("Folders", tmuxSessionName),
                screenshotName = "02-host-setup-folder-list.png",
                artifact = sessionPickerScreenshot,
            )

            // Issue #171: folders-first nav — tapping the session row
            // under its folder routes directly to TmuxSession (no
            // "Continue with SSH" intermediary screen).
            compose.onNodeWithText(tmuxSessionName).performClick()
            // Issue #216: the visible "Terminal" tab label is only
            // rendered when the consolidated tab pill (#189) has 2+
            // entries — i.e. an agent has been detected. The walkthrough
            // shell session has no agent, so we assert on the
            // screen-root tag instead.
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            waitForSessionConnectUiToSettle()
            waitForTmuxPaneReady()

            sendCommandViaTerminalSession("echo $marker")
            waitForTerminalTranscript("walkthrough marker") { transcript ->
                transcript.lineSequence().map { it.trim() }.any { it == marker }
            }
            waitForKeyboardToClear()
            assertTerminalViewportUncovered()
            WalkthroughScreenshotArtifacts.capture("03-terminal-session-input-controls")

            compose.onNodeWithTag(SESSION_ADD_SNIPPET_CHIP_TAG, useUnmergedTree = true).performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("Snippets").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("visual echo").assertExists()
            WalkthroughScreenshotArtifacts.capture("04-snippets")
        }
    }

    private suspend fun seedWalkthroughHostAndSnippets(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "walkthrough-visual-key",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Walkthrough Docker",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            db.snippetDao().insert(
                SnippetEntity(
                    hostId = hostId,
                    label = "visual echo",
                    body = "printf 'snippet visual pass\\n'",
                    kind = "command",
                ),
            )
            db.snippetDao().insert(
                SnippetEntity(
                    hostId = hostId,
                    label = "agent prompt",
                    body = "summarize the last command output",
                    kind = "prompt",
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun prepareRemoteTmuxSession(key: String, sessionName: String) {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true; " +
                        "tmux new-session -d -s ${shellQuote(sessionName)} " +
                        "${shellQuote("printf 'tmux visual pass ready\\n'; exec sh -i")}",
                )
            }
        }
        assertTrue(
            "expected tmux visual fixture setup to succeed, got ${result.exceptionOrNull()}",
            result.getOrNull()?.exitCode == 0,
        )
    }

    private fun waitForSessionConnectUiToSettle() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText(
                "connecting to $DEFAULT_USER@$DEFAULT_HOST:$DEFAULT_PORT",
                substring = false,
            ).fetchSemanticsNodes().isEmpty()
        }
    }

    private fun waitForTmuxPaneReady() {
        compose.waitUntil(timeoutMillis = 10_000) {
            terminalTranscriptSnapshot().contains("tmux visual pass ready")
        }
    }

    private fun sendCommandViaTerminalSession(command: String) {
        // The non-agent tmux shell route intentionally omits the prompt
        // composer mic FAB (#283). Assert the current bottom-controls model
        // is present, then write through the attached TerminalSession. That
        // is the same live input bridge the terminal keyboard uses, without
        // depending on ADB text injection quirks.
        hideSoftKeyboard()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(SHOW_KEYBOARD_CHIP_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        writeToCurrentTerminal("$command\r")
        hideSoftKeyboard()
    }

    private fun waitForTerminalTranscript(
        description: String,
        predicate: (String) -> Boolean,
    ) {
        var lastSnapshot = ""
        compose.waitUntil(timeoutMillis = 45_000) {
            lastSnapshot = terminalTranscriptSnapshot()
            predicate(lastSnapshot)
        }
        assertTrue(
            "expected terminal transcript to contain $description, got:\n$lastSnapshot",
            predicate(lastSnapshot),
        )
    }

    private fun terminalTranscriptSnapshot(): String {
        var snapshot = ""
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            snapshot = terminalView
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return snapshot
    }

    private fun writeToCurrentTerminal(text: String) {
        launchedActivity?.onActivity { activity ->
            val terminalView = activity.window.decorView.findTerminalView()
            checkNotNull(terminalView?.currentSession) {
                "expected a live TerminalSession before writing walkthrough command"
            }.write(text)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForKeyboardToClear() {
        compose.waitUntil(timeoutMillis = 10_000) {
            terminalVisibleHeight() >= 1_200
        }
    }

    private fun hideSoftKeyboard() {
        launchedActivity?.onActivity { activity ->
            val inputMethodManager = activity.getSystemService<InputMethodManager>()
            val token = activity.window.decorView.windowToken
            inputMethodManager?.hideSoftInputFromWindow(token, 0)
            activity.window.decorView.clearFocus()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertTerminalViewportUncovered() {
        val height = terminalVisibleHeight()
        assertTrue(
            "expected terminal viewport to be mostly visible before screenshot, visibleHeight=$height",
            height >= 1_200,
        )
    }

    private fun terminalVisibleHeight(): Int {
        var height = 0
        launchedActivity?.onActivity { activity ->
            val rect = android.graphics.Rect()
            activity.window.decorView.findTerminalView()?.let { terminalView ->
                if (terminalView.getGlobalVisibleRect(rect)) {
                    height = rect.height()
                }
            }
        }
        return height
    }

    private fun assertTextsClearOfStatusBar(
        texts: List<String>,
        screenshotName: String,
        artifact: File,
    ) {
        val insets = systemBarInsets()
        texts.forEach { text ->
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue(
                "$screenshotName overlaps status bar: text=\"$text\" top=${bounds.top}, " +
                    "statusBarBottom=${insets.statusTop}, artifact=${artifact.absolutePath}",
                bounds.top >= insets.statusTop,
            )
        }
    }

    private fun assertTextsClearOfNavigationBar(
        texts: List<String>,
        screenshotName: String,
        artifact: File,
    ) {
        val insets = systemBarInsets()
        val navigationBarTop = insets.rootHeight - insets.navigationBottom
        texts.forEach { text ->
            val bounds = compose.onNodeWithText(text, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue(
                "$screenshotName overlaps navigation bar: text=\"$text\" bottom=${bounds.bottom}, " +
                    "navigationBarTop=$navigationBarTop, artifact=${artifact.absolutePath}",
                bounds.bottom <= navigationBarTop,
            )
        }
    }

    private fun systemBarInsets(): SystemBarInsets {
        var result = SystemBarInsets()
        launchedActivity?.onActivity { activity ->
            val root = activity.window.decorView
            val insets = ViewCompat.getRootWindowInsets(root)
            val statusBars = insets?.getInsets(WindowInsetsCompat.Type.statusBars())
            val navigationBars = insets?.getInsets(WindowInsetsCompat.Type.navigationBars())
            result = SystemBarInsets(
                rootHeight = root.height,
                statusTop = statusBars?.top ?: 0,
                navigationBottom = navigationBars?.bottom ?: 0,
            )
        }
        return result
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DASHBOARD_SESSIONS_SECTION_TAG: String = "dashboard:sessions"
        const val DASHBOARD_SESSION_ROW_TAG_PREFIX: String = "dashboard:sessions:row:"
    }

    private data class SystemBarInsets(
        val rootHeight: Int = 0,
        val statusTop: Int = 0,
        val navigationBottom: Int = 0,
    )
}
