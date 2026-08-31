package com.pocketshell.app.tmux

import com.pocketshell.core.connection.SessionId

import com.pocketshell.core.connection.RevealIdentityAdoption
import kotlinx.coroutines.flow.StateFlow

/**
 * Issue #2338: the reveal reducer's exact-generation identity handoff, published
 * so the screen keyed to the SUPERSEDED route id can follow it instead of
 * holding the terminal behind the #686 stale-id fence forever.
 *
 * Lives here (with the adoption that produces it) rather than on the VM: the
 * god object is byte-ratcheted downward and this is identity-promotion state,
 * not another VM responsibility.
 */
public val TmuxSessionViewModel.revealIdentityAdoption: StateFlow<RevealIdentityAdoption?>
    get() = revealController.identityAdoption

internal data class ParsedPaneApplyResult(
    val newPanes: List<TmuxPaneState>,
    val refreshGuard: RuntimeRefreshGuard?,
)

/**
 * Test-only passthrough to the production pane reconciliation path. The
 * stale-result cases in issue #2294 must cross the real list-panes IO and
 * apply boundary; calling [TmuxSessionViewModel.applyParsedPanesForTest]
 * directly would not prove that the originating runtime was captured before
 * that IO.
 */
@androidx.annotation.VisibleForTesting
internal suspend fun TmuxSessionViewModel.reconcilePanesForTest(): PaneReconcileResult =
    reconcilePanes()

/**
 * Issue #2294: promote exact tmux identity from the live pane listing to
 * every retained target that can feed cold-restore persistence or a kill
 * signal. A single pane is sufficient, but conflicting generations are
 * deliberately ignored rather than choosing an unsafe winner.
 *
 * The caller supplies the runtime predicate so this helper uses the same
 * stale-result fence as the surrounding reconcile path without widening the
 * VM's private connection-state API.
 */
internal fun TmuxSessionViewModel.adoptExactSessionGenerationFromPanes(
    target: TmuxSessionViewModel.ConnectionTarget?,
    panes: List<TmuxSessionViewModel.ParsedPane>,
    originatingRuntime: RuntimeRefreshGuard?,
    runtimeIsCurrent: (RuntimeRefreshGuard) -> Boolean,
    revealIdentityAdopter: ((TmuxSessionViewModel.ConnectionTarget, TmuxSessionViewModel.ConnectionTarget) -> Unit)? = null,
    connectionIdentityAdopter: ((SessionId, SessionId) -> Unit)? = null,
): RuntimeRefreshGuard? {
    val observedTarget = target ?: activeTarget ?: connectingTarget ?: return originatingRuntime
    // Adoption is destructive metadata promotion: it can become the
    // identity used by cold restore and kill matching. Never infer its
    // source from a session name. The caller must supply the runtime that
    // was captured before list-panes IO, and that runtime must still be
    // current at this apply point.
    if (originatingRuntime == null || !runtimeIsCurrent(originatingRuntime)) {
        return originatingRuntime
    }

    // A complete row cannot stand in for a missing row. Unless a producer
    // explicitly proves every row came from the same exact source, any
    // incomplete generation makes the whole observation untrustworthy.
    if (panes.any { tmuxSessionGenerationOrNull(it.sessionId, it.sessionCreated) == null }) {
        return originatingRuntime
    }
    val generations = panes.asSequence()
        .map { pane ->
            // The all-complete check above makes this non-null assertion
            // selective and keeps the fail-closed contract visible.
            requireNotNull(tmuxSessionGenerationOrNull(pane.sessionId, pane.sessionCreated))
        }
        .distinct()
        .toList()
    val generation = generations.singleOrNull() ?: return originatingRuntime

    fun isCurrentSession(candidate: TmuxSessionViewModel.ConnectionTarget): Boolean =
        candidate.sessionName == observedTarget.sessionName && isSameHost(candidate, observedTarget)

    activeTarget = activeTarget
        ?.takeIf(::isCurrentSession)
        ?.copy(
            tmuxSessionId = generation.sessionId,
            sessionCreated = generation.createdEpochSeconds,
        )
    connectingTarget = connectingTarget
        ?.takeIf(::isCurrentSession)
        ?.copy(
            tmuxSessionId = generation.sessionId,
            sessionCreated = generation.createdEpochSeconds,
        )
    latestConnectIntent = latestConnectIntent?.let { intent ->
        if (!isCurrentSession(intent.target)) {
            intent
        } else {
            intent.copy(
                target = intent.target.copy(
                    tmuxSessionId = generation.sessionId,
                    sessionCreated = generation.createdEpochSeconds,
                ),
            )
        }
    }

    // The exact-generation promotion enriches the target carried by the
    // captured runtime guard. Keep its client/connect-generation fence and
    // pass the enriched target through pane detection and preload.
    // A cold attach can reach this point while the target is still held only in
    // [connectingTarget]. Rekey that target too; otherwise the pane seed below
    // is tagged with the exact id while the reveal reducer is still waiting on
    // the name-only id and drops it as foreign. Keep the host/session fence on
    // both retained candidates so a stale same-name target cannot be adopted.
    val adoptedTarget = (activeTarget ?: connectingTarget)
        ?.takeIf(::isCurrentSession)
    return adoptedTarget?.let { adopted ->
        revealIdentityAdopter?.invoke(observedTarget, adopted)
        connectionIdentityAdopter?.invoke(
            tmuxTargetSessionId(
                observedTarget.hostId,
                observedTarget.sessionName,
                observedTarget.tmuxSessionId,
                observedTarget.sessionCreated,
            ),
            tmuxTargetSessionId(
                adopted.hostId,
                adopted.sessionName,
                adopted.tmuxSessionId,
                adopted.sessionCreated,
            ),
        )
        originatingRuntime.copy(target = adopted)
    } ?: originatingRuntime
}
