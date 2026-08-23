package com.pocketshell.app.tmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2272 G6 mutation proof for the already-healed branch and its full-frame oracle.
 *
 * Each rejected case names a one-line mutation that must make this test red. The negative values
 * are deliberately one-guard cases where practical: in particular, the rendered-character
 * mutation keeps every other already-healed predicate true.
 */
class ActivePaneRenderOwnerSnapshotForTestTest {

    @Test
    fun alreadyHealedRequiresLaterStableOwnerAndVisibleFullFrame() {
        val expected = snapshot(epoch = 3, generation = 4, chars = 39, partial = true)
        val healed = snapshot(epoch = 4, generation = 4, chars = 137, partial = false)

        assertTrue(
            "a later stable same-owner rich frame with the visible marker is accepted",
            healed.isAlreadyHealedPostInjectionForTest(
                expected = expected,
                visibleFrameMarker = true,
                visibleFrameRows = 6,
                minimumFrameRows = 5,
            ),
        )

        // Mutation: remove `modelMutationEpoch > expected.modelMutationEpoch`.
        assertFalse(
            "a richer frame at the pre-injection epoch is not an intervening repaint",
            healed.copy(modelMutationEpoch = expected.modelMutationEpoch)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `sameOwnerAs(expected)`.
        assertFalse(
            "a richer frame from another pane is not a valid healed outcome",
            healed.copy(paneId = "foreign-pane")
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `controlSizeGeneration >= expected.controlSizeGeneration`.
        assertFalse(
            "a frame from an older control-size generation is not a valid healed outcome",
            healed.copy(controlSizeGeneration = expected.controlSizeGeneration - 1)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `automaticHealActivityEpoch == expected.automaticHealActivityEpoch`.
        assertFalse(
            "a frame after an automatic-heal activity change is not a valid intervening repaint",
            healed.copy(automaticHealActivityEpoch = expected.automaticHealActivityEpoch + 1)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove the `attachResizeSeedSettled` guard.
        assertFalse(
            "a resize still in flight cannot be accepted as stable",
            healed.copy(sizeOperationsInFlight = 1)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `!partiallyBlank`.
        assertFalse(
            "a partial-black frame is not an already-healed outcome",
            healed.copy(partiallyBlank = true)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `renderedNonBlankChars > expected.renderedNonBlankChars`.
        // This is selective: all other predicates, including the visible full-frame oracle, are
        // satisfied. Equality is the concrete reviewer mutation's surviving false-positive.
        assertFalse(
            "a later full-looking frame with no increase in rendered content is not healed",
            healed.copy(renderedNonBlankChars = expected.renderedNonBlankChars)
                .isAlreadyHealedPostInjectionForTest(expected, true, 6, 5),
        )

        // Mutation: remove `visibleFrameMarker`.
        assertFalse(
            "a model-only increase without the visible full frame is not healed",
            healed.isAlreadyHealedPostInjectionForTest(expected, false, 6, 5),
        )

        // Mutation: remove `visibleFrameRows >= minimumFrameRows`.
        assertFalse(
            "a visible marker with too few restored rows is not healed",
            healed.isAlreadyHealedPostInjectionForTest(expected, true, 4, 5),
        )
    }

    @Test
    fun fullFrameHealOutcomeRequiresAutomaticOrManualRestore() {
        val fullFrame = FullFrameHealProofForTest(
            automaticHealRestored = true,
            manualHealOutcome = null,
            visiblePartiallyBlank = false,
            visibleFrameMarker = true,
            visibleFrameRows = 6,
            minimumFrameRows = 5,
        )

        assertTrue(
            "an already-healed stable repaint is a valid full-frame outcome",
            fullFrame.restored,
        )
        assertTrue(
            "a manual Healed attempt is a valid full-frame outcome",
            fullFrame.copy(automaticHealRestored = false, manualHealOutcome = HealOutcome.Healed)
                .restored,
        )

        // Mutation: remove `(automaticHealRestored || manualHealOutcome == HealOutcome.Healed)`.
        // All visible full-frame predicates remain true, so neither source of restoration may be
        // omitted or replaced by the visual assertion alone.
        assertFalse(
            "a full-looking frame with neither automatic nor manual restoration must fail",
            fullFrame.copy(automaticHealRestored = false, manualHealOutcome = null).restored,
        )
        assertFalse(
            "a non-healing manual outcome must not count as restoration",
            fullFrame.copy(
                automaticHealRestored = false,
                manualHealOutcome = HealOutcome.Healthy,
            ).restored,
        )

        // Each of these changes only one visible full-frame guard from the valid automatic case.
        assertFalse(
            "an automatic restore that remains partial-black is not a full-frame outcome",
            fullFrame.copy(visiblePartiallyBlank = true).restored,
        )
        assertFalse(
            "an automatic restore without the frame marker is not a full-frame outcome",
            fullFrame.copy(visibleFrameMarker = false).restored,
        )
        assertFalse(
            "an automatic restore with too few frame rows is not a full-frame outcome",
            fullFrame.copy(visibleFrameRows = 4).restored,
        )
    }

    private fun snapshot(
        epoch: Long,
        generation: Long,
        chars: Int,
        partial: Boolean,
        paneId: String = "pane",
        sizeOperationsInFlight: Int = 0,
    ) = ActivePaneRenderOwnerSnapshotForTest(
        paneId = paneId,
        windowId = "@0",
        sessionId = "session",
        targetSessionName = "issue1138-alt-black",
        connectGeneration = 1,
        clientIdentity = 1,
        stateIdentity = 1,
        terminalSessionIdentity = 1,
        emulatorIdentity = 1,
        modelMutationEpoch = epoch,
        modelDrainBacklogged = false,
        seedOperationInFlight = false,
        sizeOperationsInFlight = sizeOperationsInFlight,
        automaticHealOperationsInFlight = 0,
        automaticHealActivityEpoch = 1,
        controlSizeGeneration = generation,
        effectiveColumns = 62,
        effectiveRows = 58,
        appliedColumns = 62,
        appliedRows = 58,
        lastSeedAtMs = 1,
        renderedNonBlankChars = chars,
        partiallyBlank = partial,
        renderLooksSuspect = partial,
        coherent = true,
    )
}
