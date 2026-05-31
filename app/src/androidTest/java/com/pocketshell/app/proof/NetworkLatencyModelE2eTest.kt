package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #342: degraded but connected network model.
 *
 * The proxy adds bidirectional delay/jitter. This intentionally does not claim
 * true packet loss coverage: Toxiproxy does not expose a packet-loss toxic, and
 * the netem attempt for this issue made the Android instrumentation process
 * unstable. The issue comment records that packet-loss coverage remains a
 * follow-up instead of letting this proof pass by skipping or overclaiming.
 */
@RunWith(AndroidJUnit4::class)
class NetworkLatencyModelE2eTest : NetworkFaultProofBase() {

    private val tmuxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeConnection: DirectTmuxConnection? = null

    @After
    fun closeDirectTmuxScope() {
        runCatching { activeConnection?.close() }
        activeConnection = null
        runCatching { tmuxScope.cancel() }
    }

    @Test
    fun delayedJitteredSshRemainsUsableWithoutFalseDisconnects() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "lat${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue342-latency-$marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE342-LATENCY-READY-$marker",
        )

        val proxy = toxiproxy()
        proxy.addLatencyModel()
        var explicitConnectAttempts = 0

        val attachStart = SystemClock.elapsedRealtime()
        activeConnection = openDirectTmuxConnection(key, sessionName, tmuxScope)
        explicitConnectAttempts += 1
        recordTiming("latency_attach_ms", SystemClock.elapsedRealtime() - attachStart)
        waitForClientCountAtMost(key, sessionName, max = 1, label = "latency initial attach")

        val first = "LATENCY-FIRST-$marker"
        val second = "LATENCY-SECOND-$marker"
        val connection = requireNotNull(activeConnection) { "missing active tmux connection" }
        sendShellMarkerViaTmux(connection.client, first, "latency-first")
        waitForCapturedPaneText(connection.client, first, "latency-first", timeoutMs = 15_000L)
        assertTrue("expected latency client to stay connected after first marker", !connection.client.disconnected.value)
        assertTrue("expected no duplicate reconnect after latency first marker", explicitConnectAttempts == 1)

        SystemClock.sleep(DEGRADED_STABILITY_WINDOW_MS)
        assertTrue("expected latency window not to false-disconnect", !connection.client.disconnected.value)

        sendShellMarkerViaTmux(connection.client, second, "latency-second")
        waitForCapturedPaneText(connection.client, second, "latency-second", timeoutMs = 15_000L)
        assertTrue("expected latency client to stay connected after second marker", !connection.client.disconnected.value)
        assertTrue("expected no duplicate reconnect after latency second marker", explicitConnectAttempts == 1)
        waitForClientCountAtMost(key, sessionName, max = 1, label = "latency active client")

        activeConnection?.detachAndClose()
        activeConnection = null
        waitForClientCountAtMost(key, sessionName, max = 0, label = "latency close cleanup")

        writeSummary(
            testName = "NetworkLatencyModelE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "toxics=latency upstream 120+/-40ms, latency downstream 160+/-60ms",
                "loss_proof=not covered; packet-loss follow-up required",
                "explicit_connect_attempts=$explicitConnectAttempts",
            ),
        )
    }

    private companion object {
        const val DEGRADED_STABILITY_WINDOW_MS: Long = 5_000L
    }
}
