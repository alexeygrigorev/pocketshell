package com.pocketshell.app.proof

import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.core.content.getSystemService
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SHEET_TAG
import com.pocketshell.app.hosts.HOST_LIST_ADD_FAB_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.settings.HOST_IMPORT_CHOOSE_FILE_TAG
import com.pocketshell.app.settings.HOST_IMPORT_DIALOG_COPY_TAG
import com.pocketshell.app.settings.HOST_IMPORT_ROW_TAG
import com.pocketshell.app.settings.HOST_IMPORT_SCAN_QR_TAG
import com.pocketshell.app.settings.SETTINGS_BACK_TAG
import com.pocketshell.app.settings.SETTINGS_LAZY_COLUMN_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_EMPTY_TAG
import com.pocketshell.app.projects.FOLDER_LIST_ERROR_TAG
import com.pocketshell.app.projects.FOLDER_LIST_LOADING_TAG
import com.pocketshell.app.projects.FOLDER_LIST_NEW_SESSION_FAB_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.projects.FOLDER_LIST_TITLE_TAG
import com.pocketshell.app.projects.folderHeaderClickTestTag
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

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

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
            waitUntilWithDiagnostics(
                label = "seeded host row visible",
                timeoutMillis = 10_000,
                textProbes = listOf(WALKTHROUGH_HOST_NAME),
                tagProbes = listOf(hostRowTag, HOST_LIST_ADD_FAB_TAG),
            ) {
                hasTag(hostRowTag)
            }
            compose.onNodeWithText(WALKTHROUGH_HOST_NAME, useUnmergedTree = true).assertExists()
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
            // Settings gear. Issue #388 moves host import into Settings
            // so it cannot be mistaken for a generic app import.
            compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).assertExists()
            listOf("Settings", "Import", "Scan", "Keys").forEach { oldTabLabel ->
                assertTrue(
                    "old $oldTabLabel pseudo-tab text should not be visible on the host list",
                    compose.onAllNodesWithText(oldTabLabel, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isEmpty(),
                )
            }
            compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()
            // Issue #531: wait for the Settings screen to be open (its
            // scroll container is the deterministic signal the NavHost route
            // swapped and the screen composed), then scroll the host-import
            // row into view. The row lives near the bottom of the Settings
            // LazyColumn (Terminal/Voice/Assistant/Usage/Workspace come
            // first after the #486 reorder), so it is below the fold on a
            // Pixel 7 and never composes until scrolled. The old flat 5 s
            // `waitUntil(hasTag(HOST_IMPORT_ROW_TAG))` was racing on a node
            // the LazyColumn had not laid out yet, throwing
            // ComposeTimeoutException.
            waitUntilWithDiagnostics(
                label = "settings screen scroll container present",
                timeoutMillis = uiNavigationTimeoutMs(),
                tagProbes = listOf(SETTINGS_LAZY_COLUMN_TAG, SETTINGS_BACK_TAG, SETTINGS_BUTTON_TAG),
            ) {
                hasTag(SETTINGS_LAZY_COLUMN_TAG)
            }
            WalkthroughScreenshotArtifacts.capture("05-settings")
            compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
                .performScrollToNode(hasTestTag(HOST_IMPORT_ROW_TAG))
            compose.onNodeWithTag(HOST_IMPORT_ROW_TAG, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(HOST_IMPORT_DIALOG_COPY_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Scan QR", useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Choose file", useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(HOST_IMPORT_SCAN_QR_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(HOST_IMPORT_CHOOSE_FILE_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithText("Cancel", useUnmergedTree = true).performClick()
            waitUntilWithDiagnostics(
                label = "host-import dialog dismissed",
                timeoutMillis = uiNavigationTimeoutMs(),
                tagProbes = listOf(HOST_IMPORT_DIALOG_COPY_TAG, HOST_IMPORT_ROW_TAG),
            ) {
                !hasTag(HOST_IMPORT_DIALOG_COPY_TAG)
            }
            compose.onNodeWithTag(SETTINGS_BACK_TAG, useUnmergedTree = true).performClick()
            waitUntilWithDiagnostics(
                label = "host list visible after settings back",
                timeoutMillis = uiNavigationTimeoutMs(),
                tagProbes = listOf(HOST_LIST_ADD_FAB_TAG, SETTINGS_BACK_TAG),
            ) {
                hasTag(HOST_LIST_ADD_FAB_TAG)
            }
            assertTextsClearOfStatusBar(
                texts = listOf("Hosts"),
                screenshotName = "01-host-list.png",
                artifact = hostListScreenshot,
            )
            compose.waitForIdle()

            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performTouchInput {
                click(center)
            }
            // Ready-host taps now pass through the setup cache gate and land
            // on the host-detail FolderListScreen. The approved visible
            // contract is the folder screen chrome for this host, not the
            // retired generic "Workspace" title.
            val sessionProjectPath = "/home/$DEFAULT_USER"
            waitUntilWithDiagnostics(
                label = "ready host detail screen for $WALKTHROUGH_HOST_NAME",
                timeoutMillis = 20_000,
                textProbes = listOf(
                    WALKTHROUGH_HOST_NAME,
                    "Workspace",
                    "Checking setup",
                    "Host setup needed",
                    "Hosts",
                    "No active sessions",
                    tmuxSessionName,
                ),
                tagProbes = hostDetailDiagnosticTags(sessionProjectPath),
            ) {
                hasTag(FOLDER_LIST_SCREEN_TAG) &&
                    hasTag(FOLDER_LIST_TITLE_TAG) &&
                    hasTag(FOLDER_LIST_NEW_SESSION_FAB_TAG) &&
                    hasText(WALKTHROUGH_HOST_NAME) &&
                    !hasText("Checking setup") &&
                    !hasText("Hosts") &&
                    !hasTag(HOST_BOOTSTRAP_SHEET_TAG) &&
                    !hasTag(HOST_LIST_ADD_FAB_TAG)
            }
            waitUntilWithDiagnostics(
                label = "seeded project row $sessionProjectPath",
                timeoutMillis = 20_000,
                textProbes = listOf(
                    WALKTHROUGH_HOST_NAME,
                    "No active sessions",
                    sessionProjectPath.substringAfterLast('/'),
                    tmuxSessionName,
                ),
                tagProbes = hostDetailDiagnosticTags(sessionProjectPath),
            ) {
                hasTag(folderHeaderClickTestTag(sessionProjectPath))
            }
            compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(FOLDER_LIST_TITLE_TAG, useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(FOLDER_LIST_NEW_SESSION_FAB_TAG, useUnmergedTree = true).assertExists()
            // Issue #531: the seeded sessions are idle (no live SSH), but #471
            // auto-expands any folder with ≥1 session, so the seeded folder
            // may already be open. An unconditional header click then *toggles
            // it closed*, hiding the session row and timing out the wait below.
            // Toggle the header only while the seeded session is still hidden
            // so we end on an expanded folder regardless of the auto-expand
            // starting state.
            expandFolderUntilSessionVisible(
                projectPath = sessionProjectPath,
                sessionName = tmuxSessionName,
            )
            waitUntilWithDiagnostics(
                label = "seeded tmux session $tmuxSessionName visible",
                timeoutMillis = uiNavigationTimeoutMs(),
                textProbes = listOf(WALKTHROUGH_HOST_NAME, sessionProjectPath.substringAfterLast('/'), tmuxSessionName),
                tagProbes = hostDetailDiagnosticTags(sessionProjectPath),
            ) {
                hasText(tmuxSessionName)
            }
            val sessionPickerScreenshot = WalkthroughScreenshotArtifacts.capture("02-host-setup-folder-list")
            assertTextsClearOfNavigationBar(
                texts = listOf(WALKTHROUGH_HOST_NAME, tmuxSessionName),
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
                    name = WALKTHROUGH_HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = System.currentTimeMillis(),
                    pocketshellDaemonRunning = true,
                    pocketshellDaemonEnabled = true,
                    pocketshellVersionCompatible = true,
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

    // Issue #531: several in-process UI-navigation settles in this
    // walkthrough used flat short `waitUntil` windows that raced on a loaded
    // emulator. The Settings gear -> host-import navigation (NavHost route
    // swap + Settings LazyColumn recomposition) overshot its 5 s wait, and
    // the folder-tree expand that reveals the already-loaded session rows
    // overshot its 10 s wait. Both are plain Compose recomposition settles
    // (no SSH/terminal round-trip — the session data is already loaded), so
    // a local deadline well above the observed slow case stays tight enough
    // to surface a real regression, while CI gets the generous ceiling the
    // sibling E2Es already adopted for the swiftshader runner under load.
    private fun uiNavigationTimeoutMs(): Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    // Issue #531: ensure the seeded folder ends up EXPANDED with its session
    // row visible, independent of #471's auto-expand starting state. The old
    // code clicked the folder header exactly once, which collapsed an
    // already-auto-expanded folder and hid the session. This toggles the
    // header only while the session is still hidden, settling on idle each
    // iteration, until the row appears or the CI-aware deadline elapses.
    private fun expandFolderUntilSessionVisible(projectPath: String, sessionName: String) {
        val deadline = System.currentTimeMillis() + uiNavigationTimeoutMs()
        compose.waitForIdle()
        while (!hasText(sessionName) && System.currentTimeMillis() < deadline) {
            compose.onNodeWithTag(folderHeaderClickTestTag(projectPath), useUnmergedTree = true)
                .performClick()
            compose.waitForIdle()
            if (hasText(sessionName)) {
                return
            }
            // Give a collapsed->expanded recomposition a poll window before
            // deciding to toggle again, so we do not bounce the folder
            // open/closed faster than it can render its session rows.
            runCatching {
                compose.waitUntil(timeoutMillis = 2_000) { hasText(sessionName) }
            }
        }
    }

    private fun waitUntilWithDiagnostics(
        label: String,
        timeoutMillis: Long,
        textProbes: List<String> = emptyList(),
        tagProbes: List<String> = emptyList(),
        condition: () -> Boolean,
    ) {
        try {
            compose.waitUntil(timeoutMillis = timeoutMillis, condition = condition)
        } catch (error: Throwable) {
            throw AssertionError(
                buildString {
                    appendLine("Timed out after ${timeoutMillis}ms waiting for $label.")
                    appendLine(screenDiagnostics(textProbes = textProbes, tagProbes = tagProbes))
                },
                error,
            )
        }
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun hasText(text: String): Boolean =
        compose.onAllNodesWithText(text, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun screenDiagnostics(textProbes: List<String>, tagProbes: List<String>): String = buildString {
        appendLine("Tag probe counts:")
        tagProbes.distinct().forEach { tag ->
            appendLine("  $tag=${nodeCountForTag(tag)}")
        }
        appendLine("Text probe counts:")
        textProbes.distinct().forEach { text ->
            appendLine("  \"$text\"=${nodeCountForText(text)}")
        }
        appendLine("Compose semantics tree:")
        appendLine(
            runCatching {
                compose.waitForIdle()
                compose.onRoot(useUnmergedTree = true).printToString()
            }.getOrElse { diagnosticsError ->
                "  <failed to capture semantics tree: ${diagnosticsError.javaClass.simpleName}: " +
                    "${diagnosticsError.message.orEmpty()}>"
            },
        )
    }

    private fun nodeCountForTag(tag: String): Int =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }.getOrDefault(-1)

    private fun nodeCountForText(text: String): Int =
        runCatching {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
        }.getOrDefault(-1)

    private fun hostDetailDiagnosticTags(sessionProjectPath: String): List<String> = listOf(
        FOLDER_LIST_SCREEN_TAG,
        FOLDER_LIST_TITLE_TAG,
        FOLDER_LIST_NEW_SESSION_FAB_TAG,
        FOLDER_LIST_LOADING_TAG,
        FOLDER_LIST_ERROR_TAG,
        FOLDER_LIST_EMPTY_TAG,
        folderHeaderClickTestTag(sessionProjectPath),
        HOST_BOOTSTRAP_SHEET_TAG,
        HOST_LIST_ADD_FAB_TAG,
    )

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
            // Issue #531: the host-list screen legitimately renders "Hosts"
            // twice — once as the ScreenHeader title and once as the host
            // group SectionHeader label — so the old single-node lookup threw
            // "Expected exactly 1 node but found 2". The status-bar-clearance
            // contract applies to every instance of the text, so assert each
            // matching node clears the status bar and require at least one.
            val nodes = compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
            assertTrue(
                "$screenshotName expected at least one \"$text\" node, found none, " +
                    "artifact=${artifact.absolutePath}",
                nodes.isNotEmpty(),
            )
            nodes.forEach { node ->
                val bounds = node.boundsInRoot
                assertTrue(
                    "$screenshotName overlaps status bar: text=\"$text\" top=${bounds.top}, " +
                        "statusBarBottom=${insets.statusTop}, artifact=${artifact.absolutePath}",
                    bounds.top >= insets.statusTop,
                )
            }
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
        const val WALKTHROUGH_HOST_NAME: String = "Walkthrough Docker"
        const val DASHBOARD_SESSIONS_SECTION_TAG: String = "dashboard:sessions"
        const val DASHBOARD_SESSION_ROW_TAG_PREFIX: String = "dashboard:sessions:row:"
    }

    private data class SystemBarInsets(
        val rootHeight: Int = 0,
        val statusTop: Int = 0,
        val navigationBottom: Int = 0,
    )
}
