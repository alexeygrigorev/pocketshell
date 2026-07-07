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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1214 — DEVICE-TRUTH journey: a MOSTLY-EMPTY model (fragments-over-black with MORE than 3
 * live lines) must heal AT REVEAL, not ~4s later at the steady watchdog.
 *
 * ## The production bug (reveal-time leg of the #1208 fragments-over-black)
 *
 * The reveal/resize/switch nets gate an authoritative `capture-pane` diff on the CHEAP LOCAL
 * pre-check [com.pocketshell.core.terminal.ui.TerminalSurfaceState.visibleRenderMayHaveLostFrame].
 * Before #1214 that pre-check only flagged a live-fraction in `(0.5, 0.75]` or a ≤3-line
 * partial-blank. So a mostly-empty pane with >3 scattered live lines but a live-fraction BELOW 0.5
 * read "healthy" at the reveal gate and the no-op-resize heal → it REVEALED UNHEALED
 * (fragments-over-black) and only the ≤16s-later steady watchdog could catch it. #1214 drops the
 * 0.5 lower bound so the reveal/resize nets pay ONE authoritative diff for the mostly-empty model.
 *
 * ## How this reproduces the bug deterministically on agents:2222 (no toxiproxy)
 *
 * Seed a FULL multi-row banner as an idle full-screen pane, attach, then model the reveal-time
 * fragments-over-black straight into the SAME emulator the app renders: a `CSI 2J` + `CSI H`
 * (erase + home) wipes the banner, then FIVE scattered live lines are painted. Five live lines is
 * MORE than the ≤3-line partial-black cap AND well below the 0.5 live-fraction floor — the exact
 * #1214 gap the pre-#1214 pre-check read "healthy". The REMOTE tmux grid is untouched, so
 * `capture-pane` still holds the FULL banner. Then drive the EXACT same-dimension (no-op) resize
 * heal production branch (a keyboard/composer dismissal). On base the widened-only-to-the-dead-zone
 * pre-check SKIPS the sub-0.5 mostly-empty pane → the banner stays gone within the SHORT heal
 * window → RED. With #1214 the pre-check flags it → the no-op-resize heal confirms against tmux and
 * re-captures the full viewport AT reveal → GREEN. Uses ONLY the deterministic `agents` fixture
 * (host port 2222), feeds the emulator locally (no toxiproxy, no
 * `Assume.assumeFalse(isRunningOnCi())`), so it RUNS on the per-PR CI emulator-journey job once
 * wired into `scripts/ci-journey-suite.sh`.
 *
 * ## Contract (DEVICE TRUTH — asserts the user's pixels)
 *
 *  1. Before the reveal, the active pane shows the full banner (baseline).
 *  2. The mostly-empty model is REAL on screen: the banner is gone, >3 fragment lines survive, the
 *     pane reads NON-blank AND NON-partial-black (the origin/main reveal-gate skip precondition),
 *     and the widened #1214 pre-check flags it.
 *  3. After the (same-dimension) resize heal the active pane is RE-SEEDED to the full prior
 *     content: the banner is restored PROMPTLY. On base the sub-0.5 mostly-empty pane is skipped,
 *     so the banner is never restored within the short window → RED.
 *  4. NO Reconnecting/Disconnected/Connecting/Attaching surface appears (the heal is a calm
 *     in-place re-capture, not a reconnect).
 */
@RunWith(AndroidJUnit4::class)
class MostlyEmptyModelHealsAtRevealJourneyE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()
    private val grantPermissions = PreGrantPermissionsRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain
        .outerRule(grantPermissions)
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private var seededKey: String? = null
    private var seededHostRowTag: String? = null
    private val timings = mutableListOf<String>()

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
    }

    @Test
    fun mostlyEmptyModelHealsAtRevealNotAtTheWatchdog() { runBlocking {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        attachSeededTmuxSession(hostRowTag)

        // (1) Baseline: the full multi-row banner is on screen and the channel is Connected.
        waitForVisibleTerminal("initial attach banner") { bannerRowCount(it) >= MIN_RESTORED_BANNER_ROWS }
        waitForConnected("initial attach")
        captureViewport("issue1214-01-attached")

        val cycleStart = SystemClock.elapsedRealtime()

        // (2) Model the reveal-time fragments-over-black: erase the display + home, then paint
        // FIVE scattered live lines — straight into the SAME emulator the app renders. Five live
        // lines is MORE than the ≤3-line partial-black cap AND far below the 0.5 live-fraction
        // floor: the exact #1214 gap the pre-#1214 pre-check read "healthy". The banner above is
        // WIPED; the REMOTE tmux grid still holds the full banner, so only the #1214-widened
        // reveal/resize heal restores it.
        injectMostlyEmptyModel()
        val fragmentView = waitForVisibleTerminal("mostly-empty fragments-over-black") {
            it.contains(FRAGMENT_MARKER) && bannerRowCount(it) < MIN_RESTORED_BANNER_ROWS
        }
        assertTrue(
            "mostly-empty precondition must keep the >3 fragment lines ('$FRAGMENT_MARKER') so the " +
                "pane reads NON-partial-black; visible:\n$fragmentView",
            fragmentView.contains(FRAGMENT_MARKER),
        )
        assertTrue(
            "mostly-empty precondition must have WIPED the full banner; visible:\n$fragmentView",
            bannerRowCount(fragmentView) < MIN_RESTORED_BANNER_ROWS,
        )
        // The exact origin/main reveal-gate skip precondition + the #1214 pre-check now catching it.
        assertFalse(
            "RED premise (#1214): the >3-line mostly-empty model reads NON-blank AND " +
                "NON-partial-black, so the pre-#1214 blank/partial reveal net SKIPPED it",
            activePaneBlankOrPartiallyBlank(),
        )
        assertTrue(
            "GREEN premise (#1214): the widened local capture-gate must flag the mostly-empty model " +
                "so the reveal/resize heal pays the authoritative capture",
            activePaneMayHaveLostFrame(),
        )
        captureViewport("issue1214-02-mostly-empty")
        recordTiming("mostly_empty_injected_ms", SystemClock.elapsedRealtime() - cycleStart)

        // ----- DISCRIMINATOR: the transport is GUARANTEED LIVE (render bug, not a drop). -----
        assertTrue(
            "the transport must stay Connected with a mostly-empty render, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // (3) Drive the EXACT same-dimension (no-op) resize heal production branch a
        // composer/keyboard dismissal takes. On base the sub-0.5 mostly-empty pane is skipped; with
        // #1214 the heal confirms against tmux and re-captures the full viewport AT reveal.
        var triggered = false
        compose.activityRule.scenario.onActivity { activity ->
            triggered = viewModel(activity).triggerSameDimensionResizeHealForTest()
        }
        assertTrue("expected the same-dimension resize heal seam to find a live runtime", triggered)
        recordTiming("resize_heal_triggered_ms", SystemClock.elapsedRealtime() - cycleStart)

        // DEVICE-TRUTH assertion (3): the active pane is RE-SEEDED to the full banner within a
        // SHORT window (a single `capture-pane`). Kept well below the idle passive-disconnect
        // timeout so a later transport reattach cannot mask a missing reveal-time heal on base.
        waitForVisibleTerminal(
            "post-heal full-viewport restore",
            timeoutMillis = HEAL_RESTORE_TIMEOUT_MS,
        ) { bannerRowCount(it) >= MIN_RESTORED_BANNER_ROWS }
        val visibleAfter = visibleTerminalText()
        assertTrue(
            "the reveal-time heal must PROMPTLY restore the active pane's full banner " +
                "(>= $MIN_RESTORED_BANNER_ROWS rows) — the pane must NOT stay fragments-over-black; " +
                "visible terminal was:\n$visibleAfter",
            bannerRowCount(visibleAfter) >= MIN_RESTORED_BANNER_ROWS,
        )
        recordTiming("banner_restored_ms", SystemClock.elapsedRealtime() - cycleStart)
        captureViewport("issue1214-03-full-viewport-restored")

        // DEVICE-TRUTH assertion (4): NO reconnect surface across the heal + a short settle.
        watchNoVisibleReconnect("mostly-empty reveal heal settle", OVERLAY_WATCH_MS)

        assertTrue(
            "tmux session screen must still be up after the reveal-time heal",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            "session must stay Connected after the heal (render fix, no reconnect), " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        writeSummary()
        writeTimings()
        Unit
    } }

    // ---------------------------------------------------------------- Helpers

    /**
     * Model the reveal-time fragments-over-black straight into the SAME emulator the app renders: a
     * `CSI 2J` (erase entire display) + `CSI H` (home) wipes the banner, then FIVE scattered live
     * lines are painted (each separated by a blank row — genuine fragments-over-black). Five live
     * lines is MORE than the ≤3-line partial-black cap AND far below the 0.5 live-fraction floor —
     * the #1214 gap. Local to the emulator; the remote tmux grid keeps the full banner.
     */
    private fun injectMostlyEmptyModel() {
        val esc = ""
        val frame = buildString {
            append("$esc[2J$esc[H")
            repeat(5) { i ->
                append("$FRAGMENT_MARKER fragment $i over black\r\n\r\n")
            }
        }.toByteArray(Charsets.UTF_8)
        var fed = false
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val emulator = view.mEmulator ?: return@onActivity
            emulator.append(frame, frame.size)
            view.invalidate()
            fed = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("expected to inject the mostly-empty frame to the active pane emulator", fed)
        Log.i(LOG_TAG, "injected mostly-empty model (2J + home + 5 scattered live lines)")
    }

    private fun activePaneBlankOrPartiallyBlank(): Boolean {
        var hit = false
        compose.activityRule.scenario.onActivity { activity ->
            hit = viewModel(activity).panes.value.firstOrNull()
                ?.terminalState
                ?.visibleScreenIsBlankOrPartiallyBlank() ?: false
        }
        return hit
    }

    private fun activePaneMayHaveLostFrame(): Boolean {
        var hit = false
        compose.activityRule.scenario.onActivity { activity ->
            hit = viewModel(activity).panes.value.firstOrNull()
                ?.terminalState
                ?.visibleRenderMayHaveLostFrame() ?: false
        }
        return hit
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
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

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun viewModel(activity: MainActivity): TmuxSessionViewModel =
        ViewModelProvider(activity)[TmuxSessionViewModel::class.java]

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = viewModel(activity).connectionStatus.value
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

    private fun bannerRowCount(text: String): Int =
        Regex("$BANNER_MARKER row (\\d{2})").findAll(text).map { it.groupValues[1] }.toSet().size

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

    private suspend fun seedBeforeLaunch() {
        val key = readFixtureKey()
        seededKey = key
        try {
            waitForSshFixtureReady(SshKey.Pem(key))
            seedTmuxSession(key)
            seededHostRowTag = seedDockerHost(key)
        } catch (t: Throwable) {
            runCatching { cleanupRemoteTmuxSession(key) }
            throw t
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
                name = "issue1214-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1214 Mostly-Empty Reveal Heal",
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
        // A FULL multi-row banner running as an idle full-screen pane that emits no further
        // output — so tmux's `capture-pane` holds materially MORE than the injected 5-line
        // mostly-empty render (Gate 2), and the restore is visibly the full banner.
        val bannerLines = (1..40).joinToString("") {
            "$BANNER_MARKER row %02d filler abcdefghijklmnopqrstuvwxyz\\n".format(it)
        }
        val payload = buildString {
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

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1214_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1214_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun writeSummary(): File =
        writeText(
            "summary.txt",
            buildString {
                appendLine("test=MostlyEmptyModelHealsAtRevealJourneyE2eTest")
                appendLine("issue=1214")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine("fragment_marker=$FRAGMENT_MARKER")
                appendLine(
                    "scenario=attach a full 40-row banner, inject a mostly-empty model (2J + home + " +
                        "5 scattered live lines, >3 lines and <0.5 live-fraction) on the LIVE " +
                        "emulator, assert the transport stays Connected, then drive the " +
                        "same-dimension (no-op) resize heal",
                )
                appendLine(
                    "expectation=the full banner is re-seeded AT reveal (not ~4s later), no " +
                        "Reconnecting/Disconnected/Attaching surface",
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
        println("ISSUE1214_TIMING $line")
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
        const val LOG_TAG: String = "Issue1214MostlyEmptyReveal"
        const val DEVICE_DIR_NAME: String = "issue1214-mostly-empty-reveal-heal"
        const val SESSION_NAME: String = "issue1214-mostly-empty-proof"
        const val BANNER_MARKER: String = "ISSUE1214-BANNER"
        const val FRAGMENT_MARKER: String = "ISSUE1214-FRAG"

        const val OVERLAY_WATCH_MS: Long = 2_500L
        const val MIN_RESTORED_BANNER_ROWS: Int = 20

        // SHORT restore window: the reveal-time heal re-captures the pane within a single
        // `capture-pane` round-trip. Kept well below the idle passive-disconnect timeout so a
        // later transport reattach cannot mask a missing reveal-time heal on base.
        val HEAL_RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 10_000L else 6_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
