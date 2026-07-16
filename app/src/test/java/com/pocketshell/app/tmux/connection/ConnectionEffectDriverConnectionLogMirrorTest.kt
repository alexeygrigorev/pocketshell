package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.Clock
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
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
import org.junit.Test

/**
 * Issue #972 — the host connection-log mirror TRIGGER wiring.
 *
 * The #969 part-3 writer ([com.pocketshell.app.diagnostics.ConnectionLogHostMirror])
 * shipped tested-but-UNWIRED (a dead method), so the on-device
 * `~/.pocketshell/connection-log.jsonl` was never produced. This pins the wiring
 * that closes that gap: the driver fires `onTransportReconnected` EXACTLY on an
 * accepted lease `Up` edge for the CURRENT host (the reconnect-completed edge),
 * and NEVER for a foreign host nor on a `Down`.
 *
 * Reproduce-first: on BASE (no `onTransportReconnected` callback wired into the
 * driver's `Up` branch) the fire count below stays 0 — red. With the wiring it
 * fires once per accepted current-host `Up` — green.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionEffectDriverConnectionLogMirrorTest {

    private class TestClock(var now: Long = 0L) : Clock {
        override fun nowMs(): Long = now
    }

    private val host = HostKey("alice@example.com:22/7")
    private val otherHost = HostKey("bob@elsewhere.example:22/9")
    private val sessionA = SessionId("7/main")

    private class InertTmuxPort : TmuxPort {
        val disconnectedFlow = MutableSharedFlow<Boolean>(extraBufferCapacity = 16)
        override val disconnected: Flow<Boolean> = disconnectedFlow
    }

    private class InertTransportPort(private val warm: Boolean) : TransportPort {
        val transportEventsFlow = MutableSharedFlow<TransportUpDown>(extraBufferCapacity = 16)
        override val transportEvents: Flow<TransportUpDown> = transportEventsFlow
        override fun isWarm(host: HostKey): Boolean = warm
    }

    @Test
    fun firesTheMirrorTriggerOnAReconnectedCurrentHostUpEdge() = runTest {
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val tmuxPort = InertTmuxPort()
        // Cold so the enter parks at Connecting and a real lease Up (the reconnect
        // edge) is what promotes it.
        val transportPort = InertTransportPort(warm = false)
        val controller = ConnectionController(clock = TestClock(), transport = transportPort)
        var mirrorFires = 0
        val driver = ConnectionEffectDriver(
            controller = controller,
            tmuxPort = tmuxPort,
            transportPort = transportPort,
            scope = scope,
            onTransportReconnected = { mirrorFires += 1 },
        ).also { it.start() }

        controller.submit(ConnectionEvent.Enter(host, sessionA))

        // A foreign-host Up must NOT fire the mirror (host filter).
        transportPort.transportEventsFlow.emit(TransportUpDown.Up(otherHost))
        assertEquals("a foreign-host Up must not mirror", 0, mirrorFires)

        // A Down for the current host must NOT fire the mirror (only Up does).
        transportPort.transportEventsFlow.emit(TransportUpDown.Down(host, reason = "closed", locallyInitiated = false))
        assertEquals("a Down edge must not mirror", 0, mirrorFires)

        // The current-host Up (reconnect completed) fires the mirror exactly once.
        transportPort.transportEventsFlow.emit(TransportUpDown.Up(host))
        assertEquals("the current-host reconnect Up must mirror once", 1, mirrorFires)

        // A second reconnect cycle fires it again (it is the per-reconnect trigger).
        transportPort.transportEventsFlow.emit(TransportUpDown.Down(host, reason = "closed", locallyInitiated = false))
        transportPort.transportEventsFlow.emit(TransportUpDown.Up(host))
        assertEquals("each reconnect Up re-mirrors", 2, mirrorFires)

        scope.cancel()
    }
}
