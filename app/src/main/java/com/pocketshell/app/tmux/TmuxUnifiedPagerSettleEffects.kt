package com.pocketshell.app.tmux

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

private sealed interface UnifiedPagerEffectEvent {
    data class Frame(
        val target: UnifiedPagerTarget,
        val observation: UnifiedPagerObservation,
        // Included so snapshotFlow replays the same measured pager frame when
        // production settle eligibility changes (for example, reveal hold
        // true -> false after a rejected #797 cached-page promotion).
        val settleRetryToken: Any?,
    ) : UnifiedPagerEffectEvent

    data object DragStarted : UnifiedPagerEffectEvent
    data object DragStopped : UnifiedPagerEffectEvent
    data object DragCancelled : UnifiedPagerEffectEvent

    data class ProgrammaticCompleted(
        val action: UnifiedPagerCoordinatorAction.Scroll,
    ) : UnifiedPagerEffectEvent

    data class ProgrammaticCancelled(
        val action: UnifiedPagerCoordinatorAction.Scroll,
    ) : UnifiedPagerEffectEvent
}

internal typealias UnifiedPagerScrollExecutor = suspend (
    pagerState: PagerState,
    pageIndex: Int,
    action: UnifiedPagerCoordinatorAction.Scroll,
) -> Unit

private val productionUnifiedPagerScrollExecutor: UnifiedPagerScrollExecutor =
    { pagerState, pageIndex, action ->
        when (action.kind) {
            UnifiedPagerProgrammaticKind.InitialWindow ->
                pagerState.scrollToPage(pageIndex)
            UnifiedPagerProgrammaticKind.TargetAlignment ->
                pagerState.requestScrollToPage(pageIndex)
        }
    }

/**
 * Issue #1778: the ONE unified-terminal-pager effect owner.
 *
 * The old implementation had independent numeric-settle, measured-key
 * correction, drag-latch, promotion and reseed collectors, while
 * [TmuxSessionScreen] separately consumed initial-window navigation. Those
 * writers could cancel one another. This effect serialises pager frames and
 * Drag Start/Stop/Cancel through [UnifiedPagerCoordinator]; only its typed
 * actions may call PagerState scrolling or emit a confirmed settle.
 */
@Composable
internal fun TmuxUnifiedPagerSettleEffects(
    pagerState: PagerState,
    targetEpoch: UnifiedPagerTargetEpoch,
    pages: List<UnifiedPagerPage>,
    sessionName: String,
    initialWindowIndex: Int?,
    terminalHeld: Boolean,
    viewModel: TmuxSessionViewModel,
) {
    UnifiedPagerCoordinatorEffects(
        pagerState = pagerState,
        targetEpoch = targetEpoch,
        pages = pages,
        sessionName = sessionName,
        initialWindowIndex = initialWindowIndex,
        settleRetryToken = UnifiedPagerSettleRetryToken(
            terminalHeld = terminalHeld,
            activePageKeys = pages
                .filter { viewModel.isActiveSessionPane(it.pane) }
                .mapTo(linkedSetOf()) { it.key },
        ),
    ) { action, settledPage ->
        // Issue #1778: the ONLY thing that crosses into the ViewModel is the
        // immutable identity the coordinator measured and confirmed. A numeric
        // page index would be re-resolved against `viewModel.unifiedPanes`,
        // which republishes independently of this measured Compose snapshot.
        val settled = settledPage.settled()
        val settledSession = settled.sessionName
        val settledPaneIsActive = viewModel.isActiveSessionPane(settledPage.pane)
        val switchTo = settleSessionSwitchTarget(
            settledPaneSession = settledSession,
            navTargetSession = sessionName,
            pagerAlignedToNavTarget = action.targetWasAlignedBeforeSettle,
            userDraggedSinceAlignment = action.userDrivenFromAlignedTarget,
        )
        val shouldPromoteStalledCachedPane =
            tmuxSessionShouldPromoteSettledCachedPane(
                settledPaneSession = settledSession,
                navTargetSession = sessionName,
                settledPaneIsActiveSession = settledPaneIsActive,
                pagerAlignedToNavTarget = action.targetWasAlignedBeforeSettle,
                switchHidesTerminal = terminalHeld,
            )
        if (switchTo != null || shouldPromoteStalledCachedPane) {
            viewModel.onUnifiedPageSettled(settled)
        }
        // Reseed only after the measured composite key is confirmed, and from
        // the SAME immutable identity. A numeric settledPage echo can no
        // longer target the wrong pane.
        viewModel.reseedVisiblePaneIfBlank(settled.paneId)
        settledSession == sessionName ||
            switchTo != null ||
            shouldPromoteStalledCachedPane
    }
}

private data class UnifiedPagerSettleRetryToken(
    val terminalHeld: Boolean,
    val activePageKeys: Set<UnifiedPagerPageKey>,
)

/**
 * The production Compose/PagerState adapter kept independent of the ViewModel
 * so the real HorizontalPager interaction contract can be composed directly
 * by the #1778 instrumentation regression.
 */
@Composable
internal fun UnifiedPagerCoordinatorEffects(
    pagerState: PagerState,
    targetEpoch: UnifiedPagerTargetEpoch,
    pages: List<UnifiedPagerPage>,
    sessionName: String,
    initialWindowIndex: Int?,
    settleRetryToken: Any? = Unit,
    scrollExecutor: UnifiedPagerScrollExecutor = productionUnifiedPagerScrollExecutor,
    // Issue #1778: the confirmed page is delivered as the immutable
    // [UnifiedPagerPage] the coordinator measured. No numeric index leaves
    // this effect — an index only ever means something inside ONE snapshot.
    onConfirmedSettle: (
        UnifiedPagerCoordinatorAction.ConfirmedSettle,
        UnifiedPagerPage,
    ) -> Boolean,
) {
    val coordinator = remember(pagerState) { UnifiedPagerCoordinator() }
    val currentTarget = rememberUpdatedState(
        UnifiedPagerTarget(
            epoch = targetEpoch,
            sessionName = sessionName,
            pages = pages,
            initialWindowIndex = initialWindowIndex,
        ),
    )
    val currentSettleRetryToken = rememberUpdatedState(settleRetryToken)
    val currentOnConfirmedSettle = rememberUpdatedState(onConfirmedSettle)
    val currentScrollExecutor = rememberUpdatedState(scrollExecutor)

    LaunchedEffect(pagerState, coordinator) {
        val frameEvents: Flow<UnifiedPagerEffectEvent> = snapshotFlow {
            val target = currentTarget.value
            val currentPage = pagerState.currentPage
            UnifiedPagerEffectEvent.Frame(
                target = target,
                observation = UnifiedPagerObservation(
                    targetEpoch = target.epoch,
                    currentPage = currentPage,
                    settledPage = pagerState.settledPage,
                    measuredCurrentPageKey = pagerState.layoutInfo.visiblePagesInfo
                        .firstOrNull { it.index == currentPage }
                        ?.key,
                    isScrollInProgress = pagerState.isScrollInProgress,
                ),
                settleRetryToken = currentSettleRetryToken.value,
            )
        }
        val dragEvents: Flow<UnifiedPagerEffectEvent> =
            pagerState.interactionSource.interactions.mapNotNull { interaction ->
                when (interaction) {
                    is DragInteraction.Start -> UnifiedPagerEffectEvent.DragStarted
                    is DragInteraction.Stop -> UnifiedPagerEffectEvent.DragStopped
                    is DragInteraction.Cancel -> UnifiedPagerEffectEvent.DragCancelled
                    else -> null
                }
            }

        val events = Channel<UnifiedPagerEffectEvent>(Channel.BUFFERED)
        coroutineScope {
            launch {
                frameEvents.collect { events.send(it) }
            }
            launch {
                dragEvents.collect { events.send(it) }
            }
            var programmaticScrollJob: Job? = null
            var activeScrollAction: UnifiedPagerCoordinatorAction.Scroll? = null
            var activeScrollPageKeys: List<UnifiedPagerPageKey>? = null
            var activeScrollSessionName: String? = null

            fun cancelProgrammaticScroll() {
                programmaticScrollJob?.cancel()
                programmaticScrollJob = null
                activeScrollAction = null
                activeScrollPageKeys = null
                activeScrollSessionName = null
            }

            for (event in events) {
                val actions = when (event) {
                    is UnifiedPagerEffectEvent.Frame -> {
                        val active = activeScrollAction
                        if (
                            active != null &&
                            (
                                active.targetEpoch != event.target.epoch ||
                                    event.target.pages.map { it.key } != activeScrollPageKeys ||
                                    event.target.sessionName != activeScrollSessionName
                                )
                        ) {
                            cancelProgrammaticScroll()
                        }
                        coordinator.updateTarget(event.target, event.observation)
                    }
                    UnifiedPagerEffectEvent.DragStarted -> {
                        cancelProgrammaticScroll()
                        coordinator.onDragStarted()
                    }
                    UnifiedPagerEffectEvent.DragStopped -> coordinator.onDragStopped()
                    UnifiedPagerEffectEvent.DragCancelled -> coordinator.onDragCancelled()
                    is UnifiedPagerEffectEvent.ProgrammaticCompleted -> {
                        if (activeScrollAction == event.action) {
                            programmaticScrollJob = null
                            activeScrollAction = null
                            activeScrollPageKeys = null
                            activeScrollSessionName = null
                        }
                        coordinator.onProgrammaticScrollCompleted(event.action)
                        // PagerState/layout invalidation publishes the measured
                        // follow-up frame. Do not synchronously re-read here:
                        // requestScrollToPage returns before remeasure and an
                        // eager read would burn all bounded corrections against
                        // the old layout key.
                        emptyList()
                    }
                    is UnifiedPagerEffectEvent.ProgrammaticCancelled -> {
                        if (activeScrollAction == event.action) {
                            programmaticScrollJob = null
                            activeScrollAction = null
                            activeScrollPageKeys = null
                            activeScrollSessionName = null
                        }
                        coordinator.onProgrammaticScrollCancelled(event.action)
                        coordinator.observeCurrentPagerFrame(
                            target = currentTarget.value,
                            pagerState = pagerState,
                        )
                    }
                }
                actions.forEach { action ->
                    val latestTarget = currentTarget.value
                    if (action.targetEpoch != latestTarget.epoch) return@forEach
                    // Resolve by measured composite key inside THIS snapshot;
                    // the page object, not its ordinal, is what flows onward.
                    val actionPage = latestTarget.pages.firstOrNull {
                        it.key == action.pageKey()
                    } ?: return@forEach

                    when (action) {
                        is UnifiedPagerCoordinatorAction.Scroll -> {
                            cancelProgrammaticScroll()
                            activeScrollAction = action
                            activeScrollPageKeys = latestTarget.pages.map { it.key }
                            activeScrollSessionName = latestTarget.sessionName
                            val executionPageKeys = activeScrollPageKeys
                            val executionSessionName = activeScrollSessionName
                            programmaticScrollJob = launch {
                                var completed = false
                                try {
                                    val executionTarget = currentTarget.value
                                    val executionPageIndex =
                                        executionTarget.pages.indexOfFirst {
                                            it.key == action.pageKey
                                        }
                                    if (
                                        executionTarget.epoch != action.targetEpoch ||
                                        executionTarget.pages.map { it.key } != executionPageKeys ||
                                        executionTarget.sessionName != executionSessionName ||
                                        executionPageIndex < 0 ||
                                        pagerState.isScrollInProgress
                                    ) {
                                        return@launch
                                    }
                                    currentScrollExecutor.value(
                                        pagerState,
                                        executionPageIndex,
                                        action,
                                    )
                                    completed = true
                                } catch (_: CancellationException) {
                                    // User input and target replacement own
                                    // cancellation. Never cancel the sole event
                                    // consumer with this child job.
                                } catch (_: Throwable) {
                                    // A failed pager mutation releases ownership;
                                    // the reducer retains the desired key and
                                    // decides whether its bounded retry applies.
                                } finally {
                                    events.trySend(
                                        if (completed) {
                                            UnifiedPagerEffectEvent.ProgrammaticCompleted(action)
                                        } else {
                                            UnifiedPagerEffectEvent.ProgrammaticCancelled(action)
                                        },
                                    )
                                }
                            }
                        }

                        is UnifiedPagerCoordinatorAction.ConfirmedSettle -> {
                            val accepted = currentOnConfirmedSettle.value(
                                action,
                                actionPage,
                            )
                            coordinator.onConfirmedSettleHandled(action, accepted)
                        }
                    }
                }
            }
        }
    }
}

private fun UnifiedPagerCoordinator.observeCurrentPagerFrame(
    target: UnifiedPagerTarget,
    pagerState: PagerState,
): List<UnifiedPagerCoordinatorAction> {
    val currentPage = pagerState.currentPage
    return updateTarget(
        target,
        UnifiedPagerObservation(
            targetEpoch = target.epoch,
            currentPage = currentPage,
            settledPage = pagerState.settledPage,
            measuredCurrentPageKey = pagerState.layoutInfo.visiblePagesInfo
                .firstOrNull { it.index == currentPage }
                ?.key,
            isScrollInProgress = pagerState.isScrollInProgress,
        ),
    )
}

private fun UnifiedPagerCoordinatorAction.pageKey(): UnifiedPagerPageKey =
    when (this) {
        is UnifiedPagerCoordinatorAction.Scroll -> pageKey
        is UnifiedPagerCoordinatorAction.ConfirmedSettle -> pageKey
    }
