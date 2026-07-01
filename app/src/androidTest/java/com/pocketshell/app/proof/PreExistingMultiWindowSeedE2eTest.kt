package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
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
import org.junit.rules.RuleChain
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
 * The failing path: a window whose content existed on the remote BEFORE the app
 * attaches has NO subsequent `%output` for an idle pane — its ONLY source of
 * content is the attach-time `capture-pane` seed. If that seed never paints, the
 * idle window stays black forever. (Markers written into a window via the live
 * `%output` stream after attach would mask this, so this test relies solely on
 * pre-existing, idle content seeded at attach time.)
 *
 * Issue #782 — PocketShell no longer manages tmux windows. A pre-existing
 * multi-window session (windows created OUTSIDE the app) is now surfaced in the
 * host tree as SEPARATE `[wN]` switcher entries (`<session> [w0]`,
 * `<session> [w1]`), each attaching to THAT window's pane. The old in-session
 * WindowStrip pills are gone (hard-cut). This test drives the new journey:
 *
 *   1. Tap the `[w0]` switcher entry — its pre-existing marker shows (the
 *      attach-time `capture-pane` seed reached window 0's pane, not blank/black).
 *   2. Back to the tree, tap the `[w1]` entry — ITS pre-existing marker shows.
 *   3. Back, tap `[w0]` again — window 0's marker shows again. Re-selecting the
 *      same session reuses the warm lease (D21 / #636 / #687 instant-switch); the
 *      `capture-pane`-seeded content renders without a black pane.
 *
 * The markers are printed once at session creation and the shells go idle, so
 * the ONLY way the content can be on screen is the attach-time `capture-pane`
 * seed reaching every window's pane. A black pane fails an assertion.
 *
 * Regular-CI gate: this class is listed in the per-push journey subset in
 * `scripts/ci-journey-suite.sh` (D28(3) / #638 / #691), so it runs on every PR
 * in `tests.yml`'s emulator-journey job. It uses ONLY the plain Docker `agents`
 * fixture on host port 2222 that `tests.yml` already brings up — no toxiproxy,
 * no extra service. It does NOT self-skip on CI.
 */
@RunWith(AndroidJUnit4::class)
class PreExistingMultiWindowSeedE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null

    @After
    fun closeLaunchedActivity() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        seededKey?.let { key ->
            runBlocking {
                runCatching { cleanupSeededSessions(key) }
            }
        }
    }

    @Test
    fun preExistingMultiWindowSurfacesPerWindowSwitcherEntriesAndSeedsEach() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }

        // Open the host tree. Under createAndroidComposeRule, MainActivity cold
        // compose can briefly expose no registered hierarchy, so probe
        // defensively until the pre-launch seeded host row exists.
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

        // Issue #782: the multi-window session is surfaced as SEPARATE
        // `<session> [wN]` switcher entries in the host tree — NOT a single
        // session row. Wait for both entries to appear.
        waitForText(WINDOW_ZERO_ENTRY, "the [w0] switcher entry must appear in the host tree")
        waitForText(WINDOW_ONE_ENTRY, "the [w1] switcher entry must appear in the host tree")
        captureFullDevice("issue782-00-switcher-entries")

        // ===== Assertion 1 — tap [w0], its pre-existing marker shows =====
        tapText(WINDOW_ZERO_ENTRY)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("window 0 pre-existing marker seeded on [w0] attach") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                WINDOW_ZERO_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue782-01-window0-seeded")

        // ===== Assertion 2 — back to tree, tap [w1], ITS marker shows =====
        pressBack()
        waitForText(WINDOW_ONE_ENTRY, "back to the host tree must re-show the [w1] entry")
        tapText(WINDOW_ONE_ENTRY)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("window 1 pre-existing marker seeded on [w1] attach") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                WINDOW_ONE_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue782-02-window1-seeded")

        // ===== Assertion 3 — back, tap [w0] again: warm re-select, marker shows =====
        // Re-selecting the SAME session reuses the warm lease (instant-switch,
        // D21 / #636 / #687): window 0's capture-seeded content renders again
        // without a black pane.
        pressBack()
        waitForText(WINDOW_ZERO_ENTRY, "back to the host tree must re-show the [w0] entry")
        tapText(WINDOW_ZERO_ENTRY)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForVisibleTerminal("window 0 marker still present after re-selecting [w0]") { transcript ->
            val columns = terminalGridSize().columns
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                WINDOW_ZERO_MARKER,
                terminalCols = columns,
            ) &&
                !TerminalTextMatcher.containsWrapTolerant(
                    transcript,
                    WINDOW_ONE_MARKER,
                    terminalCols = columns,
                )
        }
        captureViewport("issue782-03-window0-after-reselect")

        Unit
    } }

    // ---------------------------------------------------------------- Helpers

    private fun waitForText(text: String, message: String) {
        val ok = runCatching {
            compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
                runCatching {
                    compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }.getOrDefault(false)
            }
            true
        }.getOrDefault(false)
        assertTrue(message, ok)
    }

    private fun tapText(text: String) {
        compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
            .onFirst()
            .performClick()
    }

    private fun pressBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedBeforeLaunch() {
        val key = readFixtureKey()
        seededKey = key
        try {
            waitForSshFixtureReady(SshKey.Pem(key))

            // Seed a fresh session with TWO windows, each with a distinct marker
            // printed once and then left idle. The idle shells emit no further
            // %output, so the only source of on-screen content is the attach-time
            // capture-pane seed.
            seedMultiWindowSession(key)
            seededHostRowTag = seedDockerHost(key, "Issue782 Multi-Window Entries")
        } catch (t: Throwable) {
            runCatching { cleanupSeededSessions(key) }
            throw t
        }
    }

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
                    shellQuote("sh /tmp/issue662-frozen.sh $WINDOW_ZERO_MARKER"),
            )
            appendLine(
                "tmux new-window -t ${shellQuote(SESSION)} " +
                    shellQuote("sh /tmp/issue662-frozen.sh $WINDOW_ONE_MARKER"),
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

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
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
        compose.activityRule.scenario.onActivity { activity ->
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
        compose.activityRule.scenario.onActivity { activity ->
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
        compose.activityRule.scenario.onActivity { activity ->
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

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            writeBitmap(name, bitmap)
        } finally {
            bitmap.recycle()
        }
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
        const val LOG_TAG: String = "Issue782MultiWindowEntries"
        const val DEVICE_DIR_NAME: String = "issue782-multiwindow-entries"
        const val SESSION: String = "black-multiwindow"
        const val WINDOW_ZERO_MARKER: String = "WINDOW-ZERO-SEEDED"
        const val WINDOW_ONE_MARKER: String = "WINDOW-ONE-SEEDED"
        // Issue #782: each window is surfaced as a `<session> [wN]` switcher
        // entry; the test taps these by their visible label.
        const val WINDOW_ZERO_ENTRY: String = "$SESSION [w0]"
        const val WINDOW_ONE_ENTRY: String = "$SESSION [w1]"
    }
}
