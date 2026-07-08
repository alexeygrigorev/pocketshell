package com.pocketshell.core.connection

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * EPIC #686 Phase-0: the session-screen identity/reveal reducer.
 *
 * The session screen is a *pure function of one target session id*. This machine
 * is the projection layer that the view renders strictly from — it replaces the
 * accreted reveal/identity plumbing in `TmuxSessionViewModel`/`TmuxSessionScreen`
 * (the 9 ad-hoc `sameSessionIdentity` re-checks + the scattered #437 keep-frame,
 * #634 warm-open, #661 reveal-gate, #662 reseed-blank gates) with a single
 * id-keyed state machine.
 *
 * ## Inputs (the three projection sources)
 * The reducer is a pure projection of:
 *  1. the **nav target** — [navigate] supplies `(targetId, targetName)`, known at
 *     navigation time, so the header can show the target name *immediately* and
 *     never the previous screen's name.
 *  2. the **[ConnectionState]** — the lifecycle from [ConnectionController]
 *     ([onConnectionState]). One emitter (the controller), one reducer (this).
 *  3. id-tagged **[Seed]s** — captured pane content ([onSeed]); only the active
 *     pane being seeded *for the current target* promotes to [Live].
 *
 * ## The single drop rule
 * The reducer DROPS any input whose `targetId != currentTargetId`. A late
 * [ConnectionState] or [Seed] for a superseded switch is never projected. This
 * is the ONE place the drop-by-id rule lives for the view layer; it supersedes
 * every scattered identity re-check the old screen carried.
 *
 * ## Ownership boundary
 * This machine owns NO transport, NO jobs, NO leases — it is *only* the
 * projection of `(nav target + ConnectionState + id-tagged Seeds) -> RevealState`.
 * The transport/lease/grace lifecycle is [ConnectionController]'s job (#687); the
 * VM adapter that performs IO and feeds events into both is a later, #661-gated
 * slice. This file is deliberately NOT wired into `TmuxSessionScreen` yet.
 */
class RevealStateMachine {

    private val _state = MutableStateFlow<RevealState>(RevealState.Idle)

    /** The single source the view renders name + surface from. */
    val state: StateFlow<RevealState> = _state.asStateFlow()

    /** The current target id — the drop-by-id reference for all inputs. Null only
     *  before the first [navigate]. */
    private val currentTargetId: SessionId?
        get() = _state.value.targetIdOrNull()

    /** Accumulated id-tagged pane content for the CURRENT target. Cleared on every
     *  [navigate] so a leaving target's panes never bleed into the new one. */
    private val panes = mutableListOf<Seed>()

    /** Optional agent label for the current target's active pane — only allowed to
     *  override the leaf label in [RevealState.Live]. */
    private var agentName: String? = null

    /**
     * Issue #1098 (item 4 / #635 within-grace ride-through): while the VM is running
     * the SINGLE-GRACE-OWNER within-grace SILENT heal of a `-CC` socket that dropped
     * during a brief background, the transport is torn down and re-opened, so the
     * controller necessarily walks `Live -> Reattaching -> Live`. That is an INVISIBLE
     * ride-through — the user already has the prior frame on screen and the heal
     * re-seeds it — so the [ConnectionState.Reattaching]/[ConnectionState.Reconnecting]
     * projection must NOT drop the reveal to [RevealState.Seeding] (which the screen
     * renders as the full-surface "Attaching…" loading overlay, hiding the live frame —
     * the exact #635/item-4 spurious overlay). While this flag is set, a Reattaching/
     * Reconnecting projection KEEPS the current reveal (a held [RevealState.Live] frame
     * stays live) instead of resetting to Seeding. The VM sets it for the bounded
     * duration of the within-grace heal only; every OTHER control-drop heal (an
     * unexpected foreground drop) keeps the prior calm-loading behaviour
     * ([ConnectionState.Reattaching] -> [RevealState.Seeding]).
     */
    private var silentHealInFlight = false

    /**
     * Issue #1098 (item 4): mark/unmark a within-grace SILENT heal as in flight. While
     * in flight, a [ConnectionState.Reattaching]/[ConnectionState.Reconnecting]
     * projection holds the current reveal (no "Attaching…" overlay) — see
     * [silentHealInFlight].
     */
    fun setSilentHealInFlight(value: Boolean) {
        silentHealInFlight = value
    }

    /**
     * Navigation arrived at a (possibly new) target. ALWAYS supersedes whatever is
     * showing: the state is replaced *synchronously* with [RevealState.Navigating]
     * carrying the target name, so the view can never observe an
     * `(old panes, new id)` window — the header shows [targetName] immediately and
     * the surface is a loading placeholder, never the previous frame.
     *
     * Re-navigating to the SAME id (e.g. a recompose) is idempotent: it keeps the
     * current state rather than dropping back to Navigating and losing a [Live]
     * reveal.
     */
    fun navigate(targetId: SessionId, targetName: String) {
        if (targetId == currentTargetId) {
            // Same target — refresh the name if it changed, otherwise no-op so we
            // don't reset a live reveal.
            if (_state.value.targetNameOrNull() != targetName) {
                _state.value = _state.value.withTargetName(targetName)
            }
            return
        }
        panes.clear()
        agentName = null
        _state.value = RevealState.Navigating(targetId, targetName)
    }

    /**
     * Project a [ConnectionState] from the controller. Dropped if it is not for the
     * current target (a late lifecycle event from a superseded switch).
     *
     * Mapping (all keyed to the current target):
     *  - [ConnectionState.Connecting]/[ConnectionState.Attaching]/
     *    [ConnectionState.Reattaching]/[ConnectionState.Reconnecting] -> still
     *    loading: [RevealState.Seeding] (NEVER the previous frame).
     *  - [ConnectionState.Live] -> [RevealState.Live] ONLY if the active pane was
     *    already seeded for this id; otherwise stay [RevealState.Seeding] until the
     *    seed lands (never-reveal-black: a Live lifecycle with no content yet is
     *    not painted as an empty pane).
     *  - [ConnectionState.Gone] -> [RevealState.Gone] (explicit card, no resurrect).
     *  - [ConnectionState.Unreachable] -> [RevealState.Error(retrying = false)]
     *    (honest error only when exhausted).
     *  - [ConnectionState.Backgrounded] -> keep the current state (no reveal change
     *    while backgrounded; the next foreground re-drives this).
     *  - [ConnectionState.NetworkLossSuspended] -> keep the current state (calm
     *    network hold; the restored edge re-drives this).
     *  - [ConnectionState.Idle] -> ignored (a [navigate] drives the target, not Idle).
     */
    fun onConnectionState(connectionState: ConnectionState) {
        val target = currentTargetId ?: return
        val stateTarget = connectionState.targetIdOrNull()
        // Idle carries no target; everything else must match the current target or
        // it is a superseded/foreign lifecycle event and is dropped.
        if (connectionState !is ConnectionState.Idle && stateTarget != target) {
            return
        }
        val targetName = _state.value.targetNameOrNull() ?: return

        val next = when (connectionState) {
            is ConnectionState.Idle,
            is ConnectionState.Backgrounded,
            is ConnectionState.NetworkLossSuspended,
            -> _state.value // no reveal change

            is ConnectionState.Connecting,
            is ConnectionState.Attaching,
            -> RevealState.Seeding(target, targetName)

            is ConnectionState.Reattaching,
            is ConnectionState.Reconnecting,
            ->
                // Issue #1098 (item 4): a within-grace SILENT heal rides through the
                // transport re-open WITHOUT an "Attaching…" overlay — hold the current
                // (live) frame. Every other reattach/reconnect keeps the calm-loading
                // Seeding surface.
                if (silentHealInFlight) {
                    _state.value
                } else {
                    RevealState.Seeding(target, targetName)
                }

            is ConnectionState.Live ->
                if (panes.isNotEmpty()) {
                    RevealState.Live(target, targetName, panes.toList(), agentName)
                } else {
                    // never-reveal-black: lifecycle says Live but no content has
                    // landed for this id yet — keep loading until a seed lands.
                    RevealState.Seeding(target, targetName)
                }

            is ConnectionState.Gone ->
                RevealState.Gone(target, targetName)

            is ConnectionState.Unreachable ->
                // The controller-driven exhaust ladder carries no cause here (core
                // [ConnectionState.Unreachable] is opaque host/id), so the reason is
                // the generic non-retryable Unreachable. A cause-typed reason arrives
                // through [onTerminalError] instead (#1185 direct-drive path).
                RevealState.Error(
                    target,
                    targetName,
                    retrying = false,
                    reason = FailureReason.Unreachable(retryable = false),
                )
        }
        if (next != _state.value) {
            _state.value = next
        }
    }

    /**
     * An id-tagged active-pane [Seed] landed. Dropped if it is not for the current
     * target (the AC-3 invariant: a late seed for a non-target id is never painted).
     * An empty-frame seed does NOT reveal (never-reveal-black); content reveals only
     * a non-empty frame for the current target.
     *
     * When content exists, the machine reveals [RevealState.Live] for the current
     * target — this is the "active-pane seed confirmed FOR THIS id" gate, the only
     * point at which content is shown. From [RevealState.Gone]/[RevealState.Error]
     * a stray seed is ignored (those are terminal surfaces for this id).
     */
    fun onSeed(seed: Seed) {
        val target = currentTargetId ?: return
        if (seed.targetId != target) {
            return // drop foreign seed — never painted
        }
        // Gone / honest-error are terminal surfaces; a stray seed does not resurrect
        // content.
        if (_state.value is RevealState.Gone ||
            (_state.value is RevealState.Error && !(_state.value as RevealState.Error).retrying)
        ) {
            return
        }
        if (seed.frame.isEmpty()) {
            // never-reveal-black: an empty active-pane seed keeps us loading.
            return
        }
        // Accumulate / replace the pane's content (by paneId), preserving order.
        val existing = panes.indexOfFirst { it.paneId == seed.paneId }
        if (existing >= 0) {
            panes[existing] = seed
        } else {
            panes.add(seed)
        }
        val targetName = _state.value.targetNameOrNull() ?: return
        _state.value = RevealState.Live(target, targetName, panes.toList(), agentName)
    }

    /**
     * Issue #1185: drive the reveal to a terminal honest [RevealState.Error] for
     * [targetId] DIRECTLY from the VM's connect-failure path, without relying on
     * the [ConnectionController]'s synthetic drop→[ConnectionState.Unreachable]
     * ladder to deliver the matching-id edge through [onConnectionState]. Dropped
     * (no-op) for a non-current target so a superseded switch's failure never
     * overwrites the live target's surface.
     *
     * This closes the two-holder divergence the maintainer hit (create-new →
     * immediately-select an existing session on the same host): the status pill
     * read "Disconnected" while this machine stayed in [RevealState.Seeding]
     * ("Attaching…") because the indirect controller drive never delivered an
     * [ConnectionState.Unreachable] for the exact id. A terminal connect failure
     * now moves BOTH holders to an honest error in lockstep — never a red pill
     * over a live "Attaching…" spinner.
     */
    fun onTerminalError(
        targetId: SessionId,
        reason: FailureReason = FailureReason.Unreachable(retryable = false),
    ) {
        val target = currentTargetId ?: return
        if (targetId != target) {
            return
        }
        val targetName = _state.value.targetNameOrNull() ?: return
        val next = RevealState.Error(target, targetName, retrying = false, reason = reason)
        if (next != _state.value) {
            _state.value = next
        }
    }

    /**
     * Set the agent display name for the current target's active pane. Only honored
     * in [RevealState.Live] (the spec: agentName overrides the leaf label ONLY once
     * content is live for this id). Dropped for a non-target id.
     */
    fun onAgentName(targetId: SessionId, agentName: String?) {
        if (targetId != currentTargetId) {
            return
        }
        this.agentName = agentName
        val live = _state.value as? RevealState.Live ?: return
        _state.value = live.copy(agentName = agentName)
    }
}

/**
 * The view-layer reveal state. Every non-idle state carries the target [SessionId]
 * AND the [targetName] known at navigation time, so the header renders the correct
 * name in EVERY state, never the previous screen's. The view is a `when (state)`
 * with zero cross-reads.
 */
sealed interface RevealState {
    /** Before the first navigation. */
    data object Idle : RevealState

    /**
     * Nav arrived; nothing seeded yet. Header = [targetName], surface = loading.
     * Never the previous screen's name or frame.
     */
    data class Navigating(val targetId: SessionId, val targetName: String) : RevealState

    /**
     * Attach in flight; the active pane is not yet confirmed-seeded for this id.
     * Still loading — NEVER the previous frame.
     */
    data class Seeding(val targetId: SessionId, val targetName: String) : RevealState

    /**
     * The active pane was confirmed seeded FOR THIS id — content is revealed. Only
     * here is [agentName] allowed to override the leaf label, and only for this id's
     * pane. [panes] are the id-tagged captures for this target.
     */
    data class Live(
        val targetId: SessionId,
        val targetName: String,
        val panes: List<Seed>,
        val agentName: String? = null,
    ) : RevealState

    /**
     * Target deleted elsewhere (#666). Explicit card — no auto-pop, no resurrect,
     * never a stale frame.
     */
    data class Gone(val targetId: SessionId, val targetName: String) : RevealState

    /**
     * Control channel dropped. While [retrying] the view shows a calm
     * "reconnecting…" (healing per #685); an honest error surfaces only when retry
     * is exhausted ([retrying] == false).
     */
    data class Error(
        val targetId: SessionId,
        val targetName: String,
        val retrying: Boolean,
        /**
         * Issue #1326 (S3): the TYPED failure reason the view state carries — never
         * a raw exception string. Only meaningful for the settled honest error
         * ([retrying] == false); the calm heal window ([retrying] == true) ignores it.
         */
        val reason: FailureReason = FailureReason.Unreachable(retryable = false),
    ) : RevealState
}

/** The target id of a non-idle [RevealState], or null for [RevealState.Idle]. */
fun RevealState.targetIdOrNull(): SessionId? = when (this) {
    is RevealState.Idle -> null
    is RevealState.Navigating -> targetId
    is RevealState.Seeding -> targetId
    is RevealState.Live -> targetId
    is RevealState.Gone -> targetId
    is RevealState.Error -> targetId
}

/** The header name of a non-idle [RevealState], or null for [RevealState.Idle]. */
fun RevealState.targetNameOrNull(): String? = when (this) {
    is RevealState.Idle -> null
    is RevealState.Navigating -> targetName
    is RevealState.Seeding -> targetName
    is RevealState.Live -> targetName
    is RevealState.Gone -> targetName
    is RevealState.Error -> targetName
}

/** Return a copy of this state with the header name replaced (same id + surface). */
private fun RevealState.withTargetName(name: String): RevealState = when (this) {
    is RevealState.Idle -> this
    is RevealState.Navigating -> copy(targetName = name)
    is RevealState.Seeding -> copy(targetName = name)
    is RevealState.Live -> copy(targetName = name)
    is RevealState.Gone -> copy(targetName = name)
    is RevealState.Error -> copy(targetName = name)
}
