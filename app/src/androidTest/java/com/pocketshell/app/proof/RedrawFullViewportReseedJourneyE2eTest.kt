package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_REDRAW_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionLatencyTelemetry
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #892 — DEVICE-TRUTH journey for the manual **Redraw** kebab action.
 *
 * ## The maintainer's report (dogfood 2026-06-22)
 *
 * A connected `pocketshell` Claude session, Terminal tab, came back ~100% BLACK with
 * only a lone stray cursor cell painted (the partial-repaint class). The maintainer
 * asked for a one-tap kebab item, **Redraw**, that force-repaints the whole terminal
 * so they can recover without a reconnect/detach.
 *
 * ## What this journey proves (the acceptance criteria, on the REAL path)
 *
 *  - The "Redraw" kebab item is present + reachable (a stable test tag).
 *  - Tapping it on a stale/partial-black pane repaints the FULL current pane content
 *    over the WARM lease — NO reconnect, NO `Disconnected`/EOF band, NO session switch,
 *    NO "Attaching…" overlay (D21/D28).
 *  - Works for BOTH a shell pane AND an idle agent/alt-screen pane (the kind the
 *    maintainer's `pocketshell` Claude session shows — it emits no `%output` to
 *    incrementally heal itself, so without Redraw it stays black indefinitely).
 *
 * ## How the screenshot state is reproduced deterministically (no toxiproxy)
 *
 * This reuses the proven #553/#879 model: attach a full-viewport static banner pane on the
 * deterministic `agents` fixture (host port 2222), then feed `CSI 2J` (erase the visible
 * display) + `CSI H` (cursor home) straight into the SAME `TerminalView.mEmulator` the app
 * renders — the rendered viewport goes (near-)black with only the home cursor cell, exactly
 * the maintainer's screenshot. The REMOTE tmux grid is NEVER touched, so a fresh
 * `capture-pane` still holds the full banner.
 *
 * ## Why the LOAD-BEARING assertion is the capture-pane round-trip (recompose-immune)
 *
 * `TerminalSurfaceState.transcriptText` keeps the banner after a `CSI 2J` (the buffer is
 * still correct — that is the whole #879/#721 point: the black is a SURFACE artifact, not a
 * buffer one), and a full software `View.draw` re-render or a recomposition (e.g. opening
 * the kebab popup) repaints from that still-correct buffer. So neither the buffer text nor
 * the offscreen `View.draw` bitmap can DISTINGUISH "Redraw re-captured" from "a relayout
 * repainted stale buffer" — a pixel/buffer assertion would pass vacuously. The one signal
 * that ONLY a real Redraw produces is a FRESH server `capture-pane` round-trip for the
 * active pane. We snapshot [TmuxSessionLatencyTelemetry] (it records every `capture-pane`),
 * tap Redraw, and require a NEW `capture-pane` for the active pane PLUS the recaptured full
 * banner re-applied. (The on-screen dirty-clip black itself is owned by the render-layer
 * sibling `TerminalViewReattachLateSubscribeRepaintInstrumentedTest`, per the #721 lesson
 * that `View.draw` to an offscreen bitmap bypasses the dirty clip.)
 *
 * ## Fail-first (G10/D33)
 *
 * WITHOUT the Redraw action (the `redrawActivePane()` reseed wired to the kebab item) the
 * tap issues NO `capture-pane` — the load-bearing assertion goes RED. WITH the fix, tapping
 * Redraw runs the #553/#879 full-viewport reseed (`seedPaneFromCapture` → a fresh
 * `capture-pane`) over the warm `-CC` client → a NEW capture-pane fires and the banner is
 * re-applied → GREEN. (Proven red→green this change.)
 *
 * Uses ONLY the deterministic `agents` fixture and feeds the emulator locally (no
 * toxiproxy, no `Assume.assumeFalse(isRunningOnCi())`), so it RUNS on the per-PR CI
 * emulator-journey job once wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class RedrawFullViewportReseedJourneyE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var seededKey: String? = null

    @Before
    fun setUp() {
        BackgroundGraceTestOverride.setForTest(null)
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @After
    fun tearDown() {
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        BackgroundGraceTestOverride.setForTest(null)
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
    }

    /**
     * Issue #892 — Redraw restores a partial-black SHELL pane to its full prior content
     * over the warm session, with no reconnect surface.
     */
    @Test
    fun redrawRestoresFullViewportOnPartialBlackShellPane() { runBlocking {
        runRedrawJourney(altScreen = false, namePrefix = "issue892-shell")
    } }

    /**
     * Issue #892 — class coverage (D32 G2): Redraw must ALSO restore an idle
     * ALTERNATE-SCREEN (agent TUI / Claude) pane. This is the maintainer's exact
     * reported pane kind: an idle alt-screen pane emits no `%output` after the wipe, so
     * nothing incrementally heals it — without Redraw it stays black indefinitely.
     */
    @Test
    fun redrawRestoresFullViewportOnIdleAltScreenPane() { runBlocking {
        runRedrawJourney(altScreen = true, namePrefix = "issue892-altscreen")
    } }

    private suspend fun runRedrawJourney(altScreen: Boolean, namePrefix: String) {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key, altScreen)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)

        // Baseline: the full static banner is in the live pane and the session is Connected.
        waitForVisibleTerminal("$namePrefix initial banner") { it.contains(BANNER_MARKER) }
        waitForConnected("$namePrefix initial attach")
        val activePaneId = firstVisiblePaneId()
        capturePaintedRows("$namePrefix-01-attached")
        assertTrue(
            "$namePrefix baseline buffer must contain the full banner (>= $MIN_RESTORED_BANNER_ROWS " +
                "rows)",
            bannerRowCount(visibleTerminalText()) >= MIN_RESTORED_BANNER_ROWS,
        )

        // Reproduce the maintainer's black screenshot DIRECTLY on the live, RETAINED pane:
        // erase the visible display (`CSI 2J`) + home the cursor. The rendered viewport now
        // goes (near-)black — exactly the screenshot, a lone home cursor cell on black. The
        // REMOTE tmux grid is untouched, so capture-pane still holds the full banner.
        //
        // DETERMINISTIC precondition (no one-shot race): the `CSI 2J` erase must propagate to
        // the software `View.draw` before the offscreen bitmap reads (near-)black. A single
        // post-`waitForIdleSync` sample raced (~1/3 of full-class runs read the full ~168-row
        // banner before the erase landed). Poll the painted-row count DOWN to the black ceiling
        // with the SAME bounded-`waitUntil` shape the restore assertion uses, re-feeding the
        // frame each iteration so a dropped/late-applied erase is retried. Hard-fail on timeout
        // (NO assume/skip) — the precondition is now as deterministic as every other assertion.
        val blackPaintedRows = pollPaintedRowsDownToBlack("$namePrefix-02-black")
        assertTrue(
            "$namePrefix black precondition must wipe the visible viewport to (near-)black " +
                "(<= $MAX_BLACK_PAINTED_ROWS painted rows — the screenshot state); found " +
                "$blackPaintedRows painted rows after polling $BLACK_PRECONDITION_TIMEOUT_MS ms",
            blackPaintedRows <= MAX_BLACK_PAINTED_ROWS,
        )

        // The session must still report Connected over the warm lease — Redraw is a
        // warm-session reseed, not a recovery from a dropped connection.
        assertTrue(
            "$namePrefix expected the session to stay Connected before Redraw, observed=" +
                "${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // (AC1) The Redraw item is present + reachable in the kebab. Open the kebab.
        openKebab()

        // LOAD-BEARING red→green signal (recompose-immune): Redraw must issue a FRESH
        // `capture-pane` server round-trip for the active pane over the WARM session — that
        // is the literal "re-capture and full-repaint" the maintainer asked for, and it is
        // the one signal the View's own re-render (which paints from the still-correct local
        // buffer) cannot fake. We snapshot the capture-pane telemetry, confirm the kebab OPEN
        // alone fired NONE (no vacuous heal), tap Redraw, then require a NEW capture-pane for
        // this pane. On base (reseed not wired) the tap fires no capture-pane → RED; with the
        // fix `redrawActivePane()` → `seedPaneFromCapture` issues exactly one → GREEN.
        val capturesBeforeTap = capturePaneCount(activePaneId)
        tapRedraw()
        val newCaptures = waitForNewCapturePane(
            "$namePrefix redraw capture-pane",
            paneId = activePaneId,
            baseline = capturesBeforeTap,
            timeoutMillis = REDRAW_RESTORE_TIMEOUT_MS,
        )
        assertTrue(
            "$namePrefix Redraw must issue a FRESH capture-pane for pane $activePaneId over the " +
                "warm session (the full-viewport reseed); captures before tap=$capturesBeforeTap, " +
                "after=$newCaptures",
            newCaptures > capturesBeforeTap,
        )

        // And the recaptured FULL banner is applied back into the pane buffer (the reseed
        // landed, not just a round-trip), >= the bulk of the banner — not a one-line delta.
        val visibleAfter = waitForVisibleTerminal(
            "$namePrefix redraw full-viewport restore",
            timeoutMillis = REDRAW_RESTORE_TIMEOUT_MS,
        ) { bannerRowCount(it) >= MIN_RESTORED_BANNER_ROWS }
        assertTrue(
            "$namePrefix Redraw must re-apply the FULL banner (>= $MIN_RESTORED_BANNER_ROWS " +
                "distinct rows) from the fresh capture; found ${bannerRowCount(visibleAfter)}. " +
                "visible:\n$visibleAfter",
            bannerRowCount(visibleAfter) >= MIN_RESTORED_BANNER_ROWS,
        )
        // Device-truth: the rendered viewport is painted again (artifact for review).
        val restoredPaintedRows = capturePaintedRows("$namePrefix-03-redraw-restored")
        assertTrue(
            "$namePrefix Redraw repaint must leave the viewport painted (>= $MIN_PAINTED_ROWS " +
                "painted rows); found $restoredPaintedRows",
            restoredPaintedRows >= MIN_PAINTED_ROWS,
        )

        // (AC2) No reconnect/detach/switch surface appeared across the Redraw — it is a
        // calm warm-session reseed. Watch for a settle window so a late band is caught too.
        watchNoVisibleReconnect("$namePrefix redraw settle", POST_RESTORE_SETTLE_MS)

        // The session screen is still up (a reseed, not a teardown), and still Connected.
        assertTrue(
            "$namePrefix tmux session screen must still be up after Redraw",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            "$namePrefix session must stay Connected after Redraw (no reconnect/new lease), " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        writeSummary(namePrefix, altScreen)
    }

    // ---------------------------------------------------------------- Helpers

    private fun openKebab() {
        // Open the overflow kebab in the session header (contentDescription on the IconButton).
        compose.onNodeWithContentDescription("More session actions", useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        // AC1: present + reachable.
        compose.onNodeWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true).assertExists()
        Log.i(LOG_TAG, "opened kebab; Redraw item present")
    }

    private fun tapRedraw() {
        compose.onNodeWithTag(TMUX_REDRAW_BUTTON_TAG, useUnmergedTree = true)
            .assertExists()
            .performClick()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        Log.i(LOG_TAG, "tapped Redraw kebab item")
    }

    /**
     * Feed the maintainer's partial-black frame straight into the SAME emulator the app
     * renders: a `CSI 2J` (erase entire display) + `CSI H` (cursor home) wipes the visible
     * viewport, leaving only the (now-home) cursor cell — exactly the screenshot state.
     * Local to the emulator; the remote tmux grid keeps the full banner, so a correct
     * full-viewport Redraw restores it.
     */
    private fun feedBlackScreenFrameToEmulator() {
        val esc = "\u001B"
        // CSI 2J erases the visible display, CSI H homes the cursor — the rendered viewport
        // goes (near-)black with only the home cursor cell, exactly the screenshot.
        val frame = "$esc[2J$esc[H".toByteArray(Charsets.UTF_8)
        var fed = false
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val emulator = view.mEmulator ?: return@onActivity
            emulator.append(frame, frame.size)
            view.invalidate()
            fed = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("expected to feed the black-screen frame to the live emulator", fed)
        Log.i(LOG_TAG, "fed black-screen frame (2J + home) to retained emulator")
    }

    /**
     * Render the live [TerminalView] to an offscreen bitmap and count how many viewport
     * rows have meaningfully non-black pixels — the DEVICE-TRUTH measure of "is the screen
     * painted or black". `View.draw(Canvas)` is a full software render that paints straight
     * from the emulator's VISIBLE screen, so a `CSI 2J`-wiped grid reads (near-)black here
     * even while the scrollback buffer still holds the text. Writes the bitmap artifact.
     */
    private fun capturePaintedRows(name: String): Int {
        val bitmap = renderViewportBitmap() ?: return 0
        bitmap.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        val rows = paintedRowCount(bitmap)
        bitmap.recycle()
        return rows
    }

    /**
     * Deterministically drive the live viewport to the (near-)black screenshot state and prove
     * it: re-feed the `CSI 2J` + home frame and re-sample the offscreen `View.draw` bitmap on a
     * bounded poll until the painted-row count drops to `<= MAX_BLACK_PAINTED_ROWS`, OR the
     * deadline elapses. Mirrors the [waitForVisibleTerminal] / [waitForNewCapturePane] poll shape
     * — the precondition is no longer a one-shot sample that races the `CSI 2J` propagation.
     *
     * CRITICAL (the actual race the reviewer hit): the `CSI 2J` only clears the VISIBLE emulator
     * grid; the scrollback/alt buffer still holds the banner. Between a feed and a `View.draw`
     * the surface oscillates — a `View.draw` cycle that runs against the just-repainted buffer
     * reads the full banner again (the observed `last_poll_rows=8` black, then a fresh re-render
     * reads 168). So we MUST capture the artifact from the SAME bitmap that satisfied the
     * threshold — NOT a fresh re-render afterwards, which races the buffer repaint. We write the
     * PNG/text from the settled black bitmap and return its count; the caller hard-asserts it
     * (no assume/skip).
     */
    private fun pollPaintedRowsDownToBlack(name: String): Int {
        val deadline = SystemClock.elapsedRealtime() + BLACK_PRECONDITION_TIMEOUT_MS
        var rows = Int.MAX_VALUE
        while (true) {
            // Re-feed the wipe each iteration: if a prior frame's erase was dropped or applied
            // late relative to View.draw, the retry re-establishes the (near-)black grid.
            feedBlackScreenFrameToEmulator()
            val bitmap = renderViewportBitmap()
            if (bitmap != null) {
                rows = paintedRowCount(bitmap)
                if (rows <= MAX_BLACK_PAINTED_ROWS) {
                    // Persist the artifact from THIS settled black bitmap (do not re-render —
                    // a fresh draw races the buffer repaint and reads the banner back).
                    writeBitmap("$name-viewport", bitmap)
                    writeText("$name-visible-terminal.txt", visibleTerminalText())
                    bitmap.recycle()
                    return rows
                }
                bitmap.recycle()
            }
            if (SystemClock.elapsedRealtime() >= deadline) break
            SystemClock.sleep(100)
        }
        // Timed out — never settled to black. Capture a final artifact + a self-documenting RED.
        val captured = capturePaintedRows(name)
        writeText(
            "failure-$name-black-precondition.txt",
            "painted_rows=$captured (ceiling=$MAX_BLACK_PAINTED_ROWS) after " +
                "$BLACK_PRECONDITION_TIMEOUT_MS ms; last_poll_rows=$rows\n" +
                "visible:\n${visibleTerminalText()}",
        )
        return captured
    }

    private fun renderViewportBitmap(): Bitmap? {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        return bitmap
    }

    /**
     * Count how many horizontal scanlines of the bitmap contain a meaningfully non-black
     * pixel. The terminal background is near-black; any channel rising well above it is
     * painted text. Sampling every 4th row/column keeps it cheap.
     */
    private fun paintedRowCount(bitmap: Bitmap): Int {
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

    /** Count distinct numbered banner rows (`ISSUE892-BANNER row NN`) in the visible buffer. */
    private fun bannerRowCount(text: String): Int =
        Regex("$BANNER_MARKER row (\\d{2})").findAll(text).map { it.groupValues[1] }.toSet().size

    private fun firstVisiblePaneId(): String =
        checkNotNull(viewModel().panes.value.firstOrNull()?.paneId) {
            "no visible pane after attach"
        }

    private fun viewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        launchedActivity?.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return checkNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    /** How many `capture-pane` round-trips the telemetry has recorded for [paneId]. */
    private fun capturePaneCount(paneId: String): Int =
        TmuxSessionLatencyTelemetry.snapshot()
            .count { it.name == "capture_pane" && it.paneId == paneId }

    /**
     * Poll the capture-pane telemetry until a NEW `capture-pane` for [paneId] is recorded
     * past [baseline], or the timeout elapses. Returns the count observed. Writes a failure
     * artifact on timeout so a RED is self-documenting.
     */
    private fun waitForNewCapturePane(
        label: String,
        paneId: String,
        baseline: Int,
        timeoutMillis: Long,
    ): Int {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var count = baseline
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            count = capturePaneCount(paneId)
            if (count > baseline) return count
            SystemClock.sleep(100)
        }
        writeText(
            "failure-$label-capture-pane.txt",
            "baseline=$baseline observed=$count pane=$paneId\n" +
                "capture_pane spans:\n" +
                TmuxSessionLatencyTelemetry.snapshot()
                    .filter { it.name == "capture_pane" }
                    .joinToString("\n") { it.toArtifactLine() },
        )
        return count
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

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) writeText("failure-$label-visible-terminal.txt", last)
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
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

    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no Connecting overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no disconnect band for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no Tap Reconnect button for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no 'Attaching…' switching-loading overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Connecting", "Reconnecting", "Disconnected", "Tap Reconnect", "Attaching").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label",
                0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    private fun watchNoVisibleReconnect(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect(label)
            SystemClock.sleep(100)
        }
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

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
                name = "issue892-redraw-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue892 Redraw Reseed",
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
     * Seed a tmux session whose ACTIVE pane fills the viewport with a static banner, then
     * idles forever (no live output). When [altScreen] is true the banner is painted on
     * the ALTERNATE screen buffer (`CSI ?1049h`) — the idle agent-TUI / Claude case where
     * nothing repaints after the wipe.
     */
    private suspend fun seedTmuxSession(key: String, altScreen: Boolean) {
        val enterAlt = if (altScreen) "printf '\\033[?1049h'; " else ""
        val bannerLines = (1..40).joinToString("") {
            "$BANNER_MARKER row %02d filler abcdefghijklmnopqrstuvwxyz\\n".format(it)
        }
        val payload = buildString {
            append(enterAlt)
            append("printf '$bannerLines'; ")
            append("while true; do sleep 3600; done")
        }
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
            "expected tmux seeding to succeed (altScreen=$altScreen); " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session (altScreen=$altScreen): ${exec?.stdout?.trim()}")
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

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE892_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE892_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeSummary(namePrefix: String, altScreen: Boolean): File =
        writeText(
            "$namePrefix-summary.txt",
            buildString {
                appendLine("test=RedrawFullViewportReseedJourneyE2eTest#$namePrefix")
                appendLine("issue=892")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine("alt_screen=$altScreen")
                appendLine(
                    "scenario=attach a full-viewport static banner pane (alt-screen=$altScreen), " +
                        "wipe the live emulator (2J + home) to the screenshot black state, then tap " +
                        "the Redraw kebab item",
                )
                appendLine(
                    "expectation=the full prior viewport is restored over the warm session, no " +
                        "Reconnecting/Disconnected/Attaching surface, still Connected",
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
        const val LOG_TAG: String = "Issue892Redraw"
        const val DEVICE_DIR_NAME: String = "issue892-redraw-reseed"
        const val SESSION_NAME: String = "issue892-redraw-proof"
        const val BANNER_MARKER: String = "ISSUE892-BANNER"

        const val POST_RESTORE_SETTLE_MS: Long = 2_000L

        // Device-truth painted-row thresholds (sampled every 4th scanline of the rendered
        // viewport bitmap). The seeded banner fills the whole viewport, so a healthy paint
        // shows MANY painted rows; the 2J-wiped (near-)black screen shows very few. The
        // band between MAX_BLACK and MIN_PAINTED is wide so the red/green verdict is robust
        // to anti-aliasing / a stray cursor cell.
        // The seeded banner is 40 rows; after Redraw the restored buffer must hold many
        // distinct banner rows (a "bottom fragment only" failure would have very few). 20 is
        // a robust floor (the alt-screen shows the bottom screenful; the shell the full grid).
        const val MIN_RESTORED_BANNER_ROWS: Int = 20
        const val MIN_PAINTED_ROWS: Int = 30
        // The 2J-wiped grid still renders a few painted scanlines (a residual cursor cell /
        // chrome edge that View.draw catches — observed ~8). Keep the black ceiling well
        // above that floor and far below MIN_PAINTED_ROWS so the red/green verdict is robust.
        const val MAX_BLACK_PAINTED_ROWS: Int = 15

        // Bounded poll for the black precondition to propagate the `CSI 2J` erase to View.draw.
        // Generous on CI (slower swiftshader render) — it's a hard deadline, not a fixed wait.
        val BLACK_PRECONDITION_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 15_000L else 10_000L

        val REDRAW_RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
