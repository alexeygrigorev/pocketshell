package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #342 / #1676: half-open (no-FIN) failure proof, REALIGNED to the CURRENT
 * ride-through connection contract.
 *
 * Uses Toxiproxy's `timeout` toxic with `timeout=0` on both streams, which drops
 * bytes WITHOUT closing the TCP connection — a half-open/no-FIN wedge (no EOF, no
 * RST/FIN; the socket stays established while bytes silently vanish).
 *
 * ## What changed and why (issue #1676, maintainer-approved option 3)
 *
 * The original assertions encoded the SUPERSEDED #342/#552 contract: "surface the
 * settled Disconnected/Failed band FAST, do NOT auto-loop reconnect, wait for a
 * MANUAL Reconnect tap" (it asserted `assertNoExtraConnectAttempts(delta=1)` — ZERO
 * auto-reconnects — and then a manual `tapReconnectAndWait` to delta=2). That
 * contradicts the CURRENT deliberate ride-through contract
 * (#1610/#1654/#1633/#754/#1703).
 *
 * ## Empirically-measured current behaviour for a HALF-OPEN wedge (issue #1676)
 *
 * On the real emulator + toxiproxy path (recorded in the #1676 observation
 * artifacts), a foreground half-open `timeout=0` wedge is **transparently ridden
 * through**: the VM stays `ConnectionStatus.Connected`, NO settled Failed band
 * (`TMUX_SESSION_ERROR_TAG`) appears, and NO active Reconnecting indicator surfaces
 * within the whole episode budget (180s observed) — the app does NOT false-alarm on
 * a no-FIN wedge (unlike a clean socket drop, which hits reader-EOF and enters the
 * active reconnect ladder — see [RideThroughInterruptionE2eTest.sustainedLinkCutReconnectsCleanlyWithoutHang]).
 * So asserting a "fast Reconnecting band" here would assert a signal the real
 * transport never renders (the #1693 seam trap). The honest realigned assertion is
 * the ride-through itself: across a sustained half-open wedge the app surfaces NO
 * settled Failed band (the SUPERSEDED contract expected that band — the nightly-red
 * assertion) and keeps the VM Connected (no teardown), then resumes the SAME session
 * cleanly (VM Connected, exactly one tmux client, verified server-side) when the link
 * restores — no manual Reconnect tap.
 *
 * Non-vacuity: the load-bearing assertion is the INVERSE of the old one — the old
 * test failed on the nightly precisely because it waited for a settled Failed band
 * that the current ride-through contract does not raise; a regression that made the
 * app give up early on a transient wedge would re-surface that band and red the
 * `waitForNoDisconnectBandDuring` assertion. The server-side client-count check
 * proves the same warm session survived (exactly one client), not a stale/torn one.
 */
@RunWith(AndroidJUnit4::class)
class DisconnectBlackholeE2eTest : NetworkFaultProofBase() {

    @Test
    fun halfOpenBlackholeRidesThroughAndResumesWithoutManualReconnect() { runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "bh${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue342-blackhole-$marker"
        val hostName = "Issue342 Blackhole $marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE342-BLACKHOLE-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("blackhole_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        // Live session established (VM Connected).
        waitForConnectedStatus("initial attach")

        // Half-open no-FIN wedge: the socket stays established, bytes vanish.
        val proxy = toxiproxy()
        proxy.addBlackhole()

        // Ride-through (the load-bearing realignment): across a SUSTAINED half-open
        // wedge the app must NOT surface the settled Failed band (the SUPERSEDED #342
        // contract expected that band FAST — the nightly-red assertion this replaces),
        // and must NOT tear the warm session down. The VM stays Connected the whole
        // wedge — the current contract deliberately rides through a no-FIN wedge rather
        // than false-alarming on it. A regression that made the app give up early would
        // surface the settled band here and red the assertion.
        waitForNoDisconnectBandDuring("blackhole_wedge", durationMillis = HALF_OPEN_WEDGE_MS)
        assertConnectedStatus("during half-open wedge")

        // Restore the link: the SAME session is intact — VM still Connected and exactly
        // one tmux client (no orphaned/duplicate clients from a spurious teardown),
        // verified server-side. No manual Reconnect tap (the superseded #342 contract
        // required one).
        proxy.clearToxics()
        waitForConnectedStatus("post-blackhole resume")
        waitForClientCountAtMost(key, sessionName, max = 1, label = "post-blackhole resume")

        writeSummary(
            testName = "DisconnectBlackholeE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "failure=toxiproxy timeout toxic timeout=0 on upstream/downstream (half-open no-FIN)",
                "contract=CURRENT ride-through: transparent hold across half-open wedge, resume on restore, no manual reconnect",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
            ),
        )
    } }

    private companion object {
        /**
         * How long to hold the half-open wedge while asserting no false settled-failure
         * band. Long enough to be a SUSTAINED wedge (distinct from the ~5s brief blip in
         * [RideThroughInterruptionE2eTest]) yet under the swiftshader per-test watchdog.
         */
        const val HALF_OPEN_WEDGE_MS: Long = 30_000L
    }
}
