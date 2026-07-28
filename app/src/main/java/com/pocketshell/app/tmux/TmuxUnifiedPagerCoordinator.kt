package com.pocketshell.app.tmux

import com.pocketshell.core.connection.SessionId
import java.util.concurrent.atomic.AtomicLong

/**
 * One navigation lifetime of the unified pager.
 *
 * [targetSessionId] is the durable host/session identity from the connection
 * core. [instance] distinguishes A -> B -> A and unmount/remount lifetimes, so
 * an observation or correction queued by the first A can never write into the
 * second A merely because the human-readable session name is unchanged.
 */
internal data class UnifiedPagerTargetEpoch(
    val targetSessionId: SessionId,
    val instance: Long,
)

private val unifiedPagerEpochSequence = AtomicLong(0L)

internal fun newUnifiedPagerTargetEpoch(
    targetSessionId: SessionId,
): UnifiedPagerTargetEpoch = UnifiedPagerTargetEpoch(
    targetSessionId = targetSessionId,
    instance = unifiedPagerEpochSequence.incrementAndGet(),
)

/**
 * Immutable ownership carried by every page. Cached peers are scoped to the
 * current durable target epoch because tmux may reuse both `$N` and `%N` after
 * a host/session generation is replaced.
 */
internal data class UnifiedPagerPageOwner(
    val sessionName: String,
    val sessionId: SessionId,
)

/**
 * The key Compose measures. It is deliberately composite: pane-only keys let a
 * newly-created `%0` inherit layout/alignment state from an unrelated target.
 */
internal data class UnifiedPagerPageKey(
    val targetEpoch: UnifiedPagerTargetEpoch,
    val owner: UnifiedPagerPageOwner,
    val paneId: String,
) {
    /**
     * Foundation's lazy saveable-state holder requires a Bundle-saveable key.
     * Keep the typed composite identity for the reducer, but expose an
     * unambiguous length-prefixed String at the HorizontalPager boundary.
     */
    val saveableValue: String = buildString {
        append("ps-pager-v1|")
        appendKeyPart(targetEpoch.targetSessionId.value)
        appendKeyPart(targetEpoch.instance.toString())
        appendKeyPart(owner.sessionName)
        appendKeyPart(owner.sessionId.value)
        appendKeyPart(paneId)
    }
}

private fun StringBuilder.appendKeyPart(value: String) {
    append(value.length)
    append(':')
    append(value)
    append('|')
}

internal data class UnifiedPagerPage(
    val pane: TmuxPaneState,
    val owner: UnifiedPagerPageOwner,
    val key: UnifiedPagerPageKey,
) {
    /** The immutable identity this page hands the ViewModel when confirmed. */
    fun settled(): UnifiedPagerSettledPage = UnifiedPagerSettledPage(
        paneId = pane.paneId,
        sessionName = owner.sessionName,
    )
}

/**
 * Issue #1778: the ONLY thing a confirmed settle may hand the ViewModel.
 *
 * A numeric page index is meaningful exclusively inside the Compose snapshot
 * the coordinator measured. `TmuxSessionViewModel.unifiedPanes` republishes
 * independently (reconcile, window add/remove, cache park/evict, stale-heal),
 * so re-resolving that index there retargets the user's completed gesture: it
 * either swallows the switch (the drifted index lands back on an active-session
 * pane) or routes it to a DIFFERENT cached session. Carrying the measured
 * owner instead makes the settle a pure function of what was actually on
 * screen.
 */
internal data class UnifiedPagerSettledPage(
    val paneId: String,
    val sessionName: String,
)

internal fun unifiedPagerPages(
    panes: List<TmuxPaneState>,
    targetEpoch: UnifiedPagerTargetEpoch,
    targetSessionName: String,
    sessionNameForPane: (TmuxPaneState) -> String?,
): List<UnifiedPagerPage> = panes.map { pane ->
    val ownerSessionName = sessionNameForPane(pane) ?: targetSessionName
    val ownerSessionId = if (ownerSessionName == targetSessionName) {
        targetEpoch.targetSessionId
    } else {
        // A cached runtime does not expose its full TmuxRuntimeKey to Compose.
        // Scope its tmux session id + name to the durable current target. The
        // outer target epoch then separates A -> B -> A navigation lifetimes.
        SessionId(
            buildString {
                append(targetEpoch.targetSessionId.value)
                append("|peer:")
                append(ownerSessionName.length)
                append(':')
                append(ownerSessionName)
                append(':')
                append(pane.sessionId)
            },
        )
    }
    val owner = UnifiedPagerPageOwner(
        sessionName = ownerSessionName,
        sessionId = ownerSessionId,
    )
    UnifiedPagerPage(
        pane = pane,
        owner = owner,
        key = UnifiedPagerPageKey(
            targetEpoch = targetEpoch,
            owner = owner,
            paneId = pane.paneId,
        ),
    )
}

internal enum class UnifiedPagerProgrammaticKind {
    InitialWindow,
    TargetAlignment,
}

internal sealed interface UnifiedPagerScrollOwner {
    data object None : UnifiedPagerScrollOwner
    data object UserDrag : UnifiedPagerScrollOwner
    data class Programmatic(
        val key: UnifiedPagerPageKey,
        val kind: UnifiedPagerProgrammaticKind,
        val attempt: Int,
        val executionToken: Long,
    ) : UnifiedPagerScrollOwner
}

internal data class UnifiedPagerTarget(
    val epoch: UnifiedPagerTargetEpoch,
    val sessionName: String,
    val pages: List<UnifiedPagerPage>,
    val initialWindowIndex: Int?,
)

internal data class UnifiedPagerObservation(
    val targetEpoch: UnifiedPagerTargetEpoch,
    val currentPage: Int,
    val settledPage: Int,
    val measuredCurrentPageKey: Any?,
    val isScrollInProgress: Boolean,
)

internal sealed interface UnifiedPagerCoordinatorAction {
    val targetEpoch: UnifiedPagerTargetEpoch

    data class Scroll(
        override val targetEpoch: UnifiedPagerTargetEpoch,
        val pageKey: UnifiedPagerPageKey,
        val kind: UnifiedPagerProgrammaticKind,
        val attempt: Int,
        val executionToken: Long,
    ) : UnifiedPagerCoordinatorAction

    data class ConfirmedSettle(
        override val targetEpoch: UnifiedPagerTargetEpoch,
        val pageKey: UnifiedPagerPageKey,
        val userDrivenFromAlignedTarget: Boolean,
        val targetWasAlignedBeforeSettle: Boolean,
    ) : UnifiedPagerCoordinatorAction
}

internal data class UnifiedPagerCoordinatorSnapshot(
    val targetEpoch: UnifiedPagerTargetEpoch?,
    val desiredPageKey: UnifiedPagerPageKey?,
    val pendingInitialWindowKey: UnifiedPagerPageKey?,
    val scrollOwner: UnifiedPagerScrollOwner,
    val targetAligned: Boolean,
    val correctionCount: Int,
)

/**
 * Pure, single-owner reducer for the production Compose pager effect.
 *
 * Numeric indices are observations only. Alignment and settle emission require
 * an idle pager plus a measured composite key at the current + settled page.
 * This class is the only authority that may produce [Scroll] or
 * [ConfirmedSettle]; Compose and the screen merely execute those typed actions.
 */
internal class UnifiedPagerCoordinator(
    private val maxCorrectionCount: Int = MAX_CORRECTION_COUNT,
) {
    private var target: UnifiedPagerTarget? = null
    private var desiredPageKey: UnifiedPagerPageKey? = null
    private var pendingInitialWindowKey: UnifiedPagerPageKey? = null
    private var initialWindowIntentPending: Boolean = false
    private var scrollOwner: UnifiedPagerScrollOwner = UnifiedPagerScrollOwner.None
    private var targetAligned: Boolean = false
    private var targetWasAlignedAtDragStart: Boolean = false
    private var dragCompletion: DragCompletion? = null
    private var correctionCount: Int = 0
    private var lastObservation: UnifiedPagerObservation? = null
    private var lastConfirmedKey: UnifiedPagerPageKey? = null
    private var pendingConfirmedSettle: UnifiedPagerCoordinatorAction.ConfirmedSettle? = null
    private var userSwitchCommitted: Boolean = false
    private var programmaticMotionActive: Boolean = false
    private var lastExecutionToken: Long = 0L

    internal fun updateTarget(
        next: UnifiedPagerTarget,
        observation: UnifiedPagerObservation? = null,
    ): List<UnifiedPagerCoordinatorAction> {
        val previous = target
        if (previous?.epoch != next.epoch) {
            target = next
            desiredPageKey = null
            pendingInitialWindowKey = null
            initialWindowIntentPending = next.initialWindowIndex != null
            scrollOwner = UnifiedPagerScrollOwner.None
            targetAligned = false
            targetWasAlignedAtDragStart = false
            dragCompletion = null
            correctionCount = 0
            lastObservation = null
            lastConfirmedKey = null
            pendingConfirmedSettle = null
            userSwitchCommitted = false
            programmaticMotionActive = false
        } else {
            target = next
        }
        refreshDesiredKey(next)
        val currentObservation = observation
            ?.takeIf { it.targetEpoch == next.epoch }
            ?: lastObservation?.takeIf { it.targetEpoch == next.epoch }
            ?: return emptyList()
        lastObservation = currentObservation
        return reduceObservation(currentObservation)
    }

    internal fun observe(
        observation: UnifiedPagerObservation,
    ): List<UnifiedPagerCoordinatorAction> {
        if (observation.targetEpoch != target?.epoch) return emptyList()
        lastObservation = observation
        return reduceObservation(observation)
    }

    internal fun onDragStarted(): List<UnifiedPagerCoordinatorAction> {
        val currentTarget = target ?: return emptyList()
        targetWasAlignedAtDragStart = targetAligned
        targetAligned = false
        dragCompletion = null
        scrollOwner = UnifiedPagerScrollOwner.UserDrag
        lastConfirmedKey = null
        userSwitchCommitted = false
        programmaticMotionActive = false
        // The initial-window key remains pending. A real finger may interrupt
        // scrollToPage, but it may never consume/lose B1.
        refreshDesiredKey(currentTarget)
        return emptyList()
    }

    internal fun onDragStopped(): List<UnifiedPagerCoordinatorAction> =
        finishDrag(DragCompletion.Stop)

    internal fun onDragCancelled(): List<UnifiedPagerCoordinatorAction> =
        finishDrag(DragCompletion.Cancel)

    internal fun onProgrammaticScrollCompleted(
        action: UnifiedPagerCoordinatorAction.Scroll,
    ) {
        val owner = scrollOwner as? UnifiedPagerScrollOwner.Programmatic ?: return
        if (
            action.targetEpoch != target?.epoch ||
            owner.key != action.pageKey ||
            owner.kind != action.kind ||
            owner.attempt != action.attempt ||
            owner.executionToken != action.executionToken
        ) {
            return
        }
        programmaticMotionActive = false
    }

    internal fun onProgrammaticScrollCancelled(
        action: UnifiedPagerCoordinatorAction.Scroll,
    ) {
        val owner = scrollOwner as? UnifiedPagerScrollOwner.Programmatic ?: return
        if (
            action.targetEpoch != target?.epoch ||
            owner.key != action.pageKey ||
            owner.kind != action.kind ||
            owner.attempt != action.attempt ||
            owner.executionToken != action.executionToken
        ) {
            return
        }
        programmaticMotionActive = false
        scrollOwner = UnifiedPagerScrollOwner.None
        // Keep pendingInitialWindowKey/desiredPageKey intact. Cancellation is
        // ownership feedback, never consumption; the next idle measured frame
        // decides whether to confirm or retry.
    }

    /**
     * A measured settle is consumed only after the production callback accepts
     * the action it represents. In particular, #797 cached-page promotion can
     * be transiently ineligible while the reveal hold owns the surface. A
     * rejection must leave the key retryable when that eligibility changes.
     */
    internal fun onConfirmedSettleHandled(
        action: UnifiedPagerCoordinatorAction.ConfirmedSettle,
        accepted: Boolean,
    ) {
        if (
            action.targetEpoch != target?.epoch ||
            pendingConfirmedSettle != action
        ) {
            return
        }
        pendingConfirmedSettle = null
        if (accepted) {
            lastConfirmedKey = action.pageKey
        }
    }

    internal fun snapshot(): UnifiedPagerCoordinatorSnapshot =
        UnifiedPagerCoordinatorSnapshot(
            targetEpoch = target?.epoch,
            desiredPageKey = desiredPageKey,
            pendingInitialWindowKey = pendingInitialWindowKey,
            scrollOwner = scrollOwner,
            targetAligned = targetAligned,
            correctionCount = correctionCount,
        )

    private fun finishDrag(
        completion: DragCompletion,
    ): List<UnifiedPagerCoordinatorAction> {
        if (scrollOwner != UnifiedPagerScrollOwner.UserDrag) return emptyList()
        scrollOwner = UnifiedPagerScrollOwner.None
        dragCompletion = completion
        val observation = lastObservation ?: return emptyList()
        return reduceObservation(observation)
    }

    private fun refreshDesiredKey(currentTarget: UnifiedPagerTarget) {
        val previousDesired = desiredPageKey
        if (
            pendingConfirmedSettle?.pageKey?.let { pending ->
                currentTarget.pages.none { it.key == pending }
            } == true
        ) {
            pendingConfirmedSettle = null
        }
        val targetPages = currentTarget.pages.filter {
            it.owner.sessionName == currentTarget.sessionName
        }
        val initialWindowIndex = currentTarget.initialWindowIndex
        if (initialWindowIndex != null && initialWindowIntentPending) {
            pendingInitialWindowKey = targetPages
                .firstOrNull { it.pane.windowIndex == initialWindowIndex }
                ?.key
            desiredPageKey = pendingInitialWindowKey
            retireInvalidProgrammaticOwner(
                currentTarget = currentTarget,
                previousDesired = previousDesired,
            )
            return
        }
        val selectedStillExists = desiredPageKey?.let { selected ->
            targetPages.any { it.key == selected }
        } == true
        if (!selectedStillExists) {
            desiredPageKey = targetPages.firstOrNull()?.key
            correctionCount = 0
        }
        retireInvalidProgrammaticOwner(
            currentTarget = currentTarget,
            previousDesired = previousDesired,
        )
    }

    private fun retireInvalidProgrammaticOwner(
        currentTarget: UnifiedPagerTarget,
        previousDesired: UnifiedPagerPageKey?,
    ) {
        val desiredChanged = previousDesired != desiredPageKey
        if (desiredChanged) correctionCount = 0
        val owner = scrollOwner as? UnifiedPagerScrollOwner.Programmatic ?: return
        val ownerStillExists = currentTarget.pages.any { it.key == owner.key }
        val ownerStillDesired = owner.key == desiredPageKey
        if (!desiredChanged && ownerStillExists && ownerStillDesired) return

        scrollOwner = UnifiedPagerScrollOwner.None
        programmaticMotionActive = false
        // A changed identity gets a fresh bounded correction budget. Execution
        // feedback cannot alias that reset because it is matched by the
        // monotonic token stored in the retired owner, never by this count.
    }

    private fun reduceObservation(
        observation: UnifiedPagerObservation,
    ): List<UnifiedPagerCoordinatorAction> {
        val currentTarget = target ?: return emptyList()
        if (observation.targetEpoch != currentTarget.epoch) return emptyList()

        val observedKey = observation.measuredCurrentPageKey
        val measuredPage = when (observedKey) {
            is UnifiedPagerPageKey ->
                currentTarget.pages.firstOrNull { it.key == observedKey }
            is String ->
                currentTarget.pages.firstOrNull { it.key.saveableValue == observedKey }
            else -> null
        }
        val measuredKey = measuredPage?.key
        val measuredIndex = measuredPage?.let { currentTarget.pages.indexOf(it) } ?: -1
        val measuredAtCurrentAndSettled = measuredPage != null &&
            measuredIndex == observation.currentPage &&
            observation.currentPage == observation.settledPage
        val confirmedIdleSettle = !observation.isScrollInProgress &&
            measuredAtCurrentAndSettled &&
            measuredKey?.targetEpoch == currentTarget.epoch

        if (scrollOwner == UnifiedPagerScrollOwner.UserDrag) return emptyList()
        if (observation.isScrollInProgress) return emptyList()
        if (userSwitchCommitted) return emptyList()

        val pendingInitial = pendingInitialWindowKey
        if (initialWindowIntentPending) {
            if (pendingInitial == null) return emptyList()
            if (confirmedIdleSettle && measuredKey == pendingInitial) {
                val confirmedKey = measuredKey ?: return emptyList()
                pendingInitialWindowKey = null
                initialWindowIntentPending = false
                desiredPageKey = confirmedKey
                scrollOwner = UnifiedPagerScrollOwner.None
                programmaticMotionActive = false
                targetAligned = true
                correctionCount = 0
                dragCompletion = null
                return confirmedSettle(confirmedKey, userDriven = false)
            }
            if (scrollOwner is UnifiedPagerScrollOwner.Programmatic) {
                if (programmaticMotionActive) return emptyList()
                // The previous programmatic movement is now idle but did not
                // measure B1. Release that turn before the bounded retry.
                scrollOwner = UnifiedPagerScrollOwner.None
                programmaticMotionActive = false
            }
            return requestDesiredPage(UnifiedPagerProgrammaticKind.InitialWindow)
        }

        if (confirmedIdleSettle) {
            val confirmedKey = measuredKey ?: return emptyList()
            val pageBelongsToTarget =
                measuredPage.owner.sessionName == currentTarget.sessionName
            val completion = dragCompletion
            if (pageBelongsToTarget) {
                desiredPageKey = confirmedKey
                scrollOwner = UnifiedPagerScrollOwner.None
                programmaticMotionActive = false
                targetAligned = true
                correctionCount = 0
                dragCompletion = null
                return confirmedSettle(confirmedKey, userDriven = false)
            }
            if (completion == DragCompletion.Stop && targetWasAlignedAtDragStart) {
                scrollOwner = UnifiedPagerScrollOwner.None
                dragCompletion = null
                correctionCount = 0
                userSwitchCommitted = true
                return confirmedSettle(confirmedKey, userDriven = true)
            }
            if (completion == null && targetAligned) {
                // Issue #797: a reconcile/key-retention reorder can leave the
                // pager genuinely parked, without a fresh drag, on an idle
                // measured CACHED page. This confirmed settle must reach the
                // promotion predicate so input + detection can rebind to the
                // visible runtime. It is not a user switch: the callback keeps
                // settleSessionSwitchTarget drag-gated and independently
                // applies tmuxSessionShouldPromoteSettledCachedPane.
                return confirmedSettle(confirmedKey, userDriven = false)
            }
            // A cancelled gesture or a foreign numeric/layout echo never owns
            // the pager. Re-arm target alignment once the pager is idle.
            dragCompletion = null
        }

        // No measured key means the pager is not mounted/measured yet. Numeric
        // current/settled values alone neither prove alignment nor justify
        // burning the bounded correction budget during a terminal hold.
        if (observedKey == null) return emptyList()

        if (scrollOwner is UnifiedPagerScrollOwner.Programmatic) {
            if (programmaticMotionActive) return emptyList()
            // A programmatic request exclusively owns its movement. Once the
            // pager is idle, release that turn before a bounded retry; no
            // second writer can overlap the active scroll.
            val owner = scrollOwner as UnifiedPagerScrollOwner.Programmatic
            if (owner.key == desiredPageKey) {
                scrollOwner = UnifiedPagerScrollOwner.None
            } else {
                return emptyList()
            }
        }
        return requestDesiredPage(UnifiedPagerProgrammaticKind.TargetAlignment)
    }

    private fun requestDesiredPage(
        kind: UnifiedPagerProgrammaticKind,
    ): List<UnifiedPagerCoordinatorAction> {
        val currentTarget = target ?: return emptyList()
        val desired = desiredPageKey ?: return emptyList()
        // The scroll owner is checked ONCE, by [reduceObservation], which is the
        // only caller. Duplicating that check here would be an unreachable
        // second decision site — exactly the split-writer shape #1778 removes —
        // and a mutation of it would silently survive every test.
        if (correctionCount >= maxCorrectionCount) return emptyList()
        if (currentTarget.pages.none { it.key == desired }) return emptyList()
        correctionCount += 1
        check(lastExecutionToken < Long.MAX_VALUE) {
            "Unified pager execution token exhausted"
        }
        lastExecutionToken += 1L
        val executionToken = lastExecutionToken
        scrollOwner = UnifiedPagerScrollOwner.Programmatic(
            key = desired,
            kind = kind,
            attempt = correctionCount,
            executionToken = executionToken,
        )
        programmaticMotionActive = true
        return listOf(
            UnifiedPagerCoordinatorAction.Scroll(
                targetEpoch = currentTarget.epoch,
                pageKey = desired,
                kind = kind,
                attempt = correctionCount,
                executionToken = executionToken,
            ),
        )
    }

    private fun confirmedSettle(
        pageKey: UnifiedPagerPageKey,
        userDriven: Boolean,
    ): List<UnifiedPagerCoordinatorAction> {
        if (lastConfirmedKey == pageKey) return emptyList()
        if (pendingConfirmedSettle?.pageKey == pageKey) return emptyList()
        val currentTarget = target ?: return emptyList()
        val action = UnifiedPagerCoordinatorAction.ConfirmedSettle(
            targetEpoch = currentTarget.epoch,
            pageKey = pageKey,
            userDrivenFromAlignedTarget = userDriven,
            targetWasAlignedBeforeSettle =
                targetAligned || targetWasAlignedAtDragStart,
        )
        pendingConfirmedSettle = action
        return listOf(action)
    }

    private enum class DragCompletion {
        Stop,
        Cancel,
    }

    internal companion object {
        const val MAX_CORRECTION_COUNT: Int = 3
    }
}
