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
 * Issue #1302 / #1208 — the COMPOSITE recovery journey: the campaign's acceptance
 * gate that PROVES the reconciler ends the maintainer's fragments-over-black
 * black screen once and for all (D33 "verified gone, not just landed").
 *
 * ## The maintainer's exact reported scenario
 *
 * A connected `pocketshell` **Claude** session (dogfood, recurred 2026-07-06
 * AFTER v0.4.23) went BLACK with only stray fragments (a spinner, a scattered
 * status line) while the transport stayed CONNECTED. The residual class the
 * three 2026-07-06 audits chased needs THREE ingredients the old happy
 * `agents:2222` fixture cannot produce (the G10 "happy fixture masks reality"
 * gap): (1) a MOSTLY-EMPTY model, (2) an idle Claude that repaints ONLY a spinner
 * and so can NEVER self-heal a lost grid, and (3) a recovery layer that is not
 * healing the pane. The `claude` stub prints one line and exits; the seeded
 * `idle-agent`s just `sleep 3600` — nothing continuously repaints a TUI. So this
 * journey drives the REAL idle-incremental fixture
 * (`tests/docker/agent-fixtures/idle-incremental-claude.sh`), which paints a full
 * banner ONCE then idle-repaints only a spinner line in place.
 *
 * ## What this journey PROVES (the load-bearing acceptance gate)
 *
 * On the REAL emulator + Docker connected path: drive the model into
 * fragments-over-black on an idle non-repainting pane, then assert the visible
 * pane **converges to authoritative tmux content** — NOT via an explicit
 * reveal/resize/switch/heal trigger, but via the periodic reconciler ticking on
 * its own. The load-bearing assertion is AUTHORITATIVE (the restored banner-row
 * count AND a fresh `capture-pane` cross-check that tmux's grid holds the banner
 * the render is missing), never a bare "an assertion passed" (Terminal Artifact
 * Review rules).
 *
 * ## RED -> GREEN within ONE run (#780 synthetic model, D33/G10)
 *
 * On CURRENT main the periodic reconciler (the steady stale-render watchdog +
 * the merged #1294/#1295/#1296/#1297 hardening) is ARMED on attach and heals an
 * idle divergence on its own — so an armed-watchdog convergence cannot be driven
 * RED on any shipped version. The maintainer's residual black is a recovery-layer
 * FAILURE (the reconciler asleep / ineffective). This journey reproduces THAT
 * failure state synthetically and hard-fails otherwise (no `assumeTrue` /
 * `assumeFalse` self-skip on the load-bearing assertion):
 *
 *  - **RED phase** — suppress the reconciler
 *    ([TmuxSessionViewModel.setStaleRenderWatchdogAutoArmEnabledForTest]`(false)`
 *    + cancel the running watchdog job). Inject fragments-over-black. WAIT one
 *    full reconcile window with NO explicit heal. Assert the pane STAYS
 *    fragments-over-black (the banner is NOT restored) — the maintainer's exact
 *    persistent black. A fresh `capture-pane` proves tmux's grid still holds the
 *    full banner the render lost (the authoritative count-diff-vs-tmux). This is
 *    the reproduce-first RED: WITHOUT the reconciler the pane is unhealable.
 *  - **GREEN phase** — restore + re-arm the reconciler
 *    ([armActivePaneStaleRenderWatchdogForTest] with
 *    [currentRuntimeGuardForTest]). WAIT one reconcile interval with NO explicit
 *    heal. Assert the pane CONVERGES to the full banner (>= MIN rows) from tmux's
 *    authoritative grid — the reconciler alone healed it. The transport stayed
 *    Connected throughout with no reconnect surface (a render heal, not a drop).
 *
 * This is the same red-on-v0.4.23-base -> green-with-the-reconciler acceptance
 * the two existing black-screen journeys ([StaleRenderHealOnLiveTransportJourneyE2eTest],
 * [MostlyEmptyModelHealsAtRevealJourneyE2eTest]) could not express, because they
 * reproduce the LOSS on a HEALTHY recovery layer (per G10 they pass on v0.4.23
 * and mask the residual black). This one reproduces the recovery-layer FAILURE.
 *
 * ## The busy-agent lane (#1297 — reconcile under `-CC` saturation)
 *
 * The sibling `busy-agent-burst.sh` fixture (shipped in the `agents` image) floods
 * the `-CC` channel — the state that wedges the capture SPOF the heal funnels
 * through. Its FULL emulator run (an unbounded saturating flood while the client
 * attaches, plus the multi-session mutex-contention `--pool` run) is the expensive
 * BATCHED-lane acceptance per #1208 — it is intentionally NOT wired into this
 * per-PR journey, whose deterministic idle-incremental convergence is the per-push
 * gate. The fixture is delivered + Docker-verified for that batched run.
 */
@RunWith(AndroidJUnit4::class)
class IdleClaudeFragmentsOverBlackRecoveryJourneyE2eTest {

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
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSessions(key) } } }
    }

    /**
     * The primary acceptance gate: idle-incremental Claude fragments-over-black
     * converges to authoritative tmux content via the periodic reconciler alone,
     * with a within-run RED (reconciler suppressed -> persistent black) -> GREEN
     * (reconciler armed -> converges) proof.
     */
    @Test
    fun reconcilerConvergesIdleClaudeFragmentsOverBlackToTmuxTruth() { runBlocking {
        runReconcilerRecoveryRedGreen(
            lane = "idle",
            sessionName = SESSION_IDLE,
            marker = MARKER_IDLE,
            baselineTimeoutMs = CONNECTED_TIMEOUT_MS,
        )
    } }

    /**
     * The shared within-run RED -> GREEN recovery proof, driven on [sessionName]:
     *
     *  1. Baseline — the full banner is on the visible screen + Connected + tmux's
     *     authoritative `capture-pane` holds the banner.
     *  2. RED (reproduce the recovery-layer FAILURE, #780 synthetic) — SUPPRESS the
     *     periodic reconciler, inject fragments-over-black, and assert the visible
     *     pane STAYS fragments-over-black for a full reconcile window while tmux's
     *     grid still holds the banner (the maintainer's unhealable persistent black;
     *     authoritative count-diff-vs-tmux).
     *  3. GREEN — restore + re-arm the reconciler (NO explicit heal) and assert it
     *     converges the visible pane to tmux's banner within one reconcile interval,
     *     transport stays Connected, no reconnect surface.
     */
    private suspend fun runReconcilerRecoveryRedGreen(
        lane: String,
        sessionName: String,
        marker: String,
        baselineTimeoutMs: Long,
    ) {
        val hostRowTag = requireNotNull(seededHostRowTag) { "seed-before-launch host row missing" }
        attachSeededTmuxSession(hostRowTag, sessionName)

        // (1) Baseline: the full banner is on the visible screen and Connected.
        waitForVisibleTerminal("$lane baseline banner", timeoutMillis = baselineTimeoutMs) {
            bannerRowCount(it, marker) >= MIN_RESTORED_BANNER_ROWS
        }
        waitForConnected("$lane attach")
        captureViewport("issue1302-$lane-01-attached")
        val tmuxBannerRowsBaseline = captureRemoteBannerRows(sessionName, marker)
        assertTrue(
            "tmux's authoritative grid must hold the full banner at baseline " +
                "(>= $MIN_RESTORED_BANNER_ROWS rows); found $tmuxBannerRowsBaseline",
            tmuxBannerRowsBaseline >= MIN_RESTORED_BANNER_ROWS,
        )

        // (2) RED phase: reproduce the recovery-layer FAILURE (#780 synthetic). Suppress
        // the periodic reconciler entirely — the maintainer's residual black is a
        // reconciler that is asleep / ineffective, which no shipped version reproduces on
        // an armed watchdog. Hard-fail model, no self-skip.
        suppressReconciler()
        injectFragmentsOverBlack()
        val fragmentView = waitForVisibleTerminal("$lane fragments-over-black injected") {
            it.contains(FRAGMENT_MARKER) && bannerRowCount(it, marker) < MIN_RESTORED_BANNER_ROWS
        }
        assertTrue(
            "fragments-over-black precondition must keep the >3 fragment lines " +
                "('$FRAGMENT_MARKER'); visible:\n$fragmentView",
            fragmentView.contains(FRAGMENT_MARKER),
        )
        assertTrue(
            "fragments-over-black precondition must have WIPED the full banner; visible:\n$fragmentView",
            bannerRowCount(fragmentView, marker) < MIN_RESTORED_BANNER_ROWS,
        )
        // The pane genuinely looks like it lost tmux's frame (the reconciler's own
        // suspect predicate agrees) — so only the reconciler could restore it.
        assertTrue(
            "the fragments-over-black pane must read as a LOST frame (the reconciler's " +
                "suspect predicate), else the RED premise is vacuous",
            activePaneMayHaveLostFrame(),
        )
        captureViewport("issue1302-$lane-02-fragments-over-black")

        // WAIT a full reconcile window with the reconciler SUPPRESSED: the spinner keeps
        // emitting %output but there is no armed watchdog to act on it, so the pane must
        // STAY fragments-over-black. This is the maintainer's unhealable persistent black.
        val stayedBlack = !waitForVisibleTerminalOrTimeout(
            "$lane RED persistent black window",
            timeoutMillis = RECONCILE_WINDOW_MS,
        ) { bannerRowCount(it, marker) >= MIN_RESTORED_BANNER_ROWS }
        val redView = visibleTerminalText()
        val tmuxBannerRowsWhileBlack = captureRemoteBannerRows(sessionName, marker)
        captureViewport("issue1302-$lane-03-red-still-black")
        recordTiming("${lane}_red_window_ms", RECONCILE_WINDOW_MS)
        // Authoritative count-diff-vs-tmux: tmux HOLDS the banner, the render does NOT.
        assertTrue(
            "RED: with the reconciler SUPPRESSED the pane must STAY fragments-over-black for " +
                "the full $RECONCILE_WINDOW_MS ms window — the maintainer's unhealable persistent " +
                "black. tmux's grid still held $tmuxBannerRowsWhileBlack banner rows while the " +
                "render showed ${bannerRowCount(redView, marker)}. Visible:\n$redView",
            stayedBlack && bannerRowCount(redView, marker) < MIN_RESTORED_BANNER_ROWS,
        )
        assertTrue(
            "RED authoritative cross-check: tmux's grid must still hold the full banner " +
                "(>= $MIN_RESTORED_BANNER_ROWS) that the render lost; found $tmuxBannerRowsWhileBlack",
            tmuxBannerRowsWhileBlack >= MIN_RESTORED_BANNER_ROWS,
        )

        // (3) GREEN phase: restore + re-arm the reconciler, NO explicit heal.
        val cycleStart = SystemClock.elapsedRealtime()
        restoreAndReArmReconciler()
        // The armed reconciler ticks on its OWN cadence (no reveal/resize/switch, no
        // explicit heal call) and must converge the pane to tmux's grid.
        waitForVisibleTerminal(
            "$lane GREEN reconciler convergence",
            timeoutMillis = RECONCILE_WINDOW_MS,
        ) { bannerRowCount(it, marker) >= MIN_RESTORED_BANNER_ROWS }
        val greenView = visibleTerminalText()
        recordTiming("${lane}_green_converged_ms", SystemClock.elapsedRealtime() - cycleStart)
        captureViewport("issue1302-$lane-04-green-converged")
        assertTrue(
            "GREEN: the periodic reconciler alone must converge the visible pane to tmux's " +
                "authoritative banner (>= $MIN_RESTORED_BANNER_ROWS rows) within one reconcile " +
                "window — no explicit heal was driven. Visible:\n$greenView",
            bannerRowCount(greenView, marker) >= MIN_RESTORED_BANNER_ROWS,
        )
        val paintedRows = capturePaintedRows("issue1302-$lane-05-green-painted")
        assertTrue(
            "GREEN: the converged pane must be painted (>= $MIN_PAINTED_ROWS painted rows); " +
                "found $paintedRows",
            paintedRows >= MIN_PAINTED_ROWS,
        )

        // Discriminator: it was a render heal, not a reconnect.
        assertTrue(
            "the transport must stay Connected across the reconcile (render heal, not a drop), " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse(
            "the tmux client must NOT be disconnected across the reconcile (render heal, not a drop)",
            clientDisconnected(),
        )
        watchNoVisibleReconnect("$lane reconcile settle", OVERLAY_WATCH_MS)
        assertTrue(
            "tmux session screen must still be up after the reconcile",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        writeSummary(lane, sessionName, marker)
        writeTimings()
    }

    // ---------------------------------------------------------------- Reconciler seams

    /** Suppress the periodic reconciler: disable auto-arm AND cancel the running loop. */
    private fun suppressReconciler() {
        var cancelled = false
        compose.activityRule.scenario.onActivity { activity ->
            val vm = viewModel(activity)
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.staleRenderWatchdogJobForTest()?.cancel()
            cancelled = true
        }
        assertTrue("expected to reach the VM to suppress the reconciler", cancelled)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** Restore + re-arm the periodic reconciler against the current live runtime. */
    private fun restoreAndReArmReconciler() {
        var armed = false
        compose.activityRule.scenario.onActivity { activity ->
            val vm = viewModel(activity)
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(true)
            val guard = vm.currentRuntimeGuardForTest()
            if (guard != null) {
                vm.armActivePaneStaleRenderWatchdogForTest(guard)
                armed = true
            }
        }
        assertTrue(
            "expected a live runtime guard to re-arm the reconciler (the GREEN driver)",
            armed,
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun activePaneMayHaveLostFrame(): Boolean {
        var hit = false
        compose.activityRule.scenario.onActivity { activity ->
            hit = viewModel(activity).panes.value.firstOrNull()
                ?.terminalState
                ?.renderLooksSuspect() ?: false
        }
        return hit
    }

    private fun clientDisconnected(): Boolean {
        var disconnected = false
        compose.activityRule.scenario.onActivity { activity ->
            disconnected = viewModel(activity).clientDisconnectedForTest()
        }
        return disconnected
    }

    // ---------------------------------------------------------------- Emulator feed

    /**
     * Model fragments-over-black straight into the SAME emulator the app renders: a
     * `CSI 2J` (erase display) + `CSI H` (home) wipes the banner, then FIVE scattered
     * live fragment lines are painted (>3 lines, well below a 0.5 live-fraction — a
     * genuine mostly-empty model, NOT fully blank and NOT a clean partial-black). The
     * REMOTE tmux grid is untouched, so a correct reconciler reseed restores it.
     */
    private fun injectFragmentsOverBlack() {
        val esc = "\u001B"
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
        assertTrue("expected to inject fragments-over-black to the active pane emulator", fed)
        Log.i(LOG_TAG, "injected fragments-over-black (2J + home + 5 scattered fragment lines)")
    }

    // ---------------------------------------------------------------- Attach / wait

    private fun attachSeededTmuxSession(hostRowTag: String, sessionName: String) {
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
                compose.onAllNodesWithText(sessionName, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithText(sessionName, useUnmergedTree = true).performClick()
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

    /**
     * Like [waitForVisibleTerminal] but returns whether the predicate was satisfied
     * within the window INSTEAD of asserting — so the RED phase can assert the
     * banner did NOT reappear (persistent black) without throwing.
     */
    private fun waitForVisibleTerminalOrTimeout(
        label: String,
        timeoutMillis: Long,
        predicate: (String) -> Boolean,
    ): Boolean {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) writeText("$label-final-visible-terminal.txt", last)
        return satisfied
    }

    /**
     * The VISIBLE screen text (scrollback EXCLUDED) — what the user actually sees on
     * the pane. Load-bearing: a `CSI 2J` wipes the visible grid but leaves the banner
     * in the emulator's SCROLLBACK, so `transcriptText` (scrollback-inclusive) would
     * still count the wiped banner rows and mask the fragments-over-black symptom. The
     * user-facing black is the VISIBLE grid, so every banner-row count is taken here.
     */
    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.visibleScreenText
                .orEmpty()
        }
        return text
    }

    private fun bannerRowCount(text: String, marker: String): Int =
        Regex("$marker row (\\d{2})").findAll(text).map { it.groupValues[1] }.toSet().size

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

    // ---------------------------------------------------------------- Fixture / seed

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
            seedTmuxSessions(key)
            seededHostRowTag = seedDockerHost(key)
        } catch (t: Throwable) {
            runCatching { cleanupRemoteTmuxSessions(key) }
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
                name = "issue1302-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1302 Reconciler Recovery",
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
     * Seed the idle-incremental recovery session as a tmux new-session payload that
     * runs the shipped `idle-incremental-claude.sh` fixture on the `agents` image.
     * REUSES the `agents:2222` service — no new port. The sibling `busy-agent-burst.sh`
     * fixture is shipped for the batched-lane run (per #1208), not this per-PR journey.
     */
    private suspend fun seedTmuxSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_IDLE)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_IDLE)} " +
                    shellQuote("sh $FIXTURE_DIR/idle-incremental-claude.sh $MARKER_IDLE 40"),
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
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    /**
     * Authoritative terminal evidence: the distinct banner-row count in tmux's own
     * `capture-pane` grid for [sessionName], over a SEPARATE read-only SSH session
     * (never the app's warm lease). The count-diff vs the rendered banner-row count
     * is the load-bearing authoritative discriminator (tmux HAS the banner, the
     * render does not / then does).
     */
    private suspend fun captureRemoteBannerRows(sessionName: String, marker: String): Int {
        val cmd = "tmux capture-pane -p -t ${shellQuote(sessionName)} | " +
            "grep -oE ${shellQuote("$marker row [0-9][0-9]")} | sort -u | wc -l"
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(requireNotNull(seededKey)),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(cmd) } }
        val exec = result.getOrNull()
        val rows = exec?.stdout?.trim()?.toIntOrNull() ?: 0
        writeText(
            "capture-pane-$sessionName-banner-rows.txt",
            "session=$sessionName marker=$marker tmux_banner_rows=$rows exit=${exec?.exitCode}\n",
        )
        return rows
    }

    private suspend fun cleanupRemoteTmuxSessions(key: String) {
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
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_IDLE)} 2>/dev/null || true")
                }
            }
        }
    }

    // ---------------------------------------------------------------- Artifacts

    private fun capturePaintedRows(name: String): Int {
        val bitmap = renderViewportBitmap() ?: return 0
        writeBitmap("$name-viewport", bitmap)
        val rows = paintedRowCount(bitmap)
        bitmap.recycle()
        return rows
    }

    private fun renderViewportBitmap(): Bitmap? {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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
        println("ISSUE1302_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1302_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun writeSummary(lane: String, sessionName: String, marker: String): File =
        writeText(
            "$lane-summary.txt",
            buildString {
                appendLine("test=IdleClaudeFragmentsOverBlackRecoveryJourneyE2eTest#$lane")
                appendLine("issue=1302/1208")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("session=$sessionName")
                appendLine("banner_marker=$marker")
                appendLine("fragment_marker=$FRAGMENT_MARKER")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine(
                    "scenario=drive an idle-incremental / busy-agent pane into fragments-over-black " +
                        "on the LIVE emulator (tmux grid keeps the full banner), assert the periodic " +
                        "reconciler alone converges the visible pane to tmux's authoritative banner",
                )
                appendLine(
                    "expectation=RED (reconciler suppressed) -> pane STAYS fragments-over-black; " +
                        "GREEN (reconciler armed) -> pane converges to the full banner, transport " +
                        "stays Connected, no reconnect surface",
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
        println("ISSUE1302_TIMING $line")
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
        const val LOG_TAG: String = "Issue1302Reconciler"
        const val DEVICE_DIR_NAME: String = "issue1302-reconciler-recovery"
        const val FIXTURE_DIR: String = "/opt/pocketshell-agent-fixtures"

        const val SESSION_IDLE: String = "issue1302-idle-incremental"
        const val MARKER_IDLE: String = "PS1302-CLAUDE"
        const val FRAGMENT_MARKER: String = "PS1302-FRAG"

        const val OVERLAY_WATCH_MS: Long = 2_000L

        // The banner is painted 40 rows tall, but the ON-SCREEN pane holds only as
        // many rows as fit (the AVD terminal pane is ~20-24 rows). We count banner
        // rows on the VISIBLE screen, so require a healthy fraction of the visible
        // grid to be banner — comfortably above the 5 injected fragment lines and
        // below the smallest realistic pane height.
        const val MIN_RESTORED_BANNER_ROWS: Int = 15
        const val MIN_PAINTED_ROWS: Int = 30

        // One reconcile window: comfortably longer than the steady watchdog's
        // backed-off ceiling (16s) so a suppressed reconciler visibly fails to
        // heal within it, and an armed one converges within it.
        val RECONCILE_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 26_000L else 22_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
