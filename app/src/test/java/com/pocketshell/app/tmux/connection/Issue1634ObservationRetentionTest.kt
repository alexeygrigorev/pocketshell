package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1634 F5 / #1732 — the driver's diagnostic history is a bounded view,
 * while its external sink and connection effects remain lossless.
 *
 * This drives all three collectors installed by [ConnectionEffectDriver.start]:
 * controller state transitions, tmux disconnect edges, and lease transport edges.
 * The unconfined test dispatcher makes every emission deterministic while retaining
 * the production collector and callback path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1634ObservationRetentionTest {

    private val host = HostKey("alice@example.com:22/7")
    private val session = SessionId("7/main")

    private object TestClock : Clock {
        override fun nowMs(): Long = 0L
    }

    private class RecordingTmuxPort : TmuxPort {
        val events = MutableSharedFlow<Boolean>(extraBufferCapacity = GENERATED_STORM_EVENTS)
        override val disconnected: Flow<Boolean> = events
    }

    private class RecordingTransportPort : TransportPort {
        val events = MutableSharedFlow<TransportUpDown>(
            extraBufferCapacity = GENERATED_STORM_EVENTS,
        )
        override val transportEvents: Flow<TransportUpDown> = events

        override fun isWarm(host: HostKey): Boolean = true
    }

    @Test
    fun startedCollectorsRetainNewest256InOrderWithoutLosingSinkOrFreshDropEffect() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = RecordingTmuxPort()
        val transportPort = RecordingTransportPort()
        val controller = ConnectionController(clock = TestClock, transport = transportPort)
        val sinkLines = mutableListOf<String>()
        var projectedTransitions = 0
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            onControllerTransition = { projectedTransitions += 1 },
            sink = sinkLines::add,
        ).also(ConnectionEffectDriver::start)

        try {
            val expected = mutableListOf<ConnectionEffectDriver.Observation>()

            // State collector: initial replay plus a real warm open to Live.
            val idle = ConnectionState.Idle
            val attaching = ConnectionState.Attaching(host, session)
            val live = ConnectionState.Live(host, session)
            expected += ConnectionEffectDriver.Observation.StateTransition(null, idle)
            controller.submit(ConnectionEvent.Enter(host, session))
            expected += ConnectionEffectDriver.Observation.StateTransition(idle, attaching)
            controller.submit(ConnectionEvent.SeedLanded(session, paneId = "%0"))
            expected += ConnectionEffectDriver.Observation.StateTransition(attaching, live)

            // Overflow the old unbounded accumulator through the two real port
            // collectors. Unique foreign-host Up edges make the sink sequence an
            // exact oracle, while tmux false edges exercise the other port variant.
            repeat(STORM_PAIRS) { index ->
                val foreignUp = TransportUpDown.Up(HostKey("foreign-$index"))
                tmuxPort.events.emit(false)
                expected += ConnectionEffectDriver.Observation.Disconnected(false)
                transportPort.events.emit(foreignUp)
                expected += ConnectionEffectDriver.Observation.TransportEdge(foreignUp)
            }
            assertTrue(
                "the real started collectors must observe at least 512 entries before the final edge",
                expected.size >= 512,
            )

            // A fresh genuine control-channel edge after overflow must still drive
            // the controller/effect path. Capacity is diagnostics-only, never a gate.
            tmuxPort.events.emit(true)
            expected += ConnectionEffectDriver.Observation.Disconnected(true)
            val reattaching = ConnectionState.Reattaching(host, session)
            expected += ConnectionEffectDriver.Observation.StateTransition(live, reattaching)

            assertEquals(reattaching, controller.state.value)
            assertEquals(
                "the accepted fresh drop still re-projects the controller transition",
                1,
                projectedTransitions,
            )

            val retained = driver.observations.value
            assertEquals("history is capped exactly at the contract", RETAINED_CAPACITY, retained.size)
            assertEquals(
                "history contains exactly the newest observations in insertion order",
                expected.takeLast(RETAINED_CAPACITY),
                retained,
            )
            assertTrue(
                "wrapped history still represents tmux observations",
                retained.any { it is ConnectionEffectDriver.Observation.Disconnected },
            )
            assertTrue(
                "wrapped history still represents lease transport observations",
                retained.any { it is ConnectionEffectDriver.Observation.TransportEdge },
            )
            assertTrue(
                "the fresh state transition is the newest retained observation",
                retained.last() is ConnectionEffectDriver.Observation.StateTransition &&
                    (retained.last() as ConnectionEffectDriver.Observation.StateTransition).to ==
                    reattaching,
            )

            assertEquals(
                "the external sink receives every generated line once and in callback order",
                expected.map(ConnectionEffectDriver.Observation::logLine),
                sinkLines,
            )
        } finally {
            driver.stop()
            scope.cancel()
        }
    }

    private companion object {
        const val RETAINED_CAPACITY = 256
        const val STORM_PAIRS = 255
        const val GENERATED_STORM_EVENTS = STORM_PAIRS * 2
    }
}
