package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_WINDOW_STRIP_PILL_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_WINDOW_STRIP_TAG
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
 * Issue #662 — terminal panes are BLACK on a LIVE connection for a session
 * that already has multiple windows on the remote BEFORE the app attaches.
 *
 * The maintainer's dogfood: a host with a GREEN status dot (connection live),
 * a session with two tmux windows, and BOTH windows render a black pane (just
 * a cursor) — switching Window 1 <-> Window 2 does NOT recover content. So the
 * per-window pane content is never seeded from `capture-pane` on attach.
 *
 * Why the existing [TmuxSessionWindowNavigationE2eTest] did NOT catch it: that
 * test creates the second window from inside the app (`+ New window`) and then
 * writes markers into it via live `%output`. The marker content therefore lands
 * through the live stream, NOT through the attach-time `capture-pane` seed. The
 * maintainer's failing path is different: a window whose content existed on the
 * remote BEFORE attach has NO subsequent `%output` for an idle pane — its ONLY
 * source of content is the attach-time `capture-pane` seed. If that seed never
 * paints, the idle window stays black forever.
 *
 * This test seeds a session with TWO windows, each carrying a DISTINCT marker
 * printed once and then left idle (`exec sh`), attaches, and asserts:
 *   1. On attach, the first visible window's pane shows its pre-existing marker
 *      (not blank/black).
 *   2. After switching to the OTHER window via the strip, that window's pane
 *      shows ITS pre-existing marker (not blank/black).
 *   3. Switching back shows the first window's marker again.
 *
 * The markers are printed once at session creation and the shells go idle, so
 * the ONLY way the content can be on screen is the attach-time `capture-pane`
 * seed reaching every window's pane. A black pane fails assertion (1) or (2).
 *
 * Regular-CI gate: uses the plain Docker `agents` fixture on host port 2222
 * that `emulator-smoke.yml` brings up. NOT gated out of CI.
 */
@RunWith(AndroidJUnit4::class)
class PreExistingMultiWindowSeedE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupSeededSessions(readFixtureKey()) }
        }
    }

    @Test
    fun preExistingMultiWindowSessionSeedsEveryWindowFromCapture() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Seed a fresh session with TWO windows, each with a distinct marker
        // printed once and then left idle. The idle shells emit no further
        // %output, so the only source of on-screen content is the attach-time
        // capture-pane seed.
        seedMultiWindowSession(key)

        val hostRowTag = seedDockerHost(key, "Issue662 Black Multi-Window")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Attach to the multi-window session. `createEmptyComposeRule()` +
        // `ActivityScenario.launch` is racy at launch: querying semantics
        // before setContent runs throws IllegalStateException ("No compose
        // hierarchies found"), and a bare waitUntil predicate that throws
        // aborts the wait instead of retrying. Probe defensively so the wait
        // rides through the brief pre-setContent window.
        compose.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForSessionInPicker(rule = compose, sessionName = SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()

        // The multi-window session renders the WindowStrip; wait for both pills.
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(TMUX_WINDOW_STRIP_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose.onAllNodesWithTag(
                    "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}1",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(
                    "${TMUX_WINDOW_STRIP_PILL_TAG_PREFIX}2",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
        }

        // ===== Assertion 1 — land on window 1, its pre-existing marker shows =====
        performWindowStripPillClick(1)
        waitForVisibleTerminal("window 1 pre-existing marker seeded on attach") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                WINDOW_ONE_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue662-01-window1-seeded")

        // ===== Assertion 2 — switch to window 2, ITS pre-existing marker shows =====
        performWindowStripPillClick(2)
        waitForVisibleTerminal("window 2 pre-existing marker seeded on attach") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                WINDOW_TWO_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue662-02-window2-seeded")

        // ===== Assertion 3 — switch back to window 1, its marker still shows =====
        performWindowStripPillClick(1)
        waitForVisibleTerminal("window 1 marker still present after switch back") { transcript ->
            val columns = terminalGridSize().columns
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                WINDOW_ONE_MARKER,
                terminalCols = columns,
            ) &&
                !TerminalTextMatcher.containsWrapTolerant(
                    transcript,
                    WINDOW_TWO_MARKER,
                    terminalCols = columns,
                )
        }
        captureViewport("issue662-03-window1-after-switchback")

        Unit
    }

    // ---------------------------------------------------------------- Helpers

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue662-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedMultiWindowSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            // Hermetic reset: tear down the whole tmux server so a leftover
            // session from an earlier failed run cannot collide ("duplicate
            // session") with the fresh new-session below.
            appendLine("tmux kill-server 2>/dev/null || true")
            appendLine("sleep 0.3")
            // Both windows run a FROZEN full-screen TUI: it draws its frame
            // once (clear + content) then `sleep`s forever, IGNORING SIGWINCH —
            // so it NEVER redraws, not even on a resize. This is the worst case
            // of the maintainer's failing shape (#662): a full-screen app
            // (agent / pager) whose frame is on tmux's grid but which emits no
            // further %output. Its ONLY on-screen content source after attach is
            // the capture-pane seed.
            //
            // The pre-fix black-pane bug: the attach-time control-client resize
            // (refresh-client -C, applied after the surface reveals) reflows the
            // pane to the phone grid. The local emulator's seeded frame is then
            // stale/cleared, and because the frozen app emits no fresh redraw,
            // the pane stays BLACK with the cursor at home — exactly the
            // screenshot. tmux's grid still HOLDS the content after the reflow
            // (verified: capture-pane post-resize returns the content), so a
            // re-seed after the resize restores it. A plain `exec sh` would NOT
            // reproduce this because the shell re-echoes its prompt after the
            // resize; a frozen TUI exposes the missing post-resize re-seed.
            appendLine(
                "cat > /tmp/issue662-frozen.sh <<'FROZEOF'\n" +
                    "printf '\\033[2J\\033[H'\n" +
                    "printf '%s\\n' \"\$1\"\n" +
                    "exec sleep 100000\n" +
                    "FROZEOF",
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION)} -x 80 -y 24 " +
                    shellQuote("sh /tmp/issue662-frozen.sh $WINDOW_ONE_MARKER"),
            )
            appendLine(
                "tmux new-window -t ${shellQuote(SESSION)} " +
                    shellQuote("sh /tmp/issue662-frozen.sh $WINDOW_TWO_MARKER"),
            )
            // Leave window 1 active so attach lands on a deterministic window.
            appendLine("tmux select-window -t ${shellQuote(SESSION)}:0")
            appendLine("tmux list-windows -t ${shellQuote(SESSION)}")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected #662 multi-window seeding to succeed, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded multi-window session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSessions(key: String) {
        runCatching {
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).mapCatching { session ->
                    session.use {
                        it.exec("tmux kill-session -t ${shellQuote(SESSION)} 2>/dev/null || true")
                    }
                }
            }
        }
    }

    private fun performWindowStripPillClick(window: Int) {
        compose.onNodeWithTag(
            "$TMUX_WINDOW_STRIP_PILL_TAG_PREFIX$window",
            useUnmergedTree = true,
        ).performSemanticsAction(SemanticsActions.OnClick)
    }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected visible terminal text for $label within ${timeoutMillis}ms; got:\n$last",
            satisfied && predicate(last),
        )
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

    private fun terminalGridSize(): GridSize {
        var grid: GridSize? = null
        launchedActivity?.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = GridSize(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return grid ?: GridSize(columns = 80, rows = 24)
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE662_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE662_TEXT ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
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

    private data class GridSize(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue662MultiWindowSeed"
        const val DEVICE_DIR_NAME: String = "issue662-multiwindow-seed"
        const val SESSION: String = "black-multiwindow"
        const val WINDOW_ONE_MARKER: String = "WINDOW-ONE-SEEDED"
        const val WINDOW_TWO_MARKER: String = "WINDOW-TWO-SEEDED"
    }
}
