package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.SshLeaseManager
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileOutputStream

/**
 * Issue #635 / #636 / #754 (slice 1c-iv-c): brief-background-within-grace ride-through.
 *
 * This is the maintainer's #1 dogfood blocker reproduced end-to-end: an open
 * tmux session, a quick step-away while the link is momentarily dead (metro
 * tunnel / dead spot), and a return *within* the background grace window.
 *
 * The toxiproxy `addHalfOpenStall` toxic models that case: a half-open, no-FIN
 * byte drop (`bandwidth` `rate=0` on both streams) that keeps the socket
 * "established" (so the warm `-CC` lease is intact) while bytes are dropped,
 * and that HEALS on removal. `addBlackhole()` is the wrong fixture here —
 * Toxiproxy FINs every connection carrying a `timeout` toxic when that toxic is
 * removed (#2127 / #1678), so blackhole-then-clear is a stall followed by a
 * genuine close, not a recoverable metro-tunnel blip. The warm lease means the
 * within-grace foreground takes the #754 driver-owned RESEED-ONLY path.
 *
 * #754 hard-cut DELETED the inline `probeCurrentRuntimeOnForegroundIfNeeded →
 * connect(LifecycleReattach)` path that raised the reveal-machine hold (the
 * "Attaching…" overlay) on a confirmed-dead probe verdict even inside grace. The
 * within-grace foreground is now a driver/controller-owned reseed-only effect: it
 * re-promotes Live + heals blank panes over the still-warm `-CC` lease — NO
 * `connect()`, NO probe, NO "Attaching…" overlay.
 *
 * NEW-CONTRACT assertions (this is the #754 contract, NOT the deleted probe one):
 *  - returning within grace while the link is cut-then-restored stays Connected —
 *    no disconnect band, no Reconnecting/Connecting UI;
 *  - the within-grace foreground NEVER paints the `TMUX_SWITCHING_LOADING_TAG` /
 *    "Attaching…" overlay (the exact surface the maintainer reported);
 *  - the within-grace reattach records the driver-owned `foreground_reattach
 *    outcome=reseed_only` cause-trail, and the DELETED inline probe
 *    (`tmux_probe_result`) NEVER runs;
 *  - no extra tmux connect attempt is made (ride-through, not reconnect);
 *  - input still reaches the SAME session after the link recovers.
 *
 * The companion genuine-death case (a >grace sustained clean cut still
 * reconnects) is covered by
 * [RideThroughInterruptionE2eTest.sustainedLinkCutReconnectsCleanlyWithoutHang].
 */
@RunWith(AndroidJUnit4::class)
class WithinGraceResumeRideThroughE2eTest : NetworkFaultProofBase() {

    private var diagnostics: RecordingDiagnosticSink? = null

    @Before
    fun installDiagnostics() {
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun resetGraceOverride() {
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics?.close()
        diagnostics = null
    }

    @Test
    fun withinGraceForegroundDuringLinkCutRidesThroughWithoutReconnect() { runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "wg${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue635-wgrace-$marker"
        val hostName = "Issue635 WGrace $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE635-WGRACE-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("wgrace_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "before-wgrace")
        waitForVisibleTerminalText("before-wgrace") { "BEFORE-$marker" in it }
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "initial attach")
        waitForConnected("initial attach")
        diagnostics!!.clear()

        // Use a short grace override so the resume lands well within grace
        // without holding the cut longer than grace (which would be a genuine
        // outage). The stall stays half-open the whole time so the socket
        // remains `isConnected` (the `-CC` lease stays warm) — exactly the
        // within-grace case the #754 reseed-only path handles. #2127: must be
        // addHalfOpenStall, not addBlackhole — timeout-toxic removal FINs.
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)

        val proxy = toxiproxy()
        val cycleStart = SystemClock.elapsedRealtime()
        proxy.addHalfOpenStall()
        try {
            // Background within grace WHILE the link is cut.
            launchedActivity?.moveToState(Lifecycle.State.CREATED)
            waitForDiagnostic("background_grace_start", "within-grace background during cut")
            // Hold the cut briefly, then foreground while STILL cut — this is
            // where the OLD inline path ran the probe→connect and painted
            // "Attaching…". The new driver-owned reseed-only path must NOT.
            SystemClock.sleep(BACKGROUND_HOLD_MS)
            launchedActivity?.moveToState(Lifecycle.State.RESUMED)
            waitForDiagnostic("background_grace_foreground", "within-grace foreground during cut") {
                it.fields["withinGrace"] == true
            }
            // Watch the exact window the old probe→connect ran: the within-grace
            // reseed-only reattach must never paint the "Attaching…" overlay.
            assertNeverAttachingOverlayDuring("wgrace_during_cut", OVERLAY_WATCH_MS)
        } finally {
            proxy.clearToxics()
            recordTiming("wgrace_cut_total_ms", SystemClock.elapsedRealtime() - cycleStart)
        }

        // Ride-through: the live session is held, no disconnect band appears,
        // and the link recovers without a reconnect. The stall keeps the
        // socket established (warm lease), so the dropped bytes drain on restore
        // and the same `-CC` lease makes progress again — no fixture FIN.
        waitForNoDisconnectBandDuring("wgrace_after_restore", durationMillis = POST_RESTORE_SETTLE_MS)
        waitForConnected("within-grace foreground after restore")
        assertNoExtraConnectAttempts(
            attemptsBefore,
            expectedDelta = 1,
            label = "ride through within-grace cut without reconnect",
        )

        // #754 NEW contract: the within-grace foreground is the driver-owned
        // RESEED-ONLY effect; the deleted inline probe (tmux_probe_result) must
        // NOT run, and no foreground_runtime_probe_failed may fire.
        val causeTrail = diagnostics!!.eventsNamed("cause_trail")
        assertTrue(
            "expected a driver-owned reseed_only foreground reattach (the #754 new path); " +
                "trail=$causeTrail",
            causeTrail.any {
                it.fields["stage"] == "foreground_reattach" && it.fields["outcome"] == "reseed_only"
            },
        )
        assertTrue(
            "the deleted inline foreground probe must NOT run within grace; trail=$causeTrail",
            causeTrail.none { it.fields["stage"] == "tmux_probe_result" },
        )
        assertTrue(
            "no foreground_runtime_probe_failed may fire within grace; events=${diagnostics!!.events}",
            diagnostics!!.eventsNamed("foreground_runtime_probe_failed").isEmpty(),
        )

        // Input still reaches the SAME session after recovery.
        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "after-wgrace")
        waitForVisibleTerminalText("after-wgrace") { "AFTER-$marker" in it }
        waitForClientCountAtMost(key, sessionName, max = 1, label = "post-ride same client")

        writeSummary(
            testName = "WithinGraceResumeRideThroughE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "scenario=half-open stall, background within grace, foreground while cut, restore",
                "grace_override_ms=$WITHIN_GRACE_MS",
                "expectation=no Attaching overlay, reseed_only (no probe), no disconnect band, " +
                    "no reconnect, same client",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
                "reseed_only=" + causeTrail.count {
                    it.fields["stage"] == "foreground_reattach" && it.fields["outcome"] == "reseed_only"
                },
                "probe_results=" + causeTrail.count { it.fields["stage"] == "tmux_probe_result" },
            ),
        )
    } }

    /**
     * Issue #754 (slice 1c-iv-c): the maintainer's #1 dogfood blocker, reproduced as the
     * CONFIRMED-DEAD-within-grace case. This backgrounds within grace, then foregrounds
     * while the link is CLEANLY CUT (`proxy.disable()` drops the socket — a real
     * `DISCONNECTED`/`ERROR` channel, NOT the blackhole TIMEOUT).
     *
     * On the OLD inline path this is exactly when `probeCurrentRuntimeOnForegroundIfNeeded`
     * saw a dead channel inside grace and fired `connect(LifecycleReattach)` →
     * the reveal-machine hold (RevealState.Seeding) → the "Attaching…" (`TMUX_SWITCHING_LOADING_TAG`)
     * overlay — the D21 violation. The #754 hard-cut DELETED that inline probe path, so a
     * confirmed-dead within-grace foreground NEVER paints "Attaching…" and NEVER runs the
     * inline probe.
     *
     * Issue #1954 tightens the confirmed-dead arm: the foreground grace heal owns the
     * target/client, invalidates the proven-dead active lease through [SshLeaseManager],
     * and performs exactly one fresh transport acquisition. Liveness is deferred while
     * that owner is active, so no competing auto-reconnect ladder can reuse the corpse or
     * emit `Attaching`.
     *
     * The combined #754/#1954 invariant this test pins is therefore:
     *  - the confirmed-dead within-grace foreground NEVER paints `TMUX_SWITCHING_LOADING_TAG`
     *    / "Attaching…" (the EXACT surface the maintainer reported);
     *  - the DELETED inline probe (`tmux_probe_result` cause-trail /
     *    `foreground_runtime_probe_failed` diagnostic) NEVER runs;
     *  - one typed grace owner performs one dead-lease invalidation/fresh acquisition,
     *    while liveness starts zero recovery effects;
     *  - the prior marker remains revealed and the recovered channel accepts new input.
     */
    @Test
    fun withinGraceForegroundConfirmedDeadDoesNotShowAttachingOverlayOrReconnect() { runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "cd${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue754-confdead-$marker"
        val hostName = "Issue754 ConfDead $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE754-CONFDEAD-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("confdead_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "before-confdead")
        waitForVisibleTerminalText("before-confdead") { "BEFORE-$marker" in it }
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "initial attach")
        waitForConnected("initial attach")
        val firstClientHash = System.identityHashCode(
            requireNotNull(currentViewModel().liveTmuxClientForSendOrNullForTest()),
        )
        diagnostics!!.clear()

        // Short grace override so the resume lands well within grace.
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)

        val proxy = toxiproxy()
        val cycleStart = SystemClock.elapsedRealtime()
        try {
            // Background within grace WHILE the link is about to be cut.
            launchedActivity?.moveToState(Lifecycle.State.CREATED)
            waitForDiagnostic("background_grace_start", "within-grace background")
            // CLEAN CUT: disable the proxy so the socket drops — a foreground probe
            // would see DISCONNECTED/ERROR (confirmed dead), NOT a ride-through TIMEOUT.
            proxy.disable()
            // Foreground while STILL cut — this is where the OLD inline path showed
            // "Attaching…" (the deleted probe→connect). The new path must NOT paint the
            // overlay and must NOT run the inline probe, even though the channel is dead.
            launchedActivity?.moveToState(Lifecycle.State.RESUMED)
            // Deterministic signal: wait for the within-grace foreground to be PROCESSED
            // (not a fixed sleep) before judging the overlay/probe invariant.
            waitForDiagnostic("background_grace_foreground", "within-grace foreground during cut") {
                it.fields["withinGrace"] == true
            }
            // The exact window the old probe→connect ran (and any subsequent honest
            // recovery): the confirmed-dead within-grace foreground must NEVER paint the
            // "Attaching…" overlay. Watched continuously, not sampled once.
            assertNeverAttachingOverlayDuring("confdead_during_cut", OVERLAY_WATCH_MS)
            val heldText = captureTerminalViewport("issue1954-confdead-post-resume-no-overlay")
            assertTrue(
                "the no-overlay viewport must still reveal the prior marker; text=$heldText",
                "BEFORE-$marker" in heldText,
            )
        } finally {
            proxy.enable()
            recordTiming("confdead_cut_total_ms", SystemClock.elapsedRealtime() - cycleStart)
        }

        // Keep watching across the restore window: the overlay must stay absent through
        // the honest reconnect of the genuinely-dead socket too — the OLD inline probe
        // surfaced "Attaching…" specifically on the confirmed-dead verdict, so the
        // invariant holds across the whole confirmed-dead journey, not just the foreground
        // instant.
        assertNeverAttachingOverlayDuring("confdead_after_restore", POST_RESTORE_SETTLE_MS)
        waitForDiagnostic("dead_lease_recovery", "confirmed-dead lease replacement")
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentViewModel().liveTmuxClientForSendOrNullForTest()?.let { client ->
                System.identityHashCode(client) != firstClientHash && !client.disconnected.value
            } == true
        }
        waitForConnected("confirmed-dead within-grace recovery")
        assertNoExtraConnectAttempts(
            attemptsBefore,
            expectedDelta = 1,
            label = "single grace-owned recovery without reconnect ladder",
        )

        // #754 INVARIANT — the deleted inline foreground probe NEVER runs, even on a
        // confirmed-dead channel inside grace. (We do NOT assert a reseed-only fast-path:
        // a clean-cut socket is dead by foreground time, so the reseed gate correctly
        // declines and the connection honestly reconnects — see the kdoc.)
        val causeTrail = diagnostics!!.eventsNamed("cause_trail")
        assertTrue(
            "the deleted inline foreground probe must NOT run within grace; trail=$causeTrail",
            causeTrail.none { it.fields["stage"] == "tmux_probe_result" },
        )
        assertTrue(
            "no foreground_runtime_probe_failed may fire within grace; events=${diagnostics!!.events}",
            diagnostics!!.eventsNamed("foreground_runtime_probe_failed").isEmpty(),
        )
        // The within-grace foreground itself must NOT request a connect(LifecycleReattach)
        // — that is the OLD overlay-raising effect #754 deletes. (A later honest reconnect
        // of the genuinely-dead socket runs its OWN auto-reconnect ladder, which is the
        // `stage=connect_attempt`/`reconnect_start` path, NOT a within-grace
        // `foreground_reattach outcome=connect_requested`.)
        assertTrue(
            "within-grace foreground must NOT request a connect(LifecycleReattach); trail=$causeTrail",
            causeTrail.none {
                it.fields["stage"] == "foreground_reattach" &&
                    it.fields["outcome"] == "connect_requested"
            },
        )

        val deadLeaseRecovery = diagnostics!!.eventsNamed("dead_lease_recovery")
        assertEquals(
            "the grace owner must invalidate/acquire the dead lease exactly once",
            1,
            deadLeaseRecovery.size,
        )
        assertEquals(true, deadLeaseRecovery.single().fields["invalidatedLease"])
        assertEquals(true, deadLeaseRecovery.single().fields["freshTransport"])
        assertEquals(
            "liveness must not start a competing recovery effect for the owned client",
            0,
            diagnostics!!.eventsNamed("liveness_probe_silent_drop").size,
        )

        val afterMarker = "AFTER-$marker"
        sendCommandThroughTerminalInput("printf '$afterMarker\\n'", "after-confdead")
        waitForVisibleTerminalText("after-confdead") { afterMarker in it }
        captureTerminalViewport("issue1954-confdead-recovered")

        writeSummary(
            testName = "WithinGraceForegroundConfirmedDeadE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "scenario=clean cut (proxy disable), background within grace, foreground while cut",
                "grace_override_ms=$WITHIN_GRACE_MS",
                "expectation=one grace-owned fresh recovery, no liveness owner, no Attaching overlay",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
                "dead_lease_recoveries=${deadLeaseRecovery.size}",
                "liveness_recovery_starts=" +
                    diagnostics!!.eventsNamed("liveness_probe_silent_drop").size,
                "probe_results=" + causeTrail.count { it.fields["stage"] == "tmux_probe_result" },
                "foreground_reattach_outcomes=" + causeTrail
                    .filter { it.fields["stage"] == "foreground_reattach" }
                    .joinToString(",") { "${it.fields["outcome"]}" },
            ),
        )
    } }

    /**
     * Issue #754: assert the "Attaching…" SwitchingLoadingPlaceholder overlay
     * ([TMUX_SWITCHING_LOADING_TAG]) is NEVER painted across [durationMs]. This is the
     * exact surface the maintainer reported; a single appearance fails the proof.
     */
    private fun assertNeverAttachingOverlayDuring(label: String, durationMs: Long) {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < durationMs) {
            val overlayTagCount = compose
                .onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
            assertEquals(
                "expected no 'Attaching…' switching-loading overlay during $label",
                0,
                overlayTagCount,
            )
            val overlayTextCount = compose
                .onAllNodesWithText("Attaching", substring = true, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size
            assertEquals(
                "expected no visible 'Attaching' text during $label",
                0,
                overlayTextCount,
            )
            SystemClock.sleep(100)
        }
        recordTiming("${label}_no_overlay_ms", SystemClock.elapsedRealtime() - start)
    }

    private fun waitForConnected(label: String, timeoutMs: Long = CONNECTED_TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertEquals(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            true,
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
        return requireNotNull(vm) { "MainActivity/TmuxSessionViewModel unavailable" }
    }

    /** Direct TerminalView draw + same-view text: authoritative in-app viewport evidence. */
    private fun captureTerminalViewport(name: String): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        var visibleText = ""
        launchedActivity?.onActivity { activity ->
            val view = requireNotNull(activity.window.decorView.findTerminalView()) {
                "TerminalView missing while capturing $name"
            }
            check(view.width > 0 && view.height > 0) {
                "TerminalView has invalid dimensions ${view.width}x${view.height} for $name"
            }
            bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also {
                view.draw(Canvas(it))
            }
            visibleText = view.currentSession?.emulator?.screen?.transcriptText.orEmpty()
        }
        val captured = requireNotNull(bitmap) { "MainActivity unavailable while capturing $name" }
        val png = artifactFile("$name-viewport.png")
        FileOutputStream(png).use { output ->
            check(captured.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "failed to write $png"
            }
        }
        artifactFile("$name-visible-terminal.txt").writeText(visibleText)
        captured.recycle()
        return visibleText
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            getChildAt(index).findTerminalView()?.let { return it }
        }
        return null
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

    private companion object {
        const val WITHIN_GRACE_MS: Long = 8_000L
        const val BACKGROUND_HOLD_MS: Long = 1_500L
        const val OVERLAY_WATCH_MS: Long = 2_500L
        const val POST_RESTORE_SETTLE_MS: Long = 4_000L
        const val DIAGNOSTIC_TIMEOUT_MS: Long = 8_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
    }
}
