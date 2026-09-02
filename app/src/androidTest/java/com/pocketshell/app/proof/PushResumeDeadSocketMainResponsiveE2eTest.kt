package com.pocketshell.app.proof

import android.graphics.Bitmap
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import com.pocketshell.app.proof.signals.captureViewToBitmap

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
 * ## Contract (the load-bearing, freeze-detecting assertions)
 *
 * TWO measured windows, both at [MAIN_STALL_BUDGET_MS]:
 *
 *  1. The grace-loop window (the wedged socket still blackholed) — the REAL
 *     main-thread heartbeat probe's max inter-arrival gap must stay under budget,
 *     i.e. Main is NOT parked while the ladder re-dials over a dead-held socket.
 *  2. Issue #2468 — the HEAL window (fault clears → reattach settles). Live
 *     instrumentation of the real call chain showed the six
 *     `Dispatchers.Main.immediate` close sites never fire while the blackhole
 *     holds (every rung is stuck in an off-Main dial); they all run in ONE burst
 *     at the successful redial — `silentlyReconnectTransport` →
 *     `RealSshSession.close` → `TmuxClient.closeInternal` → `RealSshShell.close`,
 *     all on `main`. That burst used to land after the probe stopped, so the
 *     #1139 property was not actually policed. Window 2 measures it.
 *
 * Then the session must reconnect-or-show-Disconnected (never a permanent frozen
 * wedge).
 *
 * ## Measured window (issue #2468 — what is deliberately NOT measured)
 *
 * The probe still SAMPLES from before `moveToState(RESUMED)`, but the asserted
 * verdict starts only once the first post-resume frame has COMMITTED (the excluded
 * prefix stays on record in `main-thread-probe.txt` and in
 * `main_max_stall_including_resume_frame_ms`, so nothing is hidden — it is simply
 * not policed). That first frame is WMS-synced, so Main does not drain
 * its queue until traversal + RenderThread draw + buffer completion all finish;
 * under swiftshader on a loaded runner that ONE frame cost ~850–900ms and was the
 * entire measured "stall" in the intermittent nightly failures (runs 33591679181 /
 * 33504917674 — HWUI `Davey!` telemetry attributes ~90–112ms traversal, ~480–570ms
 * RenderThread draw, ~180–210ms buffer completion, with the first close-path event
 * on an IO thread or seconds later). It is emulator GPU-emulation cost, not app
 * work: the same test's steady-state grace loop measured a max gap of 88ms.
 * The exclusion is bounded and asserted ([RESUME_FRAME_SETTLE_CEILING_MS]); window 1
 * still spans the full ~30s retry ladder and window 2 covers the close burst, both
 * at the unchanged 750ms budget and 50ms heartbeat interval.
 *
 * ## Red→green
 *
 * On the base blocking `close()` (`runBlocking(Dispatchers.IO)` /
 * `runBlocking(...)` disconnect), `sshLeaseManager.disconnect()`
 * (RealSshSession.close, ~4s) and `staleClient.close()` (RealSshShell.close, ~2s)
 * PARK `Dispatchers.Main.immediate` at the teardown/reattach, so the probe records
 * a multi-second gap → `responsive=false` → RED. With the #1139 fix the teardown is
 * launched on an object-owned IO scope and Main stays free → GREEN.
 *
 * Verified by mutation for issue #2468 (both windows at their current scope): a
 * `Thread.sleep(CLOSE_TIMEOUT_MS)` reinstated on the caller in `RealSshShell.close()`
 * reds window 2 at 2020ms / 2027ms in 2/2 runs, located by the artifact at +0ms of
 * the fault-clear window; reverting returns it to green. That mutation run is the
 * standing answer to "is this assertion still load-bearing after the rescoping".
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

            // ---- PHASE 1: start the REAL main-thread responsiveness probe, THEN
            // foreground within grace (the push-notification tap → resume). The
            // within-grace resume hands the session to the grace ladder, which re-dials
            // the dead-held `-CC` transport from `Dispatchers.Main.immediate` for the
            // whole grace window. Its teardown closes
            // (`sshLeaseManager.disconnect()` -> `RealSshSession.close()`,
            // `staleClient.close()` -> `RealSshShell.close()`) run on Main too — but,
            // as phase 2 documents, only once a redial actually SUCCEEDS, which cannot
            // happen while the blackhole holds. So phase 1 polices "the re-dialling
            // ladder itself must not park Main" and phase 2 polices the close burst.
            //
            // The probe SAMPLES from here (so the resume-transition heartbeats are on
            // record), but the VERDICT is scoped past the resume frame — see below.
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

            // ---- Issue #2468: the VERDICT covers the GRACE LOOP, not the resume frame.
            //
            // The verdict used to cover everything from before `moveToState(RESUMED)`,
            // so it opened with the activity-restart transition itself. The FIRST
            // post-resume frame is WMS-synced: Main does not drain its queue until the
            // whole pipeline (traversal -> RenderThread draw -> buffer completion)
            // finishes, and under swiftshader on a loaded runner that single frame took
            // ~850-900ms (HWUI `Davey!` telemetry from nightly runs 33591679181 /
            // 33504917674: ~90-112ms traversal + ~480-570ms RenderThread draw + ~180-210ms
            // buffer completion, matching the observed maxStall to within one heartbeat,
            // with the first close-path event landing on an IO thread or seconds later).
            // That is emulator GPU-emulation cost the journey never intended to police —
            // on the SAME test the steady-state grace loop measured a max gap of 88ms.
            //
            // So: wait for that first frame to actually COMMIT, mark that instant, and
            // assert only on the heartbeats after it. The window that follows is ~30s of
            // pure grace-loop close/reconnect over the still-blackholed socket (the retry
            // ladder redials for the whole grace window), so the D28/D37 safety surface —
            // "the close/reconnect loop must not park Main" — is fully preserved at the
            // UNCHANGED 750ms budget and 50ms interval. Sampling still starts before the
            // resume, so the excluded frame stays ON RECORD in the artifact (and in
            // `main_max_stall_including_resume_frame_ms`) instead of vanishing.
            val resumeFrameMs = awaitPostResumeFrameCommitted()
            val measureFromUptimeMs = SystemClock.uptimeMillis()
            recordTiming("resume_frame_settle_ms", resumeFrameMs)
            assertTrue(
                "the first post-resume frame took ${resumeFrameMs}ms to commit, beyond the " +
                    "${RESUME_FRAME_SETTLE_CEILING_MS}ms ceiling — the excluded window must stay " +
                    "a single render frame, so a settle this long is itself an ANR-class resume " +
                    "and not something this journey may exclude from the measured window (#2468).",
                resumeFrameMs <= RESUME_FRAME_SETTLE_CEILING_MS,
            )

            // Hold the probe across the whole grace-loop close/reconnect window while
            // the wedged socket is STILL blackholed — this is the window that ANRs on
            // base. The probe is non-blocking (a Handler heartbeat), so it records the
            // Main-thread parks WITHOUT itself hanging the test on base.
            SystemClock.sleep(GRACE_LOOP_WINDOW_MS)
            val result = probe.stop(
                minExpectedSamples = MIN_EXPECTED_HEARTBEATS,
                sinceUptimeMs = measureFromUptimeMs,
            )
            // Diagnostic only (#2468): what the pre-fix, resume-frame-inclusive scope
            // would have concluded from the very same heartbeats. Never asserted — it is
            // the paired evidence that the excluded prefix, not the grace loop, is what
            // used to trip the budget.
            val wholeWindow = probe.analyzeAll(minExpectedSamples = MIN_EXPECTED_HEARTBEATS)
            recordTiming("main_max_stall_ms", result.maxGapMs)
            recordTiming("main_max_stall_including_resume_frame_ms", wholeWindow.maxGapMs)
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
            //
            // The artifact carries the per-gap OFFSET report (#2468) so a future
            // overshoot names its own position instead of costing a logcat/HWUI dig.
            writeText(
                "main-thread-probe.txt",
                buildString {
                    appendLine(result.message)
                    appendLine(
                        "asserted window = grace-loop close/reconnect ONLY; the first " +
                            "post-resume frame (${resumeFrameMs}ms) is EXCLUDED (#2468).",
                    )
                    appendLine(
                        "whole-window (resume-frame-INCLUSIVE, diagnostic only): " +
                            wholeWindow.message,
                    )
                    appendLine()
                    append(probe.gapReport(sinceUptimeMs = measureFromUptimeMs))
                },
            )
            assertTrue(
                "MAIN-THREAD FREEZE reproduced on the push-resume-onto-dead-socket path: " +
                    "${result.message}. maxStall=${result.maxGapMs}ms exceeds the " +
                    "${MAIN_STALL_BUDGET_MS}ms budget — the grace-loop close/reconnect " +
                    "parked Dispatchers.Main.immediate (the #1139 freeze class). " +
                    "SCOPE (#2468): the measured window starts AFTER the first post-resume " +
                    "frame committed (${resumeFrameMs}ms, excluded), so this gap is inside " +
                    "the grace-loop close/reconnect ladder itself, NOT resume/render cost. " +
                    "Do NOT re-derive the already-landed #1135/#1139 non-blocking " +
                    "RealSshShell.close()/RealSshSession.close() conversion — look for NEW " +
                    "Main-thread work on the close/reconnect path: the six " +
                    "silentlyReattach/silentlyReconnect close sites in TmuxSessionViewModel " +
                    "run on Dispatchers.Main.immediate, so any blocking call reachable from " +
                    "them (a runBlocking, a joined cancellation, a synchronous socket write) " +
                    "parks Main. The `main-thread-probe.txt` artifact lists every gap by " +
                    "OFFSET from the probe start — read it first to locate the stall.",
                result.responsive,
            )

            // ---- PHASE 2 (issue #2468) — measure the HEAL, where the six close sites
            // actually run ON MAIN.
            //
            // Instrumenting the real call chain during phase 1 (temporary `Log.i` at
            // `RealSshSession.close` / `TmuxClient.closeInternal` / `RealSshShell.close` /
            // `silentlyReattachAfterPassiveDisconnect` /
            // `silentlyReconnectTransportAfterPassiveDisconnect`) showed something the
            // journey's own KDoc did not say: while the socket stays blackholed the
            // grace ladder never REACHES a close — every rung is stuck in an off-Main
            // dial that cannot complete, so phase 1's Main thread is essentially idle
            // (max gap ~55-90ms). The six `Dispatchers.Main.immediate` close sites all
            // fire in ONE burst the moment the fault clears and the redial finally
            // succeeds:
            //
            //   silentlyReconnectTransport (main) -> RealSshSession.close (main)
            //     -> TmuxClient.closeInternal (main) -> RealSshShell.close (main)
            //
            // That burst used to land AFTER `probe.stop()`, i.e. completely outside the
            // measured window — so the #1139 property ("the teardown close must not park
            // Main") was never actually policed by this journey. Phase 2 measures exactly
            // that burst. It is what makes the rescoped assertion load-bearing rather than
            // budget-dodging: a blocking-on-caller `close()` reds HERE (proven by
            // mutation), and the close it parks on is the stale, blackholed transport.
            setForceLivenessProbeDead(false)
            val healProbe = MainThreadResponsivenessProbe(
                intervalMs = HEARTBEAT_INTERVAL_MS,
                budgetMs = MAIN_STALL_BUDGET_MS,
            )
            healProbe.start()
            val healStartedAt = SystemClock.elapsedRealtime()
            proxy.clearToxics()
            val settled = waitForConnectedOrDisconnectBand(SETTLE_WINDOW_MS)
            // Keep sampling for a floor duration so a fast heal cannot produce a
            // too-short, vacuously-green window (the #635 trap): the close burst lands
            // at the reattach, and the floor keeps the following instants measured too.
            val healElapsed = SystemClock.elapsedRealtime() - healStartedAt
            if (healElapsed < HEAL_WINDOW_FLOOR_MS) {
                SystemClock.sleep(HEAL_WINDOW_FLOOR_MS - healElapsed)
            }
            val healResult = healProbe.stop(minExpectedSamples = MIN_EXPECTED_HEAL_HEARTBEATS)
            recordTiming("main_max_stall_heal_ms", healResult.maxGapMs)
            recordTiming("main_probe_heal_samples", healResult.sampleCount.toLong())
            recordTiming("heal_window_ms", SystemClock.elapsedRealtime() - healStartedAt)
            writeText(
                "main-thread-probe-heal.txt",
                buildString {
                    appendLine(healResult.message)
                    appendLine(
                        "measured window = fault-clear -> reattach settle, i.e. the burst " +
                            "where silentlyReconnectTransport / RealSshSession.close / " +
                            "TmuxClient.closeInternal / RealSshShell.close all run on " +
                            "Dispatchers.Main.immediate (#1139's six close sites, #2468).",
                    )
                    appendLine()
                    append(healProbe.gapReport())
                },
            )
            assertTrue(
                "MAIN-THREAD FREEZE on the teardown/reattach burst: ${healResult.message}. " +
                    "maxStall=${healResult.maxGapMs}ms exceeds the ${MAIN_STALL_BUDGET_MS}ms " +
                    "budget while the stale dead-held client was being closed and replaced. " +
                    "This is the #1139 signature itself: the six silentlyReattach / " +
                    "silentlyReconnect close sites in TmuxSessionViewModel run on " +
                    "Dispatchers.Main.immediate, so any blocking call reachable from them " +
                    "(a runBlocking teardown, a joined cancellation, a synchronous socket " +
                    "write) parks Main. Read `main-thread-probe-heal.txt` — it lists every " +
                    "gap by OFFSET from the fault clear, so the stall locates itself.",
                healResult.responsive,
            )
            // Issue #2389: this capture used to race the surface. When the session
            // settles back to Connected the screen still has to release its recovery
            // hold and re-mount the Termux AndroidView (the VM flips Connected
            // FIRST), and the raw-view capture never drove the Compose frame clock,
            // so it found no TerminalView and hard-failed with the `#2135`
            // `viewFound=false` — the same ONE root cause as
            // NatIdleMappingSurvivalE2eTest (see [RecoveredTerminalViewport]). Wait
            // for the viewport to come back (bounded, hard-failing). A settled
            // DISCONNECTED band legitimately has
            // no TerminalView, so that branch captures the whole session frame
            // instead — the evidence stays authoritative either way.
            val settledConnected =
                currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
            val restoredMs = if (settledConnected) {
                RecoveredTerminalViewport.awaitRestored(
                    compose = compose,
                    scenario = launchedActivity,
                    label = "settled",
                    recordTiming = ::recordTiming,
                )
            } else {
                -1L
            }
            if (settledConnected) {
                captureViewport("issue1139-03-settled")
            } else {
                captureSessionFrame("issue1139-03-settled-disconnect-band")
            }
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
                    "resume_frame_settle_ms=$resumeFrameMs (EXCLUDED from the asserted " +
                        "window, #2468; ceiling=${RESUME_FRAME_SETTLE_CEILING_MS}ms)",
                    "main_max_stall_including_resume_frame_ms=${wholeWindow.maxGapMs} " +
                        "(diagnostic only — the pre-#2468 scope)",
                    "main_probe_scope=grace-loop close/reconnect only (the verdict starts " +
                        "after the first post-resume frame commits)",
                    "main_max_stall_heal_ms=${healResult.maxGapMs}",
                    "main_probe_heal_samples=${healResult.sampleCount}",
                    "main_responsive_heal=${healResult.responsive}",
                    "heal_probe_scope=fault-clear -> reattach settle: the six " +
                        "Dispatchers.Main.immediate close sites (#1139) run in THIS window",
                    "settled_after_clear=$settled",
                    "settled_connected=$settledConnected",
                    "settled_viewport_restored_ms=$restoredMs",
                    "expectation=Main stall < budget (no 2-4s ANR); session " +
                        "reconnects-or-Disconnected after clear",
                ),
            )
            Unit
        } }

    /**
     * Issue #2468 — block until the FIRST post-resume frame has actually been
     * drawn and committed, so the responsiveness probe measures the grace loop
     * and not the activity-restart render.
     *
     * `waitForIdleSync()` alone is not sufficient on its own: right after
     * `RESUMED` the Main queue can momentarily go idle BEFORE the vsync that
     * carries the traversal message arrives, so the idle handler can fire ahead
     * of the frame. So we additionally force a frame (`invalidate()`) and wait
     * for its COMMIT callback — a commit callback can only run once the frame
     * ahead of it in the pipeline has completed, which is exactly the WMS-synced
     * post-resume frame we want behind us. Two rounds, then a final idle drain.
     *
     * @return how long the exclusion took, ms (recorded + ceiling-asserted by the
     *   caller so the excluded window can never silently grow).
     */
    private fun awaitPostResumeFrameCommitted(): Long {
        check(android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            "ViewTreeObserver.registerFrameCommitCallback needs API 29+; " +
                "deviceApi=${android.os.Build.VERSION.SDK_INT}"
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val startedAt = SystemClock.elapsedRealtime()
        instrumentation.waitForIdleSync()
        repeat(RESUME_FRAME_SETTLE_ROUNDS) {
            val committed = CountDownLatch(1)
            launchedActivity?.onActivity { activity ->
                val decor = activity.window.decorView
                decor.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                // A clean tree draws nothing, so force a frame to exist to commit.
                decor.invalidate()
            }
            check(committed.await(RESUME_FRAME_SETTLE_CEILING_MS, TimeUnit.MILLISECONDS)) {
                "no frame committed within ${RESUME_FRAME_SETTLE_CEILING_MS}ms of the " +
                    "within-grace resume — the main thread never produced a post-resume " +
                    "frame at all, which is a worse freeze than the one under test (#2468)."
            }
        }
        instrumentation.waitForIdleSync()
        return SystemClock.elapsedRealtime() - startedAt
    }

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

    /**
     * Issue #2389: the settled-DISCONNECTED-band branch legitimately has no
     * `TerminalView` (the band replaces the surface), so its evidence is the whole
     * session frame. Still hard-fails when nothing can be captured.
     */
    private fun captureSessionFrame(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            bitmap = com.pocketshell.app.proof.signals.captureSessionFrameToBitmap(
                activity.window.decorView,
                name,
            )
        }
        val captured = checkNotNull(bitmap) {
            "activity was not available to capture session frame '$name' (#2389)"
        }
        writeBitmap("$name-session-frame", captured)
        captured.recycle()
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            bitmap = captureViewToBitmap(
                activity.window.decorView.findTerminalViewLocal(),
                name,
            )
        }
        val captured = checkNotNull(bitmap) {
            "activity was not available to capture viewport '$name' (#2135)"
        }
        writeBitmap("$name-viewport", captured)
        writeText("$name-visible-terminal.txt", visibleTerminalTextLocal())
        captured.recycle()
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

        // Issue #2468 — the excluded first-post-resume-frame window. Two forced
        // frame-commit rounds; the SECOND can only commit once the WMS-synced
        // post-resume frame ahead of it has completed.
        const val RESUME_FRAME_SETTLE_ROUNDS: Int = 2
        // Hard ceiling on how much wall clock the exclusion may consume. The worst
        // observed post-resume frame in the nightly lane was 906ms (run 33504917674),
        // so this is ~9x headroom over swiftshader's worst measured cost while still
        // hard-failing if the exclusion ever starts swallowing a real Main-thread
        // block instead of one render frame.
        const val RESUME_FRAME_SETTLE_CEILING_MS: Long = 8_000L

        // Issue #2468 phase 2 — the fault-clear -> reattach window. The floor keeps a
        // fast heal from producing a vacuously short sample window; the minimum
        // heartbeat count is derived from it (minus slack) so a wedged Main during the
        // close burst fails on sample starvation even if the max-gap maths never runs.
        const val HEAL_WINDOW_FLOOR_MS: Long = 5_000L
        const val MIN_EXPECTED_HEAL_HEARTBEATS: Int = 60

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
