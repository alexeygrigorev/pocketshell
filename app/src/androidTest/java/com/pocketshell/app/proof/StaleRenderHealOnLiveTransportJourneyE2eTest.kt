package com.pocketshell.app.proof

import android.graphics.Bitmap
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import com.pocketshell.app.proof.signals.captureViewToBitmap

/**
 * Issue #966/#967 — the DISCRIMINATING journey: a RENDER death on a LIVE transport.
 *
 * ## The maintainer's report (dogfood 2026-06-25, #966)
 *
 * A connected `pocketshell` Claude session went BLACK with only stray fragments
 * (a lone cursor, a "3", a scattered status line) while the transport stayed
 * CONNECTED (green dot). The v0.4.17 black-screen heal (#941/#942) only engages on
 * a FULLY-blank or ≤3-live-line pane, so a black-WITH-fragments pane reads "not
 * blank" → the heal SKIPS it (the #966 oracle gap). The #967 spike confirmed the
 * class: the keepalive keeps succeeding through a render stall, so a render that
 * dies SILENTLY neither reconnects NOR heals — it just sits black on a live link.
 *
 * ## What this journey PROVES (the discriminator the #967 spike asked for)
 *
 * Drive a heavy redraw / inject the scattered-fragment render state on a
 * GUARANTEED-LIVE channel, then assert BOTH:
 *
 *  - **Transport ALIVE:** `connectionStatus` stays `Connected`, NO
 *    Disconnected/Reconnecting/Attaching surface, `clientRef` not disconnected —
 *    throughout. (If this failed too it would be a real transport drop, not a
 *    render bug; the single test cleanly separates the two classes.)
 *  - **Render HEALS:** the stale-render watchdog re-seeds the pane from tmux's
 *    authoritative grid and the FULL prior viewport re-renders — no black/stale/
 *    fragment residue.
 *
 * ## RED → GREEN (G10/D33)
 *
 * On base the v0.4.17 heal oracle ([visibleScreenIsBlankOrPartiallyBlank]) reads
 * FALSE for the scattered-fragment pane (asserted here as the RED precondition —
 * the heal would skip it, leaving black-with-fragments on a live transport). With
 * the fix the widened divergence oracle ([visibleScreenDivergesFromCapture])
 * detects the stale render against a fresh `capture-pane` and the stale-render
 * heal re-renders the full pane → GREEN.
 *
 * ## Class coverage (the #966 family, all on a LIVE channel — D32 G2)
 *
 *  - `fully-blank`        — the v0.4.17 heal already covers it (sanity: still heals).
 *  - `scattered-fragment` — the #966 gap (the main proof).
 *  - `stale-after-burst`  — a heavy %output burst that partials the render, then
 *    the scattered residue (the burst variant).
 *
 * ## How the state is injected deterministically (no toxiproxy, #780 synthetic model)
 *
 * Attach a full-viewport static banner pane on the deterministic `agents` fixture
 * (host port 2222), then feed the fragment/black frame STRAIGHT into the SAME
 * `TerminalView.mEmulator` the app renders — the rendered viewport goes stale while
 * the REMOTE tmux grid keeps the full banner, so a correct stale-render heal
 * restores it. The transport is never touched, so it is GUARANTEED LIVE — exactly
 * the discriminator. No `Assume.assumeFalse(isRunningOnCi())` on the load-bearing
 * assertion: it RUNS on the per-PR CI emulator-journey job once wired into
 * `scripts/ci-journey-suite.sh`.
 */
@RunWith(AndroidJUnit4::class)
class StaleRenderHealOnLiveTransportJourneyE2eTest {

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

    /**
     * #966/#967 — ONE connected journey: attach ONCE, then exercise ALL THREE
     * stale-render kinds in sequence on the SAME live pane (fully-blank,
     * scattered-fragment, stale-after-burst). A single activity launch avoids the
     * AVD rapid-relaunch "No compose hierarchies" / ViewModel-cleared flake while
     * still class-covering the whole #966 family on a GUARANTEED-LIVE transport
     * (D32 G2).
     */
    @Test
    fun staleRenderHealsWhileTransportStaysConnectedAcrossAllKinds() { runBlocking {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)

        // Baseline: the full banner is in the live pane and the session is Connected.
        waitForVisibleTerminal("initial banner") { it.contains(BANNER_MARKER) }
        waitForConnected("initial attach")
        firstVisiblePaneId()
        capturePaintedRows("issue966-00-attached")
        assertTrue(
            "baseline buffer must contain the full banner (>= $MIN_RESTORED_BANNER_ROWS rows)",
            bannerRowCount(visibleTerminalText()) >= MIN_RESTORED_BANNER_ROWS,
        )

        // The production 4-second watchdog must not win the race between injection and this
        // journey's manual one-pass owner. Disable every future auto-arm and cancel+JOIN the
        // current loop before the first stale frame is injected.
        pauseAutomaticStaleRenderWatchdog()

        // Exercise each stale kind in turn on the SAME live pane (no relaunch).
        for (kind in StaleKind.values()) {
            runStaleKind(kind, key)
        }
    } }

    private enum class StaleKind { FULLY_BLANK, SCATTERED_FRAGMENT, STALE_AFTER_BURST }

    private suspend fun runStaleKind(kind: StaleKind, key: String) {
        val namePrefix = "issue966-${kind.name.lowercase()}"

        // Restore the banner baseline before this kind (the prior kind's heal already
        // repainted it; a fresh capture-pane reseed keeps each kind independent).
        waitForVisibleTerminal("$namePrefix pre-kind banner", timeoutMillis = RESTORE_TIMEOUT_MS) {
            bannerRowCount(it) >= MIN_RESTORED_BANNER_ROWS
        }
        val settledOwner = waitForSettledActiveRenderOwner(namePrefix)
        capturePaintedRows("$namePrefix-01-baseline")

        // ===== Inject the stale render on the LIVE, retained emulator. =====
        val injectedOwner = when (kind) {
            StaleKind.SCATTERED_FRAGMENT -> feedScatteredFragmentFrameToActiveModel(settledOwner, namePrefix)
            StaleKind.FULLY_BLANK -> feedFrameToActivePaneModel("[2J[H", settledOwner, namePrefix)
            StaleKind.STALE_AFTER_BURST -> {
                // A heavy alt-screen repaint burst that outruns the drain, then the
                // scattered residue — the burst variant of the #966 stale grid.
                val burstOwner = feedHeavyBurstToActiveModel(settledOwner, namePrefix)
                feedScatteredFragmentFrameToActiveModel(burstOwner, namePrefix)
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        capturePaintedRows("$namePrefix-02-stale")
        // The RED state on the real device: the injected stale render shows almost
        // none of tmux's banner (the user-perceived black/fragment pane).

        val remoteCapture = captureAuthoritativeRemotePane(key)
        val remoteCaptureChars = remoteCapture.count { !it.isWhitespace() }
        assertTrue(
            "$namePrefix authoritative remote tmux frame must remain nonempty before the " +
                "manual heal (>= $MIN_AUTHORITATIVE_CAPTURE_CHARS chars); found " +
                "$remoteCaptureChars.\n$remoteCapture",
            remoteCaptureChars >= MIN_AUTHORITATIVE_CAPTURE_CHARS,
        )
        assertTrue(
            "$namePrefix authoritative remote tmux frame must still carry the banner marker",
            remoteCapture.contains(BANNER_MARKER),
        )
        val localStale = activePaneLocalHealState(injectedOwner, namePrefix)
        assertTrue(
            "$namePrefix pre-call local render must still be suspect; if the automatic watchdog " +
                "was left armed it can heal first and this assertion must fail. state=$localStale",
            localStale.renderLooksSuspect,
        )
        when (kind) {
            StaleKind.FULLY_BLANK -> assertEquals(
                "$namePrefix intended local state must be fully blank before the manual call",
                0,
                localStale.renderedNonBlankChars,
            )
            StaleKind.SCATTERED_FRAGMENT,
            StaleKind.STALE_AFTER_BURST -> assertTrue(
                "$namePrefix intended local state must retain bounded fragments before the " +
                    "manual call; state=$localStale",
                localStale.renderedNonBlankChars in 1..MAX_EXPECTED_FRAGMENT_CHARS,
            )
        }

        // ----- RED precondition: the v0.4.17 heal oracle SKIPS this pane. -----
        // For the scattered-fragment / burst case the combined blank||partial-blank
        // oracle reads FALSE, so the v0.4.17 heal would never fire — proving the gap.
        // (The fully-blank case is the boundary the v0.4.17 heal already owned, so it
        // reads TRUE there; the divergence path subsumes it.)
        val v0417OracleHit = localStale.blankOrPartiallyBlank
        if (kind != StaleKind.FULLY_BLANK) {
            assertFalse(
                "$namePrefix RED: the v0.4.17 heal oracle (blank||partial-blank) must SKIP " +
                    "the scattered-fragment pane — this is the #966 gap the divergence heal closes",
                v0417OracleHit,
            )
        }

        // ----- DISCRIMINATOR half 1: the transport is GUARANTEED LIVE. -----
        assertTrue(
            "$namePrefix the transport must stay Connected with a stale render (the #967 " +
                "render-death-on-live-transport class), observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse(
            "$namePrefix the tmux client must NOT be disconnected (render bug, not a drop)",
            clientDisconnected(),
        )
        assertNoVisibleReconnect("$namePrefix stale-render (no reconnect surface)")

        // ----- GREEN: drive ONE stale-render heal pass (the watchdog tick) and -----
        // require the FULL banner to re-render from tmux's authoritative grid.
        // The divergence oracle fires for ALL three stale kinds (fully-black,
        // scattered fragments, post-burst clear) because tmux's grid holds the full
        // banner while the VISIBLE viewport shows almost none of it.
        val healResult = driveStaleRenderHeal(injectedOwner)
        writeHealAttemptArtifact(
            namePrefix = namePrefix,
            kind = kind,
            localStale = localStale,
            remoteCaptureChars = remoteCaptureChars,
            healResult = healResult,
        )
        assertEquals(
            "$namePrefix typed diagnostics must retain the pre-call stale viewport count, " +
                "not resample the healed viewport; result=$healResult",
            localStale.renderedNonBlankChars,
            healResult.stats.renderedNonBlankChars,
        )
        assertEquals(
            "$namePrefix the one-pass heal must return the exact divergence-applied reason; " +
                "result=$healResult",
            HealAttemptReason.DivergenceApplied,
            healResult.reason,
        )
        assertEquals(
            "$namePrefix the stale-render heal must be typed Healed ($kind); result=$healResult",
            HealOutcome.Healed,
            healResult.outcome,
        )
        val visibleAfter = waitForVisibleTerminal(
            "$namePrefix stale-render heal full-viewport restore",
            timeoutMillis = RESTORE_TIMEOUT_MS,
        ) { bannerRowCount(it) >= MIN_RESTORED_BANNER_ROWS }
        assertTrue(
            "$namePrefix the heal must re-render the FULL banner (>= $MIN_RESTORED_BANNER_ROWS " +
                "distinct rows) from the fresh capture; found ${bannerRowCount(visibleAfter)}.\n$visibleAfter",
            bannerRowCount(visibleAfter) >= MIN_RESTORED_BANNER_ROWS,
        )
        val restoredPaintedRows = capturePaintedRows("$namePrefix-03-healed")
        assertTrue(
            "$namePrefix the heal repaint must leave the viewport painted (>= $MIN_PAINTED_ROWS " +
                "painted rows); found $restoredPaintedRows",
            restoredPaintedRows >= MIN_PAINTED_ROWS,
        )

        // ----- DISCRIMINATOR half 2: still Connected, no reconnect across the heal. -----
        watchNoVisibleReconnect("$namePrefix heal settle", POST_RESTORE_SETTLE_MS)
        assertTrue(
            "$namePrefix tmux session screen must still be up after the heal",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "$namePrefix session must stay Connected after the heal (render fix, no reconnect), " +
                "observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        writeSummary(
            namePrefix = namePrefix,
            kind = kind,
            localStale = localStale,
            remoteCaptureChars = remoteCaptureChars,
            healResult = healResult,
        )
    }

    // ---------------------------------------------------------------- Heal driver

    /**
     * Run ONE stale-render heal pass over the active pane (the watchdog tick),
     * synchronously, via the production test seam [TmuxSessionViewModel.
     * healActivePaneIfStaleRenderResultForTest]. Returns the exact typed attempt.
     */
    private fun driveStaleRenderHeal(
        expectedOwner: ActivePaneRenderOwnerSnapshotForTest,
    ): HealAttemptResult {
        var result: HealAttemptResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        launchedActivity?.onActivity { activity ->
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
        launchedActivity?.onActivity { activity ->
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
        launchedActivity?.onActivity { activity ->
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
        val blankOrPartiallyBlank: Boolean,
        val renderLooksSuspect: Boolean,
    )

    private fun activePaneLocalHealState(
        expectedOwner: ActivePaneRenderOwnerSnapshotForTest,
        namePrefix: String,
    ): LocalHealState {
        var state: LocalHealState? = null
        var actualOwner: ActivePaneRenderOwnerSnapshotForTest? = null
        var viewTerminalSessionIdentity: Int? = null
        var viewEmulatorIdentity: Int? = null
        var failure: String? = null
        launchedActivity?.onActivity { activity ->
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
            if (!snapshot.coherent ||
                !snapshot.sameOwnerAs(expectedOwner) ||
                snapshot.modelMutationEpoch != expectedOwner.modelMutationEpoch ||
                snapshot.controlSizeGeneration != expectedOwner.controlSizeGeneration ||
                snapshot.sizeOperationsInFlight != 0 ||
                snapshot.automaticHealOperationsInFlight != 0 ||
                snapshot.automaticHealActivityEpoch != expectedOwner.automaticHealActivityEpoch ||
                viewTerminalSessionIdentity != snapshot.terminalSessionIdentity ||
                viewEmulatorIdentity != snapshot.emulatorIdentity
            ) {
                failure = "render owner/model changed before pre-call oracle"
                return@onActivity
            }
            state = LocalHealState(
                renderedNonBlankChars = snapshot.renderedNonBlankChars,
                blankOrPartiallyBlank = snapshot.partiallyBlank ||
                    snapshot.renderedNonBlankChars == 0,
                renderLooksSuspect = snapshot.renderLooksSuspect,
            )
        }
        writeRenderOwnerArtifact(
            namePrefix = namePrefix,
            label = "pre-call",
            expected = expectedOwner,
            actual = actualOwner,
            viewTerminalSessionIdentity = viewTerminalSessionIdentity,
            viewEmulatorIdentity = viewEmulatorIdentity,
            failure = failure,
        )
        assertTrue(
            "$failure expected=$expectedOwner actual=$actualOwner " +
                "viewSession=$viewTerminalSessionIdentity viewEmulator=$viewEmulatorIdentity",
            failure == null,
        )
        return checkNotNull(state) { "active pane missing while reading local stale state" }
    }

    private fun clientDisconnected(): Boolean {
        var disconnected = false
        launchedActivity?.onActivity { activity ->
            val vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
            disconnected = vm.clientDisconnectedForTest()
        }
        return disconnected
    }

    // ---------------------------------------------------------------- Emulator feed

    /**
     * Feed the #966 scattered-fragment frame straight into the SAME emulator the app
     * renders: clear, then a handful of glyphs scattered across the grid (a lone "3",
     * a status line) — NOT fully blank, NOT cleanly partial-blank, so the v0.4.17
     * oracle skips it. Local to the emulator; the remote tmux grid keeps the banner.
     */
    private fun feedScatteredFragmentFrameToActiveModel(
        expectedOwner: ActivePaneRenderOwnerSnapshotForTest,
        namePrefix: String,
    ): ActivePaneRenderOwnerSnapshotForTest {
        val esc = ""
        val frame = buildString {
            append("$esc[2J$esc[H")          // erase + home
            append("3\r\n")                  // a lone glyph (the screenshot's "3")
            append("\r\n\r\n\r\n")
            append("$esc[10;1H")             // jump down
            append("24m 3 / 8 / 4 / 3 / 31\r\n") // the scattered status fragment
            append("$esc[15;40H")
            append("x\r\n")
            append("$esc[20;5H")
            append("y z\r\n")
        }
        return feedFrameToActivePaneModel(frame, expectedOwner, namePrefix)
    }

    /** A heavy alt-screen repaint burst — many colored rows faster than the drain. */
    private fun feedHeavyBurstToActiveModel(
        initialOwner: ActivePaneRenderOwnerSnapshotForTest,
        namePrefix: String,
    ): ActivePaneRenderOwnerSnapshotForTest {
        val esc = ""
        var owner = initialOwner
        repeat(8) {
            val frame = buildString {
                append("$esc[2J$esc[H")
                repeat(40) { row ->
                    append("$esc[3${row % 7}mBURST row $row ${"x".repeat(60)}$esc[0m\r\n")
                }
            }
            owner = feedFrameToActivePaneModel(frame, owner, namePrefix)
        }
        return owner
    }

    private fun waitForSettledActiveRenderOwner(
        namePrefix: String,
    ): ActivePaneRenderOwnerSnapshotForTest {
        var lastOwner: ActivePaneRenderOwnerSnapshotForTest? = null
        var lastViewTerminalSessionIdentity: Int? = null
        var lastViewEmulatorIdentity: Int? = null
        val settled = runCatching {
            compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
                var ready = false
                launchedActivity?.onActivity { activity ->
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
            namePrefix = namePrefix,
            label = "settled",
            expected = null,
            actual = lastOwner,
            viewTerminalSessionIdentity = lastViewTerminalSessionIdentity,
            viewEmulatorIdentity = lastViewEmulatorIdentity,
            failure = if (settled) null else "attach/resize/reseed settlement timed out",
        )
        assertTrue(
            "$namePrefix attach/resize/reseed must settle on the exact visible VM model; " +
                "owner=$lastOwner viewSession=$lastViewTerminalSessionIdentity " +
                "viewEmulator=$lastViewEmulatorIdentity",
            settled,
        )
        return checkNotNull(lastOwner)
    }

    private fun feedFrameToActivePaneModel(
        frame: String,
        expectedOwner: ActivePaneRenderOwnerSnapshotForTest,
        namePrefix: String,
    ): ActivePaneRenderOwnerSnapshotForTest {
        val bytes = frame.toByteArray(Charsets.UTF_8)
        var injectedOwner: ActivePaneRenderOwnerSnapshotForTest? = null
        var viewTerminalSessionIdentity: Int? = null
        var viewTerminalSessionIdentityAfter: Int? = null
        var viewEmulatorIdentity: Int? = null
        var failure: String? = null
        launchedActivity?.onActivity { activity ->
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
            if (viewTerminalSessionIdentityAfter != injectedOwner?.terminalSessionIdentity ||
                viewSessionAfter?.emulator?.let(System::identityHashCode) != injectedOwner?.emulatorIdentity ||
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
            namePrefix = namePrefix,
            label = "injected-${expectedOwner.modelMutationEpoch}",
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
        namePrefix: String,
        label: String,
        expected: ActivePaneRenderOwnerSnapshotForTest?,
        actual: ActivePaneRenderOwnerSnapshotForTest?,
        viewTerminalSessionIdentity: Int?,
        viewTerminalSessionIdentityAfter: Int? = null,
        viewEmulatorIdentity: Int?,
        failure: String?,
    ) {
        writeText(
            "$namePrefix-render-owner-$label.txt",
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
        val bitmap = renderViewportBitmap(name)
        writeBitmap("$name-viewport", bitmap)
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        val rows = paintedRowCount(bitmap)
        bitmap.recycle()
        return rows
    }

    private fun renderViewportBitmap(label: String): Bitmap {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        var bitmap: Bitmap? = null
        val activityHolder = launchedActivity
            ?: throw AssertionError("activity was not available to capture viewport '$label' (#2135)")
        activityHolder.onActivity { activity ->
            bitmap = captureViewToBitmap(
                activity.window.decorView.findTerminalView(),
                label,
            )
        }
        return checkNotNull(bitmap) {
            "onActivity did not produce a viewport bitmap for '$label' (#2135)"
        }
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

    private fun firstVisiblePaneId(): String =
        checkNotNull(viewModel().panes.value.firstOrNull()?.paneId) { "no visible pane after attach" }

    private fun viewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        launchedActivity?.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return checkNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    // ---------------------------------------------------------------- Attach / wait

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
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
        var status: TmuxSessionViewModel.ConnectionStatus = TmuxSessionViewModel.ConnectionStatus.Idle
        launchedActivity?.onActivity { activity ->
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
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()?.currentSession?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no Connecting overlay for $label", 0,
            compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
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
        assertEquals(
            "expected no 'Attaching…' overlay for $label", 0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Connecting", "Reconnecting", "Disconnected", "Tap Reconnect", "Attaching").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label", 0,
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
                name = "issue966-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue966 Stale Render",
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
        println("ISSUE966_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE966_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeHealAttemptArtifact(
        namePrefix: String,
        kind: StaleKind,
        localStale: LocalHealState,
        remoteCaptureChars: Int,
        healResult: HealAttemptResult,
    ): File =
        writeText(
            "$namePrefix-heal-attempt.txt",
            buildString {
                appendLine("stale_kind=$kind")
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
        namePrefix: String,
        kind: StaleKind,
        localStale: LocalHealState,
        remoteCaptureChars: Int,
        healResult: HealAttemptResult,
    ): File =
        writeText(
            "$namePrefix-summary.txt",
            buildString {
                appendLine("test=StaleRenderHealOnLiveTransportJourneyE2eTest#$namePrefix")
                appendLine("issue=966/967")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine("stale_kind=$kind")
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
                    "scenario=attach a full-viewport banner, inject the stale/fragment render " +
                        "($kind) on the LIVE emulator, assert transport stays Connected, then drive " +
                        "the stale-render heal and assert the full viewport re-renders",
                )
                appendLine(
                    "expectation=transport ALIVE (Connected, no reconnect surface) AND render HEALS " +
                        "(full banner restored from tmux's authoritative grid)",
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
        const val LOG_TAG: String = "Issue966StaleRender"
        const val DEVICE_DIR_NAME: String = "issue966-stale-render-heal"
        const val SESSION_NAME: String = "issue966-stale-render"
        const val BANNER_MARKER: String = "ISSUE966-BANNER"

        const val POST_RESTORE_SETTLE_MS: Long = 2_000L
        const val MIN_RESTORED_BANNER_ROWS: Int = 20
        const val MIN_PAINTED_ROWS: Int = 30
        const val MIN_AUTHORITATIVE_CAPTURE_CHARS: Int = 40
        const val MAX_EXPECTED_FRAGMENT_CHARS: Int = 40

        val RESTORE_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
