package com.pocketshell.app.tmux

import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1778 reducer matrix. Each test drives the same typed events the real
 * Compose effect produces; [TmuxUnifiedPagerCoordinatorComposeTest] owns the
 * real HorizontalPager/gesture interop reproduction.
 */
class TmuxUnifiedPagerCoordinatorTest {

    @Test
    fun metadataRepublishDuringActiveDragCannotWriteAndSettlesExactlyOneSwitch() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)

        coordinator.onDragStarted()
        val republished = f.target(
            pages = f.pages.map { page ->
                page.copy(pane = page.pane.copy(title = "${page.pane.title}-healed"))
            },
        )
        assertTrue(
            coordinator.updateTarget(
                republished,
                f.observation(page = 0, scrolling = true),
            ).isEmpty(),
        )
        assertTrue(coordinator.observe(f.observation(page = 1, scrolling = true)).isEmpty())
        assertTrue(coordinator.onDragStopped().isEmpty())

        val settled = coordinator.observe(f.observation(page = 1))
        assertEquals(1, settled.size)
        assertTrue(
            (settled.single() as UnifiedPagerCoordinatorAction.ConfirmedSettle)
                .userDrivenFromAlignedTarget,
        )
        coordinator.onConfirmedSettleHandled(settled.singleSettle(), accepted = true)
        assertTrue(
            "the same idle observation must not emit a second switch",
            coordinator.observe(f.observation(page = 1)).isEmpty(),
        )
    }

    @Test
    fun measuredIdleCachedSettleWithoutDragStillReachesPromotionExactlyOnce() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)

        val settled = coordinator.observe(f.observation(page = 1))
        val action = settled.singleSettle()

        assertEquals(f.pages[1].key, action.pageKey)
        assertFalse(action.userDrivenFromAlignedTarget)
        assertTrue(action.targetWasAlignedBeforeSettle)
        coordinator.onConfirmedSettleHandled(action, accepted = true)
        assertTrue(
            "the same measured idle cached settle must not emit twice",
            coordinator.observe(f.observation(page = 1)).isEmpty(),
        )
    }

    @Test
    fun heldCachedPromotionRetriesOnceAfterRejectionAndDedupesAfterAcceptance() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)

        // The measured cached B settle arrives while the reveal hold rejects
        // #797 promotion. Rejection is acknowledgement, not consumption.
        val held = coordinator.observe(f.observation(page = 1)).singleSettle()
        assertEquals(f.pages[1].key, held.pageKey)
        assertFalse(held.userDrivenFromAlignedTarget)
        coordinator.onConfirmedSettleHandled(held, accepted = false)

        // When the hold clears, the settle-context token makes production
        // replay this same measured frame. Exactly one promotion is accepted.
        val afterHold = coordinator.observe(f.observation(page = 1)).singleSettle()
        assertEquals(held.pageKey, afterHold.pageKey)
        coordinator.onConfirmedSettleHandled(afterHold, accepted = true)

        assertTrue(
            "accepted #797 promotion must dedupe the same measured cached key",
            coordinator.observe(f.observation(page = 1)).isEmpty(),
        )
    }

    @Test
    fun repeatedRejectedCachedPromotionReplaysStayRetryableUntilAcceptance() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)

        // Issue #1778 CI reopen: the pager-position write and the
        // eligibility-token write are two snapshot writes, and whether Compose
        // coalesces them into one measured frame is device timing. On the CI
        // AVD they do NOT coalesce, so the SAME measured cached page is offered
        // more than once while one continuous reveal hold is still rejecting.
        // Every rejection must leave the key retryable: a rejection that
        // consumed it would strand the #797 promotion forever once the hold
        // clears, and a replay that stopped re-offering would do the same.
        repeat(3) { replay ->
            val rejected = coordinator.observe(f.observation(page = 1)).singleSettle()
            assertEquals(
                "replay $replay must re-offer the same measured cached key",
                f.pages[1].key,
                rejected.pageKey,
            )
            assertFalse(rejected.userDrivenFromAlignedTarget)
            coordinator.onConfirmedSettleHandled(rejected, accepted = false)
        }

        val accepted = coordinator.observe(f.observation(page = 1)).singleSettle()
        assertEquals(f.pages[1].key, accepted.pageKey)
        coordinator.onConfirmedSettleHandled(accepted, accepted = true)

        repeat(3) { replay ->
            assertTrue(
                "replay $replay after acceptance must stay deduped",
                coordinator.observe(f.observation(page = 1)).isEmpty(),
            )
        }
    }

    @Test
    fun dragSnapBackRearmsTargetAlignment() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)

        coordinator.onDragStarted()
        coordinator.observe(f.observation(page = 0, scrolling = true))
        coordinator.onDragStopped()
        val actions = coordinator.observe(f.observation(page = 0))

        assertTrue(coordinator.snapshot().targetAligned)
        assertFalse(actions.anyUserDrivenSettle())
    }

    @Test
    fun dragCancelClearsLatchAndCorrectsForeignPage() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)

        coordinator.onDragStarted()
        coordinator.observe(f.observation(page = 1, scrolling = true))
        coordinator.onDragCancelled()
        val correction = coordinator.observe(f.observation(page = 1))

        assertEquals(f.pages[0].key, correction.singleScroll().pageKey)
        assertFalse(correction.anyUserDrivenSettle())
        assertTrue(
            coordinator.snapshot().scrollOwner is UnifiedPagerScrollOwner.Programmatic,
        )
    }

    @Test
    fun initialWindowB1OwnsPagerUntilMeasuredAndCannotBeConsumedByB0Alignment() {
        val f = fixture(
            targetName = "B",
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", windowIndex = 0),
                PageSpec("B", "%b1", "\$b", windowIndex = 1),
            ),
        )
        val coordinator = UnifiedPagerCoordinator()
        val initialTarget = f.target(initialWindowIndex = 1)

        val first = coordinator.updateTarget(initialTarget, f.observation(page = 0))
        assertEquals(UnifiedPagerProgrammaticKind.InitialWindow, first.singleScroll().kind)
        assertEquals(f.pages[1].key, first.singleScroll().pageKey)
        assertTrue(
            "a concurrent frame while programmatic scroll owns the pager cannot align B0",
            coordinator.observe(f.observation(page = 0, scrolling = true)).isEmpty(),
        )

        val confirmed = coordinator.observe(f.observation(page = 1))
        assertEquals(f.pages[1].key, confirmed.singleSettle().pageKey)
        assertNull(coordinator.snapshot().pendingInitialWindowKey)
        assertEquals(f.pages[1].key, coordinator.snapshot().desiredPageKey)
    }

    @Test
    fun unresolvedInitialWindowBlocksFirstWindowCorrectionUntilB1Appears() {
        val epoch = UnifiedPagerTargetEpoch(SessionId("host/B"), 7L)
        val firstOnly = fixture(
            targetName = "B",
            epoch = epoch,
            pageSpecs = listOf(PageSpec("B", "%b0", "\$b", windowIndex = 0)),
        )
        val coordinator = UnifiedPagerCoordinator()

        assertTrue(
            coordinator.updateTarget(
                firstOnly.target(initialWindowIndex = 1),
                firstOnly.observation(page = 0),
            ).isEmpty(),
        )

        val both = fixture(
            targetName = "B",
            epoch = epoch,
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", windowIndex = 0),
                PageSpec("B", "%b1", "\$b", windowIndex = 1),
            ),
        )
        val action = coordinator.updateTarget(
            both.target(initialWindowIndex = 1),
            both.observation(page = 0),
        )
        assertEquals(both.pages[1].key, action.singleScroll().pageKey)
    }

    @Test
    fun delayedObservationFromAOrBIsRejectedAfterNewTargetEpoch() {
        val a1 = fixture(targetName = "A", epoch = UnifiedPagerTargetEpoch(SessionId("host/A"), 1L))
        val b = fixture(targetName = "B", epoch = UnifiedPagerTargetEpoch(SessionId("host/B"), 2L))
        val a2 = fixture(targetName = "A", epoch = UnifiedPagerTargetEpoch(SessionId("host/A"), 3L))
        val coordinator = UnifiedPagerCoordinator()

        coordinator.updateTarget(a1.target(), a1.observation(page = 0))
        coordinator.updateTarget(b.target(), b.observation(page = 0))
        coordinator.updateTarget(a2.target(), a2.observation(page = 0))

        assertTrue(coordinator.observe(a1.observation(page = 1)).isEmpty())
        assertTrue(coordinator.observe(b.observation(page = 1)).isEmpty())
        assertEquals(a2.epoch, coordinator.snapshot().targetEpoch)
    }

    @Test
    fun sameNameAndReusedPaneIdCannotReuseKeyAcrossDurableTargetGeneration() {
        val old = fixture(
            targetName = "same",
            epoch = UnifiedPagerTargetEpoch(SessionId("host-1/tmux:\$0:10"), 1L),
            pageSpecs = listOf(PageSpec("same", "%0", "\$0", 0)),
        )
        val replacement = fixture(
            targetName = "same",
            epoch = UnifiedPagerTargetEpoch(SessionId("host-2/tmux:\$0:20"), 2L),
            pageSpecs = listOf(PageSpec("same", "%0", "\$0", 0)),
        )

        assertNotEquals(old.pages.single().key, replacement.pages.single().key)
        assertNotEquals(
            old.pages.single().owner.sessionId,
            replacement.pages.single().owner.sessionId,
        )
    }

    @Test
    fun remountWithRetainedOldLayoutKeyUsesBoundedCorrectionCount() {
        val f = fixture()
        val oldEpoch = UnifiedPagerTargetEpoch(SessionId("old/A"), 1L)
        val oldKey = f.pages[0].key.copy(targetEpoch = oldEpoch)
        val coordinator = UnifiedPagerCoordinator(maxCorrectionCount = 3)
        val stale = f.observation(page = 0, measuredKey = oldKey)

        assertTrue(
            coordinator.updateTarget(
                f.target(),
                f.observation(page = 0, measuredKey = null),
            ).isEmpty(),
        )
        repeat(3) {
            assertTrue(
                coordinator.observe(
                    f.observation(page = 0, measuredKey = null),
                ).isEmpty(),
            )
        }
        assertEquals(0, coordinator.snapshot().correctionCount)

        val attempts = mutableListOf<UnifiedPagerCoordinatorAction.Scroll>()
        var attempt = coordinator.observe(stale).singleScroll()
        attempts += attempt
        repeat(4) {
            coordinator.onProgrammaticScrollCompleted(attempt)
            val actions = coordinator.observe(stale)
            attempts += actions.filterIsInstance<UnifiedPagerCoordinatorAction.Scroll>()
            actions.filterIsInstance<UnifiedPagerCoordinatorAction.Scroll>()
                .singleOrNull()
                ?.let { attempt = it }
        }

        assertEquals(listOf(1, 2, 3), attempts.map { it.attempt })
        assertEquals(3, coordinator.snapshot().correctionCount)
        assertTrue(coordinator.observe(stale).isEmpty())
    }

    @Test
    fun cancelledInitialScrollRetainsB1AndRetriesFromNextMeasuredFrame() {
        val f = fixture(
            targetName = "B",
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1", "\$b", 1),
            ),
        )
        val coordinator = UnifiedPagerCoordinator()
        val first = coordinator.updateTarget(
            f.target(initialWindowIndex = 1),
            f.observation(page = 0),
        ).singleScroll()

        coordinator.onProgrammaticScrollCancelled(first)
        assertEquals(f.pages[1].key, coordinator.snapshot().pendingInitialWindowKey)
        val retry = coordinator.observe(f.observation(page = 0)).singleScroll()

        assertEquals(2, retry.attempt)
        assertEquals(f.pages[1].key, retry.pageKey)
        assertEquals(UnifiedPagerProgrammaticKind.InitialWindow, retry.kind)
        val confirmed = coordinator.observe(f.observation(page = 1)).singleSettle()
        assertEquals(f.pages[1].key, confirmed.pageKey)
        assertNull(coordinator.snapshot().pendingInitialWindowKey)
    }

    @Test
    fun staleCompletionCannotReleaseNewEpochOrNewAttemptOwner() {
        val old = fixture(
            targetName = "B",
            epoch = UnifiedPagerTargetEpoch(SessionId("host/old-b"), 1L),
            pageSpecs = listOf(
                PageSpec("B", "%0", "\$same", 0),
                PageSpec("B", "%1", "\$same", 1),
            ),
        )
        val replacement = fixture(
            targetName = "B",
            epoch = UnifiedPagerTargetEpoch(SessionId("host/new-b"), 2L),
            pageSpecs = listOf(
                PageSpec("B", "%0", "\$same", 0),
                PageSpec("B", "%1", "\$same", 1),
            ),
        )
        val coordinator = UnifiedPagerCoordinator()
        val stale = coordinator.updateTarget(
            old.target(initialWindowIndex = 1),
            old.observation(page = 0),
        ).singleScroll()
        val current = coordinator.updateTarget(
            replacement.target(initialWindowIndex = 1),
            replacement.observation(page = 0),
        ).singleScroll()

        coordinator.onProgrammaticScrollCancelled(stale)
        coordinator.onProgrammaticScrollCompleted(stale)

        assertEquals(replacement.epoch, coordinator.snapshot().targetEpoch)
        assertEquals(replacement.pages[1].key, coordinator.snapshot().pendingInitialWindowKey)
        assertTrue(
            coordinator.snapshot().scrollOwner is UnifiedPagerScrollOwner.Programmatic,
        )
        assertTrue(
            "stale feedback must not schedule a concurrent correction",
            coordinator.observe(replacement.observation(page = 0)).isEmpty(),
        )
        coordinator.onProgrammaticScrollCancelled(current)
        assertEquals(
            replacement.pages[1].key,
            coordinator.observe(replacement.observation(page = 0)).singleScroll().pageKey,
        )
    }

    @Test
    fun sameEpochDesiredRemovalRetiresOwnerAndReaddUsesFreshBoundedTokens() {
        val epoch = UnifiedPagerTargetEpoch(SessionId("host/same-epoch-b"), 3L)
        val original = fixture(
            targetName = "B",
            epoch = epoch,
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1-old", "\$b", 1),
            ),
        )
        val replacement = fixture(
            targetName = "B",
            epoch = epoch,
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1-new", "\$b", 1),
            ),
        )
        val coordinator = UnifiedPagerCoordinator()
        val oldAction = coordinator.updateTarget(
            original.target(initialWindowIndex = 1),
            original.observation(page = 0),
        ).singleScroll()

        val removed = coordinator.updateTarget(
            original.target(
                pages = listOf(original.pages[0]),
                initialWindowIndex = 1,
            ),
            original.observation(page = 0),
        )
        assertTrue(removed.isEmpty())
        assertEquals(UnifiedPagerScrollOwner.None, coordinator.snapshot().scrollOwner)
        assertNull(coordinator.snapshot().desiredPageKey)
        assertEquals(0, coordinator.snapshot().correctionCount)

        coordinator.onProgrammaticScrollCancelled(oldAction)
        val firstReplacement = coordinator.updateTarget(
            replacement.target(initialWindowIndex = 1),
            replacement.observation(page = 0),
        ).singleScroll()
        assertEquals(1, firstReplacement.attempt)
        assertTrue(firstReplacement.executionToken > oldAction.executionToken)
        assertEquals(replacement.pages[1].key, firstReplacement.pageKey)

        // Both completion shapes from the retired writer must be harmless,
        // even though the correction attempt was reset to one.
        coordinator.onProgrammaticScrollCompleted(oldAction)
        coordinator.onProgrammaticScrollCancelled(oldAction)
        assertEquals(
            firstReplacement.executionToken,
            (coordinator.snapshot().scrollOwner as UnifiedPagerScrollOwner.Programmatic)
                .executionToken,
        )

        coordinator.onProgrammaticScrollCancelled(firstReplacement)
        val secondReplacement = coordinator.observe(
            replacement.observation(page = 0),
        ).singleScroll()
        coordinator.onProgrammaticScrollCompleted(secondReplacement)
        val thirdReplacement = coordinator.observe(
            replacement.observation(page = 0),
        ).singleScroll()
        coordinator.onProgrammaticScrollCompleted(thirdReplacement)
        assertTrue(
            coordinator.observe(replacement.observation(page = 0)).isEmpty(),
        )
        assertEquals(
            listOf(1, 2, 3),
            listOf(firstReplacement, secondReplacement, thirdReplacement).map { it.attempt },
        )
        assertEquals(
            3,
            setOf(
                firstReplacement.executionToken,
                secondReplacement.executionToken,
                thirdReplacement.executionToken,
            ).size,
        )

        val confirmed = coordinator.observe(
            replacement.observation(page = 1),
        ).singleSettle()
        assertEquals(replacement.pages[1].key, confirmed.pageKey)
        assertNull(coordinator.snapshot().pendingInitialWindowKey)
    }

    @Test
    fun selectedWindowMeasuredKeySurvivesSameTargetReorder() {
        val f = fixture(
            targetName = "B",
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1", "\$b", 1),
            ),
        )
        val coordinator = UnifiedPagerCoordinator()
        coordinator.updateTarget(f.target(), f.observation(page = 1))

        val reorderedPages = listOf(f.pages[1], f.pages[0])
        val correction = coordinator.updateTarget(
            f.target(pages = reorderedPages),
            f.observation(page = 1, measuredKey = f.pages[1].key),
        )

        assertEquals(
            "the selected B1 key, not first-window B0, remains the desired owner",
            f.pages[1].key,
            correction.singleScroll().pageKey,
        )
        val settled = coordinator.observe(
            f.observation(page = 0, measuredKey = f.pages[1].key),
        )
        assertTrue(settled.isEmpty())
        assertTrue(coordinator.snapshot().targetAligned)
        assertEquals(f.pages[1].key, coordinator.snapshot().desiredPageKey)
    }

    @Test
    fun withinGraceRepublishPreservesNonFirstWindowSelection() {
        val f = fixture(
            targetName = "B",
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1", "\$b", 1),
            ),
        )
        val coordinator = UnifiedPagerCoordinator()
        coordinator.updateTarget(f.target(), f.observation(page = 1))
        val republished = f.pages.map { it.copy(pane = it.pane.copy(cwd = "/after-grace")) }

        val actions = coordinator.updateTarget(
            f.target(pages = republished),
            f.observation(page = 1, measuredKey = f.pages[1].key),
        )

        assertTrue(actions.isEmpty())
        assertEquals(f.pages[1].key, coordinator.snapshot().desiredPageKey)
    }

    @Test
    fun outboundFlapAndStaleHealRepublishesDuringDragEmitNoExtraSwitch() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)
        coordinator.onDragStarted()

        repeat(2) { revision ->
            val republished = f.pages.map {
                it.copy(pane = it.pane.copy(currentCommand = "heal-$revision"))
            }
            assertTrue(
                coordinator.updateTarget(
                    f.target(pages = republished),
                    f.observation(page = 1, scrolling = true),
                ).isEmpty(),
            )
        }
        coordinator.onDragStopped()
        val settled = coordinator.observe(f.observation(page = 1))
        assertEquals(1, settled.countSettles())
        coordinator.onConfirmedSettleHandled(settled.singleSettle(), accepted = true)
        assertEquals(0, coordinator.observe(f.observation(page = 1)).countSettles())
    }

    @Test
    fun numericSettledPageAloneCannotMarkAlignment() {
        val f = fixture()
        val coordinator = UnifiedPagerCoordinator()

        val actions = coordinator.updateTarget(
            f.target(),
            f.observation(page = 0, measuredKey = null),
        )

        assertTrue(actions.isEmpty())
        assertFalse(coordinator.snapshot().targetAligned)
    }

    @Test
    fun activeUserAndProgrammaticOwnersBothBlockConcurrentCorrection() {
        val f = fixture(
            targetName = "B",
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1", "\$b", 1),
            ),
        )
        val coordinator = UnifiedPagerCoordinator()
        coordinator.updateTarget(f.target(initialWindowIndex = 1), f.observation(page = 0))

        assertTrue(
            coordinator.observe(f.observation(page = 0, scrolling = true)).isEmpty(),
        )
        coordinator.onDragStarted()
        assertTrue(
            coordinator.updateTarget(
                f.target(
                    initialWindowIndex = 1,
                    pages = f.pages.map { it.copy(pane = it.pane.copy(title = "republished")) },
                ),
                f.observation(page = 0, scrolling = true),
            ).isEmpty(),
        )
        assertEquals(UnifiedPagerScrollOwner.UserDrag, coordinator.snapshot().scrollOwner)
    }

    /**
     * The dangerous window the reported defect lives in: the finger is DOWN and
     * owns the pager (Foundation has published `DragInteraction.Start`) but the
     * pager itself reports IDLE — between the touch-down and the first drag
     * delta, and again while the user holds still mid-gesture. Guarding only on
     * `isScrollInProgress` leaves that window open, so a metadata republish
     * arriving in it issues an alignment scroll and cancels the real gesture.
     *
     * This drives every observation with `scrolling = false` on purpose, so the
     * ONLY thing that can keep the coordinator quiet is the user-drag owner.
     */
    @Test
    fun idlePagerUnderAHeldFingerStillBlocksAlignmentCorrection() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)

        coordinator.onDragStarted()
        assertEquals(UnifiedPagerScrollOwner.UserDrag, coordinator.snapshot().scrollOwner)

        // Foundation has moved the measured key to the cached B page while the
        // pointer is down but momentarily still. Pre-#1778 this is precisely
        // where the correction loop fired a requestScrollToPage.
        assertTrue(
            "an idle observation under a held finger must not correct",
            coordinator.observe(f.observation(page = 1, scrolling = false)).isEmpty(),
        )
        assertTrue(
            "a pane republish under a held finger must not correct",
            coordinator.updateTarget(
                f.target(
                    pages = f.pages.map { it.copy(pane = it.pane.copy(title = "healed")) },
                ),
                f.observation(page = 1, scrolling = false),
            ).isEmpty(),
        )
        assertEquals(
            "the user still owns the pager",
            UnifiedPagerScrollOwner.UserDrag,
            coordinator.snapshot().scrollOwner,
        )
        assertEquals(0, coordinator.snapshot().correctionCount)
    }

    /** The same window, but for the initial-window (#782) programmatic intent. */
    @Test
    fun idlePagerUnderAHeldFingerCannotConsumeOrRetryInitialWindowIntent() {
        val f = fixture(
            targetName = "B",
            pageSpecs = listOf(
                PageSpec("B", "%b0", "\$b", 0),
                PageSpec("B", "%b1", "\$b", 1),
            ),
        )
        val coordinator = UnifiedPagerCoordinator()
        val opening = coordinator.updateTarget(
            f.target(initialWindowIndex = 1),
            f.observation(page = 0),
        )
        assertEquals(f.pages[1].key, opening.singleScroll().pageKey)

        coordinator.onDragStarted()
        assertTrue(
            "an idle frame under a held finger must not re-request B1",
            coordinator.observe(f.observation(page = 0, scrolling = false)).isEmpty(),
        )
        assertEquals(UnifiedPagerScrollOwner.UserDrag, coordinator.snapshot().scrollOwner)
        assertEquals(
            "B1 stays pending across the whole gesture; it is never consumed",
            f.pages[1].key,
            coordinator.snapshot().pendingInitialWindowKey,
        )
    }

    /**
     * A programmatic scroll owns its own motion exclusively: while it is still
     * running, no further observation may start a second, overlapping write.
     */
    @Test
    fun inFlightProgrammaticScrollBlocksASecondOverlappingCorrection() {
        val f = fixture()
        val coordinator = alignedCoordinator(f)

        coordinator.onDragStarted()
        coordinator.observe(f.observation(page = 1, scrolling = true))
        coordinator.onDragCancelled()
        val first = coordinator.observe(f.observation(page = 1)).singleScroll()
        assertEquals(f.pages[0].key, first.pageKey)
        assertEquals(1, first.attempt)

        // No completion/cancellation feedback yet: the scroll is still moving.
        assertTrue(
            "a second frame during an in-flight programmatic scroll must not " +
                "issue an overlapping write",
            coordinator.observe(f.observation(page = 1)).isEmpty(),
        )
        assertTrue(
            "nor may a republish during that motion",
            coordinator.updateTarget(
                f.target(
                    pages = f.pages.map { it.copy(pane = it.pane.copy(title = "healed")) },
                ),
                f.observation(page = 1),
            ).isEmpty(),
        )
        assertEquals(1, coordinator.snapshot().correctionCount)
    }

    private fun alignedCoordinator(f: PagerFixture): UnifiedPagerCoordinator =
        UnifiedPagerCoordinator().also { coordinator ->
            val initial = coordinator.updateTarget(f.target(), f.observation(page = 0))
            assertEquals(1, initial.countSettles())
            coordinator.onConfirmedSettleHandled(initial.singleSettle(), accepted = true)
            assertTrue(coordinator.snapshot().targetAligned)
        }

    private fun List<UnifiedPagerCoordinatorAction>.singleScroll() =
        filterIsInstance<UnifiedPagerCoordinatorAction.Scroll>().single()

    private fun List<UnifiedPagerCoordinatorAction>.singleSettle() =
        filterIsInstance<UnifiedPagerCoordinatorAction.ConfirmedSettle>().single()

    private fun List<UnifiedPagerCoordinatorAction>.countSettles(): Int =
        count { it is UnifiedPagerCoordinatorAction.ConfirmedSettle }

    private fun List<UnifiedPagerCoordinatorAction>.anyUserDrivenSettle(): Boolean =
        filterIsInstance<UnifiedPagerCoordinatorAction.ConfirmedSettle>()
            .any { it.userDrivenFromAlignedTarget }

    private data class PageSpec(
        val sessionName: String,
        val paneId: String,
        val sessionId: String,
        val windowIndex: Int,
    )

    private data class PagerFixture(
        val targetName: String,
        val epoch: UnifiedPagerTargetEpoch,
        val pages: List<UnifiedPagerPage>,
    ) {
        fun target(
            pages: List<UnifiedPagerPage> = this.pages,
            initialWindowIndex: Int? = null,
        ) = UnifiedPagerTarget(
            epoch = epoch,
            sessionName = targetName,
            pages = pages,
            initialWindowIndex = initialWindowIndex,
        )

        fun observation(
            page: Int,
            scrolling: Boolean = false,
            measuredKey: Any? = pages[page].key,
        ) = UnifiedPagerObservation(
            targetEpoch = epoch,
            currentPage = page,
            settledPage = page,
            measuredCurrentPageKey = measuredKey,
            isScrollInProgress = scrolling,
        )
    }

    private fun fixture(
        targetName: String = "A",
        epoch: UnifiedPagerTargetEpoch =
            UnifiedPagerTargetEpoch(SessionId("host/$targetName"), 1L),
        pageSpecs: List<PageSpec> = listOf(
            PageSpec(targetName, "%a", "\$a", 0),
            PageSpec("B", "%b", "\$b", 0),
        ),
    ): PagerFixture {
        val panes = pageSpecs.map { spec ->
            TmuxPaneState(
                paneId = spec.paneId,
                windowId = "@${spec.paneId}",
                windowIndex = spec.windowIndex,
                sessionId = spec.sessionId,
                title = spec.sessionName,
                terminalState = TerminalSurfaceState(),
            )
        }
        val names = panes.zip(pageSpecs).associate { (pane, spec) ->
            pane.paneId to spec.sessionName
        }
        return PagerFixture(
            targetName = targetName,
            epoch = epoch,
            pages = unifiedPagerPages(
                panes = panes,
                targetEpoch = epoch,
                targetSessionName = targetName,
                sessionNameForPane = { names[it.paneId] },
            ),
        )
    }
}
