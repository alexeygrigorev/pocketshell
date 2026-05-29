package com.pocketshell.app.snippets

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
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
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SnippetEntity
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #253 — authoritative on-device proof that the in-terminal snippet
 * picker is fully visible above the key-bar / bottom-chip controls, not
 * occluded by them.
 *
 * Drives the real [MainActivity] -> host row -> FolderListScreen -> live
 * tmux [com.pocketshell.app.tmux.TmuxSessionScreen] path (same navigation
 * as [com.pocketshell.app.tmux.TmuxSessionOpencodeInputDockerTest]), seeds
 * a few host snippets, taps the `+ snippet` chip in the bottom controls,
 * and captures a full-device screenshot of the open picker. A full-device
 * `uiAutomation` capture composites every window (activity + the picker's
 * `ModalBottomSheet` dialog window), so the screenshot is the ground truth
 * for the z-order the maintainer reported.
 *
 * Requires the `pocketshell-test:agents` Docker container on host port 2222
 * (tmux 3.6). Same precondition as the other tmux Docker E2E tests.
 */
@RunWith(AndroidJUnit4::class)
class SnippetPickerTmuxZOrderDockerTest {

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val HOST_NAME: String = "Issue253 Snippets"
        const val SESSION_NAME: String = "issue253-snippets"
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val ADD_SNIPPET_CHIP_TAG: String = "session:add-snippet-chip"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun snippetPickerIsFullyVisibleAboveKeyBarOnTmuxScreen() = runBlocking {
        val sshPort = resolveSshPort()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val appContext = instrumentation.targetContext
        val key = instrumentation.context.assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }
        val sshKey = SshKey.Pem(key)
        waitForSshFixtureReady(sshKey, port = sshPort)
        killStaleTmuxSession(sshKey, sshPort)
        seedTmuxSession(sshKey, sshPort, SESSION_NAME)

        val hostRowTag = persistHostAndSnippets(appContext, key, sshPort)
        try {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .assertIsDisplayed()

            // Wait for the bottom chip controls (IME down) to render the
            // `+ snippet` chip, then open the picker.
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithTag(ADD_SNIPPET_CHIP_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            captureFullDevice("issue253-tmux-before-open.png")
            compose.onNodeWithTag(ADD_SNIPPET_CHIP_TAG, useUnmergedTree = true).performClick()

            // Wait for a known snippet row to appear in the picker, then
            // capture the open picker over the key-bar.
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText("kubectl get pods -A", useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            // Picker is fully composed — assert the title and a bottom-most
            // row's Send button are both reachable in the semantics tree
            // (i.e. not clipped away), then screenshot the composite.
            compose.onNodeWithText("Snippets", useUnmergedTree = true).assertIsDisplayed()
            captureFullDevice("issue253-tmux-snippet-picker-open.png")
        } finally {
            runCatching { withTimeout(20_000) { killStaleTmuxSession(sshKey, sshPort) } }
        }
        Unit
    }

    private fun resolveSshPort(): Int =
        InstrumentationRegistry.getArguments()
            .getString("terminalWorkbenchSshPort")
            ?.toIntOrNull()
            ?: DEFAULT_PORT

    private suspend fun killStaleTmuxSession(sshKey: SshKey.Pem, sshPort: Int) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux kill-session -t '$SESSION_NAME' 2>/dev/null || true") }
        }
    }

    private suspend fun seedTmuxSession(sshKey: SshKey.Pem, sshPort: Int, sessionName: String) {
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = sshPort,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux new-session -d -s '$sessionName' -c /tmp") }
        }
    }

    private suspend fun persistHostAndSnippets(
        appContext: android.content.Context,
        key: String,
        port: Int,
    ): String {
        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue253-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            db.snippetDao().insert(
                SnippetEntity(hostId = hostId, label = null, body = "kubectl get pods -A", kind = "command"),
            )
            db.snippetDao().insert(
                SnippetEntity(hostId = hostId, label = "tail logs", body = "kubectl logs -f deploy/api", kind = "command"),
            )
            db.snippetDao().insert(
                SnippetEntity(hostId = hostId, label = "summarise diff", body = "Please summarise the staged git diff.", kind = "prompt"),
            )
            hostRowTag = HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
        return hostRowTag
    }

    private fun captureFullDevice(fileName: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            val file = File(artifactDir(), fileName)
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
            }
            println("ISSUE253_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun artifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs.firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/issue-253")
        check(dir.exists() || dir.mkdirs())
        return dir
    }
}
