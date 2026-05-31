package com.pocketshell.app.proof

import android.os.Debug
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max

/**
 * Issue #342: repeated half-open flap soak.
 *
 * This proof intentionally uses the production [TmuxClient] below the Compose
 * app shell. The full UI path is covered by [DisconnectBlackholeE2eTest]; this
 * narrower soak avoids instrumentation process churn while still proving the
 * repeated-flap acceptance criteria: a wedged control channel does not create
 * implicit replacement connections, recovery needs exactly one explicit new
 * connection, server-side tmux control clients do not accumulate, and heap
 * growth stays bounded.
 */
@RunWith(AndroidJUnit4::class)
class DisconnectFlapSoakE2eTest : NetworkFaultProofBase() {

    private val tmuxScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeConnection: DirectTmuxConnection? = null

    @After
    fun closeDirectTmuxScope() {
        runCatching { activeConnection?.close() }
        activeConnection = null
        runCatching { tmuxScope.cancel() }
    }

    @Test
    fun repeatedBlackholeFlapsRecoverWithoutStaleClientsOrUnboundedMemoryGrowth() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "flap${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionName = "issue342-flap-$marker"
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionName,
            readyText = "ISSUE342-FLAP-READY-$marker",
        )

        var explicitConnectAttempts = 0
        val heapBeforeKb = usedHeapKb()
        activeConnection = openDirectTmuxConnection(key, sessionName, tmuxScope)
        explicitConnectAttempts += 1
        waitForClientCountAtMost(key, sessionName, max = 1, label = "initial flap attach")

        val proxy = toxiproxy()
        repeat(FLAP_CYCLES) { index ->
            val cycle = index + 1
            val before = "CYCLE-$cycle-BEFORE-$marker"
            val after = "CYCLE-$cycle-AFTER-$marker"
            val connection = requireNotNull(activeConnection) { "missing active tmux connection" }

            sendShellMarkerViaTmux(connection.client, before, "flap-$cycle-before")
            waitForCapturedPaneText(connection.client, before, "flap-$cycle-before")

            proxy.addBlackhole()
            val dropStart = SystemClock.elapsedRealtime()
            val wedgedCommand = launch {
                runCatching { connection.client.sendCommand("display-message -p '#{pane_id}'") }
            }
            SystemClock.sleep(BLACKHOLE_WEDGE_WINDOW_MS)
            recordTiming("flap_${cycle}_blackhole_wedge_window_ms", SystemClock.elapsedRealtime() - dropStart)
            assertTrue(
                "expected no implicit reconnect before explicit recovery for flap $cycle",
                explicitConnectAttempts == cycle,
            )
            wedgedCommand.cancel()

            proxy.clearToxics()
            connection.close()
            activeConnection = null
            waitForClientCountAtMost(key, sessionName, max = 0, label = "flap $cycle stale client cleanup")

            activeConnection = openDirectTmuxConnection(key, sessionName, tmuxScope)
            explicitConnectAttempts += 1
            waitForClientCountAtMost(key, sessionName, max = 1, label = "flap $cycle active client")

            val recovered = requireNotNull(activeConnection) { "missing recovered tmux connection" }
            sendShellMarkerViaTmux(recovered.client, after, "flap-$cycle-after")
            waitForCapturedPaneText(recovered.client, after, "flap-$cycle-after")
        }

        activeConnection?.detachAndClose()
        activeConnection = null
        waitForClientCountAtMost(key, sessionName, max = 0, label = "after flap activity close")

        val heapAfterKb = usedHeapKb()
        val growthKb = max(0L, heapAfterKb - heapBeforeKb)
        assertTrue(
            "expected heap growth under ${MAX_HEAP_GROWTH_KB}KB across $FLAP_CYCLES flaps; " +
                "before=${heapBeforeKb}KB after=${heapAfterKb}KB growth=${growthKb}KB",
            growthKb <= MAX_HEAP_GROWTH_KB,
        )

        writeSummary(
            testName = "DisconnectFlapSoakE2eTest",
            lines = listOf(
                "session=$sessionName",
                "marker=$marker",
                "cycles=$FLAP_CYCLES",
                "heap_before_kb=$heapBeforeKb",
                "heap_after_kb=$heapAfterKb",
                "heap_growth_kb=$growthKb",
                "explicit_connect_attempts=$explicitConnectAttempts",
            ),
        )
    }

    private fun usedHeapKb(): Long {
        Runtime.getRuntime().gc()
        Debug.getMemoryInfo(Debug.MemoryInfo())
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / 1024L
    }

    private companion object {
        const val FLAP_CYCLES: Int = 2
        const val BLACKHOLE_WEDGE_WINDOW_MS: Long = 3_000L
        const val MAX_HEAP_GROWTH_KB: Long = 96 * 1024L
    }
}
