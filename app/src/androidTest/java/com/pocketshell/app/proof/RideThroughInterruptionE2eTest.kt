package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.App
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.DiagnosticsEvent
import com.pocketshell.app.diagnostics.MirroredDiagnostics
import com.pocketshell.app.tmux.PASSIVE_DISCONNECT_GRACE_MS
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionJournalSchema
import com.pocketshell.core.connection.LivenessProbe
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #552 / #1676: connection-resilience ride-through proof, REALIGNED to the
 * CURRENT ride-through connection contract.
 *
 * Drives the maintainer's metro-tunnel case through the toxiproxy
 * `network-fault-proxy` harness.
 *
 * ## What changed and why (issue #1676, maintainer-approved option 3)
 *
 * The original `sustainedLinkCutReconnectsCleanlyWithoutHang` waited for the SETTLED
 * Failed band (`TMUX_SESSION_ERROR_TAG`) and then required a MANUAL Reconnect tap
 * (`tapReconnectAndWait` + `assertNoExtraConnectAttempts(delta=2)`). That encodes the
 * SUPERSEDED #342/#552 contract. Under the CURRENT deliberate ride-through contract
 * (#1610/#1654/#1633/#754/#1703) the app AUTO-reconnects through the bounded 8-rung
 * ladder; the settled Failed band renders ONLY at give-up (~119–270s), and the fast
 * honest recovery signal is the Reconnecting indicator (top-chrome "Reconnecting"
 * pill + centered "Attaching…" hold + VM `ConnectionStatus.Reconnecting`), captured
 * by [waitForReconnectingRecoveryBand]. The realigned test asserts the fast
 * Reconnecting indicator surfaces (never the settled Failed band) and the session
 * AUTO-recovers to a usable Connected session once the link restores within the
 * episode budget — no manual tap.
 *
 * ## Issue #1678 — the five-second half-open journey is a REAL gate again
 *
 * [briefLinkCutRidesThroughWithoutDisconnectOrTeardown] used to open with an
 * unconditional `Assume.assumeTrue(..., false)`, so the release-gating nightly
 * phase-2 verdict was green while carrying one skip: the five-second contract was
 * never executed. The literal-false skip is deleted and the journey now carries its
 * own proof that it did the thing it claims:
 *
 * 1. **The fault engaged.** A wholly independent SSH session, established through
 *    the SAME `network-fault-proxy:2228` port PocketShell uses, round-trips a nonce
 *    BEFORE the cut. After the symmetric heal-able stall is installed, a
 *    command issued on that same starved session must NOT complete and must NOT
 *    create its remote file for the whole physical five-second window, verified by
 *    a third, UNPROXIED direct SSH observer on port 2222. A no-op
 *    `addHalfOpenStall()` reddens this immediately.
 * 2. **Nothing recovered.** Every ~200 ms tick from before install through ≥4 s
 *    after restore hard-asserts VM `Connected`, zero Reconnecting pill / Attaching
 *    hold / Retry-now band / connecting progress row / settled Failed band, zero
 *    extra dial, an unchanged connect generation, no active connect job, an
 *    unchanged live control-client identity that is not disconnected, controller
 *    `Live`, an unchanged exact server-side tmux client list, and zero typed
 *    recovery/drop/reconnect/timeout diagnostics.
 * 3. **The restore was real and the session is usable.** A FRESH same-proxy SSH
 *    connection must complete after the toxics clear, and a command typed through
 *    PocketShell's real `TerminalView` must create an exact remote file that the
 *    unproxied observer reads back — a server-observable side effect, never the
 *    terminal echo of the command text (the #1676 echo-oracle trap).
 *
 * [cleanCloseControlSurfacesHonestRecoveryToTheSameObserver] is the positive
 * control for exactly that observer: a genuine clean socket close, watched by the
 * SAME sampler, MUST turn it red (an honest user-visible recovery surface appears)
 * and the app must then auto-heal to a usable session. Without it, "the observer
 * stayed silent" would be indistinguishable from "the observer cannot speak".
 *
 * ### Why the fixture changed, and what the old failure actually was (#1678)
 *
 * The brief journey used [ToxiproxyControl.addBlackhole] (`timeout=0`) and then
 * `clearToxics()`. Measured directly against this fixture, **Toxiproxy closes every
 * connection carrying a `timeout` toxic when that toxic is removed**: a raw TCP
 * socket holding a live SSH banner reads EOF on the first read after the DELETE.
 * So "5 s blackhole then clear" was physically a five-second stall FOLLOWED BY A
 * GENUINE REMOTE FIN, and the app's `reader_exception`/`eof` -> silent-reattach
 * response to it was CORRECT. Whether that honest recovery appeared before the
 * observation window closed was a race with runner speed — which is precisely the
 * "anti-correlated FAST-night flake" #1676 observed and could not explain. It was
 * never evidence about production ride-through policy, so nothing in
 * `core-connection` is changed here.
 *
 * The brief journey now uses [ToxiproxyControl.addHalfOpenStall] (`bandwidth`
 * `rate = 0`, both directions), which starves the same socket identically and
 * genuinely HEALS on removal. The sustained case deliberately keeps a real close
 * (`toxiproxy disable`) because a genuine outage is what it is testing.
 */
@RunWith(AndroidJUnit4::class)
class RideThroughInterruptionE2eTest : NetworkFaultProofBase() {

    /**
     * A brief half-open blip must ride through: open a session, starve the link for
     * ~5s (a half-open no-FIN wedge, shorter than any detection budget), then restore
     * it. The session must be held — no false disconnected band during or after the
     * blip, and the same tmux session resumes so input reaches the agent again
     * without teardown/reconnect.
     *
     * Issue #1678: production connection timing is deliberately untouched by this
     * change. If a fully-engaged run ever leaves `Connected`, the failure artifact
     * carries the complete typed producer/cause chain so the D28 decision can be
     * made from evidence instead of a guess.
     */
    @Test
    fun briefLinkCutRidesThroughWithoutDisconnectOrTeardown() { runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "rt${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue552-ride-$marker"
        val hostName = "Issue552 Ride $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE552-RIDE-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("ride_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "before-ride")
        waitForVisibleTerminalText("before-ride") { "BEFORE-$marker" in it }
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "initial attach")
        waitForConnectedStatus("brief initial attach")
        val briefPreCutViewport = captureAuthoritativeTerminalViewport("brief-pre-cut")
        assertTrue(
            "authoritative pre-cut terminal text did not contain the baseline marker: $briefPreCutViewport",
            "BEFORE-$marker" in briefPreCutViewport,
        )

        val baseline = captureIdentityBaseline("brief")

        val cutSentinelPath = "/tmp/pocketshell-issue1678-cut-$marker"
        val baselineSentinelPath = "/tmp/pocketshell-issue1678-baseline-$marker"
        val restoreSentinelPath = "/tmp/pocketshell-issue1678-restored-$marker"
        val appMarkerPath = "/tmp/pocketshell-issue1678-app-$marker"
        val cutSentinelMarker = "CUT-SENTINEL-$marker"
        val baselineSentinelMarker = "BASELINE-SENTINEL-$marker"
        val restoreSentinelMarker = "RESTORED-SENTINEL-$marker"
        val appMarker = "APP-RESTORED-$marker"
        val remotePaths = arrayOf(cutSentinelPath, baselineSentinelPath, restoreSentinelPath, appMarkerPath)

        val snapshots = mutableListOf<BriefRideThroughSnapshot>()
        val phases = mutableListOf<BriefPhaseBoundary>()
        val proxy = toxiproxy()

        var observer: SshSession? = null
        var proxiedSentinel: SshSession? = null
        var cutExec: Deferred<SentinelOutcome>? = null
        var cutDurationMs: Long? = null
        var postRestoreObservedMs = 0L
        var baselineSentinelCompleted = false
        var cutSentinelCompletedDuringCut = false
        var cutSentinelMarkerDuringCut = false
        var cutSentinelOutcomeAfterRestore = "not-awaited"
        var cutSentinelRecoveredAfterRestore = false
        var cutSentinelMarkerAfterRestoreVerified = false
        var restoredSentinelCompleted = false
        var appMarkerVerified = false
        var phaseCompleted = false
        var proxyStateBeforeCut = "unread"
        var proxyStateDuringCut = "unread"
        var proxyStateAfterRestore = "unread"
        var failure: Throwable? = null
        var serverClientsBefore: List<String> = emptyList()

        startConnectionDiagnosticCapture()
        val episodeStartNanos = SystemClock.elapsedRealtimeNanos()
        val episodeStartMs = SystemClock.elapsedRealtime()

        try {
            // #2397: prove the archives this proof's negative claims read are live
            // for THIS episode before any of those claims is made.
            assertDiagnosticCaptureIsLive(episodeStartNanos, marker)

            val directObserver = openDirectFixtureObserver(key)
            observer = directObserver
            removeRemoteFiles(directObserver, *remotePaths)

            serverClientsBefore = serverClientIdentities(directObserver, sessionName)
            assertEquals(
                "brief proof baseline must have exactly one server-side tmux client, got $serverClientsBefore",
                1,
                serverClientsBefore.size,
            )

            // The engagement oracle's own control: the SAME proxy port PocketShell
            // rides must demonstrably work before the fault, or a later "it did not
            // complete" would prove nothing about the toxic.
            val proxiedSession = openProxiedSentinelSession(key)
            proxiedSentinel = proxiedSession
            val baselineOutcome = withTimeout(PROXY_SENTINEL_BASELINE_TIMEOUT_MS) {
                runSentinelCommand(proxiedSession, baselineSentinelPath, baselineSentinelMarker)
            }
            baselineSentinelCompleted = baselineOutcome.succeededWith(baselineSentinelMarker)
            assertTrue(
                "the same-proxy SSH sentinel could not complete BEFORE fault installation; a later " +
                    "blocked attempt would then prove nothing about engagement: $baselineOutcome",
                baselineSentinelCompleted,
            )
            waitForExactRemoteFile(
                directObserver,
                baselineSentinelPath,
                baselineSentinelMarker,
                "pre-cut baseline sentinel",
            )
            phases += BriefPhaseBoundary("baseline_sentinel_completed", elapsedMs(episodeStartMs))

            proxyStateBeforeCut = proxy.state().toString()
            phases += BriefPhaseBoundary("pre_install", elapsedMs(episodeStartMs))
            captureBriefSnapshot(
                snapshots = snapshots,
                phase = "pre_install",
                episodeStartMs = episodeStartMs,
                episodeStartNanos = episodeStartNanos,
                observer = directObserver,
                sessionName = sessionName,
                baseline = baseline,
                serverClientsBefore = serverClientsBefore,
            ).assertSilent("pre_install")

            proxy.addHalfOpenStall()
            val installedAtMs = SystemClock.elapsedRealtime()
            proxyStateDuringCut = proxy.state().toString()
            phases += BriefPhaseBoundary("toxic_installed", elapsedMs(episodeStartMs))

            // G6 / #1678: a command on the ALREADY-ESTABLISHED same-proxy session,
            // i.e. exactly the condition PocketShell's own live socket is in. If
            // `addHalfOpenStall()` is mutated to a no-op it completes in
            // milliseconds and its remote file appears at the first tick, reddening
            // BOTH halves of the engagement oracle.
            val runningCutExec = async(Dispatchers.IO) {
                runSentinelCommand(proxiedSession, cutSentinelPath, cutSentinelMarker)
            }
            cutExec = runningCutExec

            val cutDeadline = installedAtMs + BRIEF_BLIP_MS
            // Sample only while a whole snapshot still fits inside the window, so a
            // slow tick cannot push the PHYSICAL cut past BRIEF_BLIP_MAX_MS and
            // disqualify an otherwise-valid iteration. The tail of the window is
            // slept out, and the window itself is still measured and asserted.
            while (SystemClock.elapsedRealtime() + SNAPSHOT_BUDGET_MS < cutDeadline) {
                val snapshot = captureBriefSnapshot(
                    snapshots = snapshots,
                    phase = "fault_engaged",
                    episodeStartMs = episodeStartMs,
                    episodeStartNanos = episodeStartNanos,
                    observer = directObserver,
                    sessionName = sessionName,
                    baseline = baseline,
                    serverClientsBefore = serverClientsBefore,
                    sentinelRemotePath = cutSentinelPath,
                    sentinelCompleted = runningCutExec.isCompleted,
                )
                cutSentinelCompletedDuringCut = cutSentinelCompletedDuringCut || snapshot.sentinelCompleted
                cutSentinelMarkerDuringCut = cutSentinelMarkerDuringCut || snapshot.sentinelRemoteMarkerPresent
                snapshot.assertSilent("fault_engaged")
                assertFalse(
                    "the same-proxy SSH sentinel COMPLETED during the ${BRIEF_BLIP_MS}ms cut — the " +
                        "Toxiproxy blackhole did not engage: ${snapshot.asLine()}",
                    snapshot.sentinelCompleted,
                )
                assertFalse(
                    "the same-proxy SSH sentinel REACHED THE SERVER during the ${BRIEF_BLIP_MS}ms cut — " +
                        "the Toxiproxy blackhole did not engage: ${snapshot.asLine()}",
                    snapshot.sentinelRemoteMarkerPresent,
                )
                val remaining = cutDeadline - SystemClock.elapsedRealtime()
                if (remaining > 0L) SystemClock.sleep(minOf(BRIEF_SAMPLE_INTERVAL_MS, remaining))
            }
            // The fault MUST stay installed for the whole physical five seconds even
            // if sampling finished early — the window is the thing under test.
            (cutDeadline - SystemClock.elapsedRealtime())
                .takeIf { it > 0L }
                ?.let { SystemClock.sleep(it) }
            assertTrue(
                "the ${BRIEF_BLIP_MS}ms engaged cut must be sampled, not merely slept through; " +
                    "observed ticks=${snapshots.count { it.phase == "fault_engaged" }}",
                snapshots.count { it.phase == "fault_engaged" } >= MIN_ENGAGED_TICKS,
            )

            proxy.clearToxics()
            val clearedAtMs = SystemClock.elapsedRealtime()
            cutDurationMs = clearedAtMs - installedAtMs
            proxyStateAfterRestore = proxy.state().toString()
            phases += BriefPhaseBoundary("toxic_cleared", elapsedMs(episodeStartMs))
            recordTiming("ride_blip_actual_ms", clearedAtMs - installedAtMs)
            assertTrue(
                "the qualifying brief fault must stay inside the exact five-second window " +
                    "[$BRIEF_BLIP_MS,$BRIEF_BLIP_MAX_MS]ms; observed=${cutDurationMs}ms",
                cutDurationMs in BRIEF_BLIP_MS..BRIEF_BLIP_MAX_MS,
            )

            // The settle window: the user contract stays silent for at least
            // POST_RESTORE_SETTLE_MS after the link is healthy again.
            val postRestoreStart = SystemClock.elapsedRealtime()
            val postRestoreDeadline = postRestoreStart + POST_RESTORE_SETTLE_MS
            do {
                captureBriefSnapshot(
                    snapshots = snapshots,
                    phase = "post_restore",
                    episodeStartMs = episodeStartMs,
                    episodeStartNanos = episodeStartNanos,
                    observer = directObserver,
                    sessionName = sessionName,
                    baseline = baseline,
                    serverClientsBefore = serverClientsBefore,
                ).assertSilent("post_restore")
                val remaining = postRestoreDeadline - SystemClock.elapsedRealtime()
                if (remaining > 0L) SystemClock.sleep(minOf(BRIEF_SAMPLE_INTERVAL_MS, remaining))
            } while (SystemClock.elapsedRealtime() < postRestoreDeadline)
            postRestoreObservedMs = SystemClock.elapsedRealtime() - postRestoreStart
            phases += BriefPhaseBoundary("post_restore_observation_complete", elapsedMs(episodeStartMs))
            assertTrue(
                "post-restore observation must cover at least ${POST_RESTORE_SETTLE_MS}ms, " +
                    "observed=${postRestoreObservedMs}ms",
                postRestoreObservedMs >= POST_RESTORE_SETTLE_MS,
            )

            // The already-established same-proxy session is the primary restore
            // oracle. It must drain the exact command that was blocked by the
            // half-open stall, and the independent observer must see its unique
            // server-side marker. A fresh connection below remains a corroborating
            // check that the proxy itself accepts new traffic after healing.
            val cutOutcomeAfterRestore =
                withTimeoutOrNull(SENTINEL_DRAIN_TIMEOUT_MS) { runningCutExec.await() }
            val completedCutOutcome = requireNotNull(cutOutcomeAfterRestore) {
                "the original same-proxy sentinel did not complete within " +
                    "${SENTINEL_DRAIN_TIMEOUT_MS}ms after the half-open stall was healed"
            }
            cutSentinelOutcomeAfterRestore = completedCutOutcome.evidence(cutSentinelMarker)
            cutSentinelRecoveredAfterRestore =
                completedCutOutcome.succeededWith(cutSentinelMarker)
            assertTrue(
                "the already-established same-proxy SSH sentinel did not complete after the " +
                    "half-open stall was healed: $cutSentinelOutcomeAfterRestore",
                cutSentinelRecoveredAfterRestore,
            )
            val cutSentinelRemoteMarker = waitForExactRemoteFile(
                directObserver,
                cutSentinelPath,
                cutSentinelMarker,
                "post-restore same-proxy sentinel",
            )
            assertEquals(
                "the independent observer returned an unexpected original same-proxy sentinel marker",
                cutSentinelMarker,
                cutSentinelRemoteMarker,
            )
            cutSentinelMarkerAfterRestoreVerified = true

            val restoreOutcome = withTimeout(PROXY_SENTINEL_RESTORE_TIMEOUT_MS) {
                openProxiedSentinelSession(key).use { fresh ->
                    runSentinelCommand(fresh, restoreSentinelPath, restoreSentinelMarker)
                }
            }
            restoredSentinelCompleted = restoreOutcome.succeededWith(restoreSentinelMarker)
            assertTrue(
                "a FRESH same-proxy SSH sentinel did not complete after the toxics cleared: $restoreOutcome",
                restoredSentinelCompleted,
            )
            waitForExactRemoteFile(
                directObserver,
                restoreSentinelPath,
                restoreSentinelMarker,
                "post-restore fresh sentinel",
            )
            phases += BriefPhaseBoundary("restore_sentinel_completed", elapsedMs(episodeStartMs))

            // The post-restore oracle is SERVER state, not terminal echo (#1676):
            // PocketShell types through its real TerminalView and the independent
            // UNPROXIED observer must read the exact remote file back.
            sendCommandThroughTerminalInput(
                "printf '%s' ${shellQuote(appMarker)} > ${shellQuote(appMarkerPath)}",
                "post-restore-server-marker",
            )
            waitForExactRemoteFile(
                directObserver,
                appMarkerPath,
                appMarker,
                "PocketShell post-restore marker",
            )
            appMarkerVerified = true
            val briefPostRestoreViewport = captureAuthoritativeTerminalViewport("brief-post-restore")
            assertTrue(
                "authoritative post-restore terminal text did not contain the server marker: " +
                    briefPostRestoreViewport,
                appMarker in briefPostRestoreViewport,
            )
            phases += BriefPhaseBoundary("app_server_marker_verified", elapsedMs(episodeStartMs))

            val (connectionEvents, journalEvents) = episodeDiagnostics(episodeStartNanos)
            assertMonotonicDiagnostics("connection", connectionEvents)
            assertMonotonicDiagnostics("controller journal", journalEvents)
            assertNoForbiddenRecoveryEvents(connectionEvents, journalEvents, "final brief episode")
            phaseCompleted = true
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            runCatching { cutExec?.cancel() }
            runCatching { proxy.clearToxics() }
            writeBriefRideThroughEvidence(
                episodeStartNanos = episodeStartNanos,
                snapshots = snapshots,
                phases = phases,
                cutDurationMs = cutDurationMs,
                postRestoreObservedMs = postRestoreObservedMs,
                baselineSentinelCompleted = baselineSentinelCompleted,
                cutSentinelCompletedDuringCut = cutSentinelCompletedDuringCut,
                cutSentinelMarkerDuringCut = cutSentinelMarkerDuringCut,
                cutSentinelOutcomeAfterRestore = cutSentinelOutcomeAfterRestore,
                cutSentinelRecoveredAfterRestore = cutSentinelRecoveredAfterRestore,
                cutSentinelMarkerAfterRestoreVerified = cutSentinelMarkerAfterRestoreVerified,
                restoredSentinelCompleted = restoredSentinelCompleted,
                appMarkerVerified = appMarkerVerified,
                phaseCompleted = phaseCompleted,
                proxyStateBeforeCut = proxyStateBeforeCut,
                proxyStateDuringCut = proxyStateDuringCut,
                proxyStateAfterRestore = proxyStateAfterRestore,
                failure = failure,
            )
            runCatching { observer?.let { removeRemoteFiles(it, *remotePaths) } }
            runCatching { proxiedSentinel?.close() }
            runCatching { observer?.close() }
        }

        writeSummary(
            testName = "RideThroughInterruptionE2eTest-brief",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "cut=toxiproxy bandwidth rate=0 (heal-able no-FIN stall) " +
                    "target=${BRIEF_BLIP_MS}ms actual=${cutDurationMs}ms",
                "proxy_before_cut=$proxyStateBeforeCut",
                "proxy_during_cut=$proxyStateDuringCut",
                "proxy_after_restore=$proxyStateAfterRestore",
                "same_proxy_sentinel_completed_before_cut=$baselineSentinelCompleted",
                "same_proxy_sentinel_blocked_during_cut=${!cutSentinelCompletedDuringCut}",
                "same_proxy_sentinel_marker_absent_during_cut=${!cutSentinelMarkerDuringCut}",
                "same_proxy_sentinel_completed_after_restore_existing_connection=$cutSentinelRecoveredAfterRestore",
                "same_proxy_sentinel_marker_verified_after_restore=$cutSentinelMarkerAfterRestoreVerified",
                "same_proxy_sentinel_completed_after_restore=$restoredSentinelCompleted",
                "post_restore_observed_ms=$postRestoreObservedMs",
                "app_server_marker_verified=$appMarkerVerified",
                "observed_ticks=${snapshots.size}",
                "expectation=Connected throughout; zero recovery signals/dials/client replacement",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - baseline.connectAttempts}",
            ),
        )
    } }

    /**
     * Issue #1678 POSITIVE CONTROL — the silent observer must be able to speak.
     *
     * [briefLinkCutRidesThroughWithoutDisconnectOrTeardown]'s whole load-bearing
     * claim is a NEGATIVE one ("no recovery surfaced"), and a negative claim from a
     * sampler that can never fire is exactly the G6 wrong-cost trap that produced
     * the vacuous `waitForNoDisconnectBandDuring` this issue replaces. So the SAME
     * sampler is pointed at a genuine clean socket close: it MUST report an honest,
     * user-visible recovery surface, and the app MUST then auto-heal to a session
     * that still executes real input on the server.
     *
     * Note the deliberate asymmetry with [sustainedLinkCutReconnectsCleanlyWithoutHang]:
     * that test proves the PRODUCT recovers; this one proves the OBSERVER used by
     * the brief journey detects the recovery. Deleting either the recovery-surface
     * fields or the identity fields from the sampler reddens this test while the
     * brief journey stays green — the selectivity that makes the negative claim mean
     * something.
     */
    @Test
    fun cleanCloseControlSurfacesHonestRecoveryToTheSameObserver() { runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "cc${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue1678-control-$marker"
        val hostName = "Issue1678 Control $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE1678-CONTROL-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachToSession(hostRowTag, hostName, sessionName)
        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "before-control")
        waitForVisibleTerminalText("before-control") { "BEFORE-$marker" in it }
        waitForConnectedStatus("control initial attach")
        val controlPreCloseViewport = captureAuthoritativeTerminalViewport("control-pre-close")
        assertTrue(
            "authoritative control pre-close terminal text did not contain the baseline marker: " +
                controlPreCloseViewport,
            "BEFORE-$marker" in controlPreCloseViewport,
        )

        val baseline = captureIdentityBaseline("control")
        val appMarkerPath = "/tmp/pocketshell-issue1678-control-app-$marker"
        val appMarker = "CONTROL-RECOVERED-$marker"

        val snapshots = mutableListOf<BriefRideThroughSnapshot>()
        val proxy = toxiproxy()
        var observer: SshSession? = null
        var readerEofDeferred: Deferred<DiagnosticsEvent>? = null
        var readerEofDiagnostic: DiagnosticsEvent? = null
        var firstViolatingTick: BriefRideThroughSnapshot? = null
        var recoveryDetectedMs = -1L
        var readerEofDetectedMs = -1L
        var productionDisconnectTriggered = false
        var appMarkerVerified = false
        var failure: Throwable? = null

        startConnectionDiagnosticCapture()
        val episodeStartNanos = SystemClock.elapsedRealtimeNanos()
        val episodeStartMs = SystemClock.elapsedRealtime()

        try {
            // #2397: the positive control's own typed-EOF oracle reads the same
            // archives, so prove they can speak before trusting either verdict.
            assertDiagnosticCaptureIsLive(episodeStartNanos, marker)

            val directObserver = openDirectFixtureObserver(key)
            observer = directObserver
            removeRemoteFiles(directObserver, appMarkerPath)
            val serverClientsBefore = serverClientIdentities(directObserver, sessionName)
            assertEquals(
                "control baseline must have exactly one server-side tmux client, got $serverClientsBefore",
                1,
                serverClientsBefore.size,
            )

            // Same sampler, healthy link: it must be SILENT here, otherwise "it fired
            // on the clean close" would be meaningless.
            captureBriefSnapshot(
                snapshots = snapshots,
                phase = "control_pre_close",
                episodeStartMs = episodeStartMs,
                episodeStartNanos = episodeStartNanos,
                observer = directObserver,
                sessionName = sessionName,
                baseline = baseline,
                serverClientsBefore = serverClientsBefore,
            ).assertSilent("control_pre_close")

            val beforeState = proxy.state()
            assertTrue(
                "expected an enabled 2228 -> agents:22 proxy before the control close, got $beforeState",
                beforeState.enabled,
            )
            proxy.disable()
            val closedState = proxy.state()
            assertFalse(
                "expected Toxiproxy's independent oracle to confirm the clean close engaged, got $closedState",
                closedState.enabled,
            )
            // Keep this explicit in the evidence: the positive control must drive the
            // real socket-close path that the brief journey promises to observe, not
            // merely sample a pre-existing recovery state.
            productionDisconnectTriggered = true
            val closedAtMs = SystemClock.elapsedRealtime()
            // The positive control must prove the genuine reader-side cause as
            // well as the user-visible projection. Run the typed EOF wait in
            // parallel with the shared sampler so either oracle remains within
            // the same bounded post-close episode.
            readerEofDeferred = async(Dispatchers.IO) {
                waitForReaderEofDiagnostic(sessionName, "control")
            }

            val detectDeadline = closedAtMs + RECONNECTING_BAND_BUDGET_MS
            while (SystemClock.elapsedRealtime() < detectDeadline && firstViolatingTick == null) {
                val snapshot = captureBriefSnapshot(
                    snapshots = snapshots,
                    phase = "control_closed",
                    episodeStartMs = episodeStartMs,
                    episodeStartNanos = episodeStartNanos,
                    observer = directObserver,
                    sessionName = sessionName,
                    baseline = baseline,
                    serverClientsBefore = serverClientsBefore,
                )
                if (snapshot.userVisibleRecoveryViolations().isNotEmpty()) {
                    firstViolatingTick = snapshot
                    recoveryDetectedMs = SystemClock.elapsedRealtime() - closedAtMs
                }
                SystemClock.sleep(BRIEF_SAMPLE_INTERVAL_MS)
            }
            assertNotNull(
                "POSITIVE CONTROL FAILED: the brief journey's sampler saw NOTHING for " +
                "${RECONNECTING_BAND_BUDGET_MS}ms after a genuine clean socket close. Either the " +
                    "sampler is blind (its silence in the brief journey proves nothing) or the app " +
                    "suppressed an honest drop. Last tick=${snapshots.lastOrNull()?.asLine()}",
                firstViolatingTick,
            )
            readerEofDiagnostic = requireNotNull(readerEofDeferred).await()
            readerEofDetectedMs =
                (readerEofDiagnostic!!.monotonicTimestampNanos - episodeStartNanos) / 1_000_000L
            recordTiming("control_recovery_detected_ms", recoveryDetectedMs)
            recordTiming("control_reader_eof_ms", readerEofDetectedMs)

            proxy.enable()
            val restoredState = proxy.state()
            assertTrue(
                "expected Toxiproxy's independent oracle to confirm the control restore, got $restoredState",
                restoredState.enabled,
            )
            waitForSshFixtureReady(key = SshKey.Pem(key), port = NETWORK_FAULT_SSH_PORT)

            // Not over-suppressed: a genuine drop still AUTO-heals to a usable session
            // whose input reaches the real server.
            waitForConnectedStatus("control auto-recovery")
            sendCommandThroughTerminalInput(
                "printf '%s' ${shellQuote(appMarker)} > ${shellQuote(appMarkerPath)}",
                "control-post-recovery-server-marker",
            )
            waitForExactRemoteFile(
                directObserver,
                appMarkerPath,
                appMarker,
                "control post-recovery marker",
            )
            appMarkerVerified = true
            val controlPostRecoveryViewport = captureAuthoritativeTerminalViewport("control-post-recovery")
            assertTrue(
                "authoritative control post-recovery terminal text did not contain the server marker: " +
                    controlPostRecoveryViewport,
                appMarker in controlPostRecoveryViewport,
            )
            waitForClientCountAtMost(key, sessionName, max = 1, label = "control post-recovery")
        } catch (error: Throwable) {
            failure = error
            throw error
        } finally {
            runCatching { proxy.enable() }
            runCatching { readerEofDeferred?.cancel() }
            runCatching {
                artifactFile(CONTROL_EVIDENCE_FILE).writeText(
                    buildString {
                        appendLine(
                            "test=RideThroughInterruptionE2eTest#" +
                                "cleanCloseControlSurfacesHonestRecoveryToTheSameObserver",
                        )
                        appendLine("production_disconnect_triggered=$productionDisconnectTriggered")
                        appendLine("observer_detected_recovery=${firstViolatingTick != null}")
                        appendLine("observer_recovery_detected_ms=$recoveryDetectedMs")
                        appendLine("observer_first_violations=${firstViolatingTick?.userVisibleRecoveryViolations()}")
                        appendLine("typed_reader_eof_detected=${readerEofDiagnostic != null}")
                        appendLine("typed_reader_eof_event_name=${readerEofDiagnostic?.name ?: "missing"}")
                        appendLine(
                            "typed_reader_eof_disconnect_reason=" +
                                "${readerEofDiagnostic?.metadata?.get("disconnectReason") ?: "missing"}",
                        )
                        appendLine(
                            "typed_reader_eof_source=${readerEofDiagnostic?.metadata?.get("source") ?: "missing"}",
                        )
                        appendLine(
                            "typed_reader_eof_message=${readerEofDiagnostic?.metadata?.get("message") ?: "missing"}",
                        )
                        appendLine("typed_reader_eof_event_elapsed_ms=$readerEofDetectedMs")
                        appendLine("app_server_marker_verified=$appMarkerVerified")
                        appendLine("failure=${failure?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"}")
                        appendLine("observer_ticks:")
                        snapshots.forEach { appendLine("  ${it.asLine()}") }
                    },
                )
            }
            runCatching { observer?.let { removeRemoteFiles(it, appMarkerPath) } }
            runCatching { observer?.close() }
        }

        writeSummary(
            testName = "RideThroughInterruptionE2eTest-clean-close-control",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "cut=toxiproxy disable (genuine clean socket close), then enable",
                "observer_recovery_detected_ms=$recoveryDetectedMs",
                "observer_first_violations=${firstViolatingTick?.userVisibleRecoveryViolations()}",
                "app_server_marker_verified=$appMarkerVerified",
                "observed_ticks=${snapshots.size}",
            ),
        )
    } }

    // ---------------------------------------------------------------------
    // Shared #1678 sampler / oracles
    // ---------------------------------------------------------------------

    private data class IdentityBaseline(
        val connectAttempts: Int,
        val connectGeneration: Long,
        val clientIdentity: Int,
    )

    private fun captureIdentityBaseline(label: String): IdentityBaseline {
        val viewModel = currentNetworkFaultViewModel()
        val clientIdentity = viewModel.currentClientIdentityForTest()
        assertNotNull("$label proof requires a live control client before the fault", clientIdentity)
        assertFalse(
            "$label proof requires a live (non-disconnected) control client before the fault",
            viewModel.clientDisconnectedForTest(),
        )
        return IdentityBaseline(
            connectAttempts = TMUX_CONNECT_ATTEMPTS.get(),
            connectGeneration = viewModel.currentConnectGenerationForTest(),
            clientIdentity = requireNotNull(clientIdentity),
        )
    }

    private data class SentinelOutcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
        val error: String?,
    ) {
        fun succeededWith(marker: String): Boolean = error == null && exitCode == 0 && stdout == marker

        fun evidence(marker: String): String =
            "completed=${succeededWith(marker)} " +
                "exitCode=$exitCode stdoutMatchesMarker=${stdout == marker} " +
                "stderrEmpty=${stderr.isEmpty()} error=${error ?: "none"}"
    }

    private data class BriefPhaseBoundary(val name: String, val elapsedMs: Long)

    private data class BriefRideThroughSnapshot(
        val phase: String,
        val recovery: RecoverySnapshot,
        val connectAttempts: Int,
        val connectGeneration: Long,
        val connectJobActive: Boolean,
        val clientIdentity: Int?,
        val clientDisconnected: Boolean,
        val controllerState: String,
        val serverClients: List<String>,
        val serverClientsBefore: List<String>,
        val baseline: IdentityBaseline,
        val sentinelCompleted: Boolean,
        val sentinelRemoteMarkerPresent: Boolean,
        val connectionEventCount: Int,
        val journalEventCount: Int,
        val forbiddenEvents: List<String>,
    ) {
        /**
         * Everything a USER would see or feel as "the connection dropped": the VM
         * status leaving Connected, any rendered recovery surface, a fresh dial, a
         * replaced/disconnected control client, a controller state that is no longer
         * Live, or the server-side tmux client set changing. This is the exact set
         * the brief journey requires to stay EMPTY and the clean-close control
         * requires to become NON-empty.
         */
        fun userVisibleRecoveryViolations(): List<String> = buildList {
            if (recovery.statusName != "Connected") add("vm_status=${recovery.statusName}")
            if (recovery.reconnectingPill) add("reconnecting_pill")
            if (recovery.attachingHold) add("attaching_hold")
            if (recovery.reconnectBandRetryNow) add("reconnect_band_retry_now")
            if (recovery.connectingProgressRow) add("connecting_progress_row")
            if (recovery.settledFailedBand) add("settled_failed_band")
            if (connectAttempts != baseline.connectAttempts) add("extra_dial=$connectAttempts")
            if (connectGeneration != baseline.connectGeneration) add("connect_generation=$connectGeneration")
            if (connectJobActive) add("connect_job_active")
            if (clientIdentity != baseline.clientIdentity) add("client_replaced=$clientIdentity")
            if (clientDisconnected) add("client_disconnected")
            if (controllerState != "Live") add("controller_state=$controllerState")
            if (serverClients != serverClientsBefore) add("server_clients=$serverClients")
        }

        fun assertSilent(label: String) {
            val violations = userVisibleRecoveryViolations()
            assertTrue(
                "the ride-through contract was broken during $label: $violations — ${asLine()}",
                violations.isEmpty(),
            )
            assertTrue(
                "typed recovery/cause diagnostics fired during $label: $forbiddenEvents — ${asLine()}",
                forbiddenEvents.isEmpty(),
            )
        }

        fun asLine(): String = buildString {
            append("phase=$phase ")
            append(recovery.asLine())
            append(" attempts=$connectAttempts generation=$connectGeneration")
            append(" connectJobActive=$connectJobActive")
            append(" clientIdentity=$clientIdentity clientDisconnected=$clientDisconnected")
            append(" controllerState=${controllerState.replace(' ', '_')}")
            append(" serverClients=${serverClients.joinToString(prefix = "[", postfix = "]")}")
            append(" sentinelCompleted=$sentinelCompleted")
            append(" sentinelRemoteMarkerPresent=$sentinelRemoteMarkerPresent")
            append(" connectionEvents=$connectionEventCount journalEvents=$journalEventCount")
            append(" forbiddenEvents=${forbiddenEvents.joinToString(prefix = "[", postfix = "]")}")
        }
    }

    @Suppress("LongParameterList")
    private suspend fun captureBriefSnapshot(
        snapshots: MutableList<BriefRideThroughSnapshot>,
        phase: String,
        episodeStartMs: Long,
        episodeStartNanos: Long,
        observer: SshSession,
        sessionName: String,
        baseline: IdentityBaseline,
        serverClientsBefore: List<String>,
        sentinelRemotePath: String? = null,
        sentinelCompleted: Boolean = false,
    ): BriefRideThroughSnapshot {
        val viewModel = currentNetworkFaultViewModel()
        val recovery = sampleRecovery(episodeStartMs)
        val serverClients = serverClientIdentities(observer, sessionName)
        val sentinelMarkerPresent = sentinelRemotePath
            ?.let { remoteFileContents(observer, it) != null }
            ?: false
        val (connectionEvents, journalEvents) = episodeDiagnostics(episodeStartNanos)
        val snapshot = BriefRideThroughSnapshot(
            phase = phase,
            recovery = recovery,
            connectAttempts = TMUX_CONNECT_ATTEMPTS.get(),
            connectGeneration = viewModel.currentConnectGenerationForTest(),
            connectJobActive = viewModel.connectJobActiveForTest(),
            clientIdentity = viewModel.currentClientIdentityForTest(),
            clientDisconnected = viewModel.clientDisconnectedForTest(),
            controllerState = viewModel.connectionControllerStateForTest()::class.simpleName ?: "unknown",
            serverClients = serverClients,
            serverClientsBefore = serverClientsBefore,
            baseline = baseline,
            sentinelCompleted = sentinelCompleted,
            sentinelRemoteMarkerPresent = sentinelMarkerPresent,
            connectionEventCount = connectionEvents.size,
            journalEventCount = journalEvents.size,
            forbiddenEvents = forbiddenRecoveryEvents(connectionEvents, journalEvents),
        )
        // Append BEFORE any assertion so the FAILING tick is in the artifact.
        snapshots += snapshot
        return snapshot
    }

    /** UNPROXIED direct SSH (port 2222) — the third-party server-state observer. */
    private suspend fun openDirectFixtureObserver(key: String): SshSession =
        withTimeout(OBSERVER_CONNECT_TIMEOUT_MS) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = OBSERVER_CONNECT_TIMEOUT_MS.toInt(),
            ).getOrThrow()
        }

    /** A wholly independent SSH session through the SAME proxy port the app rides. */
    private suspend fun openProxiedSentinelSession(key: String): SshSession =
        withTimeout(PROXY_SENTINEL_CONNECT_TIMEOUT_MS.toLong()) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = NETWORK_FAULT_SSH_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = PROXY_SENTINEL_CONNECT_TIMEOUT_MS,
            ).getOrThrow()
        }

    private suspend fun runSentinelCommand(
        session: SshSession,
        remotePath: String,
        marker: String,
    ): SentinelOutcome = runCatching {
        session.exec(
            "printf '%s' ${shellQuote(marker)} > ${shellQuote(remotePath)}; " +
                "printf '%s' ${shellQuote(marker)}",
        )
    }.fold(
        onSuccess = { SentinelOutcome(it.exitCode, it.stdout, it.stderr, error = null) },
        onFailure = { SentinelOutcome(-1, "", "", error = "${it.javaClass.simpleName}:${it.message}") },
    )

    private suspend fun serverClientIdentities(observer: SshSession, sessionName: String): List<String> {
        val result = withTimeout(OBSERVER_EXEC_TIMEOUT_MS) {
            observer.exec(
                "tmux list-clients -t ${shellQuote(sessionName)} " +
                    "-F '#{client_pid}:#{client_tty}:#{session_name}' 2>/dev/null || true",
            )
        }
        assertEquals("the direct observer could not list tmux clients: ${result.stderr}", 0, result.exitCode)
        return result.stdout.lines().filter { it.isNotBlank() }
    }

    private suspend fun remoteFileContents(observer: SshSession, remotePath: String): String? {
        val result = withTimeout(OBSERVER_EXEC_TIMEOUT_MS) {
            observer.exec(
                "if [ -f ${shellQuote(remotePath)} ]; then " +
                    "printf '__PRESENT__'; cat -- ${shellQuote(remotePath)}; fi",
            )
        }
        assertEquals("the direct observer could not inspect $remotePath: ${result.stderr}", 0, result.exitCode)
        return result.stdout.takeIf { it.startsWith("__PRESENT__") }?.removePrefix("__PRESENT__")
    }

    private suspend fun waitForExactRemoteFile(
        observer: SshSession,
        remotePath: String,
        expected: String,
        label: String,
    ): String {
        val deadline = SystemClock.elapsedRealtime() + REMOTE_MARKER_TIMEOUT_MS
        var last = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            last = remoteFileContents(observer, remotePath) ?: ""
            if (last == expected) return last
            SystemClock.sleep(REMOTE_MARKER_POLL_MS)
        }
        throw AssertionError(
            "expected the exact server-side marker for $label at $remotePath: " +
                "expected='$expected' actual='$last'",
        )
    }

    private suspend fun removeRemoteFiles(observer: SshSession, vararg paths: String) {
        val result = withTimeout(OBSERVER_EXEC_TIMEOUT_MS) {
            observer.exec("rm -f -- ${paths.joinToString(" ") { shellQuote(it) }}")
        }
        assertEquals("the direct observer could not clean sentinel files: ${result.stderr}", 0, result.exitCode)
    }

    /**
     * Issue #1678 / #2397 — prove the diagnostic channel this proof reads can
     * actually SPEAK for this episode.
     *
     * The brief journey's load-bearing diagnostic claim is a NEGATIVE one ("no
     * typed recovery/drop/reconnect event fired"), and a negative claim read
     * from a channel that has been silenced is vacuous — exactly the G6 /
     * `docs/ci-pitfalls.md` mutation-liveness trap. This channel really can be
     * silenced: `DiagnosticRecorders.close()` installs `DiagnosticEventSink.Noop`
     * into the global [DiagnosticEvents] and never restores `App`'s recorder
     * (#2397), so ANY earlier class in the same instrumentation process
     * permanently blinds every later reader of `app.diagnosticRecorder`.
     *
     * So round-trip a probe through the SAME global entry point production
     * writes through, into BOTH archives [episodeDiagnostics] reads, and require
     * it to come back. A poisoned sink reddens the brief journey here instead of
     * letting it pass with an empty forbidden-event list it could never have
     * populated. The probe names are deliberately outside
     * [FORBIDDEN_CONNECTION_EVENT_NAMES] / [FORBIDDEN_CONTROLLER_EVENTS], so the
     * liveness proof cannot itself trip the contract it protects.
     */
    private suspend fun assertDiagnosticCaptureIsLive(episodeStartNanos: Long, marker: String) {
        DiagnosticEvents.record(
            MirroredDiagnostics.CONNECTION_CATEGORY,
            DIAGNOSTIC_LIVENESS_PROBE_NAME,
            "marker" to marker,
        )
        DiagnosticEvents.record(
            ConnectionJournalSchema.CATEGORY,
            DIAGNOSTIC_LIVENESS_PROBE_NAME,
            "marker" to marker,
        )

        val deadline = SystemClock.elapsedRealtime() + DIAGNOSTIC_LIVENESS_TIMEOUT_MS
        var connectionSeen = false
        var journalSeen = false
        while (SystemClock.elapsedRealtime() < deadline && !(connectionSeen && journalSeen)) {
            val (connectionEvents, journalEvents) = episodeDiagnostics(episodeStartNanos)
            connectionSeen = connectionEvents.any {
                it.name == DIAGNOSTIC_LIVENESS_PROBE_NAME && it.metadata["marker"] == marker
            }
            journalSeen = journalEvents.any {
                it.name == DIAGNOSTIC_LIVENESS_PROBE_NAME && it.metadata["marker"] == marker
            }
            if (connectionSeen && journalSeen) break
            SystemClock.sleep(DIAGNOSTIC_LIVENESS_POLL_MS)
        }
        assertTrue(
            "the connection diagnostic archive is BLIND for this episode (probe " +
                "'$DIAGNOSTIC_LIVENESS_PROBE_NAME' marker=$marker never came back within " +
                "${DIAGNOSTIC_LIVENESS_TIMEOUT_MS}ms) — every 'no forbidden event fired' " +
                "assertion in this proof would be vacuous. See #2397: an earlier class in this " +
                "instrumentation process installed DiagnosticEventSink.Noop and never restored " +
                "App's recorder.",
            connectionSeen,
        )
        assertTrue(
            "the controller-journal diagnostic archive is BLIND for this episode (probe " +
                "'$DIAGNOSTIC_LIVENESS_PROBE_NAME' marker=$marker never came back within " +
                "${DIAGNOSTIC_LIVENESS_TIMEOUT_MS}ms) — the forbidden controller-event " +
                "assertions in this proof would be vacuous. See #2397.",
            journalSeen,
        )
    }

    private suspend fun episodeDiagnostics(
        episodeStartNanos: Long,
    ): Pair<List<DiagnosticsEvent>, List<DiagnosticsEvent>> {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as App
        val connectionEvents = app.diagnosticRecorder.connectionLogArchive()
            .filter { it.monotonicTimestampNanos >= episodeStartNanos }
        val journalEvents = app.diagnosticRecorder.connectionJournalArchive()
            .filter { it.monotonicTimestampNanos >= episodeStartNanos }
        return connectionEvents to journalEvents
    }

    private fun forbiddenRecoveryEvents(
        connectionEvents: List<DiagnosticsEvent>,
        journalEvents: List<DiagnosticsEvent>,
    ): List<String> = buildList {
        connectionEvents.filter { event ->
            event.category == "reconnect" ||
                event.name.contains("reconnect", ignoreCase = true) ||
                event.name in FORBIDDEN_CONNECTION_EVENT_NAMES ||
                (
                    event.name == "keepalive_death_budget_crossed" &&
                        event.metadata["outcome"] == "declared_dead"
                    )
        }.forEach { add("${it.category}/${it.name}#${it.sequence}:${it.metadata.toSortedMap()}") }
        journalEvents.filter { event ->
            event.name == "submit" && event.metadata["event"] in FORBIDDEN_CONTROLLER_EVENTS
        }.forEach { add("${it.category}/${it.name}#${it.sequence}:${it.metadata.toSortedMap()}") }
    }

    private fun assertNoForbiddenRecoveryEvents(
        connectionEvents: List<DiagnosticsEvent>,
        journalEvents: List<DiagnosticsEvent>,
        label: String,
    ) {
        val forbidden = forbiddenRecoveryEvents(connectionEvents, journalEvents)
        assertTrue("expected no recovery/cause signal for $label, found=$forbidden", forbidden.isEmpty())
    }

    /**
     * The archive's sequence numbers must strictly increase, so a timeline read out
     * of it is ordered and complete (no duplicate or replayed record can hide an
     * event this proof would otherwise call forbidden).
     *
     * Deliberately NOT asserted: that `monotonicTimestampNanos` never moves
     * backwards between adjacent records. Producers stamp their own `nanoTime`
     * before taking the recorder's sequence, so two events emitted from different
     * threads inside the same microsecond can legitimately be sequenced in the
     * opposite order to their stamps (observed: 429521435808098 -> 429521435731658,
     * a 76 µs inversion, on an otherwise perfectly silent ride-through). That is a
     * property of the diagnostics recorder, not of the connection contract under
     * test, so asserting it here would only add a false-red channel (G6).
     */
    private fun assertMonotonicDiagnostics(label: String, events: List<DiagnosticsEvent>) {
        events.zipWithNext().forEach { (before, after) ->
            assertTrue(
                "$label sequence must increase monotonically: ${before.sequence} -> ${after.sequence}",
                after.sequence > before.sequence,
            )
        }
    }

    @Suppress("LongParameterList")
    private suspend fun writeBriefRideThroughEvidence(
        episodeStartNanos: Long,
        snapshots: List<BriefRideThroughSnapshot>,
        phases: List<BriefPhaseBoundary>,
        cutDurationMs: Long?,
        postRestoreObservedMs: Long,
        baselineSentinelCompleted: Boolean,
        cutSentinelCompletedDuringCut: Boolean,
        cutSentinelMarkerDuringCut: Boolean,
        cutSentinelOutcomeAfterRestore: String,
        cutSentinelRecoveredAfterRestore: Boolean,
        cutSentinelMarkerAfterRestoreVerified: Boolean,
        restoredSentinelCompleted: Boolean,
        appMarkerVerified: Boolean,
        phaseCompleted: Boolean,
        proxyStateBeforeCut: String,
        proxyStateDuringCut: String,
        proxyStateAfterRestore: String,
        failure: Throwable?,
    ) {
        val diagnosticResult = runCatching { episodeDiagnostics(episodeStartNanos) }
        val connectionEvents = diagnosticResult.getOrNull()?.first.orEmpty()
        val journalEvents = diagnosticResult.getOrNull()?.second.orEmpty()
        val merged = (connectionEvents + journalEvents)
            .distinctBy { it.sequence }
            .sortedWith(compareBy<DiagnosticsEvent> { it.monotonicTimestampNanos }.thenBy { it.sequence })
        val text = buildString {
            appendLine(
                "test=RideThroughInterruptionE2eTest#" +
                    "briefLinkCutRidesThroughWithoutDisconnectOrTeardown",
            )
            appendLine("phase_pre_install_executed=${phases.any { it.name == "pre_install" }}")
            appendLine("phase_toxic_installed_executed=${phases.any { it.name == "toxic_installed" }}")
            appendLine("phase_toxic_cleared_executed=${phases.any { it.name == "toxic_cleared" }}")
            appendLine(
                "phase_post_restore_observation_executed=" +
                    phases.any { it.name == "post_restore_observation_complete" },
            )
            appendLine("brief_cut_target_ms=$BRIEF_BLIP_MS")
            appendLine("brief_cut_actual_ms=${cutDurationMs ?: -1L}")
            appendLine("post_restore_target_ms=$POST_RESTORE_SETTLE_MS")
            appendLine("post_restore_observed_ms=$postRestoreObservedMs")
            appendLine("detector_reproduction=$DETECTOR_REPRODUCTION")
            appendLine("production_liveness_probe_interval_ms=${LivenessProbe.DEFAULT_INTERVAL_MS}")
            appendLine("production_liveness_probe_timeout_ms=${LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS}")
            appendLine("production_liveness_probe_failure_threshold=${LivenessProbe.DEFAULT_FAILURE_THRESHOLD}")
            appendLine("production_half_open_detection_budget_ms=$PRODUCTION_HALF_OPEN_DETECTION_BUDGET_MS")
            appendLine("passive_disconnect_grace_ms=$PASSIVE_DISCONNECT_GRACE_MS")
            appendLine("controller_grace_ms=${ConnectionController.DEFAULT_GRACE_MS}")
            appendLine(
                "brief_is_below_production_detection_budget=" +
                    (BRIEF_BLIP_MS < PRODUCTION_HALF_OPEN_DETECTION_BUDGET_MS),
            )
            appendLine("sentinel_before_cut_completed=$baselineSentinelCompleted")
            appendLine("sentinel_during_cut_completed=$cutSentinelCompletedDuringCut")
            appendLine("sentinel_during_cut_marker_present=$cutSentinelMarkerDuringCut")
            appendLine("sentinel_during_cut_outcome_after_restore=$cutSentinelOutcomeAfterRestore")
            appendLine(
                "sentinel_same_connection_after_restore_outcome_completed=" +
                    cutSentinelRecoveredAfterRestore,
            )
            appendLine(
                "sentinel_same_connection_after_restore_marker_verified=" +
                    cutSentinelMarkerAfterRestoreVerified,
            )
            appendLine(
                "sentinel_same_connection_after_restore_completed=$cutSentinelRecoveredAfterRestore",
            )
            appendLine("sentinel_after_restore_completed=$restoredSentinelCompleted")
            appendLine("app_server_marker_verified=$appMarkerVerified")
            appendLine("observed_ticks=${snapshots.size}")
            appendLine("proxy_state_before_cut=$proxyStateBeforeCut")
            appendLine("proxy_state_during_cut=$proxyStateDuringCut")
            appendLine("proxy_state_after_restore=$proxyStateAfterRestore")
            appendLine("phase_completed=$phaseCompleted")
            appendLine("failure=${failure?.let { "${it.javaClass.simpleName}:${it.message}" } ?: "none"}")
            appendLine("diagnostic_capture_error=${diagnosticResult.exceptionOrNull()?.message ?: "none"}")
            appendLine("phase_boundaries_monotonic:")
            phases.forEach { appendLine("  t=${it.elapsedMs}ms phase=${it.name}") }
            appendLine("recovery_and_identity_snapshots:")
            snapshots.forEach { appendLine("  ${it.asLine()}") }
            appendLine("typed_connection_and_controller_timeline:")
            merged.forEach { event ->
                val elapsedMs = (event.monotonicTimestampNanos - episodeStartNanos) / 1_000_000L
                appendLine(
                    "  t=${elapsedMs}ms sequence=${event.sequence} category=${event.category} " +
                        "name=${event.name} metadata=${event.metadata.toSortedMap()}",
                )
            }
        }
        runCatching { artifactFile(BRIEF_EVIDENCE_FILE).writeText(text) }
            .onFailure { println("ISSUE1678_EVIDENCE_WRITE_FAILURE ${it.message}") }
    }

    private fun elapsedMs(startedAtMs: Long): Long = SystemClock.elapsedRealtime() - startedAtMs

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    /**
     * A sustained clean socket drop is a genuine outage. Under the CURRENT
     * ride-through contract the app AUTO-reconnects through the bounded ladder: the
     * fast Reconnecting indicator surfaces (never the settled Failed band), and once
     * the link is restored the session AUTO-recovers to a usable Connected session —
     * same tmux session, at most one client, no manual Reconnect tap, no hang.
     */
    @Test
    fun sustainedLinkCutReconnectsCleanlyWithoutHang() { runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "rl${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue552-longcut-$marker"
        val hostName = "Issue552 LongCut $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE552-LONGCUT-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("longcut_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        // Live session established (VM Connected).
        waitForConnectedStatus("initial attach")
        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "before-longcut")
        waitForVisibleTerminalText("before-longcut") { "BEFORE-$marker" in it }
        startConnectionDiagnosticCapture()

        // Sustained clean drop -> reader EOF -> the deliberate reconnect ladder. The
        // app surfaces the fast Reconnecting indicator (NOT the settled Failed band)
        // while the link is down, then AUTO-recovers when it is restored.
        val proxy = toxiproxy()
        val initialProxyState = proxy.state()
        org.junit.Assert.assertTrue(
            "expected exact enabled 2228 -> agents:22 proxy before cut, got $initialProxyState",
            initialProxyState.enabled &&
                initialProxyState.listen in setOf("0.0.0.0:2228", "[::]:2228") &&
                initialProxyState.upstream == "agents:22",
        )
        proxy.disable()
        val disabledProxyState = proxy.state()
        org.junit.Assert.assertTrue(
            "expected Toxiproxy's independent oracle to confirm the cut engaged, got $disabledProxyState",
            !disabledProxyState.enabled,
        )
        var readerEofReason = "missing"
        try {
            val readerEof = waitForReaderEofDiagnostic(sessionName, "longcut")
            readerEofReason = readerEof.metadata["disconnectReason"].toString()
            waitForReconnectingRecoveryBand("longcut")
        } finally {
            proxy.enable()
        }
        val restoredProxyState = proxy.state()
        org.junit.Assert.assertTrue(
            "expected Toxiproxy's independent oracle to confirm restore, got $restoredProxyState",
            restoredProxyState.enabled,
        )
        waitForSshFixtureReady(
            key = com.pocketshell.core.ssh.SshKey.Pem(key),
            port = NETWORK_FAULT_SSH_PORT,
        )

        // AUTO-recovery (no manual Reconnect tap — the superseded #342/#552 contract):
        // the VM returns to Connected, and the reconnect is CLEAN — the same tmux
        // session survives with at most one client (no orphaned/duplicate clients),
        // verified server-side over a direct SSH connection.
        waitForConnectedStatus("longcut recovery")
        val reboundMarker = "REBOUND-$marker"
        emitShellMarkerOverFixtureControlPlane(key, sessionName, reboundMarker, "longcut rebound")
        waitForVisibleTerminalText("longcut-rebound") { reboundMarker in it }
        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "after-longcut")
        waitForVisibleTerminalText("after-longcut") { "AFTER-$marker" in it }
        waitForClientCountAtMost(key, sessionName, max = 1, label = "post-longcut reconnect")

        writeSummary(
            testName = "RideThroughInterruptionE2eTest-sustained",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "cut=toxiproxy disable (clean socket drop), then enable within episode budget",
                "contract=CURRENT ride-through: fast Reconnecting indicator, auto-recover on restore",
                "proxy_before=$initialProxyState",
                "proxy_during_cut=$disabledProxyState",
                "proxy_after_restore=$restoredProxyState",
                "typed_disconnect_reason=$readerEofReason",
                "initial_visible_marker=BEFORE-$marker",
                "rebound_visible_marker=$reboundMarker",
                "final_visible_marker=AFTER-$marker",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
            ),
        )
    } }

    private companion object {
        const val BRIEF_BLIP_MS: Long = 5_000L
        const val BRIEF_BLIP_MAX_MS: Long = 6_000L
        const val POST_RESTORE_SETTLE_MS: Long = 4_000L
        const val BRIEF_SAMPLE_INTERVAL_MS: Long = 200L
        const val DETECTOR_REPRODUCTION: String = "physical-half-open-production-defaults"
        val PRODUCTION_HALF_OPEN_DETECTION_BUDGET_MS: Long =
            LivenessProbe.DEFAULT_FAILURE_THRESHOLD.toLong() *
                (LivenessProbe.DEFAULT_INTERVAL_MS + LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS)

        /**
         * Wall-clock headroom reserved for one whole snapshot (Compose tree probes +
         * two unproxied observer execs + a diagnostics read) so a slow tick cannot
         * push the measured cut past [BRIEF_BLIP_MAX_MS].
         */
        const val SNAPSHOT_BUDGET_MS: Long = 900L

        /** A cut that was slept through rather than OBSERVED proves nothing. */
        const val MIN_ENGAGED_TICKS: Int = 3
        const val OBSERVER_CONNECT_TIMEOUT_MS: Long = 15_000L
        const val OBSERVER_EXEC_TIMEOUT_MS: Long = 10_000L
        const val PROXY_SENTINEL_CONNECT_TIMEOUT_MS: Int = 15_000
        const val PROXY_SENTINEL_BASELINE_TIMEOUT_MS: Long = 10_000L
        const val PROXY_SENTINEL_RESTORE_TIMEOUT_MS: Long = 30_000L
        const val SENTINEL_DRAIN_TIMEOUT_MS: Long = 15_000L
        const val REMOTE_MARKER_TIMEOUT_MS: Long = 20_000L
        const val REMOTE_MARKER_POLL_MS: Long = 100L
        /** #2397 vacuity guard — see [assertDiagnosticCaptureIsLive]. */
        const val DIAGNOSTIC_LIVENESS_PROBE_NAME: String = "issue1678_diagnostic_capture_liveness_probe"
        const val DIAGNOSTIC_LIVENESS_TIMEOUT_MS: Long = 10_000L
        const val DIAGNOSTIC_LIVENESS_POLL_MS: Long = 100L
        const val BRIEF_EVIDENCE_FILE: String = "brief-ride-through-timeline.txt"
        const val CONTROL_EVIDENCE_FILE: String = "clean-close-control-timeline.txt"

        val FORBIDDEN_CONNECTION_EVENT_NAMES: Set<String> = setOf(
            "liveness_probe_silent_drop",
            "passive_disconnect",
            "silent_reattach_start",
            "tmux_client_reader_exit",
            "tmux_client_command_timeout",
            "network_loss_hold",
        )

        val FORBIDDEN_CONTROLLER_EVENTS: Set<Any?> = setOf(
            "transport_dropped",
            "reconnect_ladder_entered",
            "reconnect_failed",
            "reconnect_gave_up",
            "network_lost",
        )
    }
}
