package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.MainThreadResponsivenessAnalyzer
import com.pocketshell.app.proof.signals.MainThreadResponsivenessProbe
import com.pocketshell.app.tmux.LivenessProbeTestOverride
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #1139 (maintainer's #1 freeze, the top v0.4.20 release-gate item) — the
 * EXECUTED, on-the-real-path red→green proof that the push-notification →
 * resume-an-idle-overnight-session freeze is GONE (D33 / G4 / G10).
 *
 * ## The maintainer's reported symptom
 *
 * Left a session connected overnight; tapped the push notification → it navigated
 * back to the session, but the whole UI was FROZEN (buttons dead to taps) — had to
 * restart the app. Overnight the SSH/tmux `-CC` socket went half-open (NAT idle
 * timeout). On resume the app runs its grace-loop close/reconnect against that
 * DEAD-HELD socket, and the six close sites in [TmuxSessionViewModel]
 * (`silentlyReattachAfterPassiveDisconnect` / `silentlyReconnectTransportAfter
 * PassiveDisconnect`) ran on `Dispatchers.Main.immediate`. The fix (#1139) makes
 * `RealSshShell.close()` / `RealSshSession.close()` non-blocking-on-caller, so the
 * teardown socket writes no longer park Main.
 *
 * ## Why the existing socket-drop journeys do NOT catch this
 *
 * The JVM slice proves both `close()` conversions red→green at unit level, and the
 * six-site trace is a code-read — the exact "non-blocking by construction" shape
 * that was FALSE in round 1. The existing e2e journeys check the WRONG property:
 *  - `WithinGraceSocketDropForegroundJourneyE2eTest` asserts reseed + no-reconnect
 *    (a #635-class proof) — it passes with OR without this fix.
 *  - `BackgroundResumeSocketDeathE2eTest` asserts post-resume state — a transient
 *    2–4s Main ANR during the resume passes it.
 *  - The real freeze detector, [MainThreadResponsivenessProbe], was wired only to
 *    a synthetic `Thread.sleep` (`StrictModeMainThreadIoDetectorE2eTest` P2), NOT
 *    to the actual dead-socket grace-loop resume path.
 *
 * This journey closes that gap: it enters the dead-socket WITHIN-GRACE RESUME
 * state and wires the REAL [MainThreadResponsivenessProbe] to measure Main-thread
 * latency DURING the grace-loop close/reconnect, hard-asserting Main stays
 * responsive.
 *
 * ## How the dead-held socket is produced (why toxiproxy / nightly)
 *
 * The freeze only reproduces when the `-CC` teardown socket WRITE genuinely
 * WEDGES. That needs a HALF-OPEN, no-FIN socket (the overnight NAT death) — the
 * toxiproxy `addBlackhole()` (`timeout=0`) toxic. A happy `agents:2222` socket, or
 * a `kill -9`/`proxy.disable()` clean cut (RST → fast close), CANNOT wedge the
 * close and so cannot reproduce the 2–4s Main block (the v0.4.10/#847 happy-
 * fixture-masks-reality lesson). Because a blackholed socket stays "established"
 * (warm lease), the within-grace foreground would otherwise ride through
 * reseed-only and never run a close; so this ALSO arms
 * [TmuxSessionViewModel.forceLivenessProbeDeadForTest] (#780 synthetic-state
 * injection) to make the app DETECT the dead socket and run the grace-loop
 * close/reconnect over the wedged transport. The close SOCKET-WRITE is real, not
 * synthetic.
 *
 * Toxiproxy is not started by the per-push `tests.yml` job, so this class lives in
 * the NIGHTLY network-fault lane (`scripts/nightly-extensive-suite.sh`), gated by
 * [NetworkFaultProofBase.assumeNetworkFaultProofsEnabled] like every other
 * toxiproxy proof. The nightly lane runs it WITHOUT `pocketshellCi=true` (so
 * `isRunningOnCi()` is false and the guard passes) and WITH
 * `pocketshellNetworkFaultProofs=true`. There is NO self-skip on the load-bearing
 * responsiveness assertion in the lane that runs it (F3): the `assume` only gates
 * the per-push CI lane that structurally cannot start toxiproxy.
 *
 * ## Contract (the load-bearing, freeze-detecting assertion)
 *
 * Over the resume + grace-loop close/reconnect window (the wedged socket still
 * blackholed), the REAL main-thread heartbeat probe's max inter-arrival gap must
 * stay under [MAIN_STALL_BUDGET_MS] — i.e. Main is NOT parked 2–4s. Then, once the
 * fault + seam clear, the session must reconnect-or-show-Disconnected (never a
 * permanent frozen wedge).
 *
 * ## Red→green
 *
 * On the base blocking `close()` (`runBlocking(Dispatchers.IO)` /
 * `runBlocking(...)` disconnect), each grace-loop `sshLeaseManager.disconnect()`
 * (RealSshSession.close, ~4s) and `staleClient.close()` (RealSshShell.close, ~2s)
 * PARKS `Dispatchers.Main.immediate`, so the probe records a multi-second gap →
 * `responsive=false` → RED. With the #1139 fix the teardown is launched on an
 * object-owned IO scope and Main stays free → GREEN.
 */
// CI_JOURNEY_SUITE_JUSTIFIED: nightly-only toxiproxy proof (NetworkFaultProofBase
// subclass). It needs the half-open `addBlackhole` toxic to genuinely WEDGE the
// `-CC` teardown socket-write — a per-push agents:2222 (happy) fixture cannot
// reproduce the 2-4s Main-thread close block, so it cannot live in the per-push
// ci-journey-suite.sh. It runs in the nightly network-fault lane
// (scripts/nightly-extensive-suite.sh NETWORK_FAULT_CLASSES) via
// network-fault-proxy:2228 + toxiproxy API:8474, gated by
// assumeNetworkFaultProofsEnabled() exactly like every other toxiproxy proof.
@RunWith(AndroidJUnit4::class)
class PushResumeDeadSocketMainResponsiveE2eTest : NetworkFaultProofBase() {

    private var diagnostics: RecordingDiagnosticSink? = null

    @Before
    fun installDiagnostics() {
        BackgroundGraceTestOverride.setForTest(null)
        diagnostics = RecordingDiagnosticSink().also { DiagnosticEvents.install(it) }
    }

    @After
    fun resetOverrides() {
        // Best-effort: disarm the synthetic dead-socket seam so a teardown reattach
        // can heal, then clear all test overrides.
        runCatching { setForceLivenessProbeDead(false) }
        BackgroundGraceTestOverride.setForTest(null)
        LivenessProbeTestOverride.clear()
        diagnostics?.close()
        diagnostics = null
    }

    @Test
    fun withinGraceResumeOntoDeadHeldSocketKeepsMainResponsiveDuringGraceLoopClose() {
        runBlocking {
            assumeNetworkFaultProofsEnabled()

            val key = readFixtureKey()
            val marker = "pr${System.currentTimeMillis().toString(36).takeLast(5)}"
            val sessionName = "issue1139-pushresume-$marker"
            val hostName = "Issue1139 PushResume $marker"
            prepareProxyAndRemoteSession(
                key = key,
                sessionName = sessionName,
                readyText = "ISSUE1139-PUSHRESUME-READY-$marker",
            )
            // Host row points at the toxiproxy port (2228), so the app's `-CC`
            // control channel runs through the proxy we can blackhole.
            val hostRowTag = seedNetworkFaultHost(key, hostName)

            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            val attachStart = SystemClock.elapsedRealtime()
            attachToSession(hostRowTag, hostName, sessionName)
            recordTiming("attach_ms", SystemClock.elapsedRealtime() - attachStart)

            // Establish the live baseline: a fresh marker must round-trip through the
            // live `-CC` channel before we wedge it (a happy fixture that can't wedge
            // proves nothing).
            sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "before")
            waitForVisibleTerminalText("before") { "BEFORE-$marker" in it }
            waitForConnected("initial attach")
            captureViewport("issue1139-01-attached-live")
            diagnostics!!.clear()

            // Compress the timings so the resume lands well within grace and the
            // passive disconnect + grace loop fire fast against the wedged socket.
            // Production keeps its 60s grace / 10s probe defaults.
            BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)
            setPassiveDisconnectRecovery(
                graceMs = GRACE_LOOP_MS,
                silentReattachTimeoutMs = REATTACH_TIMEOUT_MS,
            )
            LivenessProbeTestOverride.setForTest(
                intervalMs = PROBE_INTERVAL_MS,
                perProbeTimeoutMs = PROBE_TIMEOUT_MS,
                failureThreshold = PROBE_FAILURE_THRESHOLD,
            )

            val proxy = toxiproxy()

            // ---- Background within grace (the overnight idle), then DEAD-HOLD the
            // socket while away. `addBlackhole` = half-open/no-FIN (NAT idle death):
            // the close socket-write WEDGES. `forceLivenessProbeDeadForTest` makes the
            // app DETECT the dead socket so the within-grace resume runs the grace-loop
            // close/reconnect (a blackhole alone stays "connected" → reseed-only, no
            // close).
            launchedActivity?.moveToState(Lifecycle.State.CREATED)
            waitForDiagnostic("background_grace_start", "within-grace background")
            proxy.addBlackhole()
            setForceLivenessProbeDead(true)
            SystemClock.sleep(BACKGROUND_HOLD_MS)

            // ---- Start the REAL main-thread responsiveness probe, THEN foreground
            // within grace (the push-notification tap → resume). The within-grace
            // resume runs the six close sites over the wedged `-CC` socket ON
            // Dispatchers.Main.immediate:
            //   sshLeaseManager.disconnect() -> RealSshSession.close()  (base ~4s Main block)
            //   staleClient.close()          -> RealSshShell.close()    (base ~2s Main block)
            val probe = MainThreadResponsivenessProbe(
                intervalMs = HEARTBEAT_INTERVAL_MS,
                budgetMs = MAIN_STALL_BUDGET_MS,
            )
            probe.start()
            val resumeAt = SystemClock.elapsedRealtime()
            launchedActivity?.moveToState(Lifecycle.State.RESUMED)
            waitForDiagnostic(
                "background_grace_foreground",
                "within-grace resume onto dead socket",
            ) { it.fields["withinGrace"] == true }

            // Hold the probe across the whole grace-loop close/reconnect window while
            // the wedged socket is STILL blackholed — this is the window that ANRs on
            // base. The probe is non-blocking (a Handler heartbeat), so it records the
            // Main-thread parks WITHOUT itself hanging the test on base.
            SystemClock.sleep(GRACE_LOOP_WINDOW_MS)
            val result = probe.stop(minExpectedSamples = MIN_EXPECTED_HEARTBEATS)
            recordTiming("main_max_stall_ms", result.maxGapMs)
            recordTiming("main_probe_samples", result.sampleCount.toLong())
            recordTiming("resume_window_ms", SystemClock.elapsedRealtime() - resumeAt)
            captureViewport("issue1139-02-during-grace-loop")

            // The session screen must still be up (a torn-down screen would be a crash,
            // not the freeze under test).
            assertTrue(
                "the tmux session screen must still be present during the resume",
                hasTagNonBlocking(TMUX_SESSION_SCREEN_TAG),
            )

            // ---- LOAD-BEARING: Main stayed responsive during the grace-loop close/
            // reconnect over the dead-held socket. RED on base (a 2–4s Main park from
            // the blocking close), GREEN with the #1139 fix.
            writeText("main-thread-probe.txt", result.message)
            assertTrue(
                "MAIN-THREAD FREEZE reproduced on the push-resume-onto-dead-socket path: " +
                    "${result.message}. maxStall=${result.maxGapMs}ms exceeds the " +
                    "${MAIN_STALL_BUDGET_MS}ms budget — the grace-loop close/reconnect " +
                    "parked Dispatchers.Main.immediate (the #1139 freeze). The fix must " +
                    "make RealSshShell.close()/RealSshSession.close() non-blocking-on-caller.",
                result.responsive,
            )

            // ---- No permanent wedge: once the fault + seam clear the SAME session must
            // reconnect-or-show-Disconnected (never a frozen UI needing a restart).
            setForceLivenessProbeDead(false)
            proxy.clearToxics()
            val settled = waitForConnectedOrDisconnectBand(SETTLE_WINDOW_MS)
            captureViewport("issue1139-03-settled")
            assertTrue(
                "after the fault cleared the session must reconnect (Connected) or show a " +
                    "Disconnected band — not a permanently frozen UI (status=" +
                    "${currentConnectionStatus()})",
                settled,
            )

            writeSummary(
                testName = "PushResumeDeadSocketMainResponsiveE2eTest",
                lines = listOf(
                    "session=$sessionName",
                    "marker=$marker",
                    "scenario=attach via proxy, background within grace, addBlackhole " +
                        "(half-open dead-held socket) + forceLivenessProbeDead, foreground " +
                        "within grace, measure Main during grace-loop close/reconnect",
                    "main_max_stall_ms=${result.maxGapMs}",
                    "main_stall_budget_ms=$MAIN_STALL_BUDGET_MS",
                    "main_probe_samples=${result.sampleCount}",
                    "main_responsive=${result.responsive}",
                    "settled_after_clear=$settled",
                    "expectation=Main stall < budget (no 2-4s ANR); session " +
                        "reconnects-or-Disconnected after clear",
                ),
            )
            Unit
        } }

    // ---- VM seams (accessed on the live VM via the launched activity) --------------

    private fun setForceLivenessProbeDead(value: Boolean) {
        launchedActivity?.onActivity { activity ->
            ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .forceLivenessProbeDeadForTest = value
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun setPassiveDisconnectRecovery(graceMs: Long, silentReattachTimeoutMs: Long) {
        launchedActivity?.onActivity { activity ->
            ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .setPassiveDisconnectRecoveryForTest(
                    graceMs = graceMs,
                    silentReattachTimeoutMs = silentReattachTimeoutMs,
                )
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
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

    private fun waitForConnected(label: String, timeoutMs: Long = CONNECTED_TIMEOUT_MS) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun waitForConnectedOrDisconnectBand(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected) {
                return true
            }
            if (hasTagNonBlocking(TMUX_SESSION_ERROR_TAG)) return true
            SystemClock.sleep(250)
        }
        return currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected ||
            hasTagNonBlocking(TMUX_SESSION_ERROR_TAG)
    }

    private fun hasTagNonBlocking(tag: String): Boolean =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)

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

    // ---- artifacts -----------------------------------------------------------------

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalViewLocal() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalTextLocal())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE1139_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE1139_TEXT ${file.absolutePath}")
        return file
    }

    private fun visibleTerminalTextLocal(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalViewLocal()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun View.findTerminalViewLocal(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalViewLocal()
            if (match != null) return match
        }
        return null
    }

    private companion object {
        // Background grace window: generous so the resume lands well within grace.
        val WITHIN_GRACE_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 40_000L else 30_000L

        // Passive-disconnect grace loop (the close/reconnect ladder). Long enough
        // that the loop keeps re-dialling + closing over the wedged socket across
        // the whole probe window, so a base build blocks Main repeatedly.
        val GRACE_LOOP_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 25_000L
        const val REATTACH_TIMEOUT_MS: Long = 2_000L

        // LivenessProbe knobs: short so the passive disconnect fires fast on the
        // dead-held socket. threshold=1 (the synthetic seam reports DEAD sustained).
        const val PROBE_INTERVAL_MS: Long = 1_000L
        const val PROBE_TIMEOUT_MS: Long = 2_000L
        const val PROBE_FAILURE_THRESHOLD: Int = 1

        const val BACKGROUND_HOLD_MS: Long = 1_500L

        // The REAL Main-thread heartbeat probe (the #933 freeze detector).
        const val HEARTBEAT_INTERVAL_MS: Long = 50L
        // Tight bound: base parks Main 2–4s per close; normal swiftshader jitter is
        // well under this (the #933 P2 detector uses 700ms for a 2000ms block).
        const val MAIN_STALL_BUDGET_MS: Long = 750L
        const val MIN_EXPECTED_HEARTBEATS: Int = 10

        // Window over which Main responsiveness is measured while the socket is
        // dead-held: multiple grace-loop iterations (each ~2–4s block on base) fall
        // inside it, so even one blocking close reds the probe.
        val GRACE_LOOP_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 32_000L else 30_000L

        val SETTLE_WINDOW_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 45_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
        const val DIAGNOSTIC_TIMEOUT_MS: Long = 12_000L
    }
}
