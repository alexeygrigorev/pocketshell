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
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.OVERFLOW_RECOVERY_MAX_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_SURFACE_RECREATE_TAG
import com.pocketshell.app.tmux.TmuxSessionLatencyTelemetry
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.terminal.bridge.TerminalSeedGateOverflowException
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #1205 — DEVICE-TRUTH journey for the maintainer's black-screen class where a pane
 * whose per-pane delivery backlog (or 2 MB seed gate) OVERFLOWS under a sustained high-output
 * burst latched a permanently-dead `surfaceError` "Recreate terminal" card instead of
 * self-healing.
 *
 * ## The bug (2026-07-03 black-screen audit on #874)
 *
 * Per-pane `%output` delivery is a bounded `Channel(4096)` fed by non-blocking `trySend`. Under
 * a sustained Claude alt-screen redraw burst with a contended main thread the channel overflows
 * and drops. On the FIRST dropped frame the app cancelled the pane producer, detached the pane,
 * and latched `surfaceError` — a permanently dead pane the blank/stale watchdog and heal oracle
 * both early-return on, so nothing self-heals and the user must tap "Recreate terminal". The
 * 2 MB seed-gate overflow latched the same way. The KDoc on
 * [com.pocketshell.core.tmux.TmuxClient.outputBacklogOverflows] already prescribes the correct
 * behavior: this is LOCAL renderer backpressure, NOT a transport disconnect, so recover by
 * RESEEDING from `capture-pane` — a transient burst costs one reseed, not the pane.
 *
 * ## What this journey PROVES on the REAL on-device path (D33 / G4 / G10)
 *
 * The JVM/Robolectric proof ([com.pocketshell.app.tmux.PaneOutputOverflowRecoveryTest]) covers
 * the state-machine logic and the real `trySend`-drop drain. This is the on-device residual the
 * reviewer flagged BLOCKED: the RENDER correctness of the detach → reattach → reseed ordering on
 * a real terminal emulator against real Docker tmux. Attach to a real `agents`-fixture tmux
 * session running a full-viewport banner, then:
 *
 *  - **[backlogOverflowSelfHealsWithContentInsteadOfDeadSurfaceErrorCard]** — the maintainer's
 *    reported LIVE-output backlog class. Wipe the LOCAL emulator to black (the REMOTE tmux grid
 *    keeps the full banner → transport GUARANTEED LIVE), then trip a REAL backlog overflow through
 *    the same private handler the `outputBacklogOverflows` collector calls. GREEN: NO dead
 *    `surfaceError` "Recreate terminal" card, a FRESH `capture-pane` reseed fires (the
 *    recompose-immune "reseed actually ran" signal), the banner content is RESTORED, the producer
 *    is REATTACHED to the LIVE client (live %output resumes), and the session stays Connected with
 *    NO reconnect surface. On base the pane latches `surfaceError` → the card covers the terminal
 *    (RED).
 *  - **[seedGateOverflowSelfHealsInsteadOfDeadSurfaceErrorCard]** — the OTHER overflow class
 *    (the 2 MB seed-gate feed-failure), on-device (G2 class coverage on the real path).
 *  - **[sustainedUnrecoverableOverflowExhaustsBudgetAndLandsOnRecoveryCard]** — the GIVE-UP
 *    class: a channel that keeps overflowing after each reseed must NOT loop into a reseed storm.
 *    After the bounded retry budget ([OVERFLOW_RECOVERY_MAX_ATTEMPTS]) is exhausted the pane DOES
 *    land on the actionable `surfaceError` card exactly once.
 *
 * No `Assume.assumeFalse(isRunningOnCi())` on the load-bearing assertions — the failing state is
 * injected synthetically (the #780 model: a real 4096-deep channel overflow / 2 MB feed can't be
 * forced deterministically on the swiftshader emulator), and everything DOWNSTREAM of the trigger
 * — drain → `capture-pane` reseed → producer reattach → render — runs on the REAL client/transport.
 * Wired into `scripts/ci-journey-suite.sh` so it RUNS on the per-PR emulator-journey job.
 */
@RunWith(AndroidJUnit4::class)
class PaneOutputOverflowRecoveryJourneyE2eTest {

    // Issue #788/#848: launch-owned `createAndroidComposeRule<MainActivity>()` (NOT
    // `createEmptyComposeRule()` + a hand-rolled `ActivityScenario.launch`) so the Compose test
    // clock drives the SAME foreground MainActivity the Termux `TerminalView` interop child is
    // placed into. The remote banner tmux session + DB host row are seeded BEFORE launch by
    // [SeedBeforeLaunchRule] in the RuleChain below.
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String

    private suspend fun seedBeforeLaunch() {
        BackgroundGraceTestOverride.setForTest(null)
        TmuxSessionLatencyTelemetry.resetForTest()
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedBannerSession(key)
        hostRowTag = seedDockerHost(key)
    }

    @After
    fun tearDown() {
        BackgroundGraceTestOverride.setForTest(null)
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    // -----------------------------------------------------------------------------------------
    // (1) Live-output backlog overflow — the maintainer's reported class — self-heals with
    //     content, NOT a dead surfaceError card.
    // -----------------------------------------------------------------------------------------

    @Test
    fun backlogOverflowSelfHealsWithContentInsteadOfDeadSurfaceErrorCard() { runBlocking {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial banner") { it.contains(BANNER_MARKER) }
        waitForConnected("initial attach")
        val paneId = firstVisiblePaneId()
        assertFalse("precondition: pane not errored before overflow", activePaneSurfaceError())
        capturePaintedRows("issue1205-backlog-00-attached")

        // Wipe the LOCAL emulator to black so the reseed's content-restoration is OBSERVABLE.
        // The REMOTE tmux grid still holds the banner → the transport is GUARANTEED LIVE, and a
        // fresh `capture-pane` will restore it. Real ESC (0x1B) fed straight into the emulator.
        feedFrameToEmulator("[2J[H")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        capturePaintedRows("issue1205-backlog-01-black")

        val capturesBefore = capturePaneCount(paneId)

        // ===== Trip a REAL live-output backlog overflow (the reported class). =====
        tripBacklogOverflow(paneId, droppedEvents = 1)

        // ----- GREEN: NO dead surfaceError "Recreate terminal" card. -----
        assertHealsNoSurfaceErrorCard("backlog overflow")

        // ----- GREEN: a FRESH capture-pane reseed actually ran (recompose-immune signal). -----
        val capturesAfter = waitForNewCapturePane(
            "backlog-reseed",
            paneId,
            baseline = capturesBefore,
            timeoutMillis = RESTORE_TIMEOUT_MS,
        )
        assertTrue(
            "REGRESSION (#1205): a backlog overflow must RESEED the pane from tmux's server-side " +
                "grid (a fresh capture-pane), not latch surfaceError; captures before=$capturesBefore " +
                "after=$capturesAfter",
            capturesAfter > capturesBefore,
        )

        // ----- GREEN: the banner content is RESTORED (self-heal WITH content). -----
        val visibleAfter = waitForVisibleTerminal(
            "banner restored",
            timeoutMillis = RESTORE_TIMEOUT_MS,
        ) { bannerRowCount(it) >= MIN_RESTORED_BANNER_ROWS }
        assertTrue(
            "the reseed must restore the FULL banner content (>= $MIN_RESTORED_BANNER_ROWS rows); " +
                "found ${bannerRowCount(visibleAfter)}",
            bannerRowCount(visibleAfter) >= MIN_RESTORED_BANNER_ROWS,
        )
        capturePaintedRows("issue1205-backlog-02-healed")

        // ----- GREEN: producer REATTACHED to the LIVE client (live %output resumes). -----
        assertProducerReattachedToLiveClient(paneId, "backlog overflow")

        // ----- DISCRIMINATOR: still Connected, no reconnect (renderer backpressure, not a drop). -----
        assertNoVisibleReconnect("post-backlog-heal")
        assertTrue(
            "session must stay Connected after the overflow heal (renderer backpressure, no " +
                "reconnect), observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("the tmux client must NOT be disconnected", clientDisconnected())
        writeSummary("backlog", "live-output pane delivery backlog Channel(4096) overflow")
    } }

    // -----------------------------------------------------------------------------------------
    // (2) Seed-gate 2 MB overflow — the OTHER class (G2), on the real path.
    // -----------------------------------------------------------------------------------------

    @Test
    fun seedGateOverflowSelfHealsInsteadOfDeadSurfaceErrorCard() { runBlocking {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial banner") { it.contains(BANNER_MARKER) }
        waitForConnected("initial attach")
        val paneId = firstVisiblePaneId()
        assertFalse("precondition: pane not errored before overflow", activePaneSurfaceError())

        feedFrameToEmulator("[2J[H")
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val capturesBefore = capturePaneCount(paneId)

        // ===== Trip a REAL 2 MB seed-gate overflow (the feed-failure class). =====
        tripSeedGateOverflow(paneId)

        assertHealsNoSurfaceErrorCard("seed-gate overflow")
        val capturesAfter = waitForNewCapturePane(
            "seedgate-reseed",
            paneId,
            baseline = capturesBefore,
            timeoutMillis = RESTORE_TIMEOUT_MS,
        )
        assertTrue(
            "REGRESSION (#1205): a seed-gate overflow must RESEED from capture-pane, not latch " +
                "surfaceError; captures before=$capturesBefore after=$capturesAfter",
            capturesAfter > capturesBefore,
        )
        val visibleAfter = waitForVisibleTerminal(
            "banner restored",
            timeoutMillis = RESTORE_TIMEOUT_MS,
        ) { bannerRowCount(it) >= MIN_RESTORED_BANNER_ROWS }
        assertTrue(
            "the seed-gate reseed must restore the FULL banner content; found " +
                "${bannerRowCount(visibleAfter)}",
            bannerRowCount(visibleAfter) >= MIN_RESTORED_BANNER_ROWS,
        )
        assertProducerReattachedToLiveClient(paneId, "seed-gate overflow")
        assertNoVisibleReconnect("post-seedgate-heal")
        assertTrue(
            "session must stay Connected after the seed-gate heal, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        writeSummary("seedgate", "2 MB seed-gate live-buffer overflow (feed-failure)")
    } }

    // -----------------------------------------------------------------------------------------
    // (3) Bounded-retry EXHAUSTION — a still-saturated channel must NOT reseed-storm; after the
    //     budget it lands on the actionable surfaceError "Recreate terminal" card.
    // -----------------------------------------------------------------------------------------

    @Test
    fun sustainedUnrecoverableOverflowExhaustsBudgetAndLandsOnRecoveryCard() { runBlocking {
        attachSeededTmuxSession(hostRowTag)
        waitForVisibleTerminal("initial banner") { it.contains(BANNER_MARKER) }
        waitForConnected("initial attach")
        val paneId = firstVisiblePaneId()
        assertFalse("precondition: pane not errored before overflow", activePaneSurfaceError())

        // The first OVERFLOW_RECOVERY_MAX_ATTEMPTS overflows RESEED within the budget. Wait for each
        // recovery to FULLY complete — a fresh capture-pane reseed AND the in-flight slot RELEASED
        // (the deterministic [waitUntilOverflowRecoveryIdle] seam) — before the next trip, so the
        // next overflow is counted against the budget rather than de-duped as a still-in-flight burst
        // signal. No local black-wipe here (unlike the self-heal tests): a black TerminalView at the
        // exhaustion moment would race the latch->card surface swap; the reseed's capture-pane
        // telemetry fires regardless, so the budget still accumulates deterministically.
        repeat(OVERFLOW_RECOVERY_MAX_ATTEMPTS) { attempt ->
            val capturesBefore = capturePaneCount(paneId)
            tripBacklogOverflow(paneId, droppedEvents = attempt + 1)
            val capturesAfter = waitForNewCapturePane(
                "exhaustion-reseed-${attempt + 1}",
                paneId,
                baseline = capturesBefore,
                timeoutMillis = RESTORE_TIMEOUT_MS,
            )
            assertTrue(
                "attempt ${attempt + 1} is WITHIN budget — the pane must reseed (fresh capture), " +
                    "not latch; captures before=$capturesBefore after=$capturesAfter",
                capturesAfter > capturesBefore,
            )
            waitUntilProducerActive(paneId, "attempt ${attempt + 1} reattach")
            // Wait deterministically for the recovery's `finally` to release the in-flight slot,
            // so the next trip is counted against the budget (not de-duped as IN_FLIGHT).
            waitUntilOverflowRecoveryIdle(paneId, "attempt ${attempt + 1}")
            assertFalse(
                "attempt ${attempt + 1} is within budget — the pane must NOT be latched yet",
                activePaneSurfaceError(),
            )
        }

        // The (budget + 1)th overflow, with the channel STILL saturated, exhausts the budget — the
        // pane now lands on the actionable card exactly once (no infinite reseed loop).
        tripBacklogOverflow(paneId, droppedEvents = 99)

        // LOAD-BEARING (G6): the `surfaceError` STATE that `latchPaneSurfaceError` sets — the card
        // (`TerminalSurfaceErrorState`) is a pure render of exactly this flag, gated on nothing else
        // for the shown pane. On base a FIRST overflow already latches this; with the fix ONLY the
        // (budget+1)th does, after two silent reseeds. Assert it flips true and STAYS latched (no
        // reseed storm un-latches it).
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) { activePaneSurfaceError() }
        assertTrue(
            "Issue #1205: after $OVERFLOW_RECOVERY_MAX_ATTEMPTS reseeds a STILL-saturated channel " +
                "must land on the bounded surfaceError give-up state (no infinite reseed storm)",
            activePaneSurfaceError(),
        )

        // And the actionable "Recreate terminal" card renders from that state (the user-visible
        // give-up affordance). Capture the artifact regardless so a card-render gap is diagnosable.
        val cardShown = runCatching {
            compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
                compose.onAllNodesWithTag(TMUX_TERMINAL_SURFACE_ERROR_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            true
        }.getOrDefault(false)
        capturePaintedRows("issue1205-exhaustion-card")
        assertTrue(
            "Issue #1205: the bounded-retry exhaustion must render the actionable 'Recreate " +
                "terminal' card (surfaceError=${activePaneSurfaceError()})",
            cardShown,
        )
        compose.onNodeWithTag(TMUX_TERMINAL_SURFACE_ERROR_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(TMUX_TERMINAL_SURFACE_RECREATE_TAG, useUnmergedTree = true).assertExists()
        writeSummary("exhaustion", "bounded-retry exhaustion lands on the Recreate-terminal card")
    } }

    // -------------------------------------------------------------------- Overflow triggers

    /**
     * Trip a REAL live-output backlog overflow via the SAME private handler the
     * `outputBacklogOverflows` collector calls ([TmuxSessionViewModel.handleTerminalOutputBacklogOverflow]).
     * Only the trigger is synthetic (the #780 model); drain → capture-pane reseed → producer
     * reattach all run on the REAL client/transport.
     */
    private fun tripBacklogOverflow(paneId: String, droppedEvents: Int) {
        compose.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .handleTerminalOutputBacklogOverflowForTest(paneId, droppedEvents)
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /** Trip a REAL 2 MB seed-gate overflow via the same production handler its feed-failure calls. */
    private fun tripSeedGateOverflow(paneId: String) {
        compose.activityRule.scenario.onActivity { activity ->
            ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .handleTerminalSeedGateOverflowForTest(
                    paneId = paneId,
                    overflow = TerminalSeedGateOverflowException(
                        pendingBytes = 2_000_000,
                        incomingBytes = 100_000,
                        maxBytes = 2_097_152,
                    ),
                )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    // -------------------------------------------------------------------- Assertions

    private fun assertHealsNoSurfaceErrorCard(label: String) {
        // Give the async recovery a beat to run, then assert the dead card never appears. If the
        // fix regressed, the pane latches surfaceError SYNCHRONOUSLY, so a short settle is enough.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(500)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(
            "REGRESSION (#1205): $label must NOT latch the dead surfaceError card — it must " +
                "reseed-and-reattach",
            0,
            compose.onAllNodesWithTag(TMUX_TERMINAL_SURFACE_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "REGRESSION (#1205): $label must NOT show a 'Recreate terminal' button",
            0,
            compose.onAllNodesWithTag(TMUX_TERMINAL_SURFACE_RECREATE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertFalse(
            "REGRESSION (#1205): $label must NOT leave the pane in surfaceError",
            activePaneSurfaceError(),
        )
    }

    private fun assertProducerReattachedToLiveClient(paneId: String, label: String) {
        waitUntilProducerActive(paneId, label)
        var producerClient: Int? = null
        var liveClient: Int? = null
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            producerClient = vm.paneProducerClientIdentityForTest(paneId)
            liveClient = vm.currentClientIdentityForTest()
        }
        assertTrue(
            "REGRESSION (#1205): after the $label heal the pane producer must be REATTACHED and " +
                "bound to the LIVE client (producer=$producerClient live=$liveClient) — live %output " +
                "resumes with no user action",
            producerClient != null && producerClient == liveClient,
        )
    }

    private fun waitUntilProducerActive(paneId: String, label: String) {
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            var active = false
            compose.activityRule.scenario.onActivity { activity ->
                active = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                    .paneProducerActiveForTest(paneId)
            }
            active
        }
    }

    /** Wait deterministically for an in-flight overflow recovery to fully complete (slot released). */
    private fun waitUntilOverflowRecoveryIdle(paneId: String, label: String) {
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            var inFlight = true
            compose.activityRule.scenario.onActivity { activity ->
                inFlight = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                    .paneOverflowRecoveryInFlightForTest(paneId)
            }
            !inFlight
        }
    }

    // -------------------------------------------------------------------- VM introspection

    private fun firstVisiblePaneId(): String {
        var paneId: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            paneId = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .panes.value.firstOrNull()?.paneId
        }
        return checkNotNull(paneId) { "no visible pane after attach" }
    }

    private fun activePaneSurfaceError(): Boolean {
        var errored = false
        compose.activityRule.scenario.onActivity { activity ->
            errored = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .panes.value.firstOrNull()?.surfaceError ?: false
        }
        return errored
    }

    private fun clientDisconnected(): Boolean {
        var disconnected = false
        compose.activityRule.scenario.onActivity { activity ->
            disconnected = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .clientDisconnectedForTest()
        }
        return disconnected
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus = TmuxSessionViewModel.ConnectionStatus.Idle
        compose.activityRule.scenario.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus.value
        }
        return status
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

    // -------------------------------------------------------------------- Emulator feed

    private fun feedFrameToEmulator(frame: String) {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        var fed = false
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val emulator = view.mEmulator ?: return@onActivity
            emulator.append(bytes, bytes.size)
            view.invalidate()
            fed = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("expected to feed the frame to the live emulator", fed)
    }

    // -------------------------------------------------------------------- Attach / wait

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
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
                .findTerminalView()?.currentSession?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no disconnect band for $label", 0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no Tap Reconnect button for $label", 0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Reconnecting", "Disconnected", "Tap Reconnect").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label", 0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    /** Count distinct numbered banner rows (`ISSUE1205-BANNER row NN`) in the visible buffer. */
    private fun bannerRowCount(text: String): Int =
        Regex("$BANNER_MARKER row (\\d{2})").findAll(text).map { it.groupValues[1] }.toSet().size

    // -------------------------------------------------------------------- Fixture

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }

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
                name = "issue1205-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1205 Overflow Recovery",
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

    /** Seed a full-viewport banner tmux session so a post-overflow `capture-pane` restores content. */
    private suspend fun seedBannerSession(key: String) {
        val bannerLines = (1..40).joinToString("") {
            "$BANNER_MARKER row %02d filler abcdefghijklmnopqrstuvwxyz\\n".format(it)
        }
        val payload = "printf '$bannerLines'; while true; do sleep 3600; done"
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
            "expected tmux banner seeding to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded banner session: ${exec?.stdout?.trim()}")
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
                session.use { it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true") }
            }
        }
    }

    // -------------------------------------------------------------------- Artifacts

    private fun capturePaintedRows(name: String): Int {
        val bitmap = renderViewportBitmap() ?: return 0
        writeBitmap("$name-viewport", bitmap)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
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

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1205_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1205_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeSummary(scenario: String, overflowClass: String): File =
        writeText(
            "issue1205-$scenario-summary.txt",
            buildString {
                appendLine("test=PaneOutputOverflowRecoveryJourneyE2eTest#$scenario")
                appendLine("issue=1205")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT), full-viewport banner")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine("overflow_class=$overflowClass")
                appendLine(
                    "scenario=attach a full-viewport banner, wipe the LOCAL emulator to black " +
                        "(remote tmux grid keeps the banner -> transport GUARANTEED LIVE), trip a REAL " +
                        "$overflowClass, then assert the pane SELF-HEALS (fresh capture-pane reseed, " +
                        "banner content restored, producer reattached to the live client, no dead " +
                        "surfaceError card, still Connected) — OR, for the exhaustion scenario, that a " +
                        "sustained un-recoverable overflow lands on the actionable Recreate-terminal card",
                )
            },
        )

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
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
        const val LOG_TAG: String = "Issue1205Overflow"
        const val DEVICE_DIR_NAME: String = "issue1205-overflow-recovery"
        const val SESSION_NAME: String = "issue1205-overflow-proof"
        const val BANNER_MARKER: String = "ISSUE1205-BANNER"

        const val MIN_RESTORED_BANNER_ROWS: Int = 20

        val RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
