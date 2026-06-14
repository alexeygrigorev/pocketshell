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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
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
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
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
import java.io.FileOutputStream

/**
 * Issue #553 (epic #687 Phase 3, J2) — DEVICE-TRUTH journey: a within-grace
 * reattach that leaves the pane PARTIALLY blank (one live line painting, the
 * static viewport above it gone) must restore the FULL prior viewport — not just
 * the single live line.
 *
 * ## The production bug
 *
 * On a within-grace foreground the warm `-CC` control client is RETAINED (the
 * teardown is deferred to grace-elapsed), so [TmuxSessionViewModel.onAppForegrounded]
 * runs the reseed-ONLY fast path [TmuxSessionViewModel.launchForegroundReattachReseed].
 * On `origin/main` that path heals only a FULLY-blank pane via
 * `reseedBlankVisiblePanes`, which `continue`s on `!visibleScreenIsBlank()`
 * (TerminalSurfaceState.visibleScreenIsBlank == `transcriptText.isBlank()`). So ONE
 * live agent line (a per-second status/timer) makes the pane non-blank → the heal is
 * SKIPPED → the static content above it is never restored ("blank except a timer").
 *
 * tmux `-CC` never re-emits an idle pane's existing frame, so when a reflow during a
 * brief link blip wipes the emulator grid above the still-repainting timer line, the
 * static banner stays gone forever.
 *
 * ## How this reproduces the partial blank deterministically on agents:2222 (no toxiproxy)
 *
 * The scaffold's earlier worry was that a FULL reconnect re-creates the emulator EMPTY
 * and the reveal gate reseeds the (fully-blank) pane BEFORE the live line lands, so the
 * agents-only worker-kill did NOT reproduce the partial blank. The bug lives on the
 * WARM-client within-grace path, where the emulator is RETAINED across the blip.
 *
 * This journey keeps the `-CC` client warm (a within-grace background with NO socket
 * drop, so `canReseedWithinGraceForeground()` stays true) and reproduces the post-reflow
 * partial blank DIRECTLY on the live, retained emulator: while backgrounded it feeds a
 * `CSI 2J` + `CSI H` (erase display + home) followed by ONE fresh timer line straight
 * into the SAME `TerminalView.mEmulator` the app renders — exactly the on-screen state a
 * tmux reflow leaves (static viewport wiped, one live line repainting). The REMOTE tmux
 * server grid is never touched, so `capture-pane` still holds the FULL banner: the fix
 * restores it. Because it uses ONLY the deterministic `agents` fixture (host port 2222)
 * and feeds the emulator locally (no toxiproxy, no `Assume.assumeFalse(isRunningOnCi())`),
 * it RUNS on the per-PR CI emulator-journey job.
 *
 * ## Contract (DEVICE TRUTH — asserts the user's pixels)
 *
 *  1. Before the foreground, the partial-blank is REAL: the visible terminal shows the
 *     live timer line but the static banner has been wiped (the `origin/main` skip
 *     precondition).
 *  2. After foregrounding within grace, the pane VIEWPORT is RE-SEEDED to the FULL prior
 *     content: the static banner [BANNER_MARKER] is restored AND the live timer line is
 *     still present — the full viewport, not just the live line.
 *  3. NO Reconnecting/Disconnected/Connecting/Attaching surface appears (the warm-client
 *     within-grace reattach is a calm ride-through).
 *
 * ## Fail-first
 *
 * On base `origin/main` the partially-blank pane is non-blank (the timer line), so
 * `reseedBlankVisiblePanes` SKIPS it: the banner is never restored and assertion (2) goes
 * RED. The P3 id-tagged full-viewport reseed (under the NEW connection path,
 * `reseedActivePaneForReattach` — UNCONDITIONAL, not gated on full-blank) restores the
 * full viewport and flips it GREEN, keyed to the target session id.
 */
@RunWith(AndroidJUnit4::class)
class ReconnectPartialBlankReseedJourneyE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var seededKey: String? = null
    private val timings = mutableListOf<String>()

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
    fun withinGraceReattachRestoresFullViewportNotJustTheLiveLine() = runBlocking {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)

        // Baseline: BOTH the static banner and the live timer line are on screen. The
        // banner is the content that must survive the within-grace partial blank and be
        // restored on return; tmux's server grid holds it throughout.
        waitForVisibleTerminal("initial attach banner") { it.contains(BANNER_MARKER) }
        waitForVisibleTerminal("initial attach timer") { it.contains(TIMER_MARKER) }
        waitForConnected("initial attach")
        captureViewport("issue553-01-attached")

        // Short grace override so the resume lands well within grace and the warm `-CC`
        // client is retained (canReseedWithinGraceForeground stays true — the warm path).
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)

        val cycleStart = SystemClock.elapsedRealtime()
        // (1) Background within grace — NO socket drop, the `-CC` client stays warm.
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(BACKGROUND_SETTLE_MS)

        // (2) Reproduce the post-reflow PARTIAL BLANK on the retained emulator: erase the
        // display + home, then paint ONE fresh timer line — exactly the on-screen state a
        // reflow during a brief link blip leaves (static viewport wiped, live line
        // repainting). The REMOTE tmux grid is untouched, so capture-pane still has the
        // full banner.
        feedPartialBlankFrameToEmulator()
        // Confirm the partial blank is REAL on screen before the foreground: the timer is
        // present but the banner is gone — the exact origin/main skip precondition.
        waitForVisibleTerminal("partial-blank precondition timer") { it.contains(TIMER_MARKER) }
        val partialBlankView = visibleTerminalText()
        assertTrue(
            "partial-blank precondition must show the live timer line; visible:\n$partialBlankView",
            partialBlankView.contains(TIMER_MARKER),
        )
        assertTrue(
            "partial-blank precondition must have WIPED the static banner (the origin/main " +
                "skip precondition); visible:\n$partialBlankView",
            !partialBlankView.contains(BANNER_MARKER),
        )
        captureViewport("issue553-02-partial-blank")
        recordTiming("partial_blank_injected_ms", SystemClock.elapsedRealtime() - cycleStart)

        // (3) Foreground WITHIN grace. The warm-client reseed-only path runs.
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - cycleStart)

        // DEVICE-TRUTH assertion (3): NO reconnect surface across the foreground window.
        watchNoVisibleReconnect("within-grace partial-blank reattach", OVERLAY_WATCH_MS)

        // DEVICE-TRUTH assertion (2): the pane VIEWPORT is RE-SEEDED to the FULL prior
        // content — the static banner is restored AND the live timer line is still
        // present. On base `origin/main` the non-blank timer line makes the heal SKIP the
        // partial blank, so the banner is never restored → this waits then fails RED.
        waitForVisibleTerminal("within-grace full-viewport restore") { it.contains(BANNER_MARKER) }
        val visibleAfter = visibleTerminalText()
        assertTrue(
            "within-grace partial-blank reattach must restore the FULL prior viewport " +
                "(static banner '$BANNER_MARKER'); visible terminal was:\n$visibleAfter",
            visibleAfter.contains(BANNER_MARKER),
        )
        assertTrue(
            "within-grace partial-blank reattach must keep the live timer line " +
                "('$TIMER_MARKER') alongside the restored banner; visible:\n$visibleAfter",
            visibleAfter.contains(TIMER_MARKER),
        )
        // The band stays absent through the settle (a late reconnect band would still be
        // the regression).
        watchNoVisibleReconnect("within-grace settle after restore", POST_RESTORE_SETTLE_MS)
        captureViewport("issue553-03-full-viewport-restored")

        // The session screen is still up (a reattach, not a teardown).
        assertTrue(
            "tmux session screen must still be up after the within-grace full-viewport restore",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeSummary()
        writeTimings()
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    /**
     * Feed a partial-blank frame straight into the SAME emulator the app renders. A
     * `CSI 2J` (erase entire display) + `CSI H` (cursor home) wipes the visible viewport;
     * a fresh `TIMER_MARKER` line then repaints, modelling the lone live line that
     * survives a reflow. This is local to the emulator — the remote tmux grid keeps the
     * full banner, so the within-grace reseed restores it.
     */
    private fun feedPartialBlankFrameToEmulator() {
        val esc = "\u001B"
        val frame = "$esc[2J$esc[H$TIMER_MARKER tick-after-reflow\r\n".toByteArray(Charsets.UTF_8)
        var fed = false
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val emulator = view.mEmulator ?: return@onActivity
            emulator.append(frame, frame.size)
            view.invalidate()
            fed = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("expected to feed the partial-blank frame to the live emulator", fed)
        Log.i(LOG_TAG, "fed partial-blank frame (2J + home + timer) to retained emulator")
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
        recordTiming("${label.replace(' ', '_')}_no_reconnect_ms", durationMs)
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
                name = "issue553-partialblank-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue553 Partial Blank Reseed",
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

    private suspend fun seedTmuxSession(key: String) {
        // A multi-line static banner (the content that must be restored) plus a live
        // ticking timer line (the lone line that survives the partial blank).
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            val payload = buildString {
                append("printf '$BANNER_MARKER line 1\\n$BANNER_MARKER line 2\\n$BANNER_MARKER line 3\\n'; ")
                append("i=0; while true; do printf '$TIMER_MARKER %s\\n' \"\$i\"; ")
                append("i=\$((i+1)); sleep 1; done")
            }
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} ${shellQuote(payload)}",
            )
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
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
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
        println("ISSUE553_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE553_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun writeSummary(): File =
        writeText(
            "summary.txt",
            buildString {
                appendLine("test=ReconnectPartialBlankReseedJourneyE2eTest")
                appendLine("issue=553")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine("timer_marker=$TIMER_MARKER")
                appendLine(
                    "scenario=attach, background within grace (warm -CC retained), " +
                        "inject reflow partial-blank (2J + home + one timer line) on the " +
                        "retained emulator, foreground within grace",
                )
                appendLine(
                    "expectation=full prior viewport restored (static banner + live timer), " +
                        "no Reconnecting/Disconnected/Attaching surface",
                )
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
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

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE553_TIMING $line")
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
        const val LOG_TAG: String = "Issue553PartialBlank"
        const val DEVICE_DIR_NAME: String = "issue553-partial-blank-reseed"
        const val SESSION_NAME: String = "issue553-partialblank-proof"
        const val BANNER_MARKER: String = "ISSUE553-BANNER"
        const val TIMER_MARKER: String = "ISSUE553-TIMER"

        const val WITHIN_GRACE_MS: Long = 8_000L
        const val BACKGROUND_SETTLE_MS: Long = 500L
        const val OVERLAY_WATCH_MS: Long = 2_500L
        const val POST_RESTORE_SETTLE_MS: Long = 2_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
