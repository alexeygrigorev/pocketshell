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
 * Issue #342: half-open/no-FIN failure proof.
 *
 * This uses Toxiproxy's `timeout` toxic with `timeout=0` on both streams,
 * which drops bytes without closing the TCP connection. The app must still
 * surface the normal disconnected UI after a bounded tmux command timeout,
 * must not auto-loop reconnect attempts, and must recover after the toxic is
 * removed and the user taps Reconnect.
 */
@RunWith(AndroidJUnit4::class)
class DisconnectBlackholeE2eTest : NetworkFaultProofBase() {

    @Test
    fun halfOpenBlackholeSurfacesErrorAndReconnectsExplicitly() { runBlocking {
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

        sendCommandThroughTerminalInput("printf 'BEFORE-$marker\\n'", "before-blackhole")
        waitForVisibleTerminalText("before-blackhole") { "BEFORE-$marker" in it }
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "initial attach")

        val proxy = toxiproxy()
        proxy.addBlackhole()
        sendCommandThroughTerminalInput("printf 'DROPPED-$marker\\n'", "blackhole-trigger")
        waitForDisconnectBand("blackhole")
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 1, label = "blackhole before explicit reconnect")

        proxy.clearToxics()
        tapReconnectAndWait("blackhole")
        assertNoExtraConnectAttempts(attemptsBefore, expectedDelta = 2, label = "blackhole explicit reconnect")

        sendCommandThroughTerminalInput("printf 'AFTER-$marker\\n'", "after-blackhole")
        waitForVisibleTerminalText("after-blackhole") { "AFTER-$marker" in it }
        waitForClientCountAtMost(key, sessionName, max = 1, label = "post-blackhole reconnect")

        writeSummary(
            testName = "DisconnectBlackholeE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "failure=toxiproxy timeout toxic timeout=0 on upstream/downstream",
                "connect_attempt_delta=${TMUX_CONNECT_ATTEMPTS.get() - attemptsBefore}",
            ),
        )
    } }
}
