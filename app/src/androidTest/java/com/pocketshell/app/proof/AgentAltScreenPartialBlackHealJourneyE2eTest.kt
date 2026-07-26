package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.HealAttemptReason
import com.pocketshell.app.tmux.HealAttemptResult
import com.pocketshell.app.tmux.HealOutcome
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.app.tmux.ActivePaneRenderOwnerSnapshotForTest
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.launch
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
 * Issue #1138 — the DISCRIMINATING journey for the maintainer's SEMI/PARTIAL-black on a live
 * AGENT alt-screen pane (v0.4.19 dogfood, 2026-07-01, reproduced on BOTH Codex and Claude).
 *
 * ## The maintainer's report
 *
 * A connected agent session showed a mostly-BLACK terminal with ONLY the agent's live status
 * line (`∘ Working 7`) + cursor near the bottom, the upper ALT-SCREEN rows black. The agent
 * redraws with cursor-addressed writes, so only its live status line repaints locally while
 * the upper rows the pane lost stay black. An alt-screen agent frame is SPARSE (a header + a
 * large blank conversation area + an input/status line), so its non-blank content is small and
 * the surviving status line is a LARGE fraction of it — ABOVE the #966 25% divergence ceiling.
 * The v0.4.18 steady-state stale-render watchdog's ONLY heal predicate was
 * [com.pocketshell.core.terminal.ui.TerminalSurfaceState.visibleScreenDivergesFromCapture], so
 * it read the pane "healthy" and never fired: the pane sat mostly-black on a LIVE transport.
 *
 * ## What this journey PROVES (D33 / G10 — real path, guaranteed-live transport)
 *
 * Seed a tmux session running a SPARSE ALT-SCREEN app (header + blank body + status), attach,
 * then inject the partial-black (clear + only the live status line) straight into the SAME
 * live emulator the app renders — the REMOTE tmux grid keeps the full sparse alt frame, so the
 * transport is GUARANTEED LIVE. Then assert:
 *  - **Symptom present:** the active pane reads `visibleScreenIsPartiallyBlank()` (the exact
 *    #1138 state) — the #941 reveal/send heals would fire IF triggered, but on a passively-
 *    observed streaming pane there is NO switch/send, so the steady-state watchdog is the only
 *    net. On base its divergence-only predicate MISSES this sparse frame (unit-proven RED in
 *    `PartialBlackPaneHealTest#steadyStateWatchdogHealsPartialBlackAltScreenAgentPane`).
 *  - **Transport ALIVE:** `connectionStatus` stays `Connected`, no reconnect surface.
 *  - **Render HEALS:** one steady-state watchdog tick re-seeds the pane from tmux's
 *    authoritative `capture-pane` and the FULL alt-screen frame (its header marker + the upper
 *    rows) re-renders — GREEN with the union predicate `visibleRenderLostFrameVsCapture`.
 *
 * No `Assume.assumeFalse(isRunningOnCi())` on the load-bearing assertions — it RUNS on the
 * per-PR emulator-journey job once wired into `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class AgentAltScreenPartialBlackHealJourneyE2eTest {

    // Issue #788/#848: `createAndroidComposeRule<MainActivity>()` (NOT
    // `createEmptyComposeRule()` + a hand-rolled `ActivityScenario.launch`) so the
    // Compose test clock drives the SAME foreground MainActivity the Termux
    // `TerminalView` AndroidView interop child is placed into — fixing the #470
    // swiftshader interop-placement / enumeration stall. The rule launches
    // MainActivity in its own `before()` phase, so the DB host row + remote
    // alt-screen tmux session must be seeded BEFORE launch — done by
    // [SeedBeforeLaunchRule] in the RuleChain below.
    val compose = createAndroidComposeRule<MainActivity>()

    // Deterministic seed-before-launch ordering via RuleChain (outer `before()`
    // runs first):
    //   1. PreGrantPermissionsRule — grant runtime perms before launch (no focus
    //      theft from the system GrantPermissionsActivity, #470 blocker #1).
    //   2. SeedBeforeLaunchRule     — seed the remote sparse alt-screen tmux
    //      session + DB host row (resolving [fixtureKey] + [hostRowTag]) BEFORE
    //      the compose rule launches MainActivity so it reads a populated DB.
    //   3. compose                  — launch MainActivity.
    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    // Resolved during [seedBeforeLaunch] (the rule's `before()` phase), read by
    // the test body after launch.
    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String

    /**
     * Seed lambda invoked by [SeedBeforeLaunchRule] in the rule's `before()`
     * phase, BEFORE [compose] launches MainActivity. Establishes the remote
     * sparse alt-screen tmux session + DB host row so MainActivity's first
     * `hostDao.getAll()` read sees the populated DB — exactly the work the body
     * used to do inline before `ActivityScenario.launch`.
     */
    private suspend fun seedBeforeLaunch() {
        BackgroundGraceTestOverride.setForTest(null)
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedAltScreenSession(key)
        hostRowTag = seedDockerHost(key)
    }

    @After
    fun tearDown() {
        BackgroundGraceTestOverride.setForTest(null)
        if (::fixtureKey.isInitialized) {
            runCatching { runBlocking { cleanupRemoteTmuxSession(fixtureKey) } }
        }
    }

    @Test
    fun steadyStateWatchdogHealsPartialBlackAltScreenAgentPane() { runBlocking {
        // Issue #788/#848: the sparse alt-screen tmux session + DB host row were
        // already seeded BEFORE MainActivity launched, by [seedBeforeLaunch] in
        // the rule chain's `before()`. Attach to it from the freshly-launched app.
        attachSeededTmuxSession(hostRowTag)

        // Baseline: the sparse alt-screen frame is in the live pane and the session is Connected.
        waitForVisibleTerminal("initial alt frame") { it.contains(FRAME_MARKER) }
        waitForConnected("initial attach")
        capturePaintedRows("issue1138-00-attached")

        // The manual one-pass assertion must own the whole injection→capture→oracle→apply
        // interval. Disable future production auto-arms and cancel+JOIN the current watchdog
        // before injecting the partial-black frame.
        pauseAutomaticStaleRenderWatchdog()
        val settledOwner = waitForSettledActiveRenderOwner()

        // ===== Inject the partial-black on the LIVE, retained emulator. =====
        // Clear the alt screen and paint ONLY the live status line (cursor-addressed) — the
        // maintainer's semi-black: upper rows black, only the status line survives. The REMOTE
        // tmux grid keeps the full sparse frame, so the transport stays GUARANTEED LIVE.
        // Real ESC (0x1B) bytes: this feeds the LOCAL emulator directly via
        // emulator.append() (no remote printf to expand octal \033), so the CSI
        // control sequences must carry an actual ESC or they paint as literal
        // text and never clear the screen (the injected partial-black is a no-op).
        val esc = ""
        val injectedOwner = feedFrameToActivePaneModel(
            "$esc[2J$esc[H$esc[24;1H Working 7  esc to interrupt streaming the reply",
            settledOwner,
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        capturePaintedRows("issue1138-01-partial-black")

        val remoteCapture = captureAuthoritativeRemotePane(fixtureKey)
        val remoteCaptureChars = remoteCapture.count { !it.isWhitespace() }
        assertTrue(
            "authoritative remote alt-screen frame must remain nonempty before the manual heal " +
                "(>= $MIN_AUTHORITATIVE_CAPTURE_CHARS chars); found $remoteCaptureChars.\n" +
                remoteCapture,
            remoteCaptureChars >= MIN_AUTHORITATIVE_CAPTURE_CHARS,
        )
        assertTrue(
            "authoritative remote alt-screen frame must still carry its marker",
            remoteCapture.contains(FRAME_MARKER),
        )
        val localStale = activePaneLocalHealState(injectedOwner)

        // ----- Symptom precondition: the active pane IS partial-black (the #1138 state). -----
        assertTrue(
            "the injected pane must be partial-black (the maintainer's #1138 semi-black state)",
            localStale.partiallyBlank,
        )
        assertTrue(
            "pre-call local render must still be suspect; if the automatic watchdog was left " +
                "armed it can heal first and this assertion must fail. state=$localStale",
            localStale.renderLooksSuspect,
        )
        assertTrue(
            "pre-call local partial-black must retain a bounded status fragment; state=$localStale",
            localStale.renderedNonBlankChars in 1..MAX_EXPECTED_FRAGMENT_CHARS,
        )

        // ----- DISCRIMINATOR half 1: the transport is GUARANTEED LIVE. -----
        assertTrue(
            "the transport must stay Connected with a partial-black render (a render bug on a " +
                "live link, not a drop), observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse(
            "the tmux client must NOT be disconnected (render bug, not a drop)",
            clientDisconnected(),
        )
        assertNoVisibleReconnect("partial-black (no reconnect surface)")

        // ----- GREEN: one steady-state watchdog tick must heal + restore the FULL alt frame. -----
        val healResult = driveStaleRenderHeal(injectedOwner)
        writeHealAttemptArtifact(localStale, remoteCaptureChars, healResult)
        assertEquals(
            "typed diagnostics must retain the pre-call stale viewport count, not resample " +
                "the healed viewport; result=$healResult",
            localStale.renderedNonBlankChars,
            healResult.stats.renderedNonBlankChars,
        )
        assertEquals(
            "REGRESSION (#1138/#966): the one-pass heal must return the exact " +
                "divergence-applied reason; result=$healResult",
            HealAttemptReason.DivergenceApplied,
            healResult.reason,
        )
        assertEquals(
            "REGRESSION (#1138): the steady-state watchdog must return typed Healed for the " +
                "partial-black alt-screen agent pane; result=$healResult",
            HealOutcome.Healed,
            healResult.outcome,
        )
        val visibleAfter = waitForVisibleTerminal(
            "alt-screen full-frame restore",
            timeoutMillis = RESTORE_TIMEOUT_MS,
        ) { it.contains(FRAME_MARKER) && frameRowCount(it) >= MIN_RESTORED_FRAME_ROWS }
        assertTrue(
            "the heal must re-render the FULL alt frame (the header marker + the upper rows " +
                "that were black); found rows=${frameRowCount(visibleAfter)}.\n$visibleAfter",
            visibleAfter.contains(FRAME_MARKER) && frameRowCount(visibleAfter) >= MIN_RESTORED_FRAME_ROWS,
        )
        assertFalse(
            "after the heal the pane must NO LONGER be partial-black (the upper rows repainted)",
            activePaneLocalHealState().partiallyBlank,
        )
        capturePaintedRows("issue1138-02-healed")

        // ----- DISCRIMINATOR half 2: still Connected, no reconnect across the heal. -----
        assertNoVisibleReconnect("post-heal (no reconnect surface)")
        assertTrue(
            "session must stay Connected after the heal (render fix, no reconnect), " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        writeSummary(localStale, remoteCaptureChars, healResult)
    } }

    // ---------------------------------------------------------------- Heal driver

    private fun driveStaleRenderHeal(
        expectedOwner: ActivePaneRenderOwnerSnapshotForTest,
    ): HealAttemptResult {
        var result: HealAttemptResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            activity.lifecycleScope.launch {
                try {
                    result = vm.healActivePaneIfStaleRenderResultForTest(expectedOwner)
                } finally {
                    latch.countDown()
                }
            }
        }
        val completed = latch.await(
            RESTORE_TIMEOUT_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        assertTrue("manual stale-render heal latch must complete within the bound", completed)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return checkNotNull(result) { "manual stale-render heal completed without a typed result" }
    }

    private fun pauseAutomaticStaleRenderWatchdog() {
        val latch = java.util.concurrent.CountDownLatch(1)
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            activity.lifecycleScope.launch {
                try {
                    vm.pauseActivePaneStaleRenderWatchdogForTest()
                } finally {
                    latch.countDown()
                }
            }
        }
        val completed = latch.await(
            RESTORE_TIMEOUT_MS,
            java.util.concurrent.TimeUnit.MILLISECONDS,
        )
        assertTrue("automatic stale-render watchdog pause latch must complete within the bound", completed)
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            assertTrue(
                "automatic stale-render watchdog must be quiescent before stale injection; " +
                    "job=${vm.staleRenderWatchdogJobForTest()}",
                vm.staleRenderWatchdogJobForTest()?.isActive != true,
            )
        }
    }

    private data class LocalHealState(
        val renderedNonBlankChars: Int,
        val partiallyBlank: Boolean,
        val renderLooksSuspect: Boolean,
    )

    private fun activePaneLocalHealState(
        expectedOwner: ActivePaneRenderOwnerSnapshotForTest? = null,
    ): LocalHealState {
        var state: LocalHealState? = null
        var actualOwner: ActivePaneRenderOwnerSnapshotForTest? = null
        var viewTerminalSessionIdentity: Int? = null
        var viewEmulatorIdentity: Int? = null
        var failure: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            val snapshot = runCatching { vm.activePaneRenderOwnerSnapshotForTest() }
                .getOrElse {
                    failure = "could not snapshot active owner: $it"
                    return@onActivity
                }
            actualOwner = snapshot
            val viewSession = activity.window.decorView.findTerminalView()?.currentSession
            viewTerminalSessionIdentity = viewSession?.let(System::identityHashCode)
            viewEmulatorIdentity = viewSession?.emulator?.let(System::identityHashCode)
            if (expectedOwner != null && (
                    !snapshot.coherent ||
                        !snapshot.sameOwnerAs(expectedOwner) ||
                        snapshot.modelMutationEpoch != expectedOwner.modelMutationEpoch ||
                        snapshot.controlSizeGeneration != expectedOwner.controlSizeGeneration ||
                snapshot.sizeOperationsInFlight != 0 ||
                snapshot.automaticHealOperationsInFlight != 0 ||
                snapshot.automaticHealActivityEpoch != expectedOwner.automaticHealActivityEpoch ||
                viewTerminalSessionIdentity != snapshot.terminalSessionIdentity ||
                viewEmulatorIdentity != snapshot.emulatorIdentity
                    )
            ) {
                failure = "render owner/model changed before pre-call oracle"
                return@onActivity
            }
            state = LocalHealState(
                renderedNonBlankChars = snapshot.renderedNonBlankChars,
                partiallyBlank = snapshot.partiallyBlank,
                renderLooksSuspect = snapshot.renderLooksSuspect,
            )
        }
        if (expectedOwner != null) {
            writeRenderOwnerArtifact(
                label = "pre-call",
                expected = expectedOwner,
            actual = actualOwner,
            viewTerminalSessionIdentity = viewTerminalSessionIdentity,
            viewEmulatorIdentity = viewEmulatorIdentity,
                failure = failure,
            )
        }
        assertTrue(
            "$failure expected=$expectedOwner actual=$actualOwner " +
                "viewSession=$viewTerminalSessionIdentity viewEmulator=$viewEmulatorIdentity",
            failure == null,
        )
        return checkNotNull(state) { "active pane missing while reading local stale state" }
    }

    private fun clientDisconnected(): Boolean {
        var disconnected = false
        compose.activityRule.scenario.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            disconnected = vm.clientDisconnectedForTest()
        }
        return disconnected
    }

    // ---------------------------------------------------------------- Emulator feed

    private fun waitForSettledActiveRenderOwner(): ActivePaneRenderOwnerSnapshotForTest {
        var lastOwner: ActivePaneRenderOwnerSnapshotForTest? = null
        var lastViewTerminalSessionIdentity: Int? = null
        var lastViewEmulatorIdentity: Int? = null
        val settled = runCatching {
            compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
                var ready = false
                compose.activityRule.scenario.onActivity { activity ->
                    val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                    lastOwner = runCatching { vm.activePaneRenderOwnerSnapshotForTest() }.getOrNull()
                    val viewSession = activity.window.decorView.findTerminalView()?.currentSession
                    lastViewTerminalSessionIdentity = viewSession?.let(System::identityHashCode)
                    lastViewEmulatorIdentity = viewSession?.emulator?.let(System::identityHashCode)
                    ready = lastOwner?.attachResizeSeedSettled == true &&
                        lastViewTerminalSessionIdentity == lastOwner?.terminalSessionIdentity &&
                        lastViewEmulatorIdentity == lastOwner?.emulatorIdentity
                }
                ready
            }
            true
        }.getOrDefault(false)
        writeRenderOwnerArtifact(
            label = "settled",
            expected = null,
            actual = lastOwner,
            viewTerminalSessionIdentity = lastViewTerminalSessionIdentity,
            viewEmulatorIdentity = lastViewEmulatorIdentity,
            failure = if (settled) null else "attach/resize/reseed settlement timed out",
        )
        assertTrue(
            "attach/resize/reseed must settle on the exact visible VM model; " +
                "owner=$lastOwner viewSession=$lastViewTerminalSessionIdentity " +
                "viewEmulator=$lastViewEmulatorIdentity",
            settled,
        )
        return checkNotNull(lastOwner)
    }

    private fun feedFrameToActivePaneModel(
        frame: String,
        expectedOwner: ActivePaneRenderOwnerSnapshotForTest,
    ): ActivePaneRenderOwnerSnapshotForTest {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        var injectedOwner: ActivePaneRenderOwnerSnapshotForTest? = null
        var viewTerminalSessionIdentity: Int? = null
        var viewTerminalSessionIdentityAfter: Int? = null
        var viewEmulatorIdentity: Int? = null
        var failure: String? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView()
            val viewSession = view?.currentSession
            viewTerminalSessionIdentity = viewSession?.let(System::identityHashCode)
            viewEmulatorIdentity = viewSession?.emulator?.let(System::identityHashCode)
            if (viewTerminalSessionIdentity != expectedOwner.terminalSessionIdentity ||
                viewEmulatorIdentity != expectedOwner.emulatorIdentity
            ) {
                failure = "TerminalView session/emulator is not bound to the expected active VM owner"
                return@onActivity
            }
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            val preInjectionOwner = vm.activePaneRenderOwnerSnapshotForTest()
            if (preInjectionOwner.automaticHealOperationsInFlight != 0 ||
                preInjectionOwner.automaticHealActivityEpoch != expectedOwner.automaticHealActivityEpoch
            ) {
                failure = "automatic render-heal activity changed before active-model injection"
                return@onActivity
            }
            injectedOwner = runCatching {
                vm.appendToActivePaneRenderModelForTest(bytes, expectedOwner)
            }.getOrElse {
                failure = it.message ?: it.toString()
                return@onActivity
            }
            val viewSessionAfter = view?.currentSession
            viewTerminalSessionIdentityAfter = viewSessionAfter?.let(System::identityHashCode)
            val viewEmulatorAfter = viewSessionAfter?.emulator?.let(System::identityHashCode)
            if (viewTerminalSessionIdentityAfter != injectedOwner?.terminalSessionIdentity ||
                viewEmulatorAfter != injectedOwner?.emulatorIdentity ||
                injectedOwner?.automaticHealOperationsInFlight != 0 ||
                injectedOwner?.automaticHealActivityEpoch != expectedOwner.automaticHealActivityEpoch
            ) {
                failure = "TerminalView owner or automatic render-heal epoch changed during active-model injection"
                return@onActivity
            }
            view?.invalidate()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        writeRenderOwnerArtifact(
            label = "injected",
            expected = expectedOwner,
            actual = injectedOwner,
            viewTerminalSessionIdentity = viewTerminalSessionIdentity,
            viewTerminalSessionIdentityAfter = viewTerminalSessionIdentityAfter,
            viewEmulatorIdentity = viewEmulatorIdentity,
            failure = failure,
        )
        assertTrue(
            "$failure expected=$expectedOwner injected=$injectedOwner " +
                "viewSession=$viewTerminalSessionIdentity " +
                "viewSessionAfter=$viewTerminalSessionIdentityAfter viewEmulator=$viewEmulatorIdentity",
            failure == null && injectedOwner != null,
        )
        return checkNotNull(injectedOwner)
    }

    private fun writeRenderOwnerArtifact(
        label: String,
        expected: ActivePaneRenderOwnerSnapshotForTest?,
        actual: ActivePaneRenderOwnerSnapshotForTest?,
        viewTerminalSessionIdentity: Int?,
        viewTerminalSessionIdentityAfter: Int? = null,
        viewEmulatorIdentity: Int?,
        failure: String?,
    ) {
        writeText(
            "issue1138-render-owner-$label.txt",
            buildString {
                appendLine("label=$label")
                appendLine("failure=${failure.orEmpty()}")
                appendLine("view_terminal_session_identity=$viewTerminalSessionIdentity")
                appendLine("view_terminal_session_identity_after=$viewTerminalSessionIdentityAfter")
                appendLine("view_emulator_identity=$viewEmulatorIdentity")
                appendLine("expected_automatic_heal_activity_epoch=${expected?.automaticHealActivityEpoch}")
                appendLine("actual_automatic_heal_activity_epoch=${actual?.automaticHealActivityEpoch}")
                appendLine("expected=$expected")
                appendLine("actual=$actual")
            },
        )
    }

    // ---------------------------------------------------------------- Render capture

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

    /** Count DISTINCT non-blank rows of the restored alt frame (its FRAME row lines). */
    private fun frameRowCount(text: String): Int =
        text.split('\n').count { it.isNotBlank() }

    // ---------------------------------------------------------------- Attach / wait

    private fun attachSeededTmuxSession(hostRowTag: String) {
        // Issue #788/#848: cold-compose-aware presence poll. Under
        // createAndroidComposeRule the activity launches in the rule's `before()`;
        // on a contended swiftshader emulator its cold compose to the HostList
        // route can take tens of seconds, and the first frames throw
        // IllegalStateException "No compose hierarchies found" instead of an empty
        // node list — wrap so the poll keeps trying rather than propagating the ISE.
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

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus = TmuxSessionViewModel.ConnectionStatus.Idle
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
                .findTerminalView()?.currentSession?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private fun assertNoVisibleReconnect(label: String) {
        org.junit.Assert.assertEquals(
            "expected no disconnect band for $label", 0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        org.junit.Assert.assertEquals(
            "expected no Tap Reconnect button for $label", 0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Reconnecting", "Disconnected", "Tap Reconnect").forEach { text ->
            org.junit.Assert.assertEquals(
                "expected no visible '$text' text for $label", 0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    // ---------------------------------------------------------------- Fixture

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
                name = "issue1138-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1138 Alt-Screen Partial Black",
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
     * Seed a tmux session running a SPARSE ALT-SCREEN app: enter the alternate screen, draw a
     * header (with the marker) + a couple of conversation lines + an input/status line, leaving
     * a large BLANK conversation area — a Codex/Claude "Working" frame. Sparse (~110 non-blank
     * chars over 6 lines): the surviving status line the local emulator keeps is > 25% of it,
     * so the #966 divergence oracle MISSES it, yet > 3 non-blank lines so a healed pane reads
     * non-partial-black.
     */
    private suspend fun seedAltScreenSession(key: String) {
        val e = "\\033"
        val frame = buildString {
            append("$e[?1049h")           // enter alternate screen buffer
            append("$e[2J$e[H")            // clear + home
            append("$e[1;1H$FRAME_MARKER header codex podwiki")
            append("$e[2;1HYou: fix the bug now")
            append("$e[3;1HCodex: plan is ready")
            append("$e[4;1HCodex: editing files")
            append("$e[22;1Hinput box prompt >_")
            append("$e[24;1H Working 7  esc to interrupt")
        }
        val payload = "printf '$frame'; while true; do sleep 3600; done"
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
        Log.i(LOG_TAG, "seeded alt-screen session: ${exec?.stdout?.trim()}")
    }

    private suspend fun captureAuthoritativeRemotePane(key: String): String {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux capture-pane -p -t ${shellQuote(SESSION_NAME)}")
            }
        }
        val exec = result.getOrNull()
        assertTrue(
            "authoritative remote capture-pane must succeed before the manual heal; " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        return exec?.stdout.orEmpty()
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

    // ---------------------------------------------------------------- Artifacts

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        java.io.FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1138_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1138_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeHealAttemptArtifact(
        localStale: LocalHealState,
        remoteCaptureChars: Int,
        healResult: HealAttemptResult,
    ): File =
        writeText(
            "issue1138-heal-attempt.txt",
            buildString {
                appendLine("stale_kind=ALT_SCREEN_PARTIAL_BLACK")
                appendLine("auto_watchdog_quiescent=true")
                appendLine("remote_capture_non_blank_chars=$remoteCaptureChars")
                appendLine("local_rendered_non_blank_chars=${localStale.renderedNonBlankChars}")
                appendLine("local_render_looks_suspect=${localStale.renderLooksSuspect}")
                appendLine("heal_outcome=${healResult.outcome}")
                appendLine("heal_reason=${healResult.reason}")
                appendLine("heal_rendered_non_blank_chars=${healResult.stats.renderedNonBlankChars}")
                appendLine("heal_capture_non_blank_chars=${healResult.stats.captureNonBlankChars}")
                appendLine("heal_capture_line_count=${healResult.stats.captureLineCount}")
            },
        )

    private fun writeSummary(
        localStale: LocalHealState,
        remoteCaptureChars: Int,
        healResult: HealAttemptResult,
    ): File =
        writeText(
            "issue1138-summary.txt",
            buildString {
                appendLine("test=AgentAltScreenPartialBlackHealJourneyE2eTest")
                appendLine("issue=1138")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT), sparse ALT-SCREEN app")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("frame_marker=$FRAME_MARKER")
                appendLine("auto_watchdog_quiescent=true")
                appendLine("remote_capture_non_blank_chars=$remoteCaptureChars")
                appendLine("local_rendered_non_blank_chars=${localStale.renderedNonBlankChars}")
                appendLine("local_render_looks_suspect=${localStale.renderLooksSuspect}")
                appendLine("heal_outcome=${healResult.outcome}")
                appendLine("heal_reason=${healResult.reason}")
                appendLine(
                    "heal_stats=rendered:${healResult.stats.renderedNonBlankChars}," +
                        "capture:${healResult.stats.captureNonBlankChars}," +
                        "lines:${healResult.stats.captureLineCount}",
                )
                appendLine(
                    "scenario=attach a sparse alt-screen agent frame, inject the partial-black " +
                        "(clear + only the live status line) on the LIVE emulator, assert transport " +
                        "stays Connected, then drive ONE steady-state stale-render watchdog tick and " +
                        "assert the FULL alt frame (marker + upper rows) re-renders",
                )
                appendLine(
                    "expectation=transport ALIVE (Connected, no reconnect surface) AND render HEALS " +
                        "(full alt frame restored from tmux's authoritative capture)",
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
        const val LOG_TAG: String = "Issue1138AltBlack"
        const val DEVICE_DIR_NAME: String = "issue1138-alt-screen-partial-black-heal"
        const val SESSION_NAME: String = "issue1138-alt-black"
        const val FRAME_MARKER: String = "ISSUE1138-ALT"

        const val MIN_RESTORED_FRAME_ROWS: Int = 5
        const val MIN_AUTHORITATIVE_CAPTURE_CHARS: Int = 40
        const val MAX_EXPECTED_FRAGMENT_CHARS: Int = 80

        val RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
