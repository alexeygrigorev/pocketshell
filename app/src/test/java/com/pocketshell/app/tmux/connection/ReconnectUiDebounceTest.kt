package com.pocketshell.app.tmux.connection

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #876 — deterministic unit proof for [debounceReconnectUi].
 *
 * All timing is driven by `runTest` VIRTUAL time (`advanceTimeBy` /
 * `advanceUntilIdle`) — NO wall-clock sleeps, no flake. The load-bearing
 * assertions are the GREEN ones (G6): for a sub-threshold drop we assert the
 * "Reconnecting" status is NEVER emitted; for a sustained drop we assert it IS
 * emitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectUiDebounceTest {

    private val connected = ConnectionStatus.Connected("h", 22, "u")
    private val connected2 = ConnectionStatus.Connected("h", 2222, "u")

    private fun reconnecting(attempt: Int = 1) = ConnectionStatus.Reconnecting(
        host = "h",
        port = 22,
        user = "u",
        attempt = attempt,
        maxAttempts = 5,
        retryDelayMs = 0L,
        reason = "Reconnecting…",
    )

    /** Collect the debounced output of a hot StateFlow into a list. */
    private fun TestScope.collectDebounced(
        upstream: MutableStateFlow<ConnectionStatus>,
        debounceMs: Long = RECONNECT_UI_DEBOUNCE_MS,
    ): MutableList<ConnectionStatus> {
        val out = mutableListOf<ConnectionStatus>()
        backgroundScope.launch {
            upstream.debounceReconnectUi(debounceMs)
                .collect { out.add(it) }
        }
        runCurrent()
        return out
    }

    // ---- AC1: a drop that recovers in < ~1s shows NO reconnect UI ----------

    @Test
    fun subThresholdDrop_emitsNoReconnectingUi() = runTest {
        val upstream = MutableStateFlow<ConnectionStatus>(connected)
        val out = collectDebounced(upstream)

        // Drop: Connected -> Reconnecting -> Connected within the window.
        upstream.value = reconnecting()
        advanceTimeBy(RECONNECT_UI_DEBOUNCE_MS - 1) // 999ms: still inside the window
        runCurrent()
        // The band must NOT have surfaced yet (it is held back).
        assertFalse(
            "Reconnecting must NOT surface before the debounce window; got $out",
            out.any { it is ConnectionStatus.Reconnecting },
        )

        // Recovery before the window closes.
        upstream.value = connected2
        runCurrent() // collector consumes the recovery at t=999, cancelling the timer
        advanceUntilIdle()

        // The held Reconnecting was dropped: the user never saw the band.
        assertFalse(
            "a sub-${RECONNECT_UI_DEBOUNCE_MS}ms drop must emit NO Reconnecting; got $out",
            out.any { it is ConnectionStatus.Reconnecting },
        )
        // The output went straight from the initial Connected to the recovered one.
        assertEquals(listOf(connected, connected2), out)
    }

    // ---- AC2: a drop >= ~1s shows the reconnect UI as today ----------------

    @Test
    fun sustainedDrop_emitsReconnectingUi() = runTest {
        val upstream = MutableStateFlow<ConnectionStatus>(connected)
        val out = collectDebounced(upstream)

        upstream.value = reconnecting()
        // Sustain the drop PAST the window (advanceTimeBy is boundary-exclusive,
        // so +1 guarantees the t=debounceMs timer task runs).
        advanceTimeBy(RECONNECT_UI_DEBOUNCE_MS + 1)
        advanceUntilIdle()

        assertTrue(
            "a drop sustained >= ${RECONNECT_UI_DEBOUNCE_MS}ms MUST surface Reconnecting; got $out",
            out.any { it is ConnectionStatus.Reconnecting },
        )
        assertEquals(listOf(connected, reconnecting()), out)
    }

    @Test
    fun sustainedDrop_thenRecover_surfacesBandThenConnected() = runTest {
        val upstream = MutableStateFlow<ConnectionStatus>(connected)
        val out = collectDebounced(upstream)

        upstream.value = reconnecting()
        advanceTimeBy(RECONNECT_UI_DEBOUNCE_MS + 1) // band surfaces (boundary-exclusive)
        runCurrent()
        upstream.value = connected2
        runCurrent() // collector consumes the recovery
        advanceUntilIdle()

        assertEquals(listOf(connected, reconnecting(), connected2), out)
    }

    // ---- Class coverage: payload churn does not reset the clock -------------

    @Test
    fun reconnectingPayloadChurn_doesNotResetTheClock() = runTest {
        val upstream = MutableStateFlow<ConnectionStatus>(connected)
        val out = collectDebounced(upstream)

        upstream.value = reconnecting(attempt = 1)
        advanceTimeBy(600)
        runCurrent()
        // A second Reconnecting payload mid-window must NOT restart the timer.
        upstream.value = reconnecting(attempt = 2)
        advanceTimeBy(401) // total 1001ms since the FIRST drop (boundary-exclusive)
        advanceUntilIdle()

        // The band surfaces after 1000ms from the FIRST drop, carrying the LATEST
        // payload — the churn did not perpetually defer it.
        val reconnects = out.filterIsInstance<ConnectionStatus.Reconnecting>()
        assertEquals("exactly one Reconnecting should surface; got $out", 1, reconnects.size)
        assertEquals("the latest payload should surface", 2, reconnects.single().attempt)
    }

    @Test
    fun churnThenRecoverBeforeFirstDeadline_emitsNoBand() = runTest {
        val upstream = MutableStateFlow<ConnectionStatus>(connected)
        val out = collectDebounced(upstream)

        upstream.value = reconnecting(attempt = 1)
        advanceTimeBy(600)
        runCurrent()
        upstream.value = reconnecting(attempt = 2)
        advanceTimeBy(300) // 900ms since first drop — still inside the window
        runCurrent()
        upstream.value = connected2 // recover before the deadline
        advanceUntilIdle()

        assertFalse(
            "recovery before the first deadline must emit NO band even across churn; got $out",
            out.any { it is ConnectionStatus.Reconnecting },
        )
    }

    // ---- Steady states are never delayed -----------------------------------

    @Test
    fun steadyStatesSurfaceImmediately() = runTest {
        val upstream = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
        val out = collectDebounced(upstream)

        val connecting = ConnectionStatus.Connecting("h", 22, "u")
        val switching = ConnectionStatus.Switching("h", 22, "u")
        val failed = ConnectionStatus.Failed("boom")

        upstream.value = connecting
        runCurrent()
        upstream.value = switching
        runCurrent()
        upstream.value = connected
        runCurrent()
        upstream.value = failed
        runCurrent()

        // No time advanced past 0 — every steady status is already present.
        assertEquals(
            listOf(ConnectionStatus.Idle, connecting, switching, connected, failed),
            out,
        )
    }
}
