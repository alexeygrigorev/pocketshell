package com.pocketshell.app.proof

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.App
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1181 — DEVICE-TRUTH journey for the BLACK terminal on tapping the connection
 * notification (background→foreground resume) while the FGS keeps the connection ALIVE.
 *
 * ## The maintainer's report (dogfood 2026-07-02)
 *
 * The app is in the background, the FGS connection notification is showing (#1159), and the
 * connection is held ALIVE. Tapping the notification foregrounds the app — and the terminal
 * pane is BLACK, despite the connection still being up.
 *
 * ## Root cause (the spike, verified in code)
 *
 * A port-forward pin SUPPRESSES the bounded-grace teardown (#1159 Part 3), so the VM never
 * stashes a `pendingReattach` and the `-CC` control client stays live across the background.
 * On the notification-tap foreground return beyond the grace deadline,
 * `onAppForegrounded(resumedWithinGrace=false)` finds nothing pending and drives ZERO repaint
 * — the ONLY live-connection foreground path in the codebase that repaints nothing. A surface
 * that went black while backgrounded is never re-seeded → permanent black.
 *
 * ## The fix (this change)
 *
 * On that beyond-grace / live / nothing-pending branch, force ONE unconditional full-viewport
 * reseed of the active pane over the WARM client — REUSING the shared #553/#721/#892 reseed
 * chokepoint (a fresh `capture-pane` + `_fullRepaintRequests` full clear+repaint), exactly like
 * manual Redraw (#892) and the within-grace reattach. No reconnect, no new lease, no polling.
 *
 * ## How the black state + the pin are reproduced deterministically (mirrors #892)
 *
 *  - Attach a full-viewport static banner pane on the deterministic `agents` fixture (2222).
 *  - PIN the connection: register an active port-forward host on the app's ForwardingController
 *    so `holdWhilePinned()` is true → the bounded-grace teardown is suppressed (the live-no-
 *    pending state).
 *  - Feed `CSI 2J` + `CSI H` straight into the live `TerminalView.mEmulator` so the rendered
 *    viewport goes (near-)black — exactly the maintainer's screenshot. The REMOTE tmux grid is
 *    never touched, so a fresh `capture-pane` still holds the full banner.
 *  - Background PAST a short injected grace (the pin holds — assert `background_grace_held_by_
 *    port_forward` fired and NO `terminal_background_teardown`), then deliver the REAL session
 *    notification `contentIntent` (`SINGLE_TOP|CLEAR_TOP`, no extra) and foreground.
 *
 * ## Why the LOAD-BEARING assertion is the capture-pane round-trip (recompose-immune)
 *
 * `transcriptText` keeps the banner in scrollback after `CSI 2J`, and a recompose / `View.draw`
 * repaints from the still-cleared VISIBLE grid → stays black. The one signal that ONLY the
 * #1181 reseed produces is a FRESH server `capture-pane` for the active pane on the
 * foreground-resume, which re-applies the full banner. We snapshot [TmuxSessionLatencyTelemetry]
 * before the resume and require a NEW `capture-pane` for the active pane PLUS the recaptured
 * banner re-applied.
 *
 * ## Fail-first (G10/D33)
 *
 * WITHOUT the fix the beyond-grace / live / no-pending foreground fires NO reseed → NO
 * `capture-pane` → the banner stays black → the load-bearing assertion goes RED. WITH the fix
 * the foreground runs the full-viewport reseed → a NEW `capture-pane` fires and the banner is
 * re-applied → GREEN.
 *
 * Class coverage (D32 G2): a SHELL pane AND an idle AGENT/alt-screen pane (the maintainer's
 * exact pane kind — it emits no `%output` to self-heal, so without the reseed it stays black).
 * The still-in-memory `onNewIntent` resume is the black class this proves; the process-recreated
 * cold-start branch is a DIFFERENT (non-black) class — the spike shows it lands on the host list
 * (no session extra, reaped task) or fresh-connect-reseeds, covered by the cold-start journeys
 * (ColdRestoreGoneSessionNoResurrectE2eTest / ColdInstallE2eTest), so it is intentionally not
 * re-driven here.
 *
 * Uses ONLY the deterministic `agents` fixture (no toxiproxy, no `Assume.assumeFalse`), so it
 * RUNS on the per-PR emulator-journey job once wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class NotificationTapLivePinnedForegroundReseedJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext.applicationContext
    private val notificationManager =
        context.getSystemService(android.app.NotificationManager::class.java)

    private var diagnostics: RecordingDiagnosticSink? = null
    private var fixtureKey: String = ""
    private var hostRowTag: String = ""
    private var pinnedHostId: Long = -1L
    private var altScreen: Boolean = false

    @Before
    fun installDiagnostics() {
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        if (pinnedHostId >= 0L) {
            runCatching {
                (context as App).forwardingController.unregisterActiveHost(pinnedHostId)
            }
        }
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics?.close()
        diagnostics = null
        notificationManager.cancelAll()
        if (fixtureKey.isNotBlank()) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    /** SHELL pane — the notification-tap resume must re-seed a black shell pane over the warm session. */
    @Test
    fun notificationTapForegroundReseedsBlackShellPaneOverLivePinnedConnection() { runBlocking {
        altScreen = false
        runJourney("issue1181-shell")
    } }

    /**
     * Idle AGENT / alt-screen pane — the maintainer's exact pane kind. It never re-emits
     * `%output` to incrementally heal, so without the resume reseed it stays black forever.
     */
    @Test
    fun notificationTapForegroundReseedsBlackAgentPaneOverLivePinnedConnection() { runBlocking {
        altScreen = true
        runJourney("issue1181-altscreen")
    } }

    private suspend fun runJourney(namePrefix: String) {
        assertEquals("API 35 evidence must run on Android 15", 35, android.os.Build.VERSION.SDK_INT)

        attachSeededTmuxSession()
        waitForVisibleTerminal("$namePrefix initial banner") { it.contains(BANNER_MARKER) }
        waitForConnected("$namePrefix initial attach")
        val activePaneId = firstVisiblePaneId()
        captureViewport("$namePrefix-01-attached")
        assertTrue(
            "$namePrefix baseline buffer must contain the full banner",
            bannerRowCount(visibleTerminalText()) >= MIN_RESTORED_BANNER_ROWS,
        )

        // PIN the connection: register an active port-forward host so `holdWhilePinned()` is
        // true → the bounded-grace teardown is SUPPRESSED. This is the exact #1159-Part-3
        // "always-on" state that leaves the `-CC` client live with NO pendingReattach.
        (context as App).forwardingController.registerActiveHost(
            hostId = pinnedHostId,
            hostName = "Issue1181 Pinned",
        )

        // Reproduce the maintainer's black screenshot DIRECTLY on the live, RETAINED pane.
        val blackRows = pollPaintedRowsDownToBlack("$namePrefix-02-black")
        assertTrue(
            "$namePrefix black precondition must wipe the viewport to (near-)black " +
                "(<= $MAX_BLACK_PAINTED_ROWS painted rows); found $blackRows",
            blackRows <= MAX_BLACK_PAINTED_ROWS,
        )
        assertTrue(
            "$namePrefix session must stay Connected over the warm pinned lease before resume, " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // ============================================================
        // Background PAST a short grace — the pin HOLDS (no teardown).
        // ============================================================
        diagnostics!!.clear()
        BackgroundGraceTestOverride.setForTest(POST_GRACE_MS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "$namePrefix background")
        waitForDiagnostic("background_grace_held_by_port_forward", "$namePrefix pinned hold")
        // The pin SUPPRESSED the bounded teardown → the live `-CC` client stays, NO pendingReattach.
        SystemClock.sleep(TEARDOWN_SETTLE_MS)
        assertEquals(
            "$namePrefix the port-forward pin must SUPPRESS the bounded-grace teardown " +
                "(the live-no-pending state); teardown events=" +
                "${diagnostics!!.eventsNamed("terminal_background_teardown")}",
            0,
            diagnostics!!.eventsNamed("terminal_background_teardown").size,
        )
        // The FGS session notification is held while backgrounded+pinned — the tappable target.
        val notification = pollForSessionNotification(NOTIFICATION_TIMEOUT_MS)
        assertNotNull("$namePrefix session foreground notification must be posted while pinned", notification)

        // ============================================================
        // Deliver the REAL notification contentIntent, then foreground.
        // ============================================================
        val capturesBeforeResume = capturePaneCount(activePaneId)
        // Fire the actual `SINGLE_TOP|CLEAR_TOP` no-extra content intent (the real tap path →
        // onNewIntent) if present; the ProcessLifecycle ON_START below is what drives
        // onAppForegrounded(false) either way.
        runCatching { notification?.notification?.contentIntent?.send() }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "$namePrefix foreground") {
            it.fields["withinGrace"] == false
        }

        // LOAD-BEARING red→green (recompose-immune): the foreground-resume must issue a FRESH
        // `capture-pane` for the active pane over the warm session (the #1181 reseed). On base
        // this branch fires no repaint → no capture → RED.
        val newCaptures = waitForNewCapturePane(
            "$namePrefix resume capture-pane",
            paneId = activePaneId,
            baseline = capturesBeforeResume,
            timeoutMillis = RESEED_RESTORE_TIMEOUT_MS,
        )
        assertTrue(
            "$namePrefix the notification-tap foreground-resume must issue a FRESH capture-pane " +
                "for pane $activePaneId over the live pinned session (the #1181 reseed); " +
                "before=$capturesBeforeResume after=$newCaptures",
            newCaptures > capturesBeforeResume,
        )

        // And the recaptured FULL banner is re-applied into the pane buffer (the reseed landed).
        val visibleAfter = waitForVisibleTerminal(
            "$namePrefix resume full-viewport restore",
            timeoutMillis = RESEED_RESTORE_TIMEOUT_MS,
        ) { bannerRowCount(it) >= MIN_RESTORED_BANNER_ROWS }
        assertTrue(
            "$namePrefix the resume must re-apply the FULL banner (>= $MIN_RESTORED_BANNER_ROWS " +
                "rows) from the fresh capture; found ${bannerRowCount(visibleAfter)}",
            bannerRowCount(visibleAfter) >= MIN_RESTORED_BANNER_ROWS,
        )
        val restoredRows = capturePaintedRows("$namePrefix-03-resume-restored")
        assertTrue(
            "$namePrefix the resume repaint must leave the viewport painted (>= $MIN_PAINTED_ROWS " +
                "rows); found $restoredRows",
            restoredRows >= MIN_PAINTED_ROWS,
        )

        // The resume is a CALM warm-session reseed — no reconnect/detach/switch surface.
        watchNoVisibleReconnect("$namePrefix resume settle", POST_RESTORE_SETTLE_MS)
        assertTrue(
            "$namePrefix tmux session screen must still be up after the resume",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "$namePrefix session must stay Connected after the resume (no reconnect/new lease), " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        writeSummary(namePrefix)
    }

    // ---------------------------------------------------------------- Helpers

    private suspend fun seedBeforeLaunch() {
        notificationManager.cancelAll()
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
        fixtureKey = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(fixtureKey))
        seedTmuxSession(fixtureKey, altScreen)
        hostRowTag = seedDockerHost(fixtureKey)
    }

    private fun attachSeededTmuxSession() {
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "host row '$hostRowTag'") {
            tolerantNodeCountWithTag(hostRowTag) > 0
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "session row '$SESSION_NAME'") {
            tolerantNodeCountWithText(SESSION_NAME) > 0
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "session screen") {
            tolerantNodeCountWithTag(TMUX_SESSION_SCREEN_TAG) > 0
        }
        waitForTerminalViewAttached()
    }

    private fun waitForRender(timeoutMs: Long, label: String, condition: () -> Boolean) {
        runCatching {
            compose.waitUntil(timeoutMillis = timeoutMs) {
                runCatching { condition() }.getOrDefault(false)
            }
        }
        check(runCatching { condition() }.getOrDefault(false)) {
            "timed out after ${timeoutMs}ms waiting for: $label"
        }
    }

    private fun tolerantNodeCountWithTag(tag: String): Int =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size
        }.getOrDefault(0)

    private fun tolerantNodeCountWithText(text: String): Int =
        runCatching {
            compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().size
        }.getOrDefault(0)

    private fun waitForTerminalViewAttached() {
        waitForRender(TerminalTestTimeouts.screenRenderPresenceTimeoutMs(), "terminal view attached") {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun firstVisiblePaneId(): String =
        checkNotNull(viewModel().panes.value.firstOrNull()?.paneId) { "no visible pane after attach" }

    private fun viewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        compose.activityRule.scenario.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return checkNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun capturePaneCount(paneId: String): Int =
        TmuxSessionLatencyTelemetry.snapshot()
            .count { it.name == "capture_pane" && it.paneId == paneId }

    private fun waitForNewCapturePane(
        label: String,
        paneId: String,
        baseline: Int,
        timeoutMillis: Long,
    ): Int {
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
                TmuxSessionLatencyTelemetry.snapshot()
                    .filter { it.name == "capture_pane" }
                    .joinToString("\n") { it.toArtifactLine() },
        )
        return count
    }

    private fun feedBlackScreenFrameToEmulator() {
        val esc = "\u001B"
        val frame = "$esc[2J$esc[H".toByteArray(Charsets.UTF_8)
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val emulator = view.mEmulator ?: return@onActivity
            emulator.append(frame, frame.size)
            view.invalidate()
        }
        instrumentation.waitForIdleSync()
    }

    private fun pollPaintedRowsDownToBlack(name: String): Int {
        val deadline = SystemClock.elapsedRealtime() + BLACK_PRECONDITION_TIMEOUT_MS
        var rows = Int.MAX_VALUE
        while (true) {
            feedBlackScreenFrameToEmulator()
            val bitmap = renderViewportBitmap()
            if (bitmap != null) {
                rows = paintedRowCount(bitmap)
                if (rows <= MAX_BLACK_PAINTED_ROWS) {
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
        val captured = capturePaintedRows(name)
        writeText(
            "failure-$name-black-precondition.txt",
            "painted_rows=$captured (ceiling=$MAX_BLACK_PAINTED_ROWS); last_poll_rows=$rows\n" +
                "visible:\n${visibleTerminalText()}",
        )
        return captured
    }

    private fun capturePaintedRows(name: String): Int {
        val bitmap = renderViewportBitmap() ?: return 0
        writeBitmap("$name-viewport", bitmap)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        val rows = paintedRowCount(bitmap)
        bitmap.recycle()
        return rows
    }

    private fun renderViewportBitmap(): Bitmap? {
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        return bitmap
    }

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

    private fun bannerRowCount(text: String): Int =
        Regex("$BANNER_MARKER row (\\d{2})").findAll(text).map { it.groupValues[1] }.toSet().size

    private fun captureViewport(name: String) {
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        renderViewportBitmap()?.let {
            writeBitmap("$name-viewport", it)
            it.recycle()
        }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
    }

    private fun waitForConnected(label: String) {
        val deadline = SystemClock.elapsedRealtime() + CONNECTED_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline &&
            currentConnectionStatus() !is TmuxSessionViewModel.ConnectionStatus.Connected
        ) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(100)
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus.value
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

    private fun assertNoVisibleReconnect(label: String) {
        listOf(
            TMUX_CONNECTING_PROGRESS_TAG,
            TMUX_SESSION_ERROR_TAG,
            TMUX_SESSION_RECONNECT_TAG,
            TMUX_SWITCHING_LOADING_TAG,
        ).forEach { tag ->
            assertEquals(
                "expected no '$tag' surface for $label",
                0,
                compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().size,
            )
        }
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

    private fun waitForDiagnostic(
        name: String,
        label: String,
        timeoutMs: Long = DIAGNOSTIC_TIMEOUT_MS,
        predicate: (RecordedDiagnosticEvent) -> Boolean = { true },
    ): RecordedDiagnosticEvent {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val matches = diagnostics!!.eventsNamed(name).filter(predicate)
            if (matches.isNotEmpty()) return matches.last()
            SystemClock.sleep(50)
        }
        error("timed out waiting for diagnostic '$name' during $label; events=${diagnostics!!.events}")
    }

    private fun pollForSessionNotification(timeoutMs: Long): android.service.notification.StatusBarNotification? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var posted = sessionNotification()
        while (posted == null && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(100)
            posted = sessionNotification()
        }
        return posted
    }

    private fun sessionNotification() =
        notificationManager.activeNotifications.firstOrNull {
            it.notification.extras.getCharSequence("android.title")?.toString() == "Session connected"
        }

    private fun readFixtureKey(): String =
        instrumentation.context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val db = Room.databaseBuilder(context, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = context,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1181-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1181 Notification Reseed",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            pinnedHostId = hostId
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

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
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1181_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1181_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeSummary(namePrefix: String): File =
        writeText(
            "$namePrefix-summary.txt",
            buildString {
                appendLine("test=NotificationTapLivePinnedForegroundReseedJourneyE2eTest#$namePrefix")
                appendLine("issue=1181")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine("alt_screen=$altScreen")
                appendLine(
                    "scenario=attach banner pane, PIN a port-forward (suppress grace teardown), " +
                        "wipe live emulator to (near-)black, background past grace (pin holds), " +
                        "deliver notification contentIntent + foreground",
                )
                appendLine(
                    "expectation=the foreground-resume re-seeds the black pane over the warm " +
                        "pinned session (fresh capture-pane + banner restored), no reconnect band",
                )
            },
        )

    private fun artifactFile(name: String): File {
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(context)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) { "could not create artifact directory ${dir.absolutePath}" }
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
        const val DEVICE_DIR_NAME: String = "issue1181-notification-reseed"
        const val SESSION_NAME: String = "issue1181-notification-reseed"
        const val BANNER_MARKER: String = "ISSUE1181-BANNER"

        const val WITHIN_GRACE_MS: Long = 3_000L
        const val POST_GRACE_MS: Long = 500L
        const val TEARDOWN_SETTLE_MS: Long = 1_500L
        const val POST_RESTORE_SETTLE_MS: Long = 1_500L
        const val MIN_RESTORED_BANNER_ROWS: Int = 20
        const val MIN_PAINTED_ROWS: Int = 30
        const val MAX_BLACK_PAINTED_ROWS: Int = 15
        const val NOTIFICATION_TIMEOUT_MS: Long = 10_000L

        val BLACK_PRECONDITION_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 15_000L else 10_000L
        val RESEED_RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
        val DIAGNOSTIC_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 12_000L
    }
}
