package com.pocketshell.app.proof

import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_REDRAW_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #989 — DEVICE-TRUTH journey for the maintainer's load-bearing requirement:
 * a black/empty pane must ALWAYS be recoverable, Redraw must restore content (never
 * clear-to-black), and a re-attach must not stay black.
 *
 * ## The gap this proves (vs the sibling [RedrawFullViewportReseedJourneyE2eTest])
 *
 * The #892 sibling wipes the LOCAL emulator (`CSI 2J`) while the REMOTE tmux grid
 * still holds the full banner — so its `capture-pane` returns a CONTENT-RICH frame
 * that Redraw paints back. That never exercises the #989 root cause: an IDLE
 * alternate-screen agent (Claude/Codex) whose REMOTE grid is itself near-blank, so
 * `capture-pane` returns a near-blank-but-NON-EMPTY frame. Before #989, Redraw /
 * re-attach painted that near-blank capture after a `CSI 2J` clear and WIPED the
 * visible content to black-with-a-fragment (the maintainer's screenshot). The
 * happy (banner-in-remote) fixture masked it — exactly the v0.4.10/#847 lesson, so
 * this test ADDS the missing near-blank-REMOTE fixture (G10).
 *
 * ## Reproduced deterministically (no toxiproxy)
 *
 * Seed a tmux pane that is genuinely IDLE and near-blank on the REMOTE side (just a
 * tiny prompt — nothing for `capture-pane` to return). Attach. Then feed a
 * CONTENT-RICH banner straight into the SAME local `TerminalView.mEmulator` the app
 * renders — so LOCAL render = rich, REMOTE grid = near-blank, exactly the idle-agent
 * mismatch. Tap Redraw: the warm-session `capture-pane` comes back near-blank.
 *
 * ## Load-bearing assertion (red→green)
 *
 *  - WITH the #989 fix: the non-destructive swap REFUSES the near-blank capture and
 *    KEEPS the last (rich) frame — the rendered viewport stays painted (NOT black).
 *  - WITHOUT the fix: the near-blank capture is painted after `CSI 2J` → the viewport
 *    collapses to (near-)black. So [redrawKeepsRichContentWhenRemoteCaptureIsNearBlank]
 *    goes RED on base, GREEN with the fix.
 *
 * Uses ONLY the deterministic `agents` fixture (host port 2222) and feeds the rich
 * frame LOCALLY (no toxiproxy, no `Assume.assumeFalse(isRunningOnCi())`), so it RUNS
 * on the per-PR CI emulator-journey job once wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class RedrawNonDestructiveNearBlankCaptureE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var seededKey: String? = null

    @Before
    fun setUp() {
        BackgroundGraceTestOverride.setForTest(null)
    }

    @After
    fun tearDown() {
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        BackgroundGraceTestOverride.setForTest(null)
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
    }

    @Test
    fun redrawKeepsRichContentWhenRemoteCaptureIsNearBlank(): Unit = runBlocking {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedIdleNearBlankSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)
        waitForConnected("issue989 initial attach")
        waitForTerminalViewAttached()

        // LOCAL render = rich: feed a full-viewport banner straight into the emulator the
        // app renders. The REMOTE tmux grid stays near-blank (the idle prompt) — this is
        // the idle-agent mismatch the #989 root cause needs.
        feedRichBannerToEmulator()
        val richRows = pollPaintedRowsUpToRich("issue989-01-rich-local")
        assertTrue(
            "precondition: the LOCAL viewport must be content-rich before Redraw " +
                "(>= $MIN_RICH_PAINTED_ROWS painted rows); found $richRows",
            richRows >= MIN_RICH_PAINTED_ROWS,
        )
        assertTrue(
            "precondition: the session must be Connected over the warm lease",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // Tap Redraw. The warm-session `capture-pane` returns the REMOTE near-blank grid.
        openKebab()
        tapRedraw()

        // LOAD-BEARING (red→green): the rich viewport must NOT be cleared to black. With the
        // #989 non-destructive swap the near-blank capture is refused and the last frame is
        // KEPT; without it the near-blank capture is painted after `CSI 2J` and the viewport
        // collapses to (near-)black. Poll a settle window so a late clear is caught too.
        val keptRich = pollViewportStaysRich("issue989-02-after-redraw", POST_REDRAW_SETTLE_MS)
        assertTrue(
            "Redraw must NOT clear the content-rich pane to black when the capture is " +
                "near-blank — the last frame must be kept (>= $MIN_RICH_PAINTED_ROWS painted " +
                "rows held across the settle window); min observed $keptRich",
            keptRich >= MIN_RICH_PAINTED_ROWS,
        )

        // The session screen is still up and Connected (a reseed, not a teardown).
        assertTrue(
            "tmux session screen must still be up after Redraw",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            "session must stay Connected after Redraw, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        writeSummary()
    }

    // ---------------------------------------------------------------- Helpers

    private fun openKebab() {
        compose.onNodeWithContentDescription("More session actions", useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true).assertExists()
    }

    private fun tapRedraw() {
        compose.onNodeWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true)
            .assertExists()
            .performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Log.i(LOG_TAG, "tapped Redraw kebab item")
    }

    /**
     * Feed a full-viewport content-rich banner into the SAME local emulator the app
     * renders. Local-only (the remote tmux grid stays near-blank), the #553/#879 model.
     */
    private fun feedRichBannerToEmulator() {
        val esc = "\u001B"
        val banner = buildString {
            append("$esc[2J$esc[H")
            (1..40).forEach { append("$BANNER_MARKER row %02d filler abcdefghijklmnopqrst\r\n".format(it)) }
        }.toByteArray(Charsets.UTF_8)
        var fed = false
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val emulator = view.mEmulator ?: return@onActivity
            emulator.append(banner, banner.size)
            view.invalidate()
            fed = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("expected to feed the rich banner to the live emulator", fed)
    }

    private fun pollPaintedRowsUpToRich(name: String): Int {
        val deadline = SystemClock.elapsedRealtime() + RICH_PRECONDITION_TIMEOUT_MS
        var best = 0
        while (true) {
            feedRichBannerToEmulator()
            val rows = capturePaintedRows(name)
            if (rows > best) best = rows
            if (best >= MIN_RICH_PAINTED_ROWS) return best
            if (SystemClock.elapsedRealtime() >= deadline) break
            SystemClock.sleep(100)
        }
        return best
    }

    /**
     * Poll the rendered viewport across a settle window and return the MINIMUM painted-row
     * count observed — so a late clear-to-black (the #989 symptom landing after a beat) is
     * caught, not raced past. With the fix the count never drops below the rich floor.
     */
    private fun pollViewportStaysRich(name: String, durationMs: Long): Int {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        var min = Int.MAX_VALUE
        var lastBitmapName = name
        while (SystemClock.elapsedRealtime() < deadline) {
            val rows = capturePaintedRows(lastBitmapName)
            if (rows < min) min = rows
            lastBitmapName = name
            SystemClock.sleep(150)
        }
        return if (min == Int.MAX_VALUE) 0 else min
    }

    private fun capturePaintedRows(name: String): Int {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var rows = 0
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = android.graphics.Bitmap.createBitmap(
                view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888,
            )
            view.draw(android.graphics.Canvas(b))
            rows = paintedRowCount(b)
            writeBitmap("$name-viewport", b)
            b.recycle()
        }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        return rows
    }

    private fun paintedRowCount(bitmap: android.graphics.Bitmap): Int {
        var painted = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            var rowPainted = false
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                if (android.graphics.Color.red(p) > 40 ||
                    android.graphics.Color.green(p) > 40 ||
                    android.graphics.Color.blue(p) > 40
                ) {
                    rowPainted = true
                    break
                }
                x += 4
            }
            if (rowPainted) painted++
            y += 4
        }
        return painted
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
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

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        launchedActivity?.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
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

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    /**
     * Seed a tmux session whose ACTIVE pane is genuinely IDLE and near-blank on the REMOTE
     * side — just a tiny prompt fragment, nothing for `capture-pane` to return. This is the
     * idle-alt-screen-agent state whose capture is near-blank (the #989 root cause).
     */
    private suspend fun seedIdleNearBlankSession(key: String) {
        val payload = "printf '\\033[?1049h'; printf '> '; while true; do sleep 3600; done"
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine("tmux new-session -d -s ${shellQuote(SESSION_NAME)} ${shellQuote(payload)}")
            appendLine("sleep 2")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected idle near-blank tmux seeding to succeed; " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded idle near-blank session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue989-redraw-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue989 Non-Destructive Redraw",
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

    private fun writeBitmap(name: String, bitmap: android.graphics.Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE989_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE989_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeSummary(): File =
        writeText(
            "issue989-summary.txt",
            buildString {
                appendLine("test=RedrawNonDestructiveNearBlankCaptureE2eTest")
                appendLine("issue=989")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine(
                    "scenario=idle near-blank REMOTE alt-screen pane; rich banner fed LOCALLY; " +
                        "tap Redraw -> warm capture-pane returns near-blank",
                )
                appendLine(
                    "expectation=Redraw KEEPS the rich frame (non-destructive swap), never " +
                        "clears the visible content to black; still Connected",
                )
            },
        )

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

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue989Redraw"
        const val DEVICE_DIR_NAME: String = "issue989-nondestructive-redraw"
        const val SESSION_NAME: String = "issue989-redraw-proof"
        const val BANNER_MARKER: String = "ISSUE989-BANNER"

        const val POST_REDRAW_SETTLE_MS: Long = 3_000L

        // The locally-fed banner fills the whole viewport, so a healthy rich render shows
        // MANY painted rows; a clear-to-black collapses to very few. 30 is a robust floor.
        const val MIN_RICH_PAINTED_ROWS: Int = 30

        val RICH_PRECONDITION_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 15_000L else 10_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
