package com.pocketshell.core.connection

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

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
    /**
     * Issue #1633: how long the connection must stay continuously [ConnectionState.Live]
     * before the current recovery EPISODE commits and the attempt counter resets.
     */
    private val stabilityWindowMs: Long = DEFAULT_STABILITY_WINDOW_MS,
    /**
     * Issue #1633: the wall-clock ceiling on one recovery episode. A flap slow enough to
     * never exhaust the rung budget still terminates here instead of grinding forever.
     */
    private val episodeBudgetMs: Long = DEFAULT_EPISODE_BUDGET_MS,
    /**
     * Issue #1633: the jitter source for [retryDelayForAttempt] (±[RETRY_JITTER_FRACTION]).
     * Injectable so tests pin the distribution deterministically instead of skipping it.
     */
    private val random: Random = Random.Default,
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

    /**
     * Issue #1328 (S5): the reconnect backoff ladder — the SINGLE source of the
     * attempt budget ([effectiveMaxAttempts]) and per-attempt backoff
     * ([retryDelayForAttempt]). Injected by the VM ([ConnectionManager.setReconnectLadder])
     * from its `autoReconnectDelaysMs`, so the controller's ladder and the VM effect's
     * backoff timing are ONE ladder, not two. Empty (the default until the VM sets it,
     * and in the many controller unit tests) means "flat: [maxReconnectAttempts]
     * attempts, 0 ms delay" — byte-identical to the pre-S5 counter.
     *
     * A plain `var` mutated only from the confining dispatcher (via [setReconnectLadder]),
     * same single-confining-dispatcher contract as [graceDeadlineMs].
     */
    private var reconnectLadderMs: List<Long> = emptyList()

    /**
     * Issue #1328 (S5): the SINGLE reconnect-attempt counter. It lives HERE, in the
     * controller, decoupled from the transient [ConnectionState] — because the VM's
     * re-dial IO walks the state through Connecting/Attaching/Live on every rung
     * (even one that then fails an attach), which would otherwise reset an in-state
     * counter. 0 means "no EPISODE in flight"; a ladder arms it to 1
     * ([ConnectionEvent.ReconnectLadderEntered] / a heal-failed drop) and each rung that
     * concludes WITHOUT a stability commit advances it until the budget exhausts.
     * A plain `var` mutated only from the confining dispatcher (same contract as
     * [graceDeadlineMs]).
     *
     * Issue #1633 widened its lifetime from "one dial" to "one EPISODE" — see
     * [liveSinceMs].
     */
    private var reconnectAttempt: Int = 0

    /**
     * Issue #1633: when the CURRENT [ConnectionState.Live] began, or null when not Live.
     *
     * This is the whole fix. Before #1633 the attempt counter's reset condition was
     * effectively **"a dial succeeded"** ([onReconnectLadderEntered] hard-armed at 1 on
     * every ladder entry), not **"a connection proved stable"**. On the maintainer's mobile
     * link — which dies ~5s after each successful dial (#1610) — every cycle therefore
     * looked like a brand-new FIRST attempt: backoff never engaged (`retryDelayMs=0` on
     * every one of 15,456 logged burst lines), the budget was never reached, and
     * [ConnectionState.Unreachable] was dead code. The burst only ever ended when the
     * maintainer backgrounded the app.
     *
     * The remedy is the Kubernetes CrashLoopBackOff / gRPC verified-acceptance model: the
     * episode commits only once the link has been continuously Live for
     * [stabilityWindowMs]. A drop before then RESUMES the episode at attempt+1.
     *
     * It is evaluated LAZILY, at the next drop ([onLiveDropped]) — never by a timer. The
     * commit has no observable effect until a drop asks "was that Live stable?", so a
     * stored stamp compared on demand is exactly equivalent to a scheduled wakeup, while
     * keeping the reducer pure, timer-free (D21) and free of any coroutine loop.
     */
    private var liveSinceMs: Long? = null

    /**
     * Issue #1633: when the current recovery EPISODE began (the first drop out of a
     * committed/fresh [ConnectionState.Live]), or null when no episode is in flight.
     *
     * An episode spans the WHOLE flap sequence, not one dial: exiting to Live does not end
     * it — only the [stabilityWindowMs] commit (or explicit user intent) does. It bounds
     * the episode by wall clock ([episodeBudgetMs]) as well as by rung count, so a flap too
     * slow to exhaust the rungs still terminates.
     */
    private var episodeStartMs: Long? = null

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

    /**
     * Issue #1328 (S5): install the reconnect backoff ladder (from the VM's
     * `autoReconnectDelaysMs`). The ladder size is the attempt budget and each
     * entry the per-attempt backoff, so the controller's SINGLE counter/exhaustion
     * decision uses the exact same ladder the VM effect times its dials against.
     *
     * A confined mutator (shares the single-dispatcher contract with [submit]).
     */
    fun setReconnectLadder(delaysMs: List<Long>) = confinement.guarded("setReconnectLadder") {
        reconnectLadderMs = delaysMs
        // If a reconnect is already in flight when the ladder is (re)installed — the
        // proactive network-handoff / passive-drop paths enter [ConnectionState.Reconnecting]
        // BEFORE the VM effect installs its `autoReconnectDelaysMs` — re-stamp the current
        // attempt's maxAttempts/retryDelayMs from the new ladder so the SINGLE displayed
        // ladder reflects the real backoff, not the stale flat default (issue #1328).
        val current = _state.value
        if (current is ConnectionState.Reconnecting) {
            _state.value = reconnectingAt(current.host, current.targetId, current.attempt)
        }
    }

    /** The attempt budget for the current ladder — the ONE exhaustion boundary. */
    private val effectiveMaxAttempts: Int
        get() = reconnectLadderMs.size.takeIf { it > 0 } ?: maxReconnectAttempts

    /**
     * Backoff before the 1-based [attempt]'s dial; clamps to the last ladder step, then
     * jittered ±[RETRY_JITTER_FRACTION] (issue #1633, the gRPC model).
     *
     * Mobile RAT handovers and NAT/keepalive reapers are PERIODIC, so a bare ladder can
     * resonate with that cadence (and synchronize retries across sessions into a
     * thundering herd). Jitter breaks both. Rung 1's `0ms` is left EXACTLY zero — that
     * rung is the instant silent heal (#680/#621) and delaying it would be a visible
     * regression — and a jittered rung never rounds down to 0, so a non-zero rung always
     * actually waits.
     */
    private fun retryDelayForAttempt(attempt: Int): Long {
        if (reconnectLadderMs.isEmpty()) return 0L
        val index = (attempt - 1).coerceIn(0, reconnectLadderMs.lastIndex)
        return jittered(reconnectLadderMs[index])
    }

    /** Apply ±[RETRY_JITTER_FRACTION] full jitter to a non-zero backoff (issue #1633). */
    private fun jittered(delayMs: Long): Long {
        if (delayMs <= 0L) return 0L
        val spread = delayMs * RETRY_JITTER_FRACTION
        val offset = random.nextDouble(-spread, spread)
        return (delayMs + offset).toLong().coerceAtLeast(1L)
    }

    /** Build a [ConnectionState.Reconnecting] for [attempt] from the single ladder,
     *  syncing the churn-surviving [reconnectAttempt] counter to it and stamping the
     *  episode start if this is the episode's first rung (issue #1633). */
    private fun reconnectingAt(host: HostKey, target: SessionId, attempt: Int): ConnectionState.Reconnecting {
        reconnectAttempt = attempt
        if (episodeStartMs == null) episodeStartMs = clock.nowMs()
        liveSinceMs = null
        return ConnectionState.Reconnecting(
            host = host,
            targetId = target,
            attempt = attempt,
            maxAttempts = effectiveMaxAttempts,
            retryDelayMs = retryDelayForAttempt(attempt),
        )
    }

    /**
     * Issue #1633: END the current episode — the counter, the episode clock and the
     * stability stamp all reset, so the NEXT drop starts a fresh episode at attempt 1 with
     * the full budget and the instant first rung.
     *
     * Called on the stability commit ([onLiveDropped]), on explicit user intent
     * ([onEnter]/[onSwitch]), and when a terminal state is reached.
     */
    private fun endEpisode() {
        reconnectAttempt = 0
        episodeStartMs = null
        liveSinceMs = null
    }

    /** Enter [ConnectionState.Live], stamping the stability window's start (issue #1633). */
    private fun liveAt(host: HostKey, target: SessionId): ConnectionState.Live {
        liveSinceMs = clock.nowMs()
        return ConnectionState.Live(host, target)
    }

    /** True once the current episode has burned its rung budget or its wall clock. */
    private fun episodeExhausted(nextAttempt: Int): Boolean {
        if (nextAttempt > effectiveMaxAttempts) return true
        val started = episodeStartMs ?: return false
        return clock.nowMs() - started >= episodeBudgetMs
    }

    /**
     * Issue #1633: the [ConnectionState.Live] that is ending faces the stability question —
     * commit the episode if it held for [stabilityWindowMs], otherwise leave the episode in
     * flight so the caller resumes it. The ONE place the window is evaluated; every path
     * that ends a Live must go through it, or that path becomes a counter-laundering hole.
     */
    private fun endLiveAndCommitIfStable() {
        val since = liveSinceMs
        liveSinceMs = null
        if (since != null && clock.nowMs() - since >= stabilityWindowMs) endEpisode()
    }

    /** The rung an ending Live should resume the episode at: 1 when none is in flight. */
    private fun resumeAttempt(): Int = if (reconnectAttempt == 0) 1 else reconnectAttempt + 1

    /**
     * Issue #1633: the transport dropped out of [ConnectionState.Live] — the ONE place the
     * "did this connection prove stable?" question is asked, and the loop-killer.
     *
     * - The prior Live lasted ≥ [stabilityWindowMs] ⇒ the episode COMMITS. The counter
     *   resets, and this drop opens a fresh episode: silent heal first, attempt 1, instant
     *   rung. This is the load-bearing NEGATIVE case — without it every ordinary reconnect
     *   would escalate spuriously.
     * - An episode was already in flight and the Live did NOT prove stable ⇒ that rung
     *   concluded WITHOUT a commit ("the dial succeeded but the link died again"), so the
     *   episode RESUMES at attempt+1 — or gives up honestly at the budget.
     *
     * A drop still routes through [ConnectionState.Reattaching] either way, so the silent
     * heal (#680/#621) and the calm band are byte-for-byte unchanged; only the counter
     * behind them now remembers.
     */
    private fun onLiveDropped(host: HostKey, target: SessionId): ConnectionState {
        endLiveAndCommitIfStable()

        if (reconnectAttempt == 0) {
            // A fresh episode: heal silently first; the counter arms only if that fails.
            episodeStartMs = clock.nowMs()
            return ConnectionState.Reattaching(host, target)
        }
        val next = reconnectAttempt + 1
        if (episodeExhausted(next)) {
            endEpisode()
            return ConnectionState.Unreachable(host, target)
        }
        reconnectAttempt = next
        return ConnectionState.Reattaching(host, target)
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
            ConnectionEvent.NetworkLost -> current
            ConnectionEvent.NetworkRestored -> current
            is ConnectionEvent.TargetGone -> onTargetGone(current, event)
            is ConnectionEvent.SeedLanded -> onSeedLanded(current, event)
            ConnectionEvent.ReconnectLadderEntered -> onReconnectLadderEntered(current)
            ConnectionEvent.ReconnectFailed -> onReconnectFailed(current)
            ConnectionEvent.ReconnectGaveUp -> onReconnectGaveUp(current)
        }

    /**
     * A user-initiated open. Warm host (lease up) goes straight to [Attaching]
     * (no overlay); cold host goes to [Connecting] (overlay allowed). An Enter to
     * a brand-new target always supersedes whatever was showing.
     */
    private fun onEnter(current: ConnectionState, event: ConnectionEvent.Enter): ConnectionState {
        // A genuine open clears any prior grace window — we are foregrounded and
        // re-targeting. It also disarms any stale reconnect counter (#1328) / recovery
        // episode (#1633): an Enter is explicit user intent, and the manual Reconnect
        // affordance out of a terminal Unreachable routes through here.
        graceDeadlineMs = null
        endEpisode()
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
     *
     * Issue #1633 — the episode reset here is DELIBERATE and unchanged. A [ConnectionEvent.Switch]
     * is EXPLICIT USER INTENT: the #1331 design lists Enter/Switch/manual-Reconnect as the
     * intent-reset set, the user is watching and has asked for this target right now, and the
     * honest response to "take me to B" is a fresh, full-budget attempt rather than one
     * inherited from A's flap history. It cannot launder the counter back to 1 during the
     * #1610 storm either: `Switch` is only ever submitted from a user tap (via
     * `ConnectionManager.switchTo`/`ensureTargeting`), never by the automatic reconnect
     * cycle, so the storm's own event sequence never reaches this arm.
     */
    private fun onSwitch(current: ConnectionState, event: ConnectionEvent.Switch): ConnectionState {
        val host = current.hostOrNull() ?: return current
        graceDeadlineMs = null
        endEpisode()
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
            // Issue #1633: a beyond-grace return starts a FRESH episode (the #1331 design's
            // "Foreground beyond grace -> Recovering, attempt 1"). Ending the episode first
            // is what makes that coherent: without it the counter would say "attempt 1"
            // while the stale episode CLOCK said "exhausted", so the first rung failure
            // would fire an instant, wrong Unreachable at the user. Nothing dialled while
            // backgrounded (D21), so no episode was meaningfully in flight; the user has
            // also been away far longer than the stability window. This deliberately keeps
            // backgrounding — today the maintainer's ONLY escape from the #1610 storm —
            // working as a clean restart rather than a trip straight to a dead end.
            endEpisode()
            reconnectingAt(current.host, current.targetId, attempt = 1)
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
            // Issue #1633: the ONE place the stability window is evaluated.
            is ConnectionState.Live -> onLiveDropped(host, target)

            is ConnectionState.Attaching,
            is ConnectionState.Connecting,
            -> ConnectionState.Reattaching(host, target)

            is ConnectionState.Reattaching ->
                // The silent heal failed once; start (or, if a re-dial churn briefly
                // dropped an in-flight ladder back through Reattaching, RESUME at) the
                // silent reconnect ladder. Preserving [reconnectAttempt] here keeps the
                // SINGLE counter churn-safe (#1328).
                reconnectingAt(host, target, attempt = if (reconnectAttempt > 0) reconnectAttempt else 1)

            is ConnectionState.Reconnecting ->
                // Issue #1328 (S5): a raw transport drop while ALREADY in the numbered
                // ladder does NOT advance the counter. The reconnect effect's re-dial IO
                // legitimately closes/evicts the transport on every rung (force-fresh
                // lease, poisoned-half-open evict), which flips the drop oracle — counting
                // those incidental churn drops would exhaust the ladder before the rung's
                // real dial even resolves (fatal on a 1-rung ladder). Advancement is owned
                // SOLELY by [ConnectionEvent.ReconnectFailed], reported once per real rung
                // failure by the effect. So hold the current attempt here (idempotent).
                current

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
            // Issue #1633: entering Live starts the stability window — it does NOT commit
            // the episode. "The dial succeeded" is not "the connection recovered".
            is ConnectionState.Reattaching -> liveAt(current.host, current.targetId)
            is ConnectionState.Reconnecting -> liveAt(current.host, current.targetId)
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
            is ConnectionState.Live -> {
                // Issue #1633: a validated handoff ENDS this Live, so it must face the same
                // stability question as a drop. It used to hard-arm at attempt 1, which on
                // the maintainer's own environment (#1610 — a flapping MOBILE link, where
                // periodic RAT handovers are exactly what fires this event) would launder an
                // uncommitted episode's counter back to 1 during one of the storm's brief
                // Live moments, and the ladder could never climb. A handoff on a link that
                // has PROVEN stable still starts fresh at attempt 1 via the commit.
                endLiveAndCommitIfStable()
                val next = resumeAttempt()
                if (reconnectAttempt > 0 && episodeExhausted(next)) {
                    endEpisode()
                    ConnectionState.Unreachable(current.host, current.targetId)
                } else {
                    reconnectingAt(current.host, current.targetId, attempt = next)
                }
            }
            // Connecting/Attaching/Reattaching/Reconnecting already have a
            // dial/heal in flight; Backgrounded/Idle/Gone/Unreachable have no live
            // channel to proactively replace. Suppress in all of them.
            else -> current
        }
    }

    /**
     * Issue #1328 (S5): the reconnect effect gave up early (a non-retryable failure
     * or explicit abort). Surface the honest [ConnectionState.Unreachable] from any
     * live-ish / recovering state. Idempotent on an already-terminal state, and a
     * no-op with no live channel (Idle/Backgrounded/NetworkLossSuspended). This is
     * NOT the exhaustion path — the reducer decides exhaustion itself in
     * [onTransportDropped]; this is the VM's honest "stop retrying" signal.
     */
    private fun onReconnectGaveUp(current: ConnectionState): ConnectionState {
        val host = current.hostOrNull() ?: return current
        val target = current.targetIdOrNull() ?: return current
        return when (current) {
            is ConnectionState.Live,
            is ConnectionState.Connecting,
            is ConnectionState.Attaching,
            is ConnectionState.Reattaching,
            is ConnectionState.Reconnecting,
            -> {
                endEpisode()
                ConnectionState.Unreachable(host, target)
            }
            // Idle/Backgrounded/NetworkLossSuspended/Gone/Unreachable: nothing to fail.
            else -> current
        }
    }

    /**
     * Issue #1328 (S5): the VM effect entered its numbered reconnect ladder. Move any
     * live-ish / recovering state to [ConnectionState.Reconnecting]. A no-op from
     * Idle/Gone/Unreachable (there is no channel to reconnect — and, per #1633, a
     * terminal give-up must NOT be re-armed out of by the VM's own ladder body, or the
     * storm would simply resume).
     *
     * Issue #1633: this RESUMES the current episode instead of hard-arming at attempt 1.
     * The old unconditional `attempt = 1` was the bad reset itself: the VM's ladder body
     * exits the moment a dial reports Connected and re-enters here on the next drop, so on
     * a connect-then-die link EVERY cycle re-armed at attempt 1 / `retryDelayMs=0` and the
     * ladder could never climb. It arms at 1 only when no episode is in flight.
     */
    private fun onReconnectLadderEntered(current: ConnectionState): ConnectionState {
        val host = current.hostOrNull() ?: return current
        val target = current.targetIdOrNull() ?: return current
        return when (current) {
            is ConnectionState.Idle,
            is ConnectionState.Gone,
            is ConnectionState.Unreachable,
            -> current
            else -> reconnectingAt(host, target, attempt = reconnectAttempt.coerceAtLeast(1))
        }
    }

    /**
     * Issue #1328 (S5): one reconnect rung's real dial failed retryably. Advance the
     * SINGLE churn-surviving counter and re-assert [ConnectionState.Reconnecting] at
     * the new attempt — REGARDLESS of the transient state the failed dial churned to
     * (Reattaching/Live/Attaching/Connecting) — or, once past the ladder budget,
     * decide exhaustion here → [ConnectionState.Unreachable]. This is the ONLY place
     * the VM effect advances the ladder; it never counts its own.
     */
    private fun onReconnectFailed(current: ConnectionState): ConnectionState {
        val host = current.hostOrNull() ?: return current
        val target = current.targetIdOrNull() ?: return current
        return when (current) {
            is ConnectionState.Idle,
            is ConnectionState.Gone,
            is ConnectionState.Unreachable,
            is ConnectionState.Backgrounded,
            is ConnectionState.NetworkLossSuspended,
            -> current
            else -> {
                val nextAttempt = reconnectAttempt.coerceAtLeast(1) + 1
                // Issue #1633: the episode is bounded by its wall clock as well as by the
                // rung budget, so a flap too slow to burn the rungs still gives up.
                if (episodeExhausted(nextAttempt)) {
                    endEpisode()
                    ConnectionState.Unreachable(host, target)
                } else {
                    reconnectingAt(host, target, attempt = nextAttempt)
                }
            }
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
            // Issue #1633: as with TransportLive, the seed landing opens the stability
            // window; the episode commits only once that window closes.
            is ConnectionState.Attaching,
            is ConnectionState.Reattaching,
            is ConnectionState.Reconnecting,
            -> liveAt(host, event.targetId)
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

        /**
         * Issue #1633: how long the connection must stay continuously
         * [ConnectionState.Live] before the recovery episode COMMITS and the attempt
         * counter resets. A drop before the window closes resumes at attempt+1.
         *
         * **PROPOSED, not locked** — the value comes from the #1331 target-state-machine
         * design (which took it from Kubernetes CrashLoopBackOff's stability window). It is
         * the loop-killer's single tuning knob: too LOW and a flapping link's brief Lives
         * look "stable" and the storm returns; too HIGH and a genuinely healthy connection
         * that drops within the window inherits a stale attempt. 30s comfortably exceeds
         * the maintainer's observed ~5s connect-then-die cadence (#1610) while staying well
         * under a normal session's uptime. Retune with evidence.
         */
        const val DEFAULT_STABILITY_WINDOW_MS: Long = 30_000L

        /**
         * Issue #1633: the wall-clock ceiling on ONE recovery episode. Past it the machine
         * gives up honestly ([ConnectionState.Unreachable]) even if rungs remain, so a flap
         * too slow to exhaust the rung budget cannot grind forever.
         *
         * **PROPOSED, not locked** — from the #1331 design. 120s roughly matches the
         * 8-rung `[0,1,2,5,10,20,30,30]s` ladder's own span, so whichever bound trips first
         * lands in the same neighbourhood.
         */
        const val DEFAULT_EPISODE_BUDGET_MS: Long = 120_000L

        /**
         * Issue #1633: full jitter applied to every non-zero ladder rung (the gRPC backoff
         * model), so retries neither resonate with a periodic mobile RAT/NAT cycle nor
         * synchronize across sessions. **PROPOSED, not locked** — ±20% is the gRPC default.
         */
        const val RETRY_JITTER_FRACTION: Double = 0.2
    }
}

/** The target id of a non-idle state, or null for [ConnectionState.Idle]. */
fun ConnectionState.targetIdOrNull(): SessionId? = when (this) {
    is ConnectionState.Idle -> null
    is ConnectionState.Connecting -> targetId
    is ConnectionState.Attaching -> targetId
    is ConnectionState.Live -> targetId
    is ConnectionState.Backgrounded -> targetId
    is ConnectionState.NetworkLossSuspended -> targetId
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
    is ConnectionState.NetworkLossSuspended -> host
    is ConnectionState.Reattaching -> host
    is ConnectionState.Reconnecting -> host
    is ConnectionState.Gone -> host
    is ConnectionState.Unreachable -> host
}

/**
 * Pure foundation reducer for the future S4 network-loss path.
 *
 * This is intentionally NOT called by [ConnectionController.submit] in this prep
 * slice. It defines the contract that VM/app wiring will later fold in:
 * live loss holds without redial; restore rides through when the transport is
 * proven alive, otherwise it enters the silent reconnect ladder.
 */
fun reduceNetworkLossRestore(
    current: ConnectionState,
    event: ConnectionEvent,
    liveness: LivenessPort,
    nowMs: Long,
): ConnectionState =
    when (event) {
        ConnectionEvent.NetworkLost ->
            if (current is ConnectionState.Live) {
                ConnectionState.NetworkLossSuspended(current.host, current.targetId, sinceMs = nowMs)
            } else {
                current
            }

        ConnectionEvent.NetworkRestored ->
            if (current is ConnectionState.NetworkLossSuspended) {
                if (liveness.transportProvenAliveRecently()) {
                    ConnectionState.Live(current.host, current.targetId)
                } else {
                    ConnectionState.Reconnecting(current.host, current.targetId, attempt = 1)
                }
            } else {
                current
            }

        else -> current
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
