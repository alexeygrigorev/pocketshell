package com.pocketshell.app.tmux

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect

/**
 * Issue #1778 reproduce-first Compose contract. Unlike the pure reducer matrix,
 * these tests compose Foundation's real HorizontalPager, its interactionSource,
 * measured layout keys and the production coordinator effect.
 */
@RunWith(AndroidJUnit4::class)
class TmuxUnifiedPagerCoordinatorComposeTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun activeFingerDragSurvivesPaneRepublishAndSettlesExactlyOneSwitch() {
        val epoch = UnifiedPagerTargetEpoch(SessionId("compose/target-a"), 1L)
        val initialPages = pages(
            epoch = epoch,
            targetName = "A",
            specs = listOf(
                PageSpec("A", "%a", "\$a", 0),
                PageSpec("B", "%b", "\$b", 0),
            ),
        )
        val confirmed = CopyOnWriteArrayList<UnifiedPagerCoordinatorAction.ConfirmedSettle>()
        val dragStarted = AtomicBoolean(false)
        lateinit var pagesState: MutableState<List<UnifiedPagerPage>>
        lateinit var pagerState: PagerState

        compose.setContent {
            pagesState = remember { mutableStateOf(initialPages) }
            val currentPages = pagesState.value
            pagerState = rememberPagerState(pageCount = { pagesState.value.size })
            LaunchedEffect(pagerState) {
                pagerState.interactionSource.interactions.collect { interaction ->
                    if (interaction is DragInteraction.Start) {
                        dragStarted.set(true)
                    }
                }
            }
            UnifiedPagerCoordinatorEffects(
                pagerState = pagerState,
                targetEpoch = epoch,
                pages = currentPages,
                sessionName = "A",
                initialWindowIndex = null,
            ) { action, _ ->
                confirmed += action
                true
            }
            TmuxTerminalPager(
                pages = currentPages,
                pagerState = pagerState,
                sessionName = "A",
                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                engineCommands = emptySet(),
                isAgentPane = false,
                onTerminalSizeChanged = { _, _ -> },
                onSurfaceError = { _, _ -> },
                onRecreateSurface = {},
                onUrlTap = {},
                onFilePathTap = { _, _ -> },
                onEngineCommandTap = {},
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            confirmed.any { it.pageKey == initialPages[0].key }
        }
        compose.runOnIdle { confirmed.clear() }

        compose.onNodeWithTag(PAGER_TAG).performTouchInput {
            val start = Offset(right - 24f, centerY)
            down(start)
            advanceEventTime(32L)
            repeat(3) {
                moveBy(Offset(-(width - 48f) / 8f, 0f))
                advanceEventTime(32L)
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            dragStarted.get() && pagerState.isScrollInProgress
        }
        compose.runOnIdle {
            assertTrue("Drag Start must be observed before republish", dragStarted.get())
            assertTrue("the pointer must still own the pager", pagerState.isScrollInProgress)
            pagesState.value = initialPages.map {
                it.copy(pane = it.pane.copy(title = "${it.pane.title}-republished"))
            }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue("republish must happen under the held pointer", dragStarted.get())
            assertTrue("republish must not cancel the held drag", pagerState.isScrollInProgress)
        }
        compose.onNodeWithTag(PAGER_TAG).performTouchInput {
            repeat(5) {
                moveBy(Offset(-(width - 48f) / 8f, 0f))
                advanceEventTime(32L)
            }
            up()
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            pagerState.settledPage == 1 &&
                confirmed.count { it.userDrivenFromAlignedTarget } == 1
        }
        compose.runOnIdle {
            assertEquals(1, pagerState.currentPage)
            assertEquals(
                initialPages[1].key,
                confirmed.single { it.userDrivenFromAlignedTarget }.pageKey,
            )
            assertEquals(
                "the republish/correction path must not emit an extra switch",
                1,
                confirmed.count { it.userDrivenFromAlignedTarget },
            )
        }
    }

    @Test
    fun heldCachedPromotionRetriesOnceAfterHoldClearsAndThenDedupes() {
        val epoch = UnifiedPagerTargetEpoch(SessionId("compose/held-a"), 7L)
        val initialPages = pages(
            epoch = epoch,
            targetName = "A",
            specs = listOf(
                PageSpec("A", "%a", "\$a", 0),
                PageSpec("B", "%b", "\$b", 0),
            ),
        )
        val cachedAttempts = CopyOnWriteArrayList<Boolean>()
        val acceptedPromotions = AtomicInteger(0)
        val initialAligned = AtomicBoolean(false)
        lateinit var heldState: MutableState<Boolean>
        lateinit var replayNonce: MutableState<Int>
        lateinit var pagerState: PagerState

        compose.setContent {
            heldState = remember { mutableStateOf(false) }
            // Issue #1778 CI reopen: production's settle-retry token is a
            // composite (`UnifiedPagerSettleRetryToken`: terminalHeld PLUS the
            // active page keys), so it can change while the reveal-hold
            // eligibility does NOT. `replayNonce` is that second, non-hold
            // dimension, controlled by the test so the frame-replay ordering
            // this class must survive is injected deterministically instead of
            // being left to device measure/apply timing (see below).
            replayNonce = remember { mutableStateOf(0) }
            pagerState = rememberPagerState(pageCount = { initialPages.size })
            UnifiedPagerCoordinatorEffects(
                pagerState = pagerState,
                targetEpoch = epoch,
                pages = initialPages,
                sessionName = "A",
                initialWindowIndex = null,
                settleRetryToken = HeldPromotionRetryToken(
                    held = heldState.value,
                    replay = replayNonce.value,
                ),
            ) { _, page ->
                if (page.key == initialPages[0].key) {
                    initialAligned.set(true)
                    true
                } else {
                    val held = heldState.value
                    cachedAttempts += held
                    if (!held) acceptedPromotions.incrementAndGet()
                    !held
                }
            }
            TmuxTerminalPager(
                pages = initialPages,
                pagerState = pagerState,
                sessionName = "A",
                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                engineCommands = emptySet(),
                isAgentPane = false,
                onTerminalSizeChanged = { _, _ -> },
                onSurfaceError = { _, _ -> },
                onRecreateSurface = {},
                onUrlTap = {},
                onFilePathTap = { _, _ -> },
                onEngineCommandTap = {},
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) { initialAligned.get() }

        compose.runOnIdle {
            heldState.value = true
            // Simulate the no-drag Foundation key-retention move that #797
            // promotes. The coordinator remains the only production writer.
            pagerState.requestScrollToPage(1)
        }
        // The measured cached page must reach the production promotion
        // predicate while the hold owns the surface, and must be rejected.
        //
        // How MANY times it is offered under one continuous hold is NOT a
        // coordinator invariant, and pinning that count is exactly what turned
        // `main` red (#1778 CI reopen, run 30323508796). The pager-position
        // write and the settle-retry-token write are two snapshot writes, and
        // whether Compose coalesces them into ONE measured `snapshotFlow` frame
        // is device measure/apply timing, observed both ways on real devices:
        //
        //   dev-box AVD : Frame(page=1,key=null,token=held-false) -> no action
        //                 Frame(page=1,key=B,   token=held-true)  -> 1 offer
        //   CI AVD      : Frame(page=1,key=B,   token=held-false) -> 1 offer
        //                 Frame(page=1,key=B,   token=held-true)  -> 2nd offer
        //
        // Both are correct: the retry token exists precisely so a replayed
        // measured frame re-reaches the predicate when the token changes, and
        // the production token also carries the active page keys, which change
        // independently of the hold. So this test does NOT wait for the device
        // to produce the multi-offer ordering — it injects it (`replayNonce`)
        // and HARD-waits for it, the #780 synthetic-state model. The durable
        // contracts are the three asserted below: never promoted through a
        // hold, retried exactly once when the hold clears, and permanently
        // deduped by acceptance.
        compose.waitUntil(timeoutMillis = 5_000) {
            pagerState.settledPage == 1 && cachedAttempts.isNotEmpty()
        }
        compose.waitForIdle()

        // Inject the CI ordering: replay the SAME measured pager frame under
        // the SAME hold. A rejected key must stay retryable across that replay
        // — a rejection that consumed it would strand the #797 promotion once
        // the hold clears, and a replay that stopped re-offering would too.
        var attemptsBeforeReplay = 0
        compose.runOnIdle {
            attemptsBeforeReplay = cachedAttempts.size
            replayNonce.value += 1
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            cachedAttempts.size > attemptsBeforeReplay
        }
        compose.waitForIdle()

        var attemptsUnderHold = 0
        compose.runOnIdle {
            assertTrue(
                "every promotion offered under the hold must observe the hold",
                cachedAttempts.isNotEmpty() && cachedAttempts.all { it },
            )
            assertEquals(
                "the reveal hold must never be promoted through",
                0,
                acceptedPromotions.get(),
            )
            attemptsUnderHold = cachedAttempts.size
            heldState.value = false
        }

        compose.waitUntil(timeoutMillis = 5_000) { acceptedPromotions.get() == 1 }
        compose.runOnIdle {
            assertEquals(
                "clearing the hold must retry the rejected key exactly once",
                listOf(false),
                cachedAttempts.drop(attemptsUnderHold),
            )
        }

        // Further eligibility changes AND pure frame replays both re-drive the
        // measured pager frame, but acceptance — and only acceptance —
        // permanently dedupes this measured B key.
        var attemptsAfterAcceptance = 0
        compose.runOnIdle {
            attemptsAfterAcceptance = cachedAttempts.size
            heldState.value = true
        }
        compose.runOnIdle { heldState.value = false }
        compose.runOnIdle { heldState.value = true }
        compose.runOnIdle { heldState.value = false }
        compose.runOnIdle { replayNonce.value += 1 }
        compose.runOnIdle { replayNonce.value += 1 }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(
                "acceptance must permanently dedupe the measured cached key",
                attemptsAfterAcceptance,
                cachedAttempts.size,
            )
            assertEquals(1, acceptedPromotions.get())
        }
    }

    @Test
    fun fingerInterruptsSuspendedInitialScrollWithoutKillingCoordinator() {
        val epoch = UnifiedPagerTargetEpoch(SessionId("compose/interrupt-b"), 3L)
        val initialPages = pages(
            epoch = epoch,
            targetName = "B",
            specs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1", "\$b", 1),
            ),
        )
        val firstStarted = AtomicBoolean(false)
        val firstCancelled = AtomicBoolean(false)
        val executions = AtomicInteger(0)
        val confirmedKeys = CopyOnWriteArrayList<UnifiedPagerPageKey>()
        lateinit var pagerState: PagerState

        compose.setContent {
            pagerState = rememberPagerState(pageCount = { initialPages.size })
            UnifiedPagerCoordinatorEffects(
                pagerState = pagerState,
                targetEpoch = epoch,
                pages = initialPages,
                sessionName = "B",
                initialWindowIndex = 1,
                scrollExecutor = { state, pageIndex, _ ->
                    if (executions.incrementAndGet() == 1) {
                        firstStarted.set(true)
                        try {
                            awaitCancellation()
                        } finally {
                            firstCancelled.set(true)
                        }
                    } else {
                        state.scrollToPage(pageIndex)
                    }
                },
            ) { action, _ ->
                confirmedKeys += action.pageKey
                true
            }
            TmuxTerminalPager(
                pages = initialPages,
                pagerState = pagerState,
                sessionName = "B",
                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                engineCommands = emptySet(),
                isAgentPane = false,
                onTerminalSizeChanged = { _, _ -> },
                onSurfaceError = { _, _ -> },
                onRecreateSurface = {},
                onUrlTap = {},
                onFilePathTap = { _, _ -> },
                onEngineCommandTap = {},
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) { firstStarted.get() }

        compose.onNodeWithTag(PAGER_TAG).performTouchInput {
            down(Offset(centerX, centerY))
            advanceEventTime(32L)
            moveBy(Offset(-48f, 0f))
            advanceEventTime(32L)
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            pagerState.isScrollInProgress && firstCancelled.get()
        }
        compose.runOnIdle {
            assertTrue("finger must cancel only the scroll child", firstCancelled.get())
            assertTrue("finger still owns the real pager", pagerState.isScrollInProgress)
        }
        compose.onNodeWithTag(PAGER_TAG).performTouchInput {
            up()
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            executions.get() >= 2 &&
                pagerState.settledPage == 1 &&
                confirmedKeys.contains(initialPages[1].key)
        }
        compose.runOnIdle {
            assertEquals(1, pagerState.currentPage)
            assertEquals(listOf(initialPages[1].key), confirmedKeys.toList())
            assertTrue("B1 must remain pending across cancellation", executions.get() >= 2)
        }
    }

    @Test
    fun targetEpochChangeCancelsSuspendedInitialScrollAndConfirmsOnlyNewOwner() {
        val oldEpoch = UnifiedPagerTargetEpoch(SessionId("compose/old-b"), 4L)
        val newEpoch = UnifiedPagerTargetEpoch(SessionId("compose/new-c"), 5L)
        val oldPages = pages(
            epoch = oldEpoch,
            targetName = "B",
            specs = listOf(
                PageSpec("B", "%0", "\$reused", 0),
                PageSpec("B", "%1", "\$reused", 1),
            ),
        )
        val newPages = pages(
            epoch = newEpoch,
            targetName = "C",
            specs = listOf(
                PageSpec("C", "%0", "\$reused", 0),
                PageSpec("C", "%1", "\$reused", 1),
            ),
        )
        val oldStarted = AtomicBoolean(false)
        val oldCancelled = AtomicBoolean(false)
        val newStarted = AtomicBoolean(false)
        val confirmedKeys = CopyOnWriteArrayList<UnifiedPagerPageKey>()
        lateinit var epochState: MutableState<UnifiedPagerTargetEpoch>
        lateinit var pagesState: MutableState<List<UnifiedPagerPage>>
        lateinit var nameState: MutableState<String>
        lateinit var pagerState: PagerState

        compose.setContent {
            epochState = remember { mutableStateOf(oldEpoch) }
            pagesState = remember { mutableStateOf(oldPages) }
            nameState = remember { mutableStateOf("B") }
            val currentPages = pagesState.value
            pagerState = rememberPagerState(pageCount = { pagesState.value.size })
            UnifiedPagerCoordinatorEffects(
                pagerState = pagerState,
                targetEpoch = epochState.value,
                pages = currentPages,
                sessionName = nameState.value,
                initialWindowIndex = 1,
                scrollExecutor = { state, pageIndex, action ->
                    if (action.targetEpoch == oldEpoch) {
                        oldStarted.set(true)
                        try {
                            awaitCancellation()
                        } finally {
                            oldCancelled.set(true)
                        }
                    } else {
                        newStarted.set(true)
                        state.scrollToPage(pageIndex)
                    }
                },
            ) { action, _ ->
                confirmedKeys += action.pageKey
                true
            }
            TmuxTerminalPager(
                pages = currentPages,
                pagerState = pagerState,
                sessionName = nameState.value,
                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                engineCommands = emptySet(),
                isAgentPane = false,
                onTerminalSizeChanged = { _, _ -> },
                onSurfaceError = { _, _ -> },
                onRecreateSurface = {},
                onUrlTap = {},
                onFilePathTap = { _, _ -> },
                onEngineCommandTap = {},
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) { oldStarted.get() }
        compose.runOnIdle {
            epochState.value = newEpoch
            pagesState.value = newPages
            nameState.value = "C"
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            oldCancelled.get() &&
                newStarted.get() &&
                pagerState.settledPage == 1 &&
                confirmedKeys.contains(newPages[1].key)
        }
        compose.runOnIdle {
            assertTrue("old target's owned scroll must be cancelled", oldCancelled.get())
            assertTrue("the sole coordinator must ingest the new target", newStarted.get())
            assertEquals(listOf(newPages[1].key), confirmedKeys.toList())
            assertTrue(confirmedKeys.none { it.targetEpoch == oldEpoch })
        }
    }

    @Test
    fun sameEpochDesiredReplacementRetiresSuspendedOwnerAndRetriesNewKey() {
        val epoch = UnifiedPagerTargetEpoch(SessionId("compose/same-epoch-b"), 6L)
        val originalPages = pages(
            epoch = epoch,
            targetName = "B",
            specs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1-old", "\$b", 1),
            ),
        )
        val replacementPages = pages(
            epoch = epoch,
            targetName = "B",
            specs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1-new", "\$b", 1),
            ),
        )
        val oldStarted = AtomicBoolean(false)
        val oldCancelled = AtomicBoolean(false)
        val oldActions = CopyOnWriteArrayList<UnifiedPagerCoordinatorAction.Scroll>()
        val replacementActions =
            CopyOnWriteArrayList<UnifiedPagerCoordinatorAction.Scroll>()
        val confirmedKeys = CopyOnWriteArrayList<UnifiedPagerPageKey>()
        lateinit var pagesState: MutableState<List<UnifiedPagerPage>>
        lateinit var pagerState: PagerState

        compose.setContent {
            pagesState = remember { mutableStateOf(originalPages) }
            val currentPages = pagesState.value
            pagerState = rememberPagerState(pageCount = { pagesState.value.size })
            UnifiedPagerCoordinatorEffects(
                pagerState = pagerState,
                targetEpoch = epoch,
                pages = currentPages,
                sessionName = "B",
                initialWindowIndex = 1,
                scrollExecutor = { state, pageIndex, action ->
                    if (action.pageKey == originalPages[1].key) {
                        oldActions += action
                        oldStarted.set(true)
                        try {
                            awaitCancellation()
                        } finally {
                            oldCancelled.set(true)
                        }
                    } else {
                        replacementActions += action
                        if (replacementActions.size == 1) {
                            throw CancellationException("first replacement retry")
                        }
                        state.scrollToPage(pageIndex)
                    }
                },
            ) { action, _ ->
                confirmedKeys += action.pageKey
                true
            }
            TmuxTerminalPager(
                pages = currentPages,
                pagerState = pagerState,
                sessionName = "B",
                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                engineCommands = emptySet(),
                isAgentPane = false,
                onTerminalSizeChanged = { _, _ -> },
                onSurfaceError = { _, _ -> },
                onRecreateSurface = {},
                onUrlTap = {},
                onFilePathTap = { _, _ -> },
                onEngineCommandTap = {},
            )
        }
        compose.waitUntil(timeoutMillis = 5_000) { oldStarted.get() }

        compose.runOnIdle {
            pagesState.value = listOf(originalPages[0])
        }
        compose.waitUntil(timeoutMillis = 5_000) { oldCancelled.get() }
        compose.runOnIdle {
            assertTrue("removed B1 cannot settle B0", confirmedKeys.isEmpty())
            pagesState.value = replacementPages
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            replacementActions.size >= 2 &&
                pagerState.settledPage == 1 &&
                confirmedKeys.contains(replacementPages[1].key)
        }
        compose.runOnIdle {
            assertEquals(listOf(replacementPages[1].key), confirmedKeys.toList())
            assertEquals(listOf(1, 2), replacementActions.map { it.attempt })
            assertEquals(
                replacementActions.size,
                replacementActions.map { it.executionToken }.toSet().size,
            )
            assertTrue(
                "reset attempt=1 must not reuse the retired execution identity",
                replacementActions.first().executionToken >
                    oldActions.single().executionToken,
            )
            assertTrue(replacementActions.size <= UnifiedPagerCoordinator.MAX_CORRECTION_COUNT)
        }
    }

    @Test
    fun initialWindowB1WinsBeforeNumericB0CanAlign() {
        val epoch = UnifiedPagerTargetEpoch(SessionId("compose/target-b"), 2L)
        val initialPages = pages(
            epoch = epoch,
            targetName = "B",
            specs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1", "\$b", 1),
            ),
        )
        val confirmedKeys = CopyOnWriteArrayList<UnifiedPagerPageKey>()
        lateinit var pagerState: PagerState

        compose.setContent {
            pagerState = rememberPagerState(pageCount = { initialPages.size })
            UnifiedPagerCoordinatorEffects(
                pagerState = pagerState,
                targetEpoch = epoch,
                pages = initialPages,
                sessionName = "B",
                initialWindowIndex = 1,
            ) { action, _ ->
                confirmedKeys += action.pageKey
                true
            }
            TmuxTerminalPager(
                pages = initialPages,
                pagerState = pagerState,
                sessionName = "B",
                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                engineCommands = emptySet(),
                isAgentPane = false,
                onTerminalSizeChanged = { _, _ -> },
                onSurfaceError = { _, _ -> },
                onRecreateSurface = {},
                onUrlTap = {},
                onFilePathTap = { _, _ -> },
                onEngineCommandTap = {},
            )
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            pagerState.settledPage == 1 && confirmedKeys.contains(initialPages[1].key)
        }
        compose.runOnIdle {
            assertEquals(1, pagerState.currentPage)
            assertEquals(listOf(initialPages[1].key), confirmedKeys.toList())
            assertTrue(confirmedKeys.none { it == initialPages[0].key })
        }
    }

    /**
     * Issue #1778: the shape of the production settle-retry token — the reveal
     * hold PLUS a second dimension that changes independently of it (production
     * carries the active page keys there). `replay` lets this test replay one
     * measured pager frame with the hold unchanged.
     */
    private data class HeldPromotionRetryToken(
        val held: Boolean,
        val replay: Int,
    )

    private data class PageSpec(
        val sessionName: String,
        val paneId: String,
        val sessionId: String,
        val windowIndex: Int,
    )

    private fun pages(
        epoch: UnifiedPagerTargetEpoch,
        targetName: String,
        specs: List<PageSpec>,
    ): List<UnifiedPagerPage> {
        val names = specs.associate { it.paneId to it.sessionName }
        return unifiedPagerPages(
            panes = specs.map { spec ->
                TmuxPaneState(
                    paneId = spec.paneId,
                    windowId = "@${spec.paneId}",
                    windowIndex = spec.windowIndex,
                    sessionId = spec.sessionId,
                    title = spec.sessionName,
                    terminalState = TerminalSurfaceState(),
                )
            },
            targetEpoch = epoch,
            targetSessionName = targetName,
            sessionNameForPane = { names[it.paneId] },
        )
    }

    private companion object {
        const val PAGER_TAG = TMUX_UNIFIED_TERMINAL_PAGER_TAG
    }
}
