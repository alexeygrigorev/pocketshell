package com.pocketshell.app.tmux.connection

import com.pocketshell.core.connection.LivenessProbe

/**
 * Issue #1863 — the liveness-probe gate, extracted out of `TmuxSessionViewModel`
 * as a PURE decision (the #1047 ratchet direction: decisions leave the
 * connection-core god-object, they do not accumulate inside it).
 *
 * ## What the old gate was — and what its `!disconnected` term protected
 *
 * The probe gate arrived with the probe itself (EPIC #792 Slice D, the #822
 * silent-drop fix) as "no-false-positive guard 1": probe ONLY when the session is
 * genuinely FOREGROUNDED + `Live` on the CURRENT, non-disconnected control client.
 * Each term earns its place:
 *
 *  - `!backgrounded` — D21 (no background work) and the #635 single-grace-owner
 *    rule: a within-grace detach is owned by the App-level grace window, never by
 *    the probe.
 *  - `appActive` — the same, for the explicit-leave path.
 *  - `hasClient` — nothing to ping.
 *  - `controllerLive` — an in-flight attach / reconnect owns the channel; probing
 *    there would race the single reconnect ladder (D28: never a second writer).
 *  - `!controlChannelDisconnected` — the term #1863 is about. It covered TWO
 *    different states that happen to share one predicate:
 *      (a) the TRANSIENT window between `TmuxClient.disconnected` flipping true and
 *          the effect driver moving the controller off `Live` (the driver's own
 *          `disconnected` collection latency, called out at the probe's construction
 *          site). This occurs on EVERY genuine passive drop. Here a probe is
 *          guaranteed to fail, and the driver is about to declare the drop anyway.
 *      (b) the TERMINAL state where the driver will NEVER move the controller —
 *          because the close was self-inflicted and the passive-drop classifier
 *          deliberately ignores it (#1568/#1610), or any other path that leaves
 *          `controllerLive && controlChannelDisconnected` standing. The app believes
 *          it holds a live wire over a closed one, no re-dial ever happens, and the
 *          ONE mechanism that exists to notice a dead wire is switched off by the
 *          wire being dead. That is the "green dot, black pane, nothing happens"
 *          class (#822/#823) the maintainer's standing direction — detect drops and
 *          SHOW them — exists to end.
 *
 * The predicate cannot tell (a) from (b) — that is exactly why the bug was
 * unrecoverable — so the split is deliberately made on the observable state, and
 * BOTH now return [DeadChannel].
 *
 * **What this costs, stated honestly.** In case (a) the behaviour changes from
 * "never act" to "act on this tick, with the N-consecutive threshold, the #982/#984
 * keepalive deferral and the 180s wedge backstop all bypassed". That is a real
 * change, not a preserved protection. It is judged acceptable — not free — because
 * the window is ONE Main-dispatch hop against a 7s probe cadence, and every WIDE
 * version of it is closed by a different term: the connect path submits
 * `submitControllerOpen` / `submitControllerSwitch` BEFORE `closeCurrentConnection
 * AndJoin`, so the controller is off `Live` for the whole `detachCleanly` teardown;
 * leave clears `appActive`; background sets `bg`. And if the probe does declare
 * inside that hop, `shouldSuppressTransportDropsForSingleGraceOwner()` suppresses
 * the driver's duplicate, so the worst case is the heavier reconnect ladder instead
 * of the calm within-grace silent reattach — never two writers (D28). Narrowing the
 * arm to "self-inflicted closes only" was considered and rejected: the same
 * dead-wire signature has been observed from more entry paths than the one #1863
 * reported, so a narrow predicate would leave the bug reachable.
 *
 * The probe still never competes with a reconnect (that is `controllerLive`), never
 * runs backgrounded, and never fights the grace owner — but it can finally observe
 * the condition it exists to detect.
 */
enum class LivenessProbeGate {
    /**
     * Not probing. Backgrounded / not app-active / no client / the controller is
     * not `Live` (an attach, reconnect or grace window owns the channel).
     */
    Closed,

    /**
     * The controller says `Live` and the current control client is provably
     * CLOSED. Usually that is the terminal #1863 state, where nothing else is
     * going to say so; it is ALSO briefly true on a normal passive drop before
     * the driver collects `disconnected` (see the class KDoc — the two are not
     * distinguishable from here, by design). Either way the wire is dead: the
     * probe runs, fails immediately, and — because a closed channel is not the
     * AMBIGUOUS signal the N-consecutive / keepalive-deferral machinery exists to
     * disambiguate — declares the drop on that tick. The duplicate-declaration
     * risk in the transient case is bounded by
     * `shouldSuppressTransportDropsForSingleGraceOwner()`, not by this gate.
     */
    DeadChannel,

    /** The normal case: foregrounded, `Live`, on a control client believed alive. */
    Probe,
}

/**
 * The pure gate selector. Term order is deliberate: every "we must not probe at
 * all" reason is evaluated BEFORE the dead-channel split, so [DeadChannel] can
 * only be reached in the foregrounded + controller-`Live` state.
 */
fun selectLivenessProbeGate(
    backgrounded: Boolean,
    appActive: Boolean,
    hasClient: Boolean,
    controlChannelDisconnected: Boolean,
    controllerLive: Boolean,
): LivenessProbeGate = when {
    backgrounded || !appActive || !hasClient || !controllerLive -> LivenessProbeGate.Closed
    controlChannelDisconnected -> LivenessProbeGate.DeadChannel
    else -> LivenessProbeGate.Probe
}

/**
 * Issue #964 — the keepalive-coordination guard the [LivenessProbe] consults before
 * declaring a drop. Reports whether the always-on transport keepalive
 * ([com.pocketshell.core.ssh.TransportKeepAlive], #945) has seen inbound transport
 * activity within its ride-through window — i.e. the LINK is provably alive even
 * though the tmux control-channel probe is momentarily failing. When true the probe
 * DEFERS rather than force-redialing, so a slow-but-live link is ridden through by
 * the single keepalive budget instead of two competing ones.
 *
 * Seam order is load-bearing:
 *  - [pinnedVerdict] (the #964 test seam) ALWAYS wins — the slow-but-live phase
 *    deliberately pins it true WHILE the `-CC` dead-seam is armed.
 *  - [syntheticChannelDeathArmed] (the #866/#822 synthetic silent-drop seam) models a
 *    genuine half-open link death — the dominant real #822 where BOTH the tmux `-CC`
 *    channel and the SSH transport keepalive die together. Without it, arming only the
 *    `-CC` dead-seam on a healthy `agents:2222` fixture left the REAL keepalive
 *    "proven alive", so the #982/#984 deferral suppressed the drop forever and the
 *    connection-lost indicator never surfaced. Production-neutral (test-only seam).
 *  - Otherwise the live transport answers. No session → no keepalive signal → false,
 *    so the probe keeps its own authority whenever there is no transport to defer to.
 */
fun keepAliveProvenAliveRecently(
    pinnedVerdict: Boolean?,
    syntheticChannelDeathArmed: Boolean,
    transportKeepAliveAlive: () -> Boolean,
): Boolean {
    pinnedVerdict?.let { return it }
    if (syntheticChannelDeathArmed) return false
    return transportKeepAliveAlive()
}

/**
 * Build the [LivenessProbe.ProbeIo] adapter from the view model's seams. Lives
 * here (not inline in the god-object) so the gate decision and the adapter that
 * consumes it stay together and are directly unit-testable.
 */
fun livenessProbeIo(
    gate: () -> LivenessProbeGate,
    ping: suspend () -> Boolean,
    onDrop: (Int) -> Unit,
    keepAliveProvenAlive: () -> Boolean,
): LivenessProbe.ProbeIo = object : LivenessProbe.ProbeIo {
    override fun shouldProbe(): Boolean = gate() != LivenessProbeGate.Closed
    override suspend fun probe(): Boolean = ping()
    override fun onProbeFailed(consecutiveFailures: Int) = onDrop(consecutiveFailures)
    override fun transportProvenAliveRecently(): Boolean = keepAliveProvenAlive()
    override fun channelDefinitivelyClosed(): Boolean = gate() == LivenessProbeGate.DeadChannel
}
