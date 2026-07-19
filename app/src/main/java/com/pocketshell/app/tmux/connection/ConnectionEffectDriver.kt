package com.pocketshell.app.tmux.connection

import android.util.Log
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.DropCause
import com.pocketshell.core.connection.ConnectionEvent
import com.pocketshell.core.connection.ConnectionState
import com.pocketshell.core.connection.TmuxPort
import com.pocketshell.core.connection.TransportPort
import com.pocketshell.core.connection.TransportUpDown
import com.pocketshell.core.connection.hostOrNull
import com.pocketshell.core.connection.targetIdOrNull
import com.pocketshell.core.tmux.TmuxClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * EPIC #687 / #792 — the effect driver: the WIRING layer of the connection facade.
 *
 * Owned by [com.pocketshell.app.tmux.connection.ConnectionManager], the driver owns a
 * [CoroutineScope] and, through the [ConnectionPortAdapters] ([TmuxPort.disconnected] /
 * [TransportPort.transportEvents]) plus the [ConnectionController]'s
 * [ConnectionController.state] flow, COLLECTS every transition and transport signal and
 * dispatches the effect to the appropriate effect class (it submits the transport events
 * to the controller, fires the [backgroundedEffect] / [foregroundReattachEffect] on the
 * relevant state edges, and re-projects the displayed status). It holds NO business logic
 * of its own — the controller decides, the effect classes do the coupled IO; this is the
 * thin glue between them (the Slice E "slim driver" goal).
 *
 * The driver OWNS the clean background detach: when the controller's
 * [ConnectionController.state] transitions INTO [ConnectionState.Backgrounded], it fires
 * [backgroundedEffect] — the VM supplies the body that runs the EXISTING full teardown
 * ([com.pocketshell.app.tmux.TmuxSessionViewModel] `closeCurrentConnectionAndJoin` under
 * `NonCancellable`), routed through the single [GraceEffects] owner. The inline
 * `backgroundDetachJob` *trigger* is deleted (D22 hard-cut — no fallback); the driver is
 * the sole detach trigger.
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
 * prerequisite for the driver ACTING in slice C. B2 replaced exactly the two mirror
 * feeds (control-channel drop + lease `Up`) and nothing more.
 *
 * Slice 3 (#766 reconnect-ladder authority) takes the LAST deferred transport input off
 * the bench: the lease `Down` edge (`TransportPort.transportEvents` `Down(host)` for the
 * controller's current host) is now SUBMITTED as [ConnectionEvent.TransportDropped],
 * under the SAME single-grace-owner suppression the control-channel drop uses. This makes
 * the [ConnectionController] AUTHORITATIVE for the reconnect LADDER STATE — a real lease
 * close walks it Live → Reattaching → Reconnecting(n) → Unreachable. The inline
 * `scheduleAutoReconnect` / network-changed reconnect IO is UNCHANGED and still performs
 * the actual re-dial until slice 4 deletes it (D22 hard-cut, gated on the maintainer
 * checkpoint).
 *
 * After each driver-submitted transport event the driver fires the VM-supplied
 * [onControllerTransition] callback so the VM re-projects `_connectionStatus` from the
 * controller's (now real-flow-driven) state — exactly as the deleted mirror feeds did
 * via `projectStatusFromController()`.
 *
 * ## What is STILL observe-only (this slice)
 * Every OTHER effect remains inline. The driver calls **ZERO port IO** — the ports
 * expose only the observed signals ([TransportPort.transportEvents] / [TransportPort.isWarm]
 * and [TmuxPort.disconnected]); there is no control-IO surface on them. The
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
 *   `Up` edge for the current host is submitted as [ConnectionEvent.TransportLive], and
 *   (slice 3) its `Down` edge for the current host as [ConnectionEvent.TransportDropped]
 *   under the [suppressTransportDrops] single-grace-owner gate; no IO method is ever called.
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
 * @param foregroundReconnectEffect fired SYNCHRONOUSLY when the controller transitions
 *   [ConnectionState.Backgrounded] -> [ConnectionState.Reconnecting] — the BEYOND-grace
 *   foreground return (#766 slice 2a). The VM supplies the body that re-homes the inline
 *   `reduceConnection(Foreground)` arm dispatch: it replays the stashed `pendingReattach`
 *   (a fresh connect to the detached session) or resumes a `pausedAutoReconnect`, selected
 *   by the connection-core `ForegroundReturnEffects` (EPIC #687 Slice 0 / #1047 — the
 *   hard-cut replacement for the deleted inline `reduceForeground` selector). This is the post-grace
 *   counterpart of [foregroundReattachEffect]: a within-grace foreground keeps the warm
 *   `-CC` channel and reseeds; a beyond-grace foreground (lease evicted on the App-grace
 *   teardown) re-dials. Defaults to a no-op so the observe-only test harness keeps its
 *   inert contract. NOTE: a [ConnectionState.Reconnecting] reached from the reconnect
 *   LADDER (a transport drop, not a foreground return) is NOT fired by this — only the
 *   `Backgrounded -> Reconnecting` edge does.
 * @param onControllerTransition fired AFTER each driver-submitted transport event so
 *   the VM re-projects `_connectionStatus` from the controller's state (the controller
 *   is the single status source). Defaults to a no-op (tests read [observations]/
 *   [ConnectionController.state] directly).
 * @param controlChannelDrops optional typed current-client drop stream. Production passes
 *   [CurrentClientTmuxPort.disconnectedClients] so the VM can reject stale old-client closes
 *   before this driver submits [ConnectionEvent.TransportDropped]. Tests that only care about
 *   the controller feed can omit it and use [TmuxPort.disconnected].
 * @param shouldSubmitControlChannelDrop stale-client gate for [controlChannelDrops]. Called
 *   before any controller submit; return false to leave the controller untouched.
 * @param controlChannelDroppedEffect fired AFTER an accepted, unsuppressed current-control-channel
 *   drop has moved the controller. This is the typed effect edge for the VM's passive recovery IO;
 *   suppressed drops, rejected stale drops, and controller no-ops do not fire it.
 * @param onDropSuppressed records the originating cause for a drop suppressed by
 *   the single-grace-owner gate, so the later within-grace foreground heal can
 *   export the real cause instead of a generic foreground placeholder.
 * @param sink where every observed transition is recorded. Defaults to logcat.
 */
class ConnectionEffectDriver(
    private val controller: ConnectionController,
    private val tmuxPort: TmuxPort,
    private val transportPort: TransportPort,
    private val scope: CoroutineScope,
    private val backgroundedEffect: () -> Unit = {},
    private val foregroundReattachEffect: () -> Unit = {},
    // Issue #1545 (Fable race audit R1, extends #904): the driver's reattach-edge
    // suppression predicate. When it returns true a `pendingReattach` (the stashed
    // beyond-grace replay arm) is OUTSTANDING, so the single pending path owns
    // recovery and the driver MUST NOT ALSO fire [foregroundReattachEffect]. This
    // closes the two-recovery-writers race: a `Foreground` arriving while a post-grace
    // teardown close is still in flight walks `Backgrounded → Reattaching` (the
    // controller's `isWarm` snapshot is still true because the `NonCancellable` close
    // has not yet evicted the lease, and `now < deadline`), so this reattach edge would
    // reseed over the DYING client WHILE `replayPendingReattach` dials a fresh transport
    // in parallel — two writers racing on the one host. Gating on `pendingReattach ==
    // null` here mirrors the #904 guard on the sibling Reconnecting edge (where
    // `replayPendingReattach` consumes the stashed arm and the duplicate driver callback
    // is a no-op). Default `{ false }` keeps the always-reseed behavior for the
    // observe-only test harness and every within-grace reattach where nothing is
    // pending (the normal single-reseed path is unchanged).
    private val hasPendingReattach: () -> Boolean = { false },
    private val foregroundReconnectEffect: () -> Unit = {},
    private val onControllerTransition: () -> Unit = {},
    private val controlChannelDrops: Flow<TmuxClient>? = null,
    private val shouldSubmitControlChannelDrop: (TmuxClient) -> Boolean = { true },
    private val controlChannelDroppedEffect: (TmuxClient) -> Unit = {},
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
    // Issue #972: fired AFTER the current host's lease transport comes back `Up`
    // (a reconnect promoted the controller to Live). This is the trigger that
    // mirrors the recorded reconnect-cause trail to `~/.pocketshell/connection-log.jsonl`
    // on the host over the now-warm lease, so the maintainer can ATTRIBUTE the
    // just-completed drop in the in-app file viewer (no adb). The driver does the
    // wiring only — the host write itself (and its fail-soft contract) lives in
    // the VM-supplied effect ([ConnectionLogHostMirror]); a no-op default keeps the
    // observe-only test harness unchanged. Fired on EVERY accepted `Up` for the
    // current host (the reconnect edge); the VM's effect is fail-soft + cheap, and
    // a blank trail is a no-op, so an extra fire never perturbs the connection.
    private val onTransportReconnected: () -> Unit = {},
    private val onDropSuppressed: (SuppressedDropDiagnostic) -> Unit = {},
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
     *    Backgrounded -> Reattaching within-grace foreground edge — slice 1c-iv-c #754;
     *    fires [foregroundReconnectEffect] on the Backgrounded -> Reconnecting
     *    beyond-grace foreground edge — slice 2a #766),
     *  - [TmuxPort.disconnected] — the transport-drop oracle; its true-edge is
     *    submitted as [ConnectionEvent.TransportDropped] (slice B2),
     *  - [TransportPort.transportEvents] — the lease up/down edge stream; an `Up` for
     *    the current host is submitted as [ConnectionEvent.TransportLive] (slice B2), a
     *    `Down` for the current host as [ConnectionEvent.TransportDropped] under the
     *    single-grace-owner gate (slice 3 — controller-authoritative reconnect ladder).
     * Idempotent: a second [start] is a no-op.
     */
    fun start() {
        if (started) return
        started = true
        jobs += scope.launch { collectStateTransitions(controller.state) }
        val typedDrops = controlChannelDrops
        jobs += scope.launch {
            if (typedDrops != null) {
                collectControlChannelDrops(typedDrops)
            } else {
                collectTransportDrops(tmuxPort.disconnected)
            }
        }
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
                // Issue #1545 (R1, extends #904): SUPPRESS this reseed while a
                // `pendingReattach` is outstanding. A `Foreground` during a post-grace
                // teardown-in-flight still resolves to Reattaching (warm-lease snapshot
                // true + within the controller's 90 s deadline), but the App-grace
                // teardown already stashed a `pendingReattach` that `replayPendingReattach`
                // will dial fresh. Reseeding here too would open a SECOND recovery writer
                // over the dying `-CC` client (the two-writers race). Let the single
                // pending path own recovery — mirror of the #904 Reconnecting-edge guard.
                if (!hasPendingReattach()) {
                    foregroundReattachEffect()
                } else {
                    record(Observation.ReattachReseedSuppressedPendingReplay)
                }
            }
            // Slice 2a (#766): the driver OWNS the BEYOND-grace FOREGROUND return — the
            // re-home of the inline `reduceConnection(Foreground)` arm dispatch. The
            // beyond-grace foreground is the controller's Backgrounded -> Reconnecting
            // edge (the App-grace teardown evicted the warm lease, so the controller's
            // own grace predicate is not-warm -> Reconnecting). On that edge the driver
            // fires the VM-supplied effect that replays `pendingReattach` / resumes a
            // `pausedAutoReconnect` (selected by the connection-core ForegroundReturnEffects
            // — EPIC #687 Slice 0 / #1047 — the hard-cut replacement for the deleted inline
            // reduceForeground selector). Only the Backgrounded -> Reconnecting edge fires this; a
            // Reconnecting reached from the reconnect LADDER (a transport drop, not a
            // foreground return) is NOT a foreground arm.
            if (current is ConnectionState.Reconnecting && previous is ConnectionState.Backgrounded) {
                foregroundReconnectEffect()
            }
            previous = current
        }
    }

    private suspend fun collectControlChannelDrops(drops: Flow<TmuxClient>) {
        drops.collect { client ->
            record(Observation.Disconnected(true))
            if (suppressTransportDrops()) {
                onDropSuppressed(suppressedControlChannelDiagnostic(client))
                record(Observation.DropSuppressed)
            } else if (shouldSubmitControlChannelDrop(client)) {
                val wasTargetless = isTargetless()
                val changed = submitTransport(
                    // Issue #1666: carry the TYPED cause derived at the single close authority
                    // off THIS client's disconnect event. This path is NOT pre-filtered for a
                    // self-inflicted `-CC` close (the lease edge's `locallyInitiated` gate is
                    // the lease side), so typing it here + the reducer's refusal is what makes
                    // a self-inflicted control-channel close structurally inert.
                    ConnectionEvent.TransportDropped(
                        SelfInflictedClose.dropCauseForControlChannelClose(client.disconnectEvent.value),
                    ),
                )
                if (changed || wasTargetless) {
                    controlChannelDroppedEffect(client)
                }
            }
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
                    onDropSuppressed(
                        SuppressedDropDiagnostic(cause = "control_channel_disconnected"),
                    )
                    record(Observation.DropSuppressed)
                } else {
                    // Issue #1666: the untyped boolean oracle fallback (feeds that omit the
                    // typed drop stream) carries no per-close intent token, so it is a genuine
                    // remote drop by construction — production uses the typed [controlChannelDrops]
                    // path above, which derives the real cause.
                    submitTransport(
                        ConnectionEvent.TransportDropped(
                            DropCause.RemoteFailure("control_channel_disconnected"),
                        ),
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
            //
            // Slice 3 (#766 reconnect-ladder authority): the lease `Down` edge — which
            // B2 deliberately deferred ("its consumer the reconnect loop is a later
            // slice") — is now SUBMITTED to the controller as a
            // [ConnectionEvent.TransportDropped] for the CURRENT host. This is the
            // missing input that makes the ConnectionController AUTHORITATIVE for the
            // reconnect ladder: a real lease close walks the controller's state
            // Live → Reattaching → Reconnecting(n) → (budget exhausted) Unreachable via
            // [ConnectionController.onTransportDropped], so the displayed status follows
            // the controller's honest ladder, not only the inline `scheduleAutoReconnect`
            // state. The inline reconnect IO (`scheduleAutoReconnect`, network-changed
            // reconnect) is UNCHANGED and still performs the actual re-dial until slice 4
            // deletes it (D22 hard-cut, gated on the maintainer checkpoint) — this slice
            // makes the controller the ladder STATE owner without removing the inline IO.
            //
            // The same single-grace-owner suppression the control-channel drop uses
            // ([suppressTransportDrops]) applies to the lease `Down`: a drop while the
            // app is BACKGROUNDED is inside the App-level background-grace window (the
            // sole grace authority), so submitting it would collapse the controller to
            // Unreachable while backgrounded and paint a band on the next within-grace
            // foreground (the #635 regression). Deferring leaves recovery to the
            // within-grace foreground heal (the single owner). A `Down` for an UNRELATED
            // host is ignored by the host filter, matching the `Up` edge's per-target gate.
            when (edge) {
                is TransportUpDown.Up ->
                    if (edge.host == controller.state.value.hostOrNull()) {
                        submitTransport(ConnectionEvent.TransportLive)
                        // Issue #972: the transport is back warm for the current
                        // host — mirror the recorded reconnect-cause trail to the
                        // host log over THIS lease so the just-completed drop is
                        // attributable in the file viewer. Fail-soft + a blank
                        // trail no-ops, so this never perturbs the live connection.
                        onTransportReconnected()
                    }

                is TransportUpDown.Down ->
                    if (edge.host == controller.state.value.hostOrNull()) {
                        // Issue #1632: a SELF-INFLICTED lease teardown is an ECHO of an
                        // action already in flight — recovery's own
                        // `sshLeaseManager.disconnect()` (the first act of
                        // `silentlyReconnectTransportAfterPassiveDisconnect`), a
                        // force-refresh eviction, an idle reap, a lifecycle teardown. It is
                        // NOT news of a failure, so submitting it as `TransportDropped`
                        // re-arms the ladder against ourselves: `Reconnecting 1/4` repaints
                        // and recovery re-triggers, whose teardown echoes again — the #1610
                        // storm the maintainer cannot work through on mobile (215
                        // ExplicitDisconnect/down events vs 26 real ladder rungs ever).
                        //
                        // The intent is READ off the edge, never inferred here: the emitter
                        // ([leaseStateToTransportEdge]) stamped it from the reason the lease
                        // manager named at its own close site. Checked BEFORE the
                        // single-grace-owner gate because "we did this on purpose" is a
                        // stronger statement than "we are backgrounded" — it holds
                        // regardless of lifecycle.
                        //
                        // A GENUINE death (peer gone, keepalive-declared dead) is NOT
                        // locally initiated and falls straight through to the normal
                        // submit below, so real failures still drive recovery. That
                        // negative is load-bearing: over-filtering here would stop the app
                        // reconnecting at all, which is worse than the storm.
                        if (edge.locallyInitiated) {
                            ReconnectCauseTrail.record(
                                stage = "lease_transport",
                                outcome = "down_self_inflicted",
                                cause = edge.reason,
                            )
                            onDropSuppressed(
                                SuppressedDropDiagnostic(
                                    cause = edge.reason,
                                    fields = mapOf(
                                        "transportDropReason" to edge.reason,
                                        "transportDropSource" to "lease_transport",
                                        "selfInflicted" to "true",
                                    ),
                                ),
                            )
                            record(Observation.DropSuppressed)
                            return@collect
                        }
                        // Issue #969: stamp the NAMED drop cause into the
                        // exported reconnect trail so the maintainer can see why
                        // the terminal reconnected (e.g. `keepalive_dead`)
                        // on-device via the file viewer — recorded even when the
                        // drop is suppressed under the single-grace-owner gate,
                        // so a backgrounded keepalive death is still attributed.
                        ReconnectCauseTrail.record(
                            stage = "lease_transport",
                            outcome = if (suppressTransportDrops()) "down_suppressed" else "down",
                            cause = edge.reason,
                        )
                        if (suppressTransportDrops()) {
                            onDropSuppressed(
                                SuppressedDropDiagnostic(
                                    cause = edge.reason,
                                    fields = mapOf(
                                        "transportDropReason" to edge.reason,
                                        "transportDropSource" to "lease_transport",
                                    ),
                                ),
                            )
                            record(Observation.DropSuppressed)
                        } else {
                            // Issue #1666: this submit is reached ONLY past the
                            // `edge.locallyInitiated` self-inflicted gate above, so a lease
                            // `Down` here is a GENUINE remote drop by construction — carry it
                            // typed as [DropCause.RemoteFailure] so the reducer runs the honest
                            // ladder (the load-bearing NEGATIVE: real deaths must still recover).
                            submitTransport(
                                ConnectionEvent.TransportDropped(
                                    DropCause.RemoteFailure("lease_down:${edge.reason}"),
                                ),
                            )
                        }
                    }
            }
        }
    }

    /**
     * Submit a transport event to the controller and re-project the displayed status.
     * The driver is now the controller's transport input (B2 #742); after each submit
     * the VM-supplied [onControllerTransition] re-projects `_connectionStatus` from the
     * controller's state — exactly as the deleted mirror feeds did.
     */
    private fun submitTransport(event: ConnectionEvent): Boolean {
        val before = controller.state.value
        controller.submit(event)
        onControllerTransition()
        return controller.state.value != before
    }

    private fun isTargetless(): Boolean =
        controller.state.value is ConnectionState.Idle

    private fun suppressedControlChannelDiagnostic(client: TmuxClient): SuppressedDropDiagnostic {
        val disconnectEvent = client.disconnectEvent.value
        return SuppressedDropDiagnostic(
            cause = disconnectEvent?.reason?.logValue ?: "control_channel_disconnected",
            fields = buildMap {
                put("transportDropSource", "control_channel")
                if (disconnectEvent != null) {
                    put("disconnectReason", disconnectEvent.reason.logValue)
                    put("disconnectSource", disconnectEvent.source)
                    put("disconnectIntent", disconnectEvent.intent)
                    put("commandKind", disconnectEvent.commandKind)
                    put("timeoutMode", disconnectEvent.timeoutMode)
                    put("exceptionClass", disconnectEvent.exceptionClass)
                    put("message", disconnectEvent.message)
                }
            },
        )
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

        /**
         * Issue #1545 (R1, extends #904): the Backgrounded -> Reattaching foreground reseed
         * the driver SUPPRESSED because a `pendingReattach` was outstanding (a post-grace
         * teardown-in-flight foreground). Recovery is left to the single pending replay path
         * so no second recovery writer opens over the dying `-CC` client.
         */
        data object ReattachReseedSuppressedPendingReplay : Observation {
            override fun logLine(): String =
                "foreground reattach reseed SUPPRESSED (pending-replay owns recovery)"
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
                is ConnectionState.NetworkLossSuspended -> "NetworkLossSuspended"
                is ConnectionState.Reattaching -> "Reattaching"
                is ConnectionState.Reconnecting -> "Reconnecting(${state.attempt})"
                is ConnectionState.Gone -> "Gone"
                is ConnectionState.Unreachable -> "Unreachable"
            }
        }
    }

    companion object {
        /** Logcat tag for the connection effect driver (the facade's wiring layer). */
        const val TAG: String = "PsConnEffectDriver"
    }
}

data class SuppressedDropDiagnostic(
    val cause: String,
    val fields: Map<String, Any?> = emptyMap(),
)
