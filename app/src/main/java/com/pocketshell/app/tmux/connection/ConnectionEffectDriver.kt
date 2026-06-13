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
 * EPIC #687 Phase-2, slice 1c-iv-b — the effect driver.
 *
 * Slice 1c-iv-b-A landed this as the INERT, OBSERVE-ONLY seam: it owns a
 * [CoroutineScope] and, through the already-built+unit-tested
 * [ConnectionPortAdapters] ([TmuxPort.disconnected] / [TransportPort.transportEvents])
 * plus the pure [ConnectionController]'s [ConnectionController.state] flow, it
 * COLLECTS every transition and signal the effect bodies fire off.
 *
 * Slice 1c-iv-b-B1 (#738) takes the FIRST effect off observe-only: the driver now
 * OWNS the clean background detach. When the controller's [ConnectionController.state]
 * transitions INTO [ConnectionState.Backgrounded], the driver fires
 * [backgroundedEffect] — the VM supplies the body that runs the EXISTING full
 * teardown ([com.pocketshell.app.tmux.TmuxSessionViewModel] `closeCurrentConnectionAndJoin`
 * under `NonCancellable`). The inline `backgroundDetachJob` *trigger* is deleted
 * (D22 hard-cut — no fallback); the driver is the sole detach trigger.
 *
 * ## What is STILL observe-only (this slice)
 * Every OTHER effect remains inline. The driver calls **ZERO port IO** — it never
 * invokes [TransportPort.ensureLease], [TransportPort.evictStale], [TmuxPort.attach],
 * [TmuxPort.selectWindow], [TmuxPort.seedActivePane], or [TmuxPort.detachCleanly],
 * and it does not submit any [com.pocketshell.core.connection.ConnectionEvent] back
 * into the controller. The OPEN path (`runConnect`/`connectJob`) and the cold/warm
 * projection read stay inline (deferred to a later slice). The detach effect is
 * routed through the VM-supplied [backgroundedEffect] callback — which calls the
 * VM's own full teardown — so no behavior moves into the driver itself; only the
 * *trigger* moves.
 *
 * ## Backgrounded-effect timing (the bg→fg-within-grace invariant)
 * The state collector fires [backgroundedEffect] SYNCHRONOUSLY in the same collector
 * resumption that observes the [ConnectionState.Backgrounded] transition. In
 * production the controller reaches Backgrounded from the VM's synchronous
 * `observeBackground()` (inside `onAppBackgrounded()` on the Main thread); the
 * collector resumption — and thus the detach trigger — runs on the next Main loop
 * turn, exactly like the inline `viewModelScope.launch` the detach job used. The VM
 * keeps the `pendingReattach` bookkeeping SYNCHRONOUS in `onAppBackgrounded()` so the
 * within-grace foreground reattach reads it identically; only the teardown-job launch
 * is driver-triggered. `ProcessLifecycleOwner` always posts `ON_STOP`/`ON_START` on
 * separate Main turns, so the collector resumes between background and foreground —
 * preserving the rapid-bg→fg join behavior.
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
 * @param backgroundedEffect fired SYNCHRONOUSLY when the controller transitions INTO
 *   [ConnectionState.Backgrounded]. The VM supplies the clean-detach body (its full
 *   `closeCurrentConnectionAndJoin` teardown). Defaults to a no-op so the
 *   observe-only test harness keeps its inert contract.
 * @param sink where every observed transition is recorded. Defaults to logcat.
 */
class ConnectionEffectDriver(
    private val controller: ConnectionController,
    private val tmuxPort: TmuxPort,
    private val transportPort: TransportPort,
    private val scope: CoroutineScope,
    private val backgroundedEffect: () -> Unit = {},
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
            // Slice 1c-iv-b-B1 (#738): the driver OWNS the clean background detach.
            // On a transition INTO Backgrounded (from a non-Backgrounded state),
            // fire the VM-supplied teardown effect SYNCHRONOUSLY in this collector
            // resumption — the sole detach trigger now the inline one is deleted.
            if (current is ConnectionState.Backgrounded && previous !is ConnectionState.Backgrounded) {
                backgroundedEffect()
            }
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
