package com.pocketshell.core.connection

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * The pure-JVM connection lifecycle state machine (EPIC #687 Phase-2, slice 1).
 *
 * It is a synchronous reducer: callers [submit] a [ConnectionEvent], the
 * controller transitions [state], updates [revealGate], and — for seeds — re-emits
 * on [seeds] only the captures whose id matches the CURRENT target (drop-by-id,
 * the #686 contract). There is NO coroutine, NO `android.*`, NO IO inside the
 * controller: all transport/tmux/clock effects are injected via [TransportPort],
 * [TmuxPort], and [Clock] so the within-grace / beyond-grace / heal decisions are
 * deterministic under a virtual clock. The VM adapter (a later, #661-gated slice)
 * performs the port IO and feeds results back as events.
 *
 * The single grace window collapses the old three clocks (8s passive,
 * 5s silent-reattach, the probe timeout) into ONE 60s lease-anchored predicate:
 * on [ConnectionEvent.Foreground] after a [ConnectionState.Backgrounded],
 * "within grace" == `clock.nowMs() < graceDeadline && transport.isWarm(host)`.
 * No timer runs while Backgrounded (D21); the deadline is a stored value compared
 * on the next foreground, never a scheduled wakeup.
 *
 * ## Threading contract — single confining dispatcher (issue #1234, item 6)
 *
 * This controller is a NON-thread-safe reducer. [submit] performs an unguarded
 * read-modify-write of [state] and mutates the plain [graceDeadlineMs] `var`;
 * nothing here synchronizes. It is correct ONLY because every mutator
 * ([submit] and the reduce helpers it drives, plus [offerSeed]) is driven from a
 * SINGLE confining DISPATCHER — in production the Main/UI dispatcher the VM
 * adapter marshals all connection events onto. That dispatcher *serializes* the
 * mutators, so no two ever overlap; an off-confinement caller that races the
 * reducer would corrupt the `graceDeadlineMs`/`_state.value` updates silently.
 *
 * To keep that contract honest, DEBUG builds install a [ThreadConfinementGuard]
 * that asserts the load-bearing invariant a confining dispatcher provides:
 * **no two mutator calls run concurrently.** It latches an owner for the duration
 * of each mutation and hard-fails if a second thread enters a mutator while the
 * first is still inside — the genuine data race. It deliberately does NOT key off
 * a fixed `Thread.id`: a real dispatcher (the Main dispatcher over its lifetime,
 * and every coroutine test dispatcher — including an `UnconfinedTestDispatcher`
 * that resumes a confined `collect { submit() }` inline on a foreign emitter
 * thread) legitimately serializes work across DIFFERENT pool threads, and that
 * benign migration must pass. In release builds the guard is a no-op (zero
 * overhead) — it documents + enforces the existing contract, changing no behavior.
 */
class ConnectionController(
    private val clock: Clock,
    private val transport: TransportPort,
    private val maxReconnectAttempts: Int = DEFAULT_MAX_RECONNECT_ATTEMPTS,
    private val graceMs: Long = DEFAULT_GRACE_MS,
    // Item 6 (#1234): debug-gated dispatcher-confinement assertion. Defaults to
    // `BuildConfig.DEBUG` so the guard is active under `testDebugUnitTest` and
    // developer debug builds, and a no-op in release. Injectable so a test can
    // pin it on/off deterministically regardless of the build variant.
    confinementAssertionsEnabled: Boolean = BuildConfig.DEBUG,
) {
    /**
     * Enforces the single-confining-dispatcher contract in DEBUG builds by
     * asserting no two mutators overlap. It touches NO controller state and only
     * reads the current thread id, so it can never change reducer behavior — it
     * only converts a silent concurrent (off-confinement) mutation into a loud
     * [IllegalStateException] during development.
     */
    private val confinement = ThreadConfinementGuard(confinementAssertionsEnabled)
    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)

    /** The single honest connection-state source. The VM maps this 1:1 to its
     *  header indicator (the maintainer's "indicator == current state" criterion). */
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _revealGate = MutableStateFlow<RevealDecision>(RevealDecision.None)

    /** Reveal/input gate the view renders from; never reveals a non-target frame. */
    val revealGate: StateFlow<RevealDecision> = _revealGate.asStateFlow()

    private val _seeds = MutableSharedFlow<Seed>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Id-tagged seeds that survived the drop-by-id check (current target only). */
    val seeds: Flow<Seed> = _seeds.asSharedFlow()

    /**
     * Grace deadline stored on Background; null when not backgrounded. No timer.
     *
     * A plain `var` with NO synchronization — it is read-modified-written only
     * from within [submit]'s reduce path, so it relies on the single-confining-
     * dispatcher contract documented on the class (issue #1234, item 6).
     */
    private var graceDeadlineMs: Long? = null

    /** Current target id — the drop-by-id reference for events and seeds. */
    private val currentTargetId: SessionId?
        get() = _state.value.targetIdOrNull()

    private val currentHost: HostKey?
        get() = _state.value.hostOrNull()

    /**
     * Reduce one event into a transition. Returns the resulting [ConnectionState]
     * for convenience (also published on [state]). Events whose target id does not
     * match the current target are dropped (return current state unchanged) — the
     * one place that rule lives.
     *
     * MUST be driven from the controller's single confining (Main) dispatcher: it
     * does an unguarded read-modify-write of [state] and mutates [graceDeadlineMs]
     * (issue #1234, item 6). DEBUG builds assert no mutator overlaps via
     * [confinement].
     */
    fun submit(event: ConnectionEvent): ConnectionState = confinement.guarded("submit") {
        val next = reduce(_state.value, event)
        if (next !== _state.value || next != _state.value) {
            _state.value = next
        }
        _revealGate.value = revealFor(next)
        _state.value
    }

    /**
     * Offer a freshly captured pane seed. Re-emitted on [seeds] ONLY if its id
     * matches the current target; a seed for a superseded/non-current target is
     * dropped. Returns true if accepted (emitted), false if dropped.
     *
     * A confined mutator: shares the controller's single-dispatcher contract with
     * [submit] (issue #1234, item 6). DEBUG builds assert no mutator overlaps via
     * [confinement].
     */
    fun offerSeed(seed: Seed): Boolean = confinement.guarded("offerSeed") {
        if (seed.targetId != currentTargetId) {
            return@guarded false
        }
        _seeds.tryEmit(seed)
    }

    private fun reduce(current: ConnectionState, event: ConnectionEvent): ConnectionState =
        when (event) {
            is ConnectionEvent.Enter -> onEnter(current, event)
            is ConnectionEvent.Switch -> onSwitch(current, event)
            ConnectionEvent.Foreground -> onForeground(current)
            ConnectionEvent.Background -> onBackground(current)
            is ConnectionEvent.TransportDropped -> onTransportDropped(current, event)
            ConnectionEvent.TransportLive -> onTransportLive(current)
            is ConnectionEvent.NetworkChanged -> onNetworkChanged(current, event)
            is ConnectionEvent.TargetGone -> onTargetGone(current, event)
            is ConnectionEvent.SeedLanded -> onSeedLanded(current, event)
        }

    /**
     * A user-initiated open. Warm host (lease up) goes straight to [Attaching]
     * (no overlay); cold host goes to [Connecting] (overlay allowed). An Enter to
     * a brand-new target always supersedes whatever was showing.
     */
    private fun onEnter(current: ConnectionState, event: ConnectionEvent.Enter): ConnectionState {
        // A genuine open clears any prior grace window — we are foregrounded and
        // re-targeting.
        graceDeadlineMs = null
        return if (transport.isWarm(event.host)) {
            ConnectionState.Attaching(event.host, event.targetId)
        } else {
            ConnectionState.Connecting(event.host, event.targetId)
        }
    }

    /**
     * Same-host fast switch: from a [ConnectionState.Live] (or already attaching)
     * state, go to [Attaching] on the new id WITHOUT a re-handshake. The VM will
     * `selectWindow` + seed the active pane. A switch from Idle is a no-op (there
     * is no host to switch on).
     */
    private fun onSwitch(current: ConnectionState, event: ConnectionEvent.Switch): ConnectionState {
        val host = current.hostOrNull() ?: return current
        graceDeadlineMs = null
        return ConnectionState.Attaching(host, event.targetId)
    }

    /**
     * App -> background. From any live-ish state, detach the control channel but
     * keep the lease warm; stamp the single grace deadline. No timer is scheduled
     * (D21) — the deadline is compared on the next [onForeground].
     */
    private fun onBackground(current: ConnectionState): ConnectionState {
        val host = current.hostOrNull() ?: return current
        val target = current.targetIdOrNull() ?: return current
        // Gone/Unreachable are terminal-ish surfaces; backgrounding them keeps
        // them as-is (nothing to detach, no grace to start).
        if (current is ConnectionState.Gone || current is ConnectionState.Unreachable) {
            return current
        }
        val now = clock.nowMs()
        graceDeadlineMs = now + graceMs
        return ConnectionState.Backgrounded(host, target, sinceMs = now)
    }

    /**
     * App -> foreground. The single grace predicate decides:
     * - within grace (`now < deadline && transport.isWarm`) -> [Reattaching]:
     *   zero reconnect, reseed the active pane only (#685 Bug-A).
     * - beyond grace OR lease no longer warm -> [Reconnecting] (attempt 1):
     *   silent auto-reconnect, NO band (#685 Bug-B).
     * Foregrounding from a non-backgrounded state is a no-op.
     */
    private fun onForeground(current: ConnectionState): ConnectionState {
        if (current !is ConnectionState.Backgrounded) {
            return current
        }
        val deadline = graceDeadlineMs
        graceDeadlineMs = null
        val withinGrace = deadline != null &&
            clock.nowMs() < deadline &&
            transport.isWarm(current.host)
        return if (withinGrace) {
            ConnectionState.Reattaching(current.host, current.targetId)
        } else {
            ConnectionState.Reconnecting(current.host, current.targetId, attempt = 1)
        }
    }

    /**
     * Transport dropped while connected. A live channel heals silently through
     * [Reattaching] (no band, #680/#621). A drop while already
     * reattaching/reconnecting escalates the reconnect ladder; once the attempt
     * budget is exhausted the ONLY honest error surfaces: [Unreachable].
     */
    private fun onTransportDropped(
        current: ConnectionState,
        @Suppress("UNUSED_PARAMETER") event: ConnectionEvent.TransportDropped,
    ): ConnectionState {
        val host = current.hostOrNull() ?: return current
        val target = current.targetIdOrNull() ?: return current
        return when (current) {
            is ConnectionState.Live,
            is ConnectionState.Attaching,
            is ConnectionState.Connecting,
            -> ConnectionState.Reattaching(host, target)

            is ConnectionState.Reattaching ->
                // The silent heal failed once; start the silent reconnect ladder.
                ConnectionState.Reconnecting(host, target, attempt = 1)

            is ConnectionState.Reconnecting -> {
                val nextAttempt = current.attempt + 1
                if (nextAttempt > maxReconnectAttempts) {
                    ConnectionState.Unreachable(host, target)
                } else {
                    ConnectionState.Reconnecting(host, target, attempt = nextAttempt)
                }
            }

            // Backgrounded/Idle/Gone/Unreachable: a drop is irrelevant.
            else -> current
        }
    }

    /**
     * Transport healed / lease connected. Reattaching and Reconnecting resolve to
     * [Live] on the same target (silent recovery). A spurious live signal in
     * another state is ignored.
     */
    private fun onTransportLive(current: ConnectionState): ConnectionState =
        when (current) {
            is ConnectionState.Reattaching -> ConnectionState.Live(current.host, current.targetId)
            is ConnectionState.Reconnecting -> ConnectionState.Live(current.host, current.targetId)
            is ConnectionState.Connecting -> ConnectionState.Attaching(current.host, current.targetId)
            else -> current
        }

    /**
     * Device network changed (#548 suppression contract). ONLY a real, VALIDATED
     * identity handoff on a [ConnectionState.Live] channel proactively
     * silent-reconnects (through the [ConnectionState.Reconnecting] ladder, NO
     * band). A non-validated change is a no-op: the live channel is left alone so
     * a transient blip / same-network re-validation never tears down a healthy
     * channel (sshj keepalive stays the sole death oracle). A change in any
     * non-live state is also a no-op — there is nothing to proactively re-dial,
     * and an in-flight reattach/reconnect must not be disturbed.
     */
    private fun onNetworkChanged(
        current: ConnectionState,
        event: ConnectionEvent.NetworkChanged,
    ): ConnectionState {
        if (!event.validatedHandoff) return current
        return when (current) {
            is ConnectionState.Live ->
                ConnectionState.Reconnecting(current.host, current.targetId, attempt = 1)
            // Connecting/Attaching/Reattaching/Reconnecting already have a
            // dial/heal in flight; Backgrounded/Idle/Gone/Unreachable have no live
            // channel to proactively replace. Suppress in all of them.
            else -> current
        }
    }

    /**
     * Target deleted elsewhere (#666). Drops events for a non-current target.
     * Transitions to [Gone] — the controller never issues attach-create from here.
     */
    private fun onTargetGone(current: ConnectionState, event: ConnectionEvent.TargetGone): ConnectionState {
        if (event.targetId != current.targetIdOrNull()) {
            return current
        }
        val host = current.hostOrNull() ?: return current
        return ConnectionState.Gone(host, event.targetId)
    }

    /**
     * A capture completed for ([targetId], [paneId]). A landing for the current
     * target while attaching/reattaching/reconnecting confirms the active pane and
     * promotes to [Live]; a landing for a non-current target is dropped. SeedLanded
     * is the reseed-gate decision feedback (it does NOT itself carry the frame —
     * that arrives via [offerSeed]).
     */
    private fun onSeedLanded(current: ConnectionState, event: ConnectionEvent.SeedLanded): ConnectionState {
        if (event.targetId != current.targetIdOrNull()) {
            return current
        }
        val host = current.hostOrNull() ?: return current
        return when (current) {
            is ConnectionState.Attaching,
            is ConnectionState.Reattaching,
            is ConnectionState.Reconnecting,
            -> ConnectionState.Live(host, event.targetId)
            // Already Live (a background-pane reveal seed) or any other state:
            // the landing doesn't change the lifecycle state.
            else -> current
        }
    }

    /** Project a state to its reveal/input gate. Only [ConnectionState.Live]
     *  reveals content; everything else holds (or None for Idle). */
    private fun revealFor(state: ConnectionState): RevealDecision =
        when (state) {
            is ConnectionState.Idle -> RevealDecision.None
            is ConnectionState.Live -> RevealDecision.Reveal(state.targetId, inputEnabled = true)
            else -> {
                val target = state.targetIdOrNull()
                if (target == null) RevealDecision.None else RevealDecision.Hold(target)
            }
        }

    companion object {
        /**
         * Single lease-anchored grace window. Issue #1159 (maintainer directive
         * 2026-07-01): lowered 5 min -> **90 s** ("5 minutes is longer than needed");
         * within the window a background return is seamless, beyond it the connection
         * fully tears down (no indefinite hold) unless a port-forward pins it always-on
         * (issue #1159 Part 3). Mirrors `AppSettings.DEFAULT_BACKGROUND_GRACE_MILLIS`.
         */
        const val DEFAULT_GRACE_MS: Long = 90_000L

        /** Silent reconnect attempts before the only honest error ([Unreachable]). */
        const val DEFAULT_MAX_RECONNECT_ATTEMPTS: Int = 4
    }
}

/** The target id of a non-idle state, or null for [ConnectionState.Idle]. */
fun ConnectionState.targetIdOrNull(): SessionId? = when (this) {
    is ConnectionState.Idle -> null
    is ConnectionState.Connecting -> targetId
    is ConnectionState.Attaching -> targetId
    is ConnectionState.Live -> targetId
    is ConnectionState.Backgrounded -> targetId
    is ConnectionState.Reattaching -> targetId
    is ConnectionState.Reconnecting -> targetId
    is ConnectionState.Gone -> targetId
    is ConnectionState.Unreachable -> targetId
}

/** The host of a non-idle state, or null for [ConnectionState.Idle]. */
fun ConnectionState.hostOrNull(): HostKey? = when (this) {
    is ConnectionState.Idle -> null
    is ConnectionState.Connecting -> host
    is ConnectionState.Attaching -> host
    is ConnectionState.Live -> host
    is ConnectionState.Backgrounded -> host
    is ConnectionState.Reattaching -> host
    is ConnectionState.Reconnecting -> host
    is ConnectionState.Gone -> host
    is ConnectionState.Unreachable -> host
}

/**
 * Debug-only dispatcher-confinement guard for [ConnectionController]
 * (issue #1234, item 6).
 *
 * The controller's mutators ([ConnectionController.submit] / [offerSeed]) are
 * NOT thread-safe: they read-modify-write `_state.value` and the plain
 * `graceDeadlineMs` `var` without synchronization, correct only under the
 * contract that they are driven from a SINGLE confining DISPATCHER (in production
 * the Main/UI dispatcher the VM marshals every connection event onto). The
 * load-bearing invariant that dispatcher guarantees, and the ONLY thing that
 * keeps the unguarded read-modify-writes safe, is that **no two mutator calls
 * ever run concurrently** — a single confining dispatcher serializes them.
 *
 * ## Why this asserts NO-CONCURRENT-MUTATION, not raw-thread identity
 * A confining dispatcher is a *serialization* contract, NOT a fixed-thread one.
 * A dispatcher (the production Main dispatcher over its lifetime, and every
 * coroutine test dispatcher) may legitimately run its serialized work on
 * DIFFERENT pool threads at different times — and, under an
 * `UnconfinedTestDispatcher`, may even resume a confined `collect { submit(...) }`
 * body *inline on the foreign thread that emitted upstream*. All of those are
 * still confined: the calls never OVERLAP. Keying the guard off the first raw
 * `Thread.id` (round 1) wrongly flagged that benign pool-thread migration as a
 * violation and tripped 5 `TmuxSessionViewModelTest` cases. So the guard asserts
 * the invariant that actually matters: it latches an owner for the DURATION of a
 * mutation and hard-fails only if a SECOND thread enters a mutator while the
 * first is still inside it — the genuine data race that corrupts lifecycle state.
 * Sequential hand-off across pool threads (a real dispatcher) passes; a real
 * off-confinement caller that races the reducer trips.
 *
 * When [enabled] is false (release builds, `BuildConfig.DEBUG == false`) it is a
 * zero-work no-op — it never touches controller state and only ever reads the
 * current thread, so it cannot alter reducer behavior on any build.
 */
internal class ThreadConfinementGuard(private val enabled: Boolean) {
    // The thread currently executing a mutator, or UNSET when none is. A CAS
    // UNSET -> current on entry latches the owner for the mutation's duration;
    // the matching exit releases it back to UNSET. AtomicLong so a concurrent
    // second entrant observes the in-progress owner atomically.
    private val activeThreadId = AtomicLong(UNSET)
    // Reentrancy depth for the owner thread (a mutator that re-enters a mutator
    // on the same thread must not release the latch early). Only ever read/written
    // by the owner thread while it holds the latch, so it needs no synchronization.
    private var reentrancyDepth: Int = 0

    /**
     * Run [body] as a confined mutation. No-op wrapper when [enabled] is false.
     * If another thread is already inside a mutator when this call enters, that is
     * a genuine concurrent (off-confinement) mutation and hard-fails with an
     * [IllegalStateException]; sequential calls — including ones that migrate
     * across a dispatcher's pool threads — pass. [operation] names the caller for
     * the failure message.
     */
    fun <T> guarded(operation: String, body: () -> T): T {
        if (!enabled) return body()
        val currentId = Thread.currentThread().id
        val reentrant = activeThreadId.get() == currentId
        if (!reentrant) {
            if (!activeThreadId.compareAndSet(UNSET, currentId)) {
                val holder = activeThreadId.get()
                throw IllegalStateException(
                    "ConnectionController.$operation() ran concurrently with another " +
                        "mutator: thread '${Thread.currentThread().name}' (id=$currentId) " +
                        "entered while thread id=$holder was still inside a mutation. The " +
                        "controller is a non-thread-safe reducer — all mutators must be " +
                        "confined to a single serializing (Main) dispatcher so they never " +
                        "overlap (issue #1234, item 6)."
                )
            }
            reentrancyDepth = 1
        } else {
            reentrancyDepth++
        }
        try {
            return body()
        } finally {
            if (--reentrancyDepth == 0) {
                activeThreadId.set(UNSET)
            }
        }
    }

    private companion object {
        private const val UNSET: Long = -1L
    }
}
