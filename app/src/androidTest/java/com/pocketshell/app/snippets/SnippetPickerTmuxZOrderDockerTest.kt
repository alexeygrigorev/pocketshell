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
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.projects.folderHeaderClickTestTag
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
        // The seeded session is created with `tmux new-session -c /tmp`, so it
        // is grouped under the `/tmp` folder on the folders-first FolderListScreen.
        const val SESSION_PROJECT_PATH: String = "/tmp"
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val ADD_SNIPPET_CHIP_TAG: String = "session:add-snippet-chip"
    }

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

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
            // Issue #761: the first semantics lookup right after launch can race
            // the system GrantPermissionsActivity transiently occluding the
            // Compose window on a loaded emulator (PreGrantPermissionsRule grants
            // best-effort, but a slow first launch of a freshly-installed
            // suffixed APK can still flash the grant dialog). `fetchSemanticsNodes`
            // THROWS "No compose hierarchies found" rather than returning empty in
            // that window, which would abort `waitUntil` on the first poll. Swallow
            // that transient throw so the wait keeps polling until the host-list
            // Compose hierarchy is actually attached.
            compose.waitUntil(timeoutMillis = 30_000) {
                runCatching {
                    compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

            // Issue #761: the host row lands on the folders-first FolderListScreen.
            // The seeded session lives under its cwd folder (`/tmp`, seeded with
            // `tmux new-session -c /tmp`), which may render COLLAPSED depending on
            // the #471 auto-expand state. Waiting on the bare session text then
            // raced: if the folder was collapsed the session row never composed and
            // the wait timed out. Wait for the folder screen, then toggle the
            // folder header only while the session is still hidden (settling on
            // idle each pass) until the row appears — the same robust pattern the
            // walkthrough visual proof uses.
            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                runCatching {
                    compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            expandFolderUntilSessionVisible(SESSION_PROJECT_PATH, SESSION_NAME)
            compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .assertIsDisplayed()

            // Wait for the bottom chip controls (IME down) to render the
            // `+ snippet` chip, then open the picker. Issue #761: the chip is a
            // shell-pane affordance gated on the ACTUAL agent signal (not the
            // optimistic presumed-agent default), so it is present on this fresh
            // tmux shell pane. The wait is generous because the giant
            // TmuxSessionScreen composable runs interpreted on a loaded
            // emulator (logcat: "Method exceeds compiler instruction limit"),
            // so the IME-down recomposition can lag.
            compose.waitUntil(timeoutMillis = 30_000) {
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

    // Issue #761: ensure the seeded folder ends EXPANDED with its session row
    // visible, independent of #471's auto-expand starting state. Toggle the
    // header only while the session is still hidden, settling on idle each pass,
    // until the row appears or the deadline elapses. Mirrors the walkthrough
    // visual proof's robust expand helper.
    private fun expandFolderUntilSessionVisible(projectPath: String, sessionName: String) {
        val deadline = SystemClock.uptimeMillis() + ATTACH_TIMEOUT_MS
        compose.waitForIdle()
        while (!hasText(sessionName) && SystemClock.uptimeMillis() < deadline) {
            if (!hasTag(folderHeaderClickTestTag(projectPath))) {
                // Folder header not composed yet — give the list a poll window.
                runCatching { compose.waitUntil(timeoutMillis = 2_000) { hasText(sessionName) } }
                continue
            }
            runCatching {
                compose.onNodeWithTag(folderHeaderClickTestTag(projectPath), useUnmergedTree = true)
                    .performClick()
                compose.waitForIdle()
            }
            if (hasText(sessionName)) return
            runCatching { compose.waitUntil(timeoutMillis = 2_000) { hasText(sessionName) } }
        }
    }

    // Issue #761: `fetchSemanticsNodes` THROWS "No compose hierarchies found"
    // (rather than returning empty) during a transient screen transition right
    // after the host-row tap, while the folder screen is still composing. Guard
    // it so the expand poll keeps looping instead of aborting on that throw.
    private fun hasText(text: String): Boolean =
        runCatching {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)

    private fun hasTag(tag: String): Boolean =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)

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
                    // Issue #761: pre-seed the pocketshell-daemon detection state
                    // (mirrors WalkthroughVisualScreenshotTest) so the host-detail
                    // screen renders the FolderListScreen IMMEDIATELY instead of
                    // gating on a live "Checking setup" SSH probe. That probe was
                    // the slow/racy step that intermittently kept the folder list
                    // (and therefore the seeded session row) from composing within
                    // the wait window.
                    pocketshellInstalled = true,
                    pocketshellLastDetectedAt = System.currentTimeMillis(),
                    pocketshellDaemonRunning = true,
                    pocketshellDaemonEnabled = true,
                    pocketshellVersionCompatible = true,
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
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/issue-253")
        check(dir.exists() || dir.mkdirs())
        return dir
    }
}
