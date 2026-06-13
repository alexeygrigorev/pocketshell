package com.pocketshell.app.tmux.connection

import android.util.Log
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.connection.hostOrNull
import com.pocketshell.core.connection.targetIdOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EPIC #687 Phase-2, slice 1c-iv-b-A — the INERT, OBSERVE-ONLY effect driver.
 *
 * This is the de-risking seam for the effect-driver hard-cut. It owns a
 * [CoroutineScope] and, through the already-built+unit-tested
 * [ConnectionPortAdapters] ([TmuxPort.disconnected] / [TransportPort.transportEvents])
 * plus the pure [ConnectionController]'s [ConnectionController.state] flow, it
 * COLLECTS every transition and signal the eventual effect bodies will fire off.
 *
 * ## Inert contract (HARD RULE for this slice)
 * The driver calls **ZERO port IO**. It never invokes [TransportPort.ensureLease],
 * [TransportPort.evictStale], [TmuxPort.attach], [TmuxPort.selectWindow],
 * [TmuxPort.seedActivePane], or [TmuxPort.detachCleanly]. It does not submit any
 * [com.pocketshell.core.connection.ConnectionEvent] back into the controller. It
 * only READS the three flows and LOGS what it sees. The inline VM machinery
 * remains the sole driver of all connection behavior — this driver changes no
 * user-visible behavior. It exists ONLY to prove the driver SEES the right
 * transition sequence (enter / switch / background / foreground / transport-drop)
 * through the real adapters BEFORE a later sub-slice (1c-iv-b-B onward) lets it
 * ACT and the inline machinery is deleted.
 *
 * ## Why an injectable [sink]
 * Production logs go to logcat under [TAG]. Tests inject a recording sink so the
 * observed transition sequence is deterministically assertable in a plain JVM
 * unit test (no Robolectric, no logcat) — this is the lever that makes the whole
 * effect-driver cut PR-testable (`ConnectionEffectDriverTest`).
 *
 * @param controller the pure connection-core reducer whose [state] transitions
 *   the driver observes (read-only — never [ConnectionController.submit]ted to).
 * @param tmuxPort the tmux control-mode port. Only its [TmuxPort.disconnected]
 *   flow is collected; no IO method is ever called.
 * @param transportPort the SSH-lease transport port. Only its
 *   [TransportPort.transportEvents] flow is collected; no IO method is ever called.
 * @param scope the scope the three collectors run in (the VM supplies its
 *   `viewModelScope`; tests supply a `TestScope`'s scope).
 * @param sink where every observed transition is recorded. Defaults to logcat.
 */
class ConnectionEffectDriver(
    private val controller: ConnectionController,
    private val tmuxPort: TmuxPort,
    private val transportPort: TransportPort,
    private val scope: CoroutineScope,
    private val sink: (String) -> Unit = { line -> Log.i(TAG, line) },
) {
    private val jobs = mutableListOf<Job>()

    private val _observations = MutableStateFlow<List<Observation>>(emptyList())

    /**
     * The full ordered list of observations the driver has recorded — a read-only
     * diagnostic for the characterization test. Drives NOTHING; nothing in
     * production reads it to gate an effect.
     */
    val observations: StateFlow<List<Observation>> = _observations.asStateFlow()

    @Volatile
    private var started = false

    /**
     * Begin observing. Launches three inert collectors in [scope]:
     *  - [ConnectionController.state] transitions (the lifecycle the effect bodies
     *    will eventually fire off),
     *  - [TmuxPort.disconnected] — the transport-drop oracle,
     *  - [TransportPort.transportEvents] — the lease up/down edge stream.
     * Idempotent: a second [start] is a no-op.
     */
    fun start() {
        if (started) return
        started = true
        jobs += scope.launch { collectStateTransitions(controller.state) }
        jobs += scope.launch { collectTransportDrops(tmuxPort.disconnected) }
        jobs += scope.launch { collectTransportEvents(transportPort.transportEvents) }
    }

    /** Stop all collectors. Idempotent. */
    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        started = false
    }

    private suspend fun collectStateTransitions(states: Flow<ConnectionState>) {
        var previous: ConnectionState? = null
        states.collect { current ->
            // Only record genuine transitions, not the initial replay of an
            // unchanged StateFlow value.
            if (previous != null && current == previous) return@collect
            record(Observation.StateTransition(from = previous, to = current))
            previous = current
        }
    }

    private suspend fun collectTransportDrops(disconnected: Flow<Boolean>) {
        disconnected.collect { isDisconnected ->
            record(Observation.Disconnected(isDisconnected))
        }
    }

    private suspend fun collectTransportEvents(events: Flow<TransportUpDown>) {
        events.collect { edge ->
            record(Observation.TransportEdge(edge))
        }
    }

    private fun record(observation: Observation) {
        _observations.value = _observations.value + observation
        sink(observation.logLine())
    }

    /** One thing the driver observed. Read-only diagnostics; drive nothing. */
    sealed interface Observation {
        fun logLine(): String

        /** A [ConnectionController.state] transition. [from] is null for the first. */
        data class StateTransition(
            val from: ConnectionState?,
            val to: ConnectionState,
        ) : Observation {
            override fun logLine(): String =
                "state ${from?.let(::nameOf) ?: "<initial>"} -> ${nameOf(to)}" +
                    " host=${to.hostOrNull()?.value ?: "-"}" +
                    " target=${to.targetIdOrNull()?.value ?: "-"}"
        }

        /** A [TmuxPort.disconnected] oracle edge. */
        data class Disconnected(val isDisconnected: Boolean) : Observation {
            override fun logLine(): String = "tmux.disconnected=$isDisconnected"
        }

        /** A [TransportPort.transportEvents] lease up/down edge. */
        data class TransportEdge(val edge: TransportUpDown) : Observation {
            override fun logLine(): String = when (edge) {
                is TransportUpDown.Up -> "transport.up host=${edge.host.value}"
                is TransportUpDown.Down ->
                    "transport.down host=${edge.host.value} reason=${edge.reason}"
            }
        }

        companion object {
            /** Short variant name of a [ConnectionState] for logging. */
            fun nameOf(state: ConnectionState): String = when (state) {
                is ConnectionState.Idle -> "Idle"
                is ConnectionState.Connecting -> "Connecting"
                is ConnectionState.Attaching -> "Attaching"
                is ConnectionState.Live -> "Live"
                is ConnectionState.Backgrounded -> "Backgrounded"
                is ConnectionState.Reattaching -> "Reattaching"
                is ConnectionState.Reconnecting -> "Reconnecting(${state.attempt})"
                is ConnectionState.Gone -> "Gone"
                is ConnectionState.Unreachable -> "Unreachable"
            }
        }
    }

    companion object {
        /** Logcat tag for the inert observe-only driver (1c-iv-b-A). */
        const val TAG: String = "PsConnEffectDriver"
    }
}
