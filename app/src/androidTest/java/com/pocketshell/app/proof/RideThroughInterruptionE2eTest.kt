package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import kotlinx.coroutines.runBlocking
import org.junit.Assume
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
 * The sustained case uses a clean socket drop (`toxiproxy disable`), so the SSH
 * reader hits EOF immediately and the reconnect ladder is entered without needing
 * any detection-timing override — the EOF is the deciding signal.
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
     * Issue #1678: this case has an anti-correlated FAST-night flake on the nightly
     * toxiproxy phase-2 cohort (it fails only on the FASTEST emulator nights, passes
     * otherwise). Per the maintainer-approved #1676 plan (option 3) and #1678, it is
     * GATED off the automated gate with this documented reference until the #1678
     * root-cause (the FAST-night anti-correlation mechanism) yields a deterministic
     * grace-vs-blip seam. See issue #1678 for the tracking + intended deterministic
     * reframe.
     */
    @Test
    fun briefLinkCutRidesThroughWithoutDisconnectOrTeardown() { runBlocking {
        assumeNetworkFaultProofsEnabled()
        // Issue #1678 — gated: anti-correlated FAST-night flake, tracked in #1678.
        Assume.assumeTrue(
            "briefLinkCutRidesThroughWithoutDisconnectOrTeardown is gated per issue #1678 " +
                "(anti-correlated FAST-night flake); re-enable once #1678 lands a deterministic " +
                "grace-vs-blip seam.",
            false,
        )

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

        starveLinkFor("ride_blip", downMillis = BRIEF_BLIP_MS)
        waitForNoDisconnectBandDuring("ride_blip_after_restore", durationMillis = POST_RESTORE_SETTLE_MS)
        assertNoExtraConnectAttempts(
            attemptsBefore,
            expectedDelta = 1,
            label = "ride through brief cut without teardown",
        )

        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "after-ride")
        waitForVisibleTerminalText("after-ride") { "AFTER-$marker" in it }
        waitForClientCountAtMost(key, sessionName, max = 1, label = "post-blip same client")

        writeSummary(
            testName = "RideThroughInterruptionE2eTest-brief",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "cut=toxiproxy timeout toxic timeout=0 for ${BRIEF_BLIP_MS}ms",
                "expectation=session held, no disconnect band, same client resumes",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
            ),
        )
    } }

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

        // Sustained clean drop -> reader EOF -> the deliberate reconnect ladder. The
        // app surfaces the fast Reconnecting indicator (NOT the settled Failed band)
        // while the link is down, then AUTO-recovers when it is restored.
        val proxy = toxiproxy()
        proxy.disable()
        try {
            waitForReconnectingRecoveryBand("longcut")
        } finally {
            proxy.enable()
        }

        // AUTO-recovery (no manual Reconnect tap — the superseded #342/#552 contract):
        // the VM returns to Connected, and the reconnect is CLEAN — the same tmux
        // session survives with at most one client (no orphaned/duplicate clients),
        // verified server-side over a direct SSH connection.
        waitForConnectedStatus("longcut recovery")
        waitForClientCountAtMost(key, sessionName, max = 1, label = "post-longcut reconnect")

        writeSummary(
            testName = "RideThroughInterruptionE2eTest-sustained",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "cut=toxiproxy disable (clean socket drop), then enable within episode budget",
                "contract=CURRENT ride-through: fast Reconnecting indicator, auto-recover on restore",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
            ),
        )
    } }

    private companion object {
        const val BRIEF_BLIP_MS: Long = 5_000L
        const val POST_RESTORE_SETTLE_MS: Long = 4_000L
    }
}
