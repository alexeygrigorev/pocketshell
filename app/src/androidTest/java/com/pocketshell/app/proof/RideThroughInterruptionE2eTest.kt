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
 * Issue #552: connection-resilience ride-through proof.
 *
 * Drives the maintainer's metro-tunnel case through the existing toxiproxy
 * `network-fault-proxy` harness.
 *
 * Two cases:
 *
 * 1. Brief blip rides through: open a session, starve the link for about 5s,
 *    then restore it. The session must be held: no false disconnected band
 *    during or after the blip, and the same tmux session must resume so input
 *    reaches the agent again without teardown/reconnect.
 * 2. Longer cut reconnects cleanly: a sustained clean socket drop is a genuine
 *    outage. The app may surface the disconnect band, but reconnect must be
 *    clean and bounded, resume the same session, and leave at most one tmux
 *    client.
 *
 * This is the verification tool for the #548 ride-through fix. Case 1 asserts
 * the desired behavior and may fail on current `main` until keepalive/
 * ride-through work lands; the assertions are intentionally not weakened to
 * make today's behavior pass.
 */
@RunWith(AndroidJUnit4::class)
class RideThroughInterruptionE2eTest : NetworkFaultProofBase() {

    @Test
    fun briefLinkCutRidesThroughWithoutDisconnectOrTeardown() = runBlocking {
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
    }

    @Test
    fun sustainedLinkCutReconnectsCleanlyWithoutHang() = runBlocking {
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

        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "before-longcut")
        waitForVisibleTerminalText("before-longcut") { "BEFORE-$marker" in it }
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "initial attach")

        val cutStart = SystemClock.elapsedRealtime()
        val proxy = toxiproxy()
        proxy.disable()
        try {
            waitForDisconnectBand("longcut")
            val remainingHold = LONG_CUT_MS - (SystemClock.elapsedRealtime() - cutStart)
            if (remainingHold > 0L) {
                SystemClock.sleep(remainingHold)
            }
        } finally {
            proxy.enable()
            recordTiming("longcut_proxy_disable_total_ms", SystemClock.elapsedRealtime() - cutStart)
        }

        tapReconnectAndWait("longcut")
        assertNoExtraConnectAttempts(
            attemptsBefore,
            expectedDelta = 2,
            label = "sustained cut explicit reconnect",
        )

        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "after-longcut")
        waitForVisibleTerminalText("after-longcut") { "AFTER-$marker" in it }
        waitForClientCountAtMost(key, sessionName, max = 1, label = "post-longcut reconnect")

        writeSummary(
            testName = "RideThroughInterruptionE2eTest-sustained",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "cut=toxiproxy disable for at least ${LONG_CUT_MS}ms, then enable + Reconnect",
                "expectation=disconnect band, clean bounded reconnect, same client resumes",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
            ),
        )
    }

    private companion object {
        const val BRIEF_BLIP_MS: Long = 5_000L
        const val POST_RESTORE_SETTLE_MS: Long = 4_000L
        const val LONG_CUT_MS: Long = 20_000L
    }
}
