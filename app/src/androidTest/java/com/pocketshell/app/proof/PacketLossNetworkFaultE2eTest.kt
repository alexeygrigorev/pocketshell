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
 * Issue #346: connected SSH/tmux proof under true packet loss.
 *
 * The Docker packet-loss proxy applies Linux `tc netem loss` inside the proxy
 * container and forwards TCP to the normal `agents` fixture. Keeping netem
 * inside Docker avoids changing the Android emulator/UTP network path that
 * previously killed instrumentation while still dropping real packets on the
 * SSH stream. The loss rate is intentionally modest so TCP retransmission can
 * recover and the app should not surface a disconnect or start replacement
 * tmux clients.
 */
@RunWith(AndroidJUnit4::class)
class PacketLossNetworkFaultE2eTest : NetworkFaultProofBase() {

    @Test
    fun sshTmuxSessionSurvivesPacketLossWithoutFalseDisconnectOrDuplicateReconnects() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "loss${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue346-loss-$marker"
        val hostName = "Issue346 Loss $marker"
        preparePacketLossProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE346-PACKET-LOSS-READY-$marker",
        )
        val hostRowTag = seedNetworkFaultHost(key, hostName, port = PACKET_LOSS_SSH_PORT)

        val attemptsBefore = TMUX_CONNECT_ATTEMPTS.get()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, sessionName)
        recordTiming("packet_loss_attach_ms", SystemClock.elapsedRealtime() - attachStart)
        assertNoDisconnectBand("packet-loss initial attach")
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "packet-loss initial attach")

        val first = "LOSS-FIRST-$marker"
        val final = "LOSS-FINAL-$marker"
        sendCommandThroughTerminalInput("printf '$first\\n'", "packet-loss-first-input")
        waitForVisibleTerminalText("packet-loss-first-marker", timeoutMillis = 25_000L) { first in it }
        recordTiming("packet_loss_first_marker_visible", 1L)
        assertNoDisconnectBand("packet-loss after first input")
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "packet-loss first marker")

        waitForNoDisconnectBandDuring("packet_loss", STABILITY_WINDOW_MS)
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "packet-loss stability window")

        sendCommandThroughTerminalInput("printf '$final\\n'", "packet-loss-final-input")
        waitForVisibleTerminalText("packet-loss-final-marker", timeoutMillis = 25_000L) { final in it }
        recordTiming("packet_loss_final_marker_visible", 1L)
        assertNoDisconnectBand("packet-loss final marker")
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "packet-loss final marker")
        waitForClientCountAtMost(key, sessionName, max = 1, label = "packet-loss active client")

        writeSummary(
            testName = "PacketLossNetworkFaultE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "model=docker packet-loss-proxy tc netem loss $PACKET_LOSS_RATE on eth0",
                "input_success=first_and_final_markers_visible",
                "final_marker=$final",
                "final_marker_visible=true",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
                "false_disconnect_band_count=${disconnectBandCount()}",
                "expected_duplicate_reconnects=0",
            ),
        )
    }

    private companion object {
        const val PACKET_LOSS_RATE: String = "5%"
        const val STABILITY_WINDOW_MS: Long = 8_000L
    }
}
