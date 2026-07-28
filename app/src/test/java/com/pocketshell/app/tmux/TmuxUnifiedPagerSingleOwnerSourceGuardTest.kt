package com.pocketshell.app.tmux

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #1778 D22 structure guard.
 *
 * The defect class this issue exists to end is *another writer appearing beside
 * the one that already owns the pager*. Behavioural tests cannot see that: a
 * second writer is usually silent on the happy path and only cancels a real
 * gesture under contention. These assertions therefore pin the two things that
 * make the single-owner design true by construction:
 *
 *  1. exactly one source location may move the pager, and
 *  2. no numeric page index may cross out of the measured Compose snapshot.
 *
 * They deliberately match declarations and call sites only — never prose in a
 * comment, which would make the guard a spelling test rather than a structure
 * test.
 */
class TmuxUnifiedPagerSingleOwnerSourceGuardTest {

    @Test
    fun onlySingleEffectExecutorWritesPagerPosition() {
        val effect = code("TmuxUnifiedPagerSettleEffects.kt")
        val siblingSources = listOf(
            code("TmuxUnifiedPagerCoordinator.kt"),
            code("TmuxUnifiedPagerModel.kt"),
            code("TmuxTerminalPager.kt"),
            code("TmuxSessionScreen.kt"),
        ).joinToString("\n")

        assertEquals(
            "one production site may issue requestScrollToPage",
            1,
            effect.countLiteral("pagerState.requestScrollToPage("),
        )
        assertEquals(
            "one production site may issue scrollToPage",
            1,
            effect.countLiteral("pagerState.scrollToPage("),
        )
        assertFalse(
            "no sibling pager source may move the pager",
            siblingSources.contains("requestScrollToPage(") ||
                siblingSources.contains("scrollToPage("),
        )
    }

    @Test
    fun numericOnlyRearmAndSplitSettleWritersStayDeleted() {
        val effect = code("TmuxUnifiedPagerSettleEffects.kt")

        assertFalse(
            "the numeric ownership snapshot helper is deleted (D22 hard cut)",
            effect.contains("UnifiedPagerOwnershipSnapshot"),
        )
        assertFalse(
            "the name-keyed alignment latch is deleted",
            effect.contains("pagerAlignedSession"),
        )
        assertFalse(
            "the free-standing drag latch is deleted",
            effect.contains("var userDraggedSinceAlignment"),
        )
        assertFalse(
            "the split numeric settledPage collectors are deleted",
            effect.contains("snapshotFlow { pagerState.settledPage }"),
        )
    }

    @Test
    fun confirmedSettleReachesTheViewModelAsImmutableIdentityNotAnIndex() {
        val effect = code("TmuxUnifiedPagerSettleEffects.kt")
        val vm = code("TmuxSessionViewModel.kt")

        assertEquals(
            "one confirmed-settle site owns promotion, by identity",
            1,
            effect.countLiteral("viewModel.onUnifiedPageSettled(settled)"),
        )
        assertEquals(
            "one confirmed-settle site owns reseed, from the same identity",
            1,
            effect.countLiteral("viewModel.reseedVisiblePaneIfBlank(settled.paneId)"),
        )
        assertTrue(
            "the ViewModel entry point accepts only the immutable settled page",
            vm.contains("fun onUnifiedPageSettled(settled: UnifiedPagerSettledPage)"),
        )
        assertFalse(
            "no index-taking overload may survive (D22 hard cut)",
            vm.contains("onUnifiedPageSettled(pageIndex"),
        )
        assertFalse(
            "the ViewModel must not re-resolve a settle against its own list",
            vm.contains("_unifiedPanes.value.getOrNull(pageIndex)"),
        )
    }

    @Test
    fun measuredCompositeIdentityAndAllScrollOwnersAreLoadBearing() {
        val model = code("TmuxUnifiedPagerCoordinator.kt")
        val effect = code("TmuxUnifiedPagerSettleEffects.kt")
        val pager = code("TmuxTerminalPager.kt")

        assertTrue(model.contains("val targetSessionId: SessionId"))
        assertTrue(model.contains("val targetEpoch: UnifiedPagerTargetEpoch"))
        assertTrue(model.contains("val owner: UnifiedPagerPageOwner"))
        assertTrue(model.contains("val paneId: String"))
        assertTrue(model.contains("measuredCurrentPageKey"))
        assertTrue(model.contains("isScrollInProgress"))
        assertTrue(effect.contains("is DragInteraction.Start"))
        assertTrue(effect.contains("is DragInteraction.Stop"))
        assertTrue(effect.contains("is DragInteraction.Cancel"))
        assertTrue(
            "Foundation must measure the same composite key the coordinator reads",
            pager.contains("pages.getOrNull(pageIndex)?.key?.saveableValue"),
        )
    }

    @Test
    fun programmaticMotionCannotSuspendOrCancelSoleEventConsumer() {
        val model = code("TmuxUnifiedPagerCoordinator.kt")
        val effect = code("TmuxUnifiedPagerSettleEffects.kt")

        assertTrue(effect.contains("programmaticScrollJob = launch"))
        assertTrue(effect.contains("UnifiedPagerEffectEvent.ProgrammaticCompleted"))
        assertTrue(effect.contains("UnifiedPagerEffectEvent.ProgrammaticCancelled"))
        assertTrue(effect.contains("catch (_: CancellationException)"))
        assertTrue(model.contains("fun onProgrammaticScrollCompleted("))
        assertTrue(model.contains("fun onProgrammaticScrollCancelled("))
        assertTrue(model.contains("val executionToken: Long"))
        assertTrue(model.contains("lastExecutionToken += 1L"))
        assertTrue(model.contains("retireInvalidProgrammaticOwner("))
        assertTrue(model.contains("fun onConfirmedSettleHandled("))
        assertTrue(effect.contains("settleRetryToken"))
        assertTrue(effect.contains("coordinator.onConfirmedSettleHandled(action, accepted)"))
    }

    private fun String.countLiteral(needle: String): Int =
        windowed(needle.length, 1).count { it == needle }

    /**
     * Read a production source with line comments and block comments stripped,
     * so no assertion here can be satisfied by prose.
     */
    private fun code(relative: String): String {
        val candidates = listOf(
            File("app/src/main/java/com/pocketshell/app/tmux/$relative"),
            File("src/main/java/com/pocketshell/app/tmux/$relative"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("Could not locate $relative from ${File(".").absolutePath}")
        return file.readText()
            .replace(BLOCK_COMMENT, "")
            .lines()
            .joinToString("\n") { it.substringBefore("//") }
    }

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)
    }
}
