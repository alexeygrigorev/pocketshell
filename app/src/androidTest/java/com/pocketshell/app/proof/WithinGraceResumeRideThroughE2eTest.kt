package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TmuxSessionViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #635 / #636 (Slice 1): brief-background-within-grace ride-through.
 *
 * This is the maintainer's #1 dogfood blocker reproduced end-to-end: an open
 * tmux session, a quick step-away while the link is momentarily dead (metro
 * tunnel / dead spot), and a return *within* the background grace window.
 *
 * On a within-grace resume the app runs the foreground tmux control-channel
 * health probe. The regressed behavior treated a 750ms probe timeout — on a
 * transport that is still `isConnected` (socket open, sshj keepalive not yet
 * expired) — as proof of death and forced a full `connect(LifecycleReattach)`.
 * That converts a ride-through-able link blip into a visible reconnect.
 *
 * The toxiproxy `addBlackhole` toxic models exactly that case: a half-open,
 * no-FIN byte drop that keeps the socket "established" so `isConnected`
 * stays true and the probe write goes into a black hole and times out.
 *
 * Assertions:
 *  - returning within grace while the link is cut-then-restored stays
 *    Connected — no disconnect band, no Reconnecting/Connecting UI;
 *  - no extra tmux connect attempt is made (ride-through, not reconnect);
 *  - the `tmux_probe_result` reconnect-cause trail shows the timeout was
 *    ridden through (outcome `ride_through`, failReason `timeout`), not a
 *    reconnect;
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
    fun withinGraceForegroundDuringLinkCutRidesThroughWithoutReconnect() = runBlocking {
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
        // outage). The cut stays half-open the whole time so the probe times
        // out on a still-isConnected socket — the exact #635 race.
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)

        val proxy = toxiproxy()
        val cycleStart = SystemClock.elapsedRealtime()
        proxy.addBlackhole()
        try {
            // Background within grace WHILE the link is cut.
            launchedActivity?.moveToState(Lifecycle.State.CREATED)
            waitForDiagnostic("background_grace_start", "within-grace background during cut")
            // Hold the cut briefly, then foreground while STILL cut — the
            // foreground probe fires here and must time out, not reconnect.
            SystemClock.sleep(BACKGROUND_HOLD_MS)
            launchedActivity?.moveToState(Lifecycle.State.RESUMED)
            waitForDiagnostic("background_grace_foreground", "within-grace foreground during cut") {
                it.fields["withinGrace"] == true
            }
            // Let the foreground probe (+ its single retry) run while the
            // link is still dead, then restore the link.
            SystemClock.sleep(PROBE_WINDOW_MS)
        } finally {
            proxy.clearToxics()
            recordTiming("wgrace_cut_total_ms", SystemClock.elapsedRealtime() - cycleStart)
        }

        // Ride-through: the live session is held, no disconnect band appears,
        // and the link recovers without a reconnect.
        waitForNoDisconnectBandDuring("wgrace_after_restore", durationMillis = POST_RESTORE_SETTLE_MS)
        waitForConnected("within-grace foreground after restore")
        assertNoExtraConnectAttempts(
            attemptsBefore,
            expectedDelta = 1,
            label = "ride through within-grace cut without reconnect",
        )

        val probeTrail = diagnostics!!.eventsNamed("cause_trail")
            .filter { it.fields["stage"] == "tmux_probe_result" }
        assertTrue(
            "expected a foreground probe result to be recorded; trail=${diagnostics!!.events}",
            probeTrail.isNotEmpty(),
        )
        assertTrue(
            "the within-grace probe must NOT report a failed reconnect; trail=$probeTrail",
            probeTrail.none { it.fields["outcome"] == "failed" },
        )
        val rodeThrough = probeTrail.any {
            it.fields["outcome"] == "ride_through" && it.fields["failReason"] == "timeout"
        }
        val healthyResume = probeTrail.all { it.fields["outcome"] == "healthy" }
        assertTrue(
            "expected the probe to ride through a timeout (or resume healthy if the " +
                "link recovered before the probe); trail=$probeTrail",
            rodeThrough || healthyResume,
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
                "scenario=blackhole link, background within grace, foreground while cut, restore",
                "grace_override_ms=$WITHIN_GRACE_MS",
                "expectation=ride through, no disconnect band, no reconnect, same client",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
                "probe_outcomes=" + probeTrail.joinToString(",") {
                    "${it.fields["outcome"]}/${it.fields["failReason"]}"
                },
            ),
        )
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
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        launchedActivity?.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
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
        const val PROBE_WINDOW_MS: Long = 2_000L
        const val POST_RESTORE_SETTLE_MS: Long = 4_000L
        const val DIAGNOSTIC_TIMEOUT_MS: Long = 8_000L
        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 20_000L
    }
}
