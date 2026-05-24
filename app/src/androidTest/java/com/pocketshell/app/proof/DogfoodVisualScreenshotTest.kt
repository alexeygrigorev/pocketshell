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
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.composer.COMPOSER_DRAFT_TAG
import com.pocketshell.app.composer.COMPOSER_SEND_ENTER_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.migrations.MIGRATION_1_2
import com.pocketshell.core.storage.migrations.MIGRATION_2_3
import com.pocketshell.core.storage.migrations.MIGRATION_3_4
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DogfoodVisualScreenshotTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun capturesMainDogfoodScreens() {
        runBlocking {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val key = instrumentation.context.assets
                .open("test_key")
                .bufferedReader()
                .use { it.readText() }
            val marker = "psvisual${System.currentTimeMillis()}"
            val tmuxSessionName = "visual-$marker"
            val sshKey = SshKey.Pem(key)

            val hostRowTag = seedDogfoodHostAndSnippets(key)
            waitForSshFixtureReady(sshKey)
            prepareRemoteTmuxSession(key, tmuxSessionName)

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithText("Dogfood Docker", useUnmergedTree = true).assertExists()
            val hostListScreenshot = DogfoodScreenshotArtifacts.capture("01-host-list")
            assertTextsClearOfStatusBar(
                texts = listOf("PocketShell", "Crashes", "Import", "Keys"),
                screenshotName = "01-host-list.png",
                artifact = hostListScreenshot,
            )

            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodesWithText("Tmux sessions").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Continue with SSH").fetchSemanticsNodes().isNotEmpty()
            }
            val sessionPickerScreenshot = DogfoodScreenshotArtifacts.capture("02-host-setup-session-picker")
            assertTextsClearOfNavigationBar(
                texts = listOf("Tmux sessions", "+ New session", "Continue with SSH"),
                screenshotName = "02-host-setup-session-picker.png",
                artifact = sessionPickerScreenshot,
            )

            compose.onNodeWithText("Continue with SSH").performClick()
            compose.onNodeWithText("Terminal").assertExists()
            compose.onNodeWithText("tmux ls").assertExists()
            waitForSessionConnectUiToSettle()

            sendCommandViaComposer("printf '%s\\n' '$marker'")
            waitForTerminalTranscript("dogfood marker") { transcript ->
                transcript.lineSequence().map { it.trim() }.any { it == marker }
            }
            waitForComposerAndKeyboardToClear()
            assertTerminalViewportUncovered()
            DogfoodScreenshotArtifacts.capture("03-terminal-session-input-controls")

            compose.onNodeWithText("+ snippet").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("Snippets").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("visual echo").assertExists()
            DogfoodScreenshotArtifacts.capture("04-snippets")
        }
    }

    private suspend fun seedDogfoodHostAndSnippets(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
            .fallbackToDestructiveMigration(dropAllTables = false)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "dogfood-visual-key",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Dogfood Docker",
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
                        "${shellQuote("printf 'tmux visual pass ready\\n'; sleep 300")}",
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

    private fun sendCommandViaComposer(command: String) {
        compose.onNodeWithText("dictate").performClick()
        compose.onNodeWithText("Prompt Composer").assertExists()
        compose.onNodeWithTag(COMPOSER_DRAFT_TAG).performTextInput(command)
        DogfoodScreenshotArtifacts.capture("05-composer-draft")
        compose.onNodeWithTag(COMPOSER_SEND_ENTER_TAG).performClick()
        hideSoftKeyboard()
    }

    private fun waitForTerminalTranscript(
        description: String,
        predicate: (String) -> Boolean,
    ) {
        var lastSnapshot = ""
        compose.waitUntil(timeoutMillis = 15_000) {
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

    private fun waitForComposerAndKeyboardToClear() {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Prompt Composer", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
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
    }

    private data class SystemBarInsets(
        val rootHeight: Int = 0,
        val statusTop: Int = 0,
        val navigationBottom: Int = 0,
    )
}
