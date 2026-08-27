package com.pocketshell.app.tmux

import com.pocketshell.app.tmux.TmuxSessionViewModel.ConnectionTarget
import com.pocketshell.core.connection.ConnectionState as CoreConnectionState
import com.pocketshell.core.connection.ConnectionProjection
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.RevealIdentityAdoption
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.RevealStateMachine
import com.pocketshell.core.connection.Seed
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.connection.classifyFailure
import com.pocketshell.core.connection.targetIdOrNull
import kotlinx.coroutines.flow.StateFlow

/**
 * Narrow adapter between [TmuxSessionViewModel.ConnectionTarget] and the id-keyed
 * reveal reducer in `:shared:core-connection`.
 *
 * The reducer still receives the same three sources as before: navigation
 * target, controller state, and id-tagged active-pane seeds. This class only
 * centralizes target-to-[SessionId]/[HostKey] mapping and the small reveal
 * commands that used to live directly in [TmuxSessionViewModel].
 */
internal class TmuxRevealController(
    private val hostKeyForTarget: (ConnectionTarget) -> HostKey,
) {
    private val revealStateMachine: RevealStateMachine = RevealStateMachine()

    val state: StateFlow<RevealState> = revealStateMachine.state

    /**
     * Issue #2338: the reducer's last identity handoff, so the rendered screen
     * can follow an adoption of ITS OWN route id (and only that one).
     */
    val identityAdoption: StateFlow<RevealIdentityAdoption?> = revealStateMachine.identityAdoption

    fun onConnectionState(state: CoreConnectionState) {
        revealStateMachine.onConnectionState(state)
    }

    fun navigateTo(target: ConnectionTarget) {
        revealStateMachine.navigate(sessionId(target), target.sessionName)
    }

    /**
     * Issue #2294: keep reveal keyed to the same session while pane listing
     * enriches a name-only target with tmux's exact generation.
     */
    fun adoptTargetIdentity(
        previous: ConnectionTarget,
        adopted: ConnectionTarget,
    ) {
        revealStateMachine.adoptTargetId(
            from = sessionId(previous),
            to = sessionId(adopted),
        )
    }

    fun offerSeed(target: ConnectionTarget, paneId: String, frame: String) {
        revealStateMachine.onSeed(
            Seed(targetId = sessionId(target), paneId = paneId, frame = frame),
        )
    }

    fun promoteLive(target: ConnectionTarget, activePaneId: String) {
        revealStateMachine.onSeed(
            Seed(
                targetId = sessionId(target),
                paneId = activePaneId,
                frame = REVEAL_LIVE_SENTINEL_FRAME,
            ),
        )
    }

    fun hostAndSessionId(
        activeTarget: ConnectionTarget?,
        connectingTarget: ConnectionTarget?,
    ): Pair<HostKey?, SessionId?> {
        val target = activeTarget ?: connectingTarget ?: return (null to null)
        return (hostKey(target) to sessionId(target))
    }

    fun hostKey(target: ConnectionTarget): HostKey = hostKeyForTarget(target)

    fun sessionId(target: ConnectionTarget): SessionId =
        tmuxTargetSessionId(
            hostId = target.hostId,
            sessionName = target.sessionName,
            tmuxSessionId = target.tmuxSessionId,
            sessionCreated = target.sessionCreated,
        )

    fun currentTargetId(): SessionId? = state.value.targetIdOrNull()

    fun setConnectionProjection(value: ConnectionProjection) {
        revealStateMachine.setConnectionProjection(value)
    }

    fun driveTerminalError(target: ConnectionTarget, cause: Throwable?) {
        revealStateMachine.onTerminalError(sessionId(target), classifyFailure(cause))
    }

    fun holdFor(target: ConnectionTarget) {
        revealStateMachine.onConnectionState(
            CoreConnectionState.Attaching(hostKey(target), sessionId(target)),
        )
    }
}
