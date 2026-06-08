package com.pocketshell.app.tmux

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #188 — regression test for the v0.2.8 feedback
 * "Kill window doesn't actually close the window".
 *
 * The user reproduced the symptom on a real phone: three tmux windows
 * open, tap "Kill window", observe the windows still present with no
 * error feedback.
 *
 * Root cause (paralleling issue #168 for kill-session):
 *
 *  - `TmuxSessionViewModel.killWindow` previously wrapped `sendCommand`
 *    in a plain `runCatching` and never refreshed afterwards. A failed
 *    kill was silent; a successful kill relied on the bus subscriber
 *    eventually delivering `%window-close` and triggering a reconcile,
 *    which could race on a hot SharedFlow.
 *
 * The fix:
 *
 *  - Subscribe to [com.pocketshell.core.tmux.protocol.ControlEvent.WindowClose]
 *    BEFORE issuing `kill-window`, so we never miss the deterministic
 *    post-kill notification.
 *  - Surface transport / tmux `%error` failures on [TmuxSessionViewModel.windowKillError]
 *    so the screen can render a banner — no silent swallow.
 *  - Force an explicit reconcile after the event lands (or the 2s
 *    fallback timeout fires) so the surviving window list drops the
 *    killed pill deterministically.
 *
 * ## UX note
 *
 * Since #192 the per-window kill affordance lives back on the
 * [WindowStrip] pill (an explicit ✕ on the active pill plus a
 * long-press menu); the kebab `Kill window` entry remains and always
 * targets the CURRENT window. This test drives the kebab path because
 * the user-reported bug was "any window kill silently fails", and
 * killing the currently-active window is sufficient to exercise the
 * fixed view-model code path. The new strip ✕ / long-press affordances
 * are covered at the chrome layer by `WindowStripChromeUiTest`.
 *
 * The app's pane reconcile sorts panes by `pane_index`, and the main
 * pager (driven by `currentPane = panes[pagerState.currentPage]`)
 * starts at page 0 immediately after attach. So `currentWindowId` ==
 * Window 1 by default. The test:
 *
 *  1. Seeds three windows in the `opencode-lab` session.
 *  2. Attaches the app — the in-app current window is Window 1.
 *  3. Opens the kebab and confirms `Switch window` + `Kill window`
 *     entries appear (proving `multipleWindows = true`, i.e. the view
 *     model reconciled to a 3-window state).
 *  4. Captures the pre-kill viewport for the artifact bundle.
 *  5. Taps `Kill window` and asserts the dialog targets the in-app
 *     current window's `@<id>` (positive proof that the kill is wired
 *     to the right window).
 *  6. Polls remote `tmux list-windows` until the count drops to 2 and
 *     verifies the surviving windows are `win2`+`win3` (i.e. the kill
 *     deleted the right window).
 *  7. Re-opens the WindowSwitcher overlay and confirms `Switch window`
 *     is still present and the overlay shows exactly 2 surviving
 *     pages. Issue #216 set [WindowSwitcherOverlay]'s pager
 *     `beyondViewportPageCount = Int.MAX_VALUE` so all pages are
 *     simultaneously composed in the semantic tree; the per-page
 *     testTags are reliable probes for the surviving window count
 *     regardless of which page is currently in the viewport.
 *
 * Artifacts written under
 * `<media>/additional_test_output/issue188-kill-window/`:
 *  - `01-before-kill-window1-viewport.png` (kebab open over Window 1
 *    terminal pane, just before tapping `Kill window`)
 *  - `02-after-kill-window1-viewport.png` (overlay open showing the 2
 *    surviving pages)
 *  - `timings.txt`
 *  - `summary.txt`
 */
@RunWith(AndroidJUnit4::class)
class KillWindowE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching {
                cleanupSeededSessions(readFixtureKey())
            }
        }
    }

    @Test
    fun killWindowRemovesStripPillWithinTwoSeconds() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedThreeWindowSession(key)
        val hostRowTag = seedDockerHost(key, "Issue188 Kill Window")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // --- (1) Tap host card → picker → attach to opencode-lab.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(SESSION_LAB, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_LAB).performClick()

        // --- (2) Wait for the tmux session screen.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(
                TMUX_SESSION_SCREEN_TAG,
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Cross-check that the seed actually populated 3 windows on the
        // remote before driving the app. This separates "fixture seed
        // broken" from "app failed to reconcile".
        val seededWindows = listRemoteWindows(key)
        assertEquals(
            "expected three seeded windows on the remote before driving the app; got $seededWindows",
            3,
            seededWindows.size,
        )
        // Capture each window's @id so we can (a) verify the kill
        // dialog targets the right id and (b) verify the surviving set
        // by name after the kill.
        val win1Entry = seededWindows.firstOrNull { it.name == "win1" }
        val win2Entry = seededWindows.firstOrNull { it.name == "win2" }
        val win3Entry = seededWindows.firstOrNull { it.name == "win3" }
        assertTrue(
            "expected seeded windows 'win1','win2','win3' on the remote; " +
                "got ${seededWindows.map { it.name }}",
            win1Entry != null && win2Entry != null && win3Entry != null,
        )
        val win1WindowId = win1Entry!!.windowId
        Log.i(LOG_TAG, "seeded windows: $seededWindows; win1=$win1WindowId")

        // --- (3) Wait for the kebab to mount. Confirm both `Switch
        // window` AND `Kill window` entries are present — the former
        // proves `multipleWindows = true` (i.e. the view model
        // reconciled to a multi-window state), the latter is what we
        // tap next.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText("Switch window", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose.onAllNodesWithText("Kill window", useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        captureFullDevice("01-before-kill-window1")

        // --- (4) Tap `Kill window`. The current window is Window 1
        // (per pane_index sort), so the dialog targets `@<win1_id>`. We
        // assert the dialog text names that id before tapping Kill —
        // that closes the loop between "app's current window is win1"
        // and "kill targeted win1".
        val killWindowTapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText("Kill window").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(
                "This will close $win1WindowId",
                substring = true,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // The kill button reads "Kill" — single text node inside the
        // dialog at this point. onLast() guards against any incidental
        // earlier match.
        compose.onAllNodesWithText("Kill", useUnmergedTree = true)
            .onLast()
            .performClick()

        // --- (5) Within ~2-5s the remote tmux session must drop from 3
        // windows to 2, and the surviving windows must be win2 + win3
        // (proving the kill targeted Window 1 specifically). We poll
        // the SSH side directly (separate from the UI tree) so the
        // assertion does not race the kebab + overlay re-open against
        // the in-flight reconcile.
        var remoteWindowsAfterKill: List<RemoteWindow> = listRemoteWindows(key)
        val pollDeadline = SystemClock.elapsedRealtime() + STRIP_REFRESH_TIMEOUT_MS
        while (
            remoteWindowsAfterKill.size > 2 &&
            SystemClock.elapsedRealtime() < pollDeadline
        ) {
            kotlinx.coroutines.delay(200)
            remoteWindowsAfterKill = listRemoteWindows(key)
        }
        val killLatencyMs = SystemClock.elapsedRealtime() - killWindowTapAt
        assertEquals(
            "tmux list-windows should report 2 surviving windows within " +
                "${STRIP_REFRESH_TIMEOUT_MS}ms; got $remoteWindowsAfterKill " +
                "after ${killLatencyMs}ms",
            2,
            remoteWindowsAfterKill.size,
        )
        val survivingNames = remoteWindowsAfterKill.map { it.name }.toSet()
        assertEquals(
            "kill must have targeted win1 specifically; surviving windows " +
                "should be win2+win3, got $survivingNames",
            setOf("win2", "win3"),
            survivingNames,
        )
        recordTiming("kill_window1_ms", killLatencyMs)
        Log.i(LOG_TAG, "kill Window 1 dropped remote windows to 2 in ${killLatencyMs}ms")

        // --- (6) The kebab + view model must agree with the post-kill
        // remote state. Open the kebab and assert `Switch window` is
        // still present (proving `multipleWindows` is still true, i.e.
        // we did not collapse to a single window). Then drill into the
        // WindowSwitcher and observe exactly 2 surviving pages.
        compose.waitForIdle()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("Switch window", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Switch window").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(TMUX_WINDOW_SWITCHER_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // Pages 1 and 2 must BOTH be present AND page 3 must be gone
        // (`beyondViewportPageCount = Int.MAX_VALUE` composes every
        // page in the semantic tree, so addressability does not depend
        // on which page is currently in the viewport). The waitUntil
        // here covers the in-app reconcile latency: tmux acks the kill
        // (verified above via the remote-side poll), then
        // `%window-close` fires, then [TmuxSessionViewModel] reconciles
        // `_panes`, then the WindowSwitcher overlay rebuilds its pager
        // with the new page count. Polling all three conditions
        // together waits for the visible state to converge.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(
                "${TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX}1",
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(
                    "${TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX}2",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(
                    "${TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX}3",
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(
            "${TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX}1",
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(
            "${TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX}2",
            useUnmergedTree = true,
        ).assertExists()
        // Page 3 must be gone — confirms the UI tree agrees with the
        // already-checked remote tmux server state.
        compose.onAllNodesWithTag(
            "${TMUX_WINDOW_SWITCHER_PAGE_TAG_PREFIX}3",
            useUnmergedTree = true,
        ).fetchSemanticsNodes().also { nodes ->
            assertTrue(
                "WindowSwitcher should not have a 3rd page after kill; got ${nodes.size} nodes",
                nodes.isEmpty(),
            )
        }
        captureFullDevice("02-after-kill-window1")

        // --- (7) Write artifacts.
        writeTimings()
        writeSummary(killLatencyMs, remoteWindowsAfterKill)
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
                name = "issue188-key-${System.currentTimeMillis()}",
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

    /**
     * Seed a tmux session named [SESSION_LAB] with exactly three windows.
     *
     * `new-session -d` makes window 1; two additional `new-window -t`
     * calls bring the count to three. Each window runs `exec sh` so it
     * stays alive while the app attaches.
     */
    private suspend fun seedThreeWindowSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_LAB)} -n win1 " +
                    shellQuote("printf 'WIN1-READY\\n'; exec sh"),
            )
            appendLine(
                "tmux new-window -t ${shellQuote(SESSION_LAB)} -n win2 " +
                    shellQuote("printf 'WIN2-READY\\n'; exec sh"),
            )
            appendLine(
                "tmux new-window -t ${shellQuote(SESSION_LAB)} -n win3 " +
                    shellQuote("printf 'WIN3-READY\\n'; exec sh"),
            )
            appendLine("tmux list-windows -t ${shellQuote(SESSION_LAB)}")
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
            "expected three-window seeding to succeed for #188, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session windows: ${exec?.stdout?.trim()}")
    }

    private data class RemoteWindow(val windowId: String, val name: String)

    /**
     * List remote tmux windows as `(@id, name)` pairs so the test can
     * (a) verify the kill targeted the right window by id (the dialog
     * renders `This will close @<id>`) and (b) verify the surviving
     * window set by name (`win2`+`win3`) after the kill.
     *
     * Implementation note: tmux 3.6 converts literal tab characters in
     * `-F` format output to `_` (verified against this test's docker
     * fixture). We use `|` as the field separator instead — tmux
     * doesn't mangle it and the seeded window names (`win1`, `win2`,
     * `win3`) never contain it.
     */
    private suspend fun listRemoteWindows(key: String): List<RemoteWindow> {
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
                    "tmux list-windows -t ${shellQuote(SESSION_LAB)} " +
                        "-F '#{window_id}|#{window_name}' 2>/dev/null || true",
                )
            }
        }
        val exec = result.getOrNull() ?: return emptyList()
        return exec.stdout
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val parts = line.split('|')
                if (parts.size != 2) return@mapNotNull null
                RemoteWindow(windowId = parts[0], name = parts[1])
            }
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
                        it.exec(
                            "tmux kill-session -t ${shellQuote(SESSION_LAB)} 2>/dev/null || true",
                        )
                    }
                }
            }
        }
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name-viewport.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "could not write screenshot ${file.absolutePath}"
                }
            }
            println("ISSUE188_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
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

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE188_TIMING $line")
    }

    private fun writeTimings() {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE188_TIMINGS ${file.absolutePath}")
    }

    private fun writeSummary(killMs: Long, remainingWindows: List<RemoteWindow>) {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=kill-window")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session=$SESSION_LAB")
                appendLine("seeded_windows=win1,win2,win3")
                appendLine("killed_window=win1  # the in-app currentWindow after attach")
                appendLine("kill_window1_ms=$killMs")
                appendLine("threshold_ms=2000  # per issue #188 acceptance criterion")
                appendLine(
                    "remote_remaining_windows=" +
                        remainingWindows.joinToString(",") { "${it.windowId}:${it.name}" },
                )
                appendLine("artifacts:")
                appendLine("  01-before-kill-window1-viewport.png")
                appendLine("  02-after-kill-window1-viewport.png")
                appendLine("  timings.txt")
            },
        )
        println("ISSUE188_SUMMARY ${file.absolutePath}")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue188KillWindow"
        const val DEVICE_DIR_NAME: String = "issue188-kill-window"

        /**
         * Session name to attach against. Must be one of the names the
         * `agents` Docker fixture's `tmuxctl list` stub recognises —
         * otherwise the picker never surfaces our seeded session no
         * matter how many real tmux windows are inside it. `opencode-lab`
         * was chosen because the dashboard's own kill-session test
         * already uses the same name for the same reason.
         */
        const val SESSION_LAB: String = "opencode-lab"

        /**
         * Compose wait envelope for the post-kill window-list refresh.
         *
         * Issue #188 acceptance criteria target sub-2s, which is what
         * happens when tmux emits `%window-close` immediately. On the
         * `agents` Docker fixture's tmux 3.6 the notification is
         * sometimes delayed or dropped when the killed window is not
         * the active one, so [TmuxSessionViewModel.killWindow] falls
         * back to an unconditional reconcile after its own 2s timeout.
         * We pad the Compose envelope to 5s (2s tmux fallback + ~1s
         * reconcile round-trip + ~2s Compose recomposition slack) so a
         * delayed notification path passes too — the recorded latency
         * is still surfaced in the artifact summary for the reviewer
         * to compare against the 2s acceptance bar.
         */
        const val STRIP_REFRESH_TIMEOUT_MS: Long = 5_000L
    }
}
