package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.assertNodeFullyWithinRoot
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTION_STATUS_PILL_TAG
import com.pocketshell.app.tmux.TMUX_RECONNECTING_RETRY_NOW_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import com.pocketshell.app.proof.signals.captureViewToBitmap

/**
 * Issue #635/#822 (epic #687 Phase 0, J1) — DEVICE-TRUTH journey: after a socket
 * drops while backgrounded, a foreground return WITHIN grace keeps the last pane
 * viewport visible but must truthfully show `Reconnecting` with an in-place action.
 * Tapping that action recovers the SAME session; no A→B→A switch workaround.
 *
 * ## Why the existing within-grace CI tests miss the maintainer's bug
 *
 * The audit (#687 consolidated verdict): the real-world #635 failure is that
 * stepping outside DROPS the socket (WiFi→cellular handoff / Doze), so by the
 * time the user foregrounds within grace the `-CC` lease is gone. The
 * within-grace reseed fast path is gated by
 * `canReseedWithinGraceForeground()` (TmuxSessionViewModel.kt:2041) which
 * requires a still-warm lease (`liveLeaseKeys.contains(...)`) + Connected; a
 * dropped socket makes that predicate FALSE, so the app falls through to a
 * reconnect — the exact regression. But the per-PR-CI within-grace tests
 * (`BackgroundGraceReconnectE2eTest`,
 * `WithinGraceResumeRideThroughE2eTest`'s reseed_only assertion) NEVER drop the
 * link — the link stays clean across the background — so the predicate is
 * always true and they go green while the real journey is broken.
 *
 * The strongest reproduction so far (`WithinGraceResumeRideThroughE2eTest`,
 * toxiproxy `addBlackhole`/`disable`) IS gated out of CI via
 * `assumeNetworkFaultProofsEnabled()` (NetworkFaultProofBase.kt:86,
 * `Assume.assumeFalse(isRunningOnCi())`), because tests.yml's per-push job
 * deliberately keeps the toxiproxy proxy family down. So the ONLY journey that
 * drops the link is disabled on CI — exactly how four broken journeys shipped
 * green.
 *
 * ## How this journey drops the socket on the per-push CI fixture (no toxiproxy)
 *
 * It reproduces the socket drop with a CI-compatible mechanism the journey
 * suite already uses: a `kill -9` of the app's own sshd worker from a sidecar
 * SSH session (the same wire-level transport death
 * [ReconnectRepaintE2eTest] / [BackgroundResumeSocketDeathE2eTest] use), but
 * performed WHILE the app is BACKGROUNDED within grace. The remote tmux server
 * and the seeded pane stay alive; only the app's `-CC` control socket dies,
 * exactly modelling the WiFi→cellular handoff the maintainer hits. Because it
 * uses ONLY the deterministic `agents` fixture (host port 2222), it RUNS on the
 * per-PR CI emulator-journey job — no `Assume.assumeFalse(isRunningOnCi())`, no
 * toxiproxy.
 *
 * ## Contract (DEVICE TRUTH — asserts the user's pixels)
 *
 *  1. During the confirmed-dead interval the prior [READY_MARKER] viewport remains
 *     visible — proven on the RENDERED `TerminalView` (measured, shown, on-screen and
 *     actually painted; see [assertRetainedFrameIsRendered]) as well as in the terminal
 *     buffer, because the buffer survives a view collapsed to 0x0 — while raw status is
 *     Reconnecting, send is unwritable, and the top `Retry now` action is visible. No
 *     destructive centered "Attaching…" overlay, collapsed pull-to-refresh wrapper, or
 *     terminal `Disconnected` failure surface may replace it.
 *  2. Tapping `Retry now` recovers through a different control client, remains on
 *     [SESSION_NAME], and round-trips [AFTER_MARKER] without a session switch.
 *
 * ## Fail-first
 *
 * On the reopened-#822 base, the dropped socket makes the reseed gate decline, but
 * `launchForegroundHealWithinGrace` sends a synthetic preserved-channel seed and the
 * status projector rewrites Reconnecting to Connected. The first wait therefore REDs:
 * raw status stays false Connected and no Retry-now action exists over the dead wire.
 * A genuine typed drop plus reveal-only hold flips it GREEN.
 */
@RunWith(AndroidJUnit4::class)
class WithinGraceSocketDropForegroundJourneyE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var diagnostics: RecordingDiagnosticSink? = null
    private var seededKey: String? = null
    private val timings = mutableListOf<String>()

    @Before
    fun setUp() {
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun tearDown() {
        runCatching { currentViewModel().forceCleanOutageForTest = false }
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics?.close()
        diagnostics = null
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
    }

    @Test
    fun withinGraceForegroundAfterSocketDropRetainsViewportAndRetryRecoversSameSession() { runBlocking {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))

        // Baseline sshd workers BEFORE the app connects so we can identify the
        // app's `-CC` worker by set-difference once it attaches.
        val baselineSshdPids = listSshdPidsForTestuser(key)
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)

        // #818 defaults an agent-kinded session to Conversation; this journey is about the
        // TERMINAL viewport the user was looking at, so put the real Terminal tab on screen
        // before the drop. Otherwise the retained frame under test is not the visible
        // surface and the chrome screenshot cannot evidence the reported state.
        selectTerminalTabForJourney()
        // Baseline: the seeded content is on screen. This is the content that
        // must survive the within-grace socket drop and be re-seeded on return.
        waitForVisibleTerminal("initial attach") { it.contains(READY_MARKER) }
        waitForConnected("initial attach")
        val vm = currentViewModel()
        val clientBeforeDrop = vm.currentClientIdentityForTest()
        captureViewport("issue635-01-attached")
        diagnostics!!.clear()

        // Identify the app's sshd worker NOW (while still foregrounded + warm)
        // so the kill during background targets exactly the app's `-CC` socket.
        val attachedPids = listSshdPidsForTestuser(key)
        val appSshdPids = attachedPids - baselineSshdPids
        assertTrue(
            "expected at least one new sshd worker for the app `-CC` connection; " +
                "baseline=$baselineSshdPids attached=$attachedPids",
            appSshdPids.isNotEmpty(),
        )

        // Use a short grace override so the resume lands well within grace.
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
        // Hold the passive replacement primitives down after the real socket death so the
        // UI has a deterministic confirmed-dead interval to render. The real `Retry now`
        // action uses the production manual reconnect entrypoint and remains available.
        vm.forceCleanOutageForTest = true

        val cycleStart = SystemClock.elapsedRealtime()
        // (1) Background within grace.
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        waitForDiagnostic("background_grace_start", "within-grace background")

        // (2) DROP THE SOCKET while backgrounded: kill the app's sshd worker.
        // This is the real-world WiFi→cellular handoff — the `-CC` lease dies
        // while the app is away. The remote tmux + pane stay alive.
        val killAt = SystemClock.elapsedRealtime()
        killRemoteSshdPids(key, appSshdPids)
        Log.i(LOG_TAG, "killed app sshd PIDs while backgrounded within grace: $appSshdPids")
        recordTiming("socket_dropped_at_ms", killAt - cycleStart)
        // Hold briefly so the dropped socket is fully observed by the transport
        // before the foreground (the lease is gone by foreground time — exactly
        // the case the within-grace reseed gate declines on base `main`).
        SystemClock.sleep(BACKGROUND_HOLD_MS)

        // (3) Foreground WITHIN grace following the drop.
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        waitForDiagnostic("background_grace_foreground", "within-grace foreground after drop") {
            it.fields["withinGrace"] == true
        }
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - cycleStart)

        // #822 DEVICE TRUTH: the dead wire is honestly Reconnecting and actionable, while
        // the reveal hold preserves the exact prior viewport instead of painting Attaching.
        waitForHonestReconnectWithRetainedViewport(vm)
        captureViewport("issue822-02-post-resume-reconnecting-retained")
        // The whole window too: the reviewer must SEE the honest chrome the user gets —
        // the amber "Reconnecting" breadcrumb pill and the in-place `Retry now` band over
        // the retained pane frame. The TerminalView-only capture cannot show either.
        captureScreen("issue822-02b-post-resume-reconnecting-chrome")
        val visibleDuringOutage = visibleTerminalText()
        assertTrue(
            "within-grace foreground after a socket drop must retain a non-blank viewport",
            visibleDuringOutage.isNotBlank(),
        )
        assertTrue(
            "the held viewport must retain '$READY_MARKER' during the real outage; visible:\n" +
                visibleDuringOutage,
            visibleDuringOutage.contains(READY_MARKER),
        )
        assertEquals(
            "liveness must stay deferred while the within-grace owner heals the dead client",
            0,
            diagnostics!!.eventsNamed("liveness_probe_silent_drop").size,
        )

        // The in-place action must work. This is the exact user journey that previously
        // required switching to another session and back: tap Retry now on this session.
        compose.onNodeWithTag(TMUX_RECONNECTING_RETRY_NOW_TAG, useUnmergedTree = true).performClick()
        waitForDiagnostic("reconnect_tapped", "in-place Retry now action")
        vm.forceCleanOutageForTest = false
        waitForConnected("same-session Retry now recovery")
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            vm.currentClientIdentityForTest()?.let { it != clientBeforeDrop } == true &&
                vm.isSendTransportWritable()
        }
        val clientAfterRecovery = vm.currentClientIdentityForTest()
        assertNotEquals(
            "Retry now must install a replacement control client without a session switch",
            clientBeforeDrop,
            clientAfterRecovery,
        )

        // The recovered channel must accept real input, not merely repaint the old marker.
        assertPostRecoveryInputReachesTheVisibleViewport()
        assertNoReconnectSurface("same-session settle after Retry now")
        captureViewport("issue822-03-same-session-recovered")

        // The session screen is still up (a cleared pane that also lost the
        // screen would be a teardown/reconnect, not a within-grace ride-through).
        assertTrue(
            "tmux session screen must still be up after the within-grace re-seed",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeSummary()
        writeTimings()
        Unit
    } }

    // ---------------------------------------------------------------- Helpers

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
        return currentViewModel().connectionStatus.value
    }

    private fun currentViewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        launchedActivity?.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return requireNotNull(vm) { "MainActivity/TmuxSessionViewModel is not available" }
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
                last.isNotBlank() && predicate(last)
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

    /**
     * The load-bearing writability proof: real input over the RECOVERED same-session wire
     * must reach the user's visible viewport.
     *
     * It is a bounded re-send loop rather than a single send because a `-CC` client streams
     * only the output produced AFTER it subscribes — tmux does not replay what landed during
     * the attach window. A marker sent into that window is therefore never painted, which
     * made a single-send assertion fail ~1 run in 3 on the dev box for a reason that has
     * nothing to do with writability. The loop's EXIT CONDITION is the load-bearing
     * assertion (the marker is visible), each send is still hard-checked for a transport
     * error, and the whole thing hard-fails at the deadline — so a genuinely unwritable wire
     * can never pass by retrying.
     */
    private suspend fun assertPostRecoveryInputReachesTheVisibleViewport() {
        val deadline = SystemClock.elapsedRealtime() + TerminalTestTimeouts.terminalVisibilityTimeoutMs()
        var sends = 0
        var visible = false
        while (!visible && SystemClock.elapsedRealtime() < deadline) {
            val client = requireNotNull(currentViewModel().liveTmuxClientForSendOrNullForTest()) {
                "the recovered same-session wire must expose a live client for send"
            }
            val send = client.sendKeysViaExec(
                "send-keys -t ${shellQuote(SESSION_NAME)} ${shellQuote("printf '$AFTER_MARKER\\n'")} Enter",
            )
            sends += 1
            assertTrue("post-recovery input failed: ${send.output}", !send.isError)
            visible = runCatching {
                compose.waitUntil(timeoutMillis = POST_RECOVERY_INPUT_PAINT_MS) {
                    visibleTerminalText().contains(AFTER_MARKER)
                }
                true
            }.getOrDefault(false)
        }
        recordTiming("post_recovery_input_sends", sends.toLong())
        if (!visible) writeText("failure-post-recovery-visible-terminal.txt", visibleTerminalText())
        assertTrue(
            "the recovered same-session wire must carry input through to the visible " +
                "viewport after $sends send(s); visible terminal was:\n${visibleTerminalText()}",
            visible,
        )
    }

    private fun selectTerminalTabForJourney() {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_CONVERSATION_PANE_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun waitForHonestReconnectWithRetainedViewport(vm: TmuxSessionViewModel) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting &&
                compose.onAllNodesWithTag(
                    TMUX_RECONNECTING_RETRY_NOW_TAG,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty() &&
                visibleTerminalText().contains(READY_MARKER)
        }
        assertTrue(
            "#822: confirmed-dead foreground must report Reconnecting; " +
                "status=${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertFalse(
            "#822: confirmed-dead foreground must be transport-unwritable before Retry now",
            vm.isSendTransportWritable(),
        )
        // ...and the retained viewport must actually be ON SCREEN. See
        // [assertRetainedFrameIsRendered]: the transcript checks below read the terminal
        // EMULATOR BUFFER, which survives a collapsed view, so they cannot see the
        // reported destruction on their own.
        assertRetainedFrameIsRendered("post-resume confirmed-dead interval")
        // The user must SEE the drop (the maintainer's "nothing tells me it dropped"
        // report): the breadcrumb pill/dot reads the amber "Reconnecting", not the
        // green Connected the retained frame used to imply.
        val reconnectingPillLabel = compose
            .onNodeWithTag(TMUX_CONNECTION_STATUS_PILL_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .joinToString(separator = "") { it.text }
        assertTrue(
            "#822/#2130: the breadcrumb pill must be a complete honest reconnecting " +
                "word, never a clipped fragment like 'Reco'; got '$reconnectingPillLabel'",
            reconnectingPillLabel in setOf("Reconnecting", "Retrying", "Retry"),
        )
        compose.onNodeWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true).assertExists()
        // ...and must be able to ACT on it. Containment, not `assertIsDisplayed`
        // (#657/F3): an off-edge control still reports displayed, and this tap target
        // is the whole point of the issue.
        compose.assertNodeFullyWithinRoot(TMUX_RECONNECTING_RETRY_NOW_TAG, useUnmergedTree = true)
        assertEquals(
            "the retained viewport must not be replaced by the centered Attaching overlay",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "a recoverable within-grace outage must not show the terminal failure band",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "a recoverable within-grace outage must not show the terminal Tap Reconnect action",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
    }

    /**
     * Issue #822 (round 2): the load-bearing "the user still SEES their pane" assertion.
     *
     * ## Why the buffer is not enough
     *
     * Every other "retained viewport" check in this journey goes through
     * [visibleTerminalText], which reads `TerminalView.currentSession.emulator.screen
     * .transcriptText` — the terminal emulator's BUFFER. That buffer is completely
     * independent of whether the view is measured, laid out, or drawn, so it stays intact
     * while the user is looking at nothing. On the emulator, removing the one-line
     * `surfaceOwnsPrimary` guard on `pullToReconnectActive` mounts the #823
     * `PullToRefreshBox` over the live `TerminalView`; its `verticalScroll` gives the
     * terminal an unbounded height constraint, the terminal measures to 0x0, and the
     * user's frame is replaced by an empty pull area with a spinner — while every
     * buffer-based assertion in this file stayed green.
     *
     * ## What this asserts instead
     *
     * The RENDERED view: a `TerminalView` exists, is attached and shown, has non-zero
     * measured size, occupies a non-empty rectangle on screen, and — drawn through the
     * same `view.draw(Canvas)` path the authoritative viewport artifact uses — is not a
     * single uniform colour (i.e. the retained pane content is actually painted, not a
     * blank surface of the right size).
     *
     * The bounded wait exists only so a transient layout pass cannot flake it; the HARD
     * assertion is the loop's exit condition, never the loop body.
     */
    private fun assertRetainedFrameIsRendered(label: String) {
        var last = "never sampled"
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = RENDERED_FRAME_TIMEOUT_MS) {
                val probe = probeRenderedTerminalFrame()
                last = probe.describe()
                probe.isRendered
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            writeText("failure-rendered-frame.txt", "$label\n$last\n")
            runCatching { captureScreen("issue822-failure-rendered-frame") }
        }
        assertTrue(
            "#822: during $label the retained terminal frame must still be RENDERED for " +
                "the user, not merely present in the terminal buffer. A collapsed " +
                "(0x0 / unshown / blank) TerminalView is the reported destruction of the " +
                "retained viewport. Observed: $last",
            satisfied,
        )
    }

    private fun probeRenderedTerminalFrame(): RenderedTerminalProbe {
        var probe = RenderedTerminalProbe(found = false)
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val onScreen = Rect()
            val hasVisibleRect = view.getGlobalVisibleRect(onScreen)
            val width = view.width
            val height = view.height
            var uniform = true
            if (width > 0 && height > 0) {
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(bitmap))
                uniform = bitmapIsUniform(bitmap)
                bitmap.recycle()
            }
            probe = RenderedTerminalProbe(
                found = true,
                shown = view.isShown,
                width = width,
                height = height,
                visibleWidth = if (hasVisibleRect) onScreen.width() else 0,
                visibleHeight = if (hasVisibleRect) onScreen.height() else 0,
                uniformPixels = uniform,
            )
        }
        return probe
    }

    /**
     * True when every pixel of the drawn frame is the same colour — a surface that is the
     * right SIZE but paints nothing (the other way a retained frame can be lost). The
     * seeded pane always carries at least the [READY_MARKER] line and a prompt, so a
     * genuinely retained frame is never uniform.
     */
    private fun bitmapIsUniform(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val first = pixels[0]
        for (pixel in pixels) {
            if (pixel != first) return false
        }
        return true
    }

    private class RenderedTerminalProbe(
        val found: Boolean,
        val shown: Boolean = false,
        val width: Int = 0,
        val height: Int = 0,
        val visibleWidth: Int = 0,
        val visibleHeight: Int = 0,
        val uniformPixels: Boolean = true,
    ) {
        val isRendered: Boolean
            get() = found && shown && width > 0 && height > 0 &&
                visibleWidth > 0 && visibleHeight > 0 && !uniformPixels

        fun describe(): String =
            "terminalViewFound=$found attachedAndShown=$shown measured=${width}x$height " +
                "onScreenRect=${visibleWidth}x$visibleHeight drawnFrameIsUniformBlank=$uniformPixels"
    }

    private fun assertNoReconnectSurface(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
        }
        assertEquals(
            "expected no connecting/reconnecting band for $label",
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

    private fun waitForDiagnostic(
        name: String,
        label: String,
        timeoutMs: Long = DIAGNOSTIC_TIMEOUT_MS,
        predicate: (RecordedDiagnosticEvent) -> Boolean = { true },
    ): RecordedDiagnosticEvent {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val match = diagnostics!!.eventsNamed(name).filter(predicate)
            if (match.isNotEmpty()) return match.last()
            SystemClock.sleep(50)
        }
        error("timed out waiting for diagnostic '$name' during $label; events=${diagnostics!!.events}")
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
                name = "issue635-socketdrop-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue635 Socket Drop Grace",
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
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf '$READY_MARKER\\n'; exec sh -i"),
            )
            appendLine("sleep 1")
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

    private suspend fun listSshdPidsForTestuser(key: String): Set<Int> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("pgrep -u $DEFAULT_USER sshd 2>/dev/null || true") }
        }
        val out = result.getOrNull()?.stdout.orEmpty()
        return out.lines().mapNotNull { it.trim().toIntOrNull() }.toSet()
    }

    private suspend fun killRemoteSshdPids(key: String, pids: Set<Int>) {
        if (pids.isEmpty()) return
        val script = buildString {
            for (pid in pids) appendLine("kill -9 $pid 2>/dev/null || true")
        }
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session -> session.use { it.exec(script) } }
        }
    }

    /**
     * Issue #822 (round 2): captures the AUTHORITATIVE `*-viewport.png` terminal artifact
     * and HARD-FAILS when it cannot.
     *
     * It used to `return@onActivity` silently when the `TerminalView` was missing or
     * measured 0x0 — so a run whose terminal had been collapsed produced no authoritative
     * viewport artifact at all and nothing noticed. That is the worst artifact shape in
     * this repo: "I could not check" reading exactly like "I checked and it is fine". The
     * missing PNG was in fact the strongest available evidence that the retained frame had
     * been destroyed, and it was thrown away. Every state this journey captures in
     * (attached, confirmed-dead-with-retained-frame, recovered) must have a measurable
     * terminal, so an unmeasurable one is a failure of the journey, never a skipped
     * screenshot.
     */
    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        val activityHolder = launchedActivity
        if (activityHolder == null) {
            writeText("$name-visible-terminal.txt", visibleTerminalText())
            runCatching { captureScreen("$name-failure-no-viewport") }
            throw AssertionError(
                "#822/#2135: the authoritative viewport artifact '$name-viewport.png' " +
                    "could not be captured: MainActivity was not available",
            )
        }
        try {
            activityHolder.onActivity { activity ->
                bitmap = captureViewToBitmap(
                    activity.window.decorView.findTerminalView(),
                    name,
                )
            }
        } catch (failed: AssertionError) {
            writeText("$name-visible-terminal.txt", visibleTerminalText())
            runCatching { captureScreen("$name-failure-no-viewport") }
            throw failed
        }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        val captured = checkNotNull(bitmap) {
            "#822/#2135: onActivity did not produce a viewport bitmap for '$name'"
        }
        writeBitmap("$name-viewport", captured)
        captured.recycle()
    }

    /**
     * Whole-window capture (advisory per the terminal-artifact rules, but the ONLY way to
     * evidence the chrome): draws the activity decor view, so the breadcrumb pill and the
     * under-header reconnect band appear alongside the retained terminal frame.
     */
    private fun captureScreen(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            bitmap = captureViewToBitmap(activity.window.decorView, name)
        }
        val captured = requireNotNull(bitmap) { "decor view was not measurable for $name" }
        writeBitmap("$name-screen", captured)
        captured.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE635_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE635_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun writeSummary(): File =
        writeText(
            "summary.txt",
            buildString {
                appendLine("test=WithinGraceSocketDropForegroundJourneyE2eTest")
                appendLine("issue=822")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("ready_marker=$READY_MARKER")
                appendLine(
                    "scenario=attach, background within grace, kill app sshd worker " +
                        "(socket drop), foreground within grace",
                )
                appendLine(
                    "expectation=retained prior viewport + honest Reconnecting + working Retry now, " +
                        "then same-session fresh-client recovery without A→B→A",
                )
                appendLine(
                    "liveness_recovery_starts=" +
                        diagnostics!!.eventsNamed("liveness_probe_silent_drop").size,
                )
                appendLine(
                    "dead_lease_recoveries=" + diagnostics!!.eventsNamed("dead_lease_recovery").size,
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
        println("ISSUE635_TIMING $line")
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
        const val LOG_TAG: String = "Issue635SocketDrop"
        const val DEVICE_DIR_NAME: String = "issue635-within-grace-socket-drop"
        const val SESSION_NAME: String = "issue635-socketdrop-proof"
        const val READY_MARKER: String = "ISSUE635-SOCKETDROP-READY"
        const val AFTER_MARKER: String = "ISSUE1954-SOCKETDROP-AFTER"

        const val WITHIN_GRACE_MS: Long = 8_000L
        const val BACKGROUND_HOLD_MS: Long = 1_500L
        const val DIAGNOSTIC_TIMEOUT_MS: Long = 8_000L
        const val POST_RECOVERY_INPUT_PAINT_MS: Long = 6_000L

        /**
         * Generous bound for the rendered-frame probe. The retained frame is a settled
         * state, not a transient one, so this only absorbs a layout pass on a contended
         * emulator; the destroyed-frame case never becomes rendered and burns the whole
         * budget before hard-failing.
         */
        const val RENDERED_FRAME_TIMEOUT_MS: Long = 10_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
