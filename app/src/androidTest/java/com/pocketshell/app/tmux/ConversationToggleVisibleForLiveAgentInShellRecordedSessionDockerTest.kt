package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
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
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #962 — a recorded `@ps_agent_kind=shell` session with NO agent correctly
 * shows NO Terminal/Conversation toggle (the #894 no-flap adjacency the fix must
 * preserve). The positive direction (a live agent in a recorded-shell session
 * regains its toggle) is owned by the deterministic JVM red→green — see the
 * coverage split below.
 *
 * The maintainer's dogfood report: in an active `claude` session the top chrome
 * shows only a single "Terminal" pill — no "Conversation" toggle. Root cause
 * (research, cited): the session was recorded `@ps_agent_kind=shell` (a plain
 * shell the user/kind-picker classified as shell) with `claude` started INSIDE
 * it. The recorded-shell verdict (#894) publishes the pane confirmed-shell,
 * which collapses `presumedAgent` and never binds a live source — so BOTH inputs
 * to `tmuxSessionTabState.showsConversationTab = hasLiveDetection || presumedAgent`
 * stay false and the toggle is gone for the life of the session.
 *
 * ### Coverage split (G4/D33 — the right test on the right surface)
 *
 * The LOAD-BEARING reproduction of the fix — a live agent detection clears the
 * confirmed-shell verdict so the toggle returns — is the DETERMINISTIC JVM
 * red→green in
 * `TmuxSessionViewModelTest.liveAgentDetectionClearsConfirmedShellSoConversationToggleReturns`
 * (+ codex / opencode + the no-flap control). That synthetic injection is
 * required because the Docker `agents` fixture cannot make the host agent-kind
 * daemon classify a process: the daemon gates on a cgroup-v2 scope that the
 * non-systemd container does not provide (`pocketshell agents kind` returns
 * `unknown`, `scope=null`), so a recorded-shell pane simply cannot bind a live
 * detection in-fixture. Per D33 the unenterable state is injected synthetically
 * and hard-asserted there, never self-skipped.
 *
 * This connected journey covers the part the REAL path reliably exercises on the
 * emulator + Docker — the **no-agent control / #894 no-flap invariant**: a session
 * recorded `@ps_agent_kind=shell` with NO agent process must show NO toggle on the
 * production `TmuxSessionScreen`. This is the active-rework adjacency the #962 fix
 * must not resurrect (a recorded shell flashing the Conversation tab), and it runs
 * unchanged in-fixture (the absence assertion needs no daemon classification). The
 * positive recorded-shell-agent-regains-toggle direction is owned by the
 * deterministic JVM red→green above (it cannot bind a live detection in-fixture).
 *
 * Modeled on the full-Activity Docker harness of
 * `TmuxSessionOpencodeInputDockerTest.issue303TerminalConversationPillStaysInToolbarRow`
 * (persist host → launch MainActivity → attach to a seeded session → assert the
 * tab pill on the real `TmuxSessionScreen`).
 */
@RunWith(AndroidJUnit4::class)
class ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val cleanupCommands = mutableListOf<String>()
    private val stamps = mutableListOf<String>()

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        if (cleanupCommands.isNotEmpty()) {
            runBlocking {
                runCatching {
                    withTimeout(20_000) { execRemote(readFixtureKey(), cleanupCommands.joinToString("\n")) }
                }
            }
        }
        if (stamps.isNotEmpty()) {
            writeText("conversation-toggle-962-stamps.txt", stamps.joinToString("\n", postfix = "\n"))
        }
    }

    /**
     * Adjacency / #894 no-flap invariant: a session recorded `@ps_agent_kind=shell`
     * with NO agent process must show NO toggle. This is the regression the #962
     * override must NOT resurrect (a recorded shell flashing the Conversation tab).
     * Runs unchanged in-fixture (the absence assertion needs no daemon classify).
     */
    @Test
    fun plainShellRecordedSessionShowsNoConversationToggle() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext

        val suffix = unique()
        val sessionName = "issue962-plain-shell-$suffix"
        cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"

        execRemote(
            key,
            buildString {
                appendLine("set -eu")
                appendLine("tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -x 80 -y 24 -s ${shellQuote(sessionName)} -c /tmp " +
                        "\"printf 'issue962-plain-ready\\r\\n'; exec sh\"",
                )
                appendLine("tmux set-option -t ${shellQuote(sessionName)} @ps_agent_kind shell")
                appendLine("sleep 1")
            },
        )

        val hostRowTag = persistHost(appContext, key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachToSeededSession(hostRowTag, sessionName)
        waitForTerminalSessionAttached()
        waitForVisibleTerminalText("issue962-plain-ready", VISIBLE_TIMEOUT_MS) {
            "issue962-plain-ready" in it
        }
        stamp("plain_shell_attached session=$sessionName")

        // Settle: give live detection a generous window to (not) fire, so the
        // "no toggle" assertion is meaningful and not racing the detector.
        SystemClock.sleep(SHELL_NO_TOGGLE_SETTLE_MS)

        // CONTROL ASSERTION (#894): a genuine recorded shell with NO agent shows
        // NO toggle. No live detection ever binds → confirmedShell is never
        // cleared → presumedAgent false → no toggle. This is the no-flap invariant
        // the #962 override must not resurrect.
        compose.onNodeWithTag(TMUX_TABS_TAG, useUnmergedTree = true).assertDoesNotExist()
        captureFullFrame("issue962-plain-shell-no-toggle")
        stamp("plain_shell_no_toggle_ok")
        Unit
    }

    // ----------------------------------------------------------------- helpers

    private suspend fun persistHost(appContext: android.content.Context, key: String): String {
        // Clear the persisted last-session so MainActivity starts on the host
        // list, not auto-restoring a stale (sibling/prior) TmuxSessionScreen — an
        // auto-restore would bypass the host row and race the attach.
        appContext.getSharedPreferences("last_session", android.content.Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        var hostRowTag = ""
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue962-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = HOST_NAME,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            hostRowTag = HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
        return hostRowTag
    }

    private fun attachToSeededSession(hostRowTag: String, sessionName: String) {
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        clickRobustly { compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick() }
        // Tap the session by its NAME text — it matches whether the folder list
        // renders the session as a flat row (cwd at the host root) or a nested
        // folder-detail row (a nested cwd), so this works for both fixtures. The
        // swiftshader AVD intermittently rejects a tap's touch injection while the
        // freshly-populated list is still settling, so retry until the session
        // screen mounts.
        compose.waitUntil(timeoutMillis = ATTACH_TIMEOUT_MS) {
            compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        tapUntilSessionScreenShown(sessionName)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * Perform a Compose click, retrying once on a transient "Failed to inject
     * touch input" — the host/session list can shift a row by a pixel during a
     * still-settling recomposition between the framework reading the node bounds
     * and dispatching the gesture. Settle to idle and retry.
     */
    private fun clickRobustly(click: () -> Unit) {
        runCatching { launchedActivity?.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
        compose.waitForIdle()
        try {
            click()
        } catch (e: AssertionError) {
            if (e.message?.contains("Failed to inject touch input") != true) throw e
            runCatching { launchedActivity?.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
            compose.waitForIdle()
            SystemClock.sleep(300)
            click()
        }
    }

    /**
     * Tap the session row until the [TMUX_SESSION_SCREEN_TAG] mounts. The
     * swiftshader AVD intermittently rejects a tap's touch injection while the
     * freshly-populated folder list is still settling; we retry the tap (settling
     * to idle + a short pause between attempts) until the session screen appears
     * or the budget is exhausted, so a transient injection rejection does not fail
     * the run.
     */
    private fun tapUntilSessionScreenShown(sessionName: String) {
        val deadline = SystemClock.elapsedRealtime() + ATTACH_TIMEOUT_MS
        var lastError: Throwable? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            // Under memory pressure the AVD can send the app to the background
            // (focus moves to the launcher), which makes Compose touch injection
            // fail ("Failed to inject touch input"). Bring the Activity back to
            // RESUMED before each tap so the app window is foreground+touchable.
            runCatching { launchedActivity?.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED) }
            compose.waitForIdle()
            runCatching {
                compose.onNodeWithText(sessionName, useUnmergedTree = true).performClick()
            }.onFailure { lastError = it }
            val shown = compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
            if (shown) return
            SystemClock.sleep(400)
        }
        throw AssertionError(
            "session screen ($TMUX_SESSION_SCREEN_TAG) never mounted after tapping session " +
                "'$sessionName' within ${ATTACH_TIMEOUT_MS}ms; lastTapError=$lastError",
        )
    }

    private fun waitForTerminalSessionAttached() {
        compose.waitUntil(timeoutMillis = 20_000) {
            findTerminalView()?.currentSession?.emulator != null
        }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun waitForVisibleTerminalText(
        label: String,
        timeoutMs: Long,
        predicate: (String) -> Boolean,
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = visibleTerminalText()
            if (predicate(last)) return
            SystemClock.sleep(50)
        }
        writeText("issue962-failure-$label-visible-terminal.txt", last)
        assertTrue("predicate $label timed out; visible terminal:\n$last", false)
    }

    private fun findTerminalView(): TerminalView? {
        var found: TerminalView? = null
        launchedActivity?.onActivity { activity ->
            found = activity.window.decorView.findTerminalView()
        }
        return found
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

    private fun captureFullFrame(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write full-frame screenshot to ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE962_FULLFRAME ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE962_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/conversation-toggle-962")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact dir ${dir.absolutePath}" }
        return File(dir, name)
    }

    private suspend fun execRemote(key: String, command: String) {
        val result = withTimeout(30_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session -> session.use { it.exec(command) } }
        }
        val exec = result.getOrNull()
        assertTrue(
            "remote command failed: ${result.exceptionOrNull()} exit=${exec?.exitCode} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun unique(): String =
        "${System.currentTimeMillis().toString().takeLast(6)}${System.nanoTime().toString().takeLast(4)}"

    private fun stamp(name: String) {
        stamps += "[issue962] $name at ${SystemClock.elapsedRealtime()}"
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val HOST_NAME: String = "Issue962 Agents"
        const val ATTACH_TIMEOUT_MS: Long = 30_000
        const val VISIBLE_TIMEOUT_MS: Long = 20_000
        const val SHELL_NO_TOGGLE_SETTLE_MS: Long = 8_000
    }
}
