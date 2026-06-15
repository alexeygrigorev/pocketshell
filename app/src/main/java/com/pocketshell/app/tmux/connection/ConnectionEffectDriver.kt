package com.pocketshell.app.tmux.connection

import android.util.Log
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent
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
 * Slice 1c-iv-b-B2 (#742) takes the controller's TRANSPORT INPUTS off the shadow
 * mirror: the driver now SUBMITS [ConnectionEvent.TransportLive] /
 * [ConnectionEvent.TransportDropped] to the controller from the REAL port flows it
 * already collects, REPLACING the bridge's `observeTransportLive` /
 * `observeTransportDropped` mirror feeds (deleted — D22 hard-cut). This is a faithful
 * 1:1 substitution that preserves behavior:
 *  - [TransportPort.transportEvents] `Up(host)` for the controller's CURRENT host →
 *    [ConnectionEvent.TransportLive]. The lease going `Connected` for the active host
 *    is the SAME signal the deleted `observeTransportLiveInShadow` mirrored (the host
 *    filter reproduces its per-target gate — both encode the lease key via `hostKeyFor`).
 *  - [TmuxPort.disconnected] true-edge → [ConnectionEvent.TransportDropped]. The
 *    control-channel drop oracle is the SAME `TmuxClient.disconnected` source the
 *    deleted inline `handlePassiveClientDisconnect` mirror fed from; the controller's
 *    own [ConnectionController] reducer self-gates a drop to a live-ish state (no-op
 *    from Idle/Backgrounded/Gone/Unreachable), reproducing the old `Connected` gate.
 * After this slice the controller's transport inputs are driver-fed FROM REALITY — the
 * prerequisite for the driver ACTING in slice C. The lease `Down` edge is NOT yet
 * submitted (the reconnect-loop ownership that consumes it is slice C/D); B2 replaces
 * exactly the two mirror feeds and nothing more.
 *
 * After each driver-submitted transport event the driver fires the VM-supplied
 * [onControllerTransition] callback so the VM re-projects `_connectionStatus` from the
 * controller's (now real-flow-driven) state — exactly as the deleted mirror feeds did
 * via `projectStatusFromController()`.
 *
 * ## What is STILL observe-only (this slice)
 * Every OTHER effect remains inline. The driver calls **ZERO port IO** — it never
 * invokes [TransportPort.ensureLease], [TransportPort.evictStale], [TmuxPort.attach],
 * [TmuxPort.selectWindow], [TmuxPort.seedActivePane], or [TmuxPort.detachCleanly]. The
 * OPEN path (`runConnect`/`connectJob`), the switch path, the generation counter, and
 * the cold/warm projection read stay inline (deferred to a later slice). The only
 * controller submissions are the two transport events above; the detach effect is
 * routed through the VM-supplied [backgroundedEffect] callback.
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
 * @param controller the connection-core reducer whose [state] transitions the driver
 *   observes AND (slice B2) the controller it SUBMITs the two transport events to.
 * @param tmuxPort the tmux control-mode port. Its [TmuxPort.disconnected] true-edge is
 *   submitted as [ConnectionEvent.TransportDropped]; no IO method is ever called.
 * @param transportPort the SSH-lease transport port. Its [TransportPort.transportEvents]
 *   `Up` edge for the current host is submitted as [ConnectionEvent.TransportLive]; no
 *   IO method is ever called.
 * @param scope the scope the three collectors run in (the VM supplies its
 *   `viewModelScope`; tests supply a `TestScope`'s scope).
 * @param backgroundedEffect fired SYNCHRONOUSLY when the controller transitions INTO
 *   [ConnectionState.Backgrounded]. The VM supplies the clean-detach body (its full
 *   `closeCurrentConnectionAndJoin` teardown). Defaults to a no-op so the
 *   observe-only test harness keeps its inert contract.
 * @param foregroundReattachEffect fired SYNCHRONOUSLY when the controller transitions
 *   [ConnectionState.Backgrounded] -> [ConnectionState.Reattaching] — the within-grace
 *   foreground return (#754, slice 1c-iv-c). The VM supplies the RESEED-ONLY body: the
 *   warm `-CC` lease is still attached, so it re-captures the active pane(s) and lets a
 *   real `SeedLanded` promote the controller back to [ConnectionState.Live]. It NEVER
 *   runs `connect()` and NEVER raises the "Attaching…" overlay (`_switchHidesTerminal`)
 *   — that is the whole point of the D21 within-grace contract (no reconnect UI). The
 *   superseded inline `probeCurrentRuntimeOnForegroundIfNeeded -> connect(LifecycleReattach)`
 *   path is DELETED in the same PR (D22 hard-cut). Defaults to a no-op so the observe-only
 *   test harness keeps its inert contract. NOTE: a [ConnectionState.Reattaching] reached
 *   from a transport DROP (the silent heal ladder) is NOT a foreground return and does
 *   NOT fire this effect — only the `Backgrounded -> Reattaching` edge does.
 * @param onControllerTransition fired AFTER each driver-submitted transport event so
 *   the VM re-projects `_connectionStatus` from the controller's state (the controller
 *   is the single status source). Defaults to a no-op (tests read [observations]/
 *   [ConnectionController.state] directly).
 * @param sink where every observed transition is recorded. Defaults to logcat.
 */
class ConnectionEffectDriver(
    private val controller: ConnectionController,
    private val tmuxPort: TmuxPort,
    private val transportPort: TransportPort,
    private val scope: CoroutineScope,
    private val backgroundedEffect: () -> Unit = {},
    private val foregroundReattachEffect: () -> Unit = {},
    private val onControllerTransition: () -> Unit = {},
    // EPIC #687 P2 (J1/#635): the SINGLE-GRACE-OWNER gate. When this returns true, the
    // driver SUPPRESSES the `TransportDropped` submission for a control-channel drop —
    // i.e. it does NOT walk the controller down the reconnect ladder. The VM supplies a
    // process-backgrounded predicate: a `-CC` drop that arrives while the app is
    // BACKGROUNDED is, by construction, inside the App-level background-grace window
    // (#450), which is the SOLE grace authority. Acting on it here would be a SECOND,
    // competing grace clock that collapses the controller to Unreachable while
    // backgrounded — the literal cause of the #635 spurious band on the next
    // within-grace foreground. Suppressing it leaves recovery entirely to the single
    // grace owner (the within-grace foreground heal). Default `{ false }` keeps the
    // always-submit behavior for the observe-only test harness. The lease
    // `Up`/`TransportLive` feed is NEVER suppressed (a healthy re-`Connected` must
    // always promote the controller).
    private val suppressTransportDrops: () -> Boolean = { false },
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
     * Begin observing. Launches three collectors in [scope]:
     *  - [ConnectionController.state] transitions (fires [backgroundedEffect] on the
     *    Backgrounded entry — slice B1; fires [foregroundReattachEffect] on the
     *    Backgrounded -> Reattaching within-grace foreground edge — slice 1c-iv-c #754),
     *  - [TmuxPort.disconnected] — the transport-drop oracle; its true-edge is
     *    submitted as [ConnectionEvent.TransportDropped] (slice B2),
     *  - [TransportPort.transportEvents] — the lease up/down edge stream; an `Up` for
     *    the current host is submitted as [ConnectionEvent.TransportLive] (slice B2).
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
            // Slice 1c-iv-c (#754): the driver OWNS the within-grace FOREGROUND reattach.
            // The within-grace foreground return is the controller's
            // Backgrounded -> Reattaching edge (warm lease + still in grace). On that
            // edge the driver fires the VM-supplied RESEED-ONLY effect — re-capture the
            // active pane(s) over the still-warm `-CC` lease and let the real SeedLanded
            // promote the controller back to Live. NO connect(), NO "Attaching…" overlay
            // (the deleted inline probe->connect path is what showed it). Only the
            // Backgrounded -> Reattaching edge fires this; a Reattaching reached from a
            // transport DROP (the silent heal) is NOT a foreground return.
            if (current is ConnectionState.Reattaching && previous is ConnectionState.Backgrounded) {
                foregroundReattachEffect()
            }
            previous = current
        }
    }

    private suspend fun collectTransportDrops(disconnected: Flow<Boolean>) {
        disconnected.collect { isDisconnected ->
            record(Observation.Disconnected(isDisconnected))
            // Slice 1c-iv-b-B2 (#742): the control-channel drop oracle is the SAME
            // `TmuxClient.disconnected` source the deleted inline
            // `handlePassiveClientDisconnect` mirror fed `observeTransportDropped` from.
            // Submit it to the controller on the true-edge only; the reducer self-gates
            // a drop to a live-ish state (no-op from Idle/Backgrounded/Gone/Unreachable),
            // reproducing the old `Connected` gate without re-reading inline VM state.
            if (isDisconnected) {
                // EPIC #687 P2 (J1/#635): SINGLE GRACE OWNER. Suppress the drop submission
                // while the app is backgrounded under the NEW path — the App-level grace
                // window owns recovery. Without this the controller is walked Live → …
                // → Unreachable while backgrounded, and the next within-grace foreground
                // returns to a (controller-projected) disconnect band. Deferring leaves
                // the channel-heal to the within-grace foreground (the single owner).
                if (suppressTransportDrops()) {
                    record(Observation.DropSuppressed)
                } else {
                    submitTransport(
                        ConnectionEvent.TransportDropped(reason = "control_channel_disconnected"),
                    )
                }
            }
        }
    }

    private suspend fun collectTransportEvents(events: Flow<TransportUpDown>) {
        events.collect { edge ->
            record(Observation.TransportEdge(edge))
            // Slice 1c-iv-b-B2 (#742): the lease going `Connected` for the controller's
            // CURRENT host is the SAME signal the deleted `observeTransportLiveInShadow`
            // mirror fed `observeTransportLive` from. The host filter reproduces its
            // per-target gate (both encode the lease key via `hostKeyFor`, so a live
            // signal for an unrelated host never spuriously promotes the controller).
            // The lease `Down` edge is NOT submitted in B2 — its consumer (the
            // reconnect loop) is a later slice; `TransportDropped` here comes from the
            // control-channel oracle above, matching the old mirror feed exactly.
            if (edge is TransportUpDown.Up && edge.host == controller.state.value.hostOrNull()) {
                submitTransport(ConnectionEvent.TransportLive)
            }
        }
    }

    /**
     * Submit a transport event to the controller and re-project the displayed status.
     * The driver is now the controller's transport input (B2 #742); after each submit
     * the VM-supplied [onControllerTransition] re-projects `_connectionStatus` from the
     * controller's state — exactly as the deleted mirror feeds did.
     */
    private fun submitTransport(event: ConnectionEvent) {
        controller.submit(event)
        onControllerTransition()
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

        /**
         * EPIC #687 P2 (J1/#635): a control-channel drop the driver SUPPRESSED under the
         * single-grace-owner gate (backgrounded under the NEW path). Recorded for the
         * characterization test; the controller is intentionally NOT walked down the
         * reconnect ladder — recovery is deferred to the within-grace foreground heal.
         */
        data object DropSuppressed : Observation {
            override fun logLine(): String = "tmux.disconnected=true SUPPRESSED (single-grace-owner)"
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
